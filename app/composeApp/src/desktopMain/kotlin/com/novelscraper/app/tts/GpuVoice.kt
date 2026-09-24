package com.novelscraper.app.tts

import com.novelscraper.app.net.Net
import com.novelscraper.app.platform.DesktopDirs
import com.novelscraper.app.platform.Log
import com.novelscraper.app.platform.Os
import com.novelscraper.app.platform.settingsStore
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Narration on the graphics card: through CUDA on an NVIDIA card, and through
 * DirectML on any other DirectX 12 card on Windows (see [Backend]).
 *
 * The CUDA pack:
 * sherpa-onnx's Java binding loads ONNX Runtime as a library of its own and
 * asks it at run time whether CUDA is there, so the build the app ships only
 * needs a CUDA-enabled ONNX Runtime beside it, plus NVIDIA's own libraries:
 * the CUDA runtime, cuBLAS, cuFFT and cuDNN. None of that fits in an installer
 * (about 2 GB to download, 2.5 GB on disk), so it is a pack the reader fetches
 * from Settings, kept in the data folder, and loaded instead of the CPU-only
 * libraries the next time narration starts.
 *
 * Measured on a GTX 1060 with the full-precision Kokoro: RTF 0.17 for 0.15
 * CPU-seconds per second of audio, against RTF 0.48 for 1.9 on four cores of
 * the i7-8750H beside it.
 *
 * cuDNN is pinned to 9.10.2: 9.26 fails on that card (compute capability 6.1)
 * inside Kokoro's LSTM with CUDNN_STATUS_EXECUTION_FAILED_CUDART.
 *
 * The DirectML pack is sherpa-onnx's binding built against ONNX Runtime
 * DirectML by this repository's own workflow (.github/workflows/directml.yml),
 * since nobody publishes one; it is small, because DirectX comes with Windows.
 *
 * Whatever goes wrong, narration falls back to the processor: a pack that
 * won't load, a driver too old, a card CUDA doesn't know.
 */
object GpuVoice {

    private const val TAG = "GpuVoice"

    /**
     * The two ways onto a graphics card. [packId] names everything a pack is
     * made of: a change there is a new pack, and the old one is cleared away.
     */
    enum class Backend(val packId: String, val provider: String) {
        /** NVIDIA cards, through CUDA 12 and cuDNN. */
        CUDA("cuda12-sherpa1.13.8-ort1.28.2-cudnn9.10.2", "cuda"),
        /** Any DirectX 12 card on Windows (AMD, Intel, NVIDIA), through DirectML. */
        DIRECTML("directml-sherpa1.13.8-ort1.24.4-dml1.15.4", "directml"),
    }

    private val prefs by lazy { settingsStore("tts-gpu") }

    /** A graphics card as its driver describes it. [computeCapability] is
     *  CUDA's measure of a card's generation, and 0 where CUDA isn't involved. */
    data class Card(val name: String, val computeCapability: Double, val driver: String)

    sealed interface Support {
        data class Ready(val card: Card, val backend: Backend) : Support
        /** There is a card, but not one this pack can use. */
        data class Unsuitable(val card: Card, val why: String) : Support
        data object None : Support
    }

    sealed interface State {
        data object Idle : State
        data class Downloading(val bytes: Long, val total: Long, val what: String) : State
        data class Unpacking(val what: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> by lazy {
        _enabled.value = prefs.getBoolean("enabled", false)
        _enabled.asStateFlow()
    }

    /** Why narration isn't on the card this run, when it was meant to be. */
    @Volatile var problem: String? = null
        private set

    /** True once narration has loaded its libraries this run: from then on the
     *  choice between the pack and the processor holds until the app restarts. */
    @Volatile var nativeLoaded = false
        private set

    /** True when narration is running on the graphics card this run. */
    @Volatile var active = false
        private set

    @Volatile private var cancelled = false

    /** The backend this machine gets, once [support] has been worked out. */
    val backend: Backend? get() = (support as? Support.Ready)?.backend

    /** What the engine asks ONNX Runtime for when the pack is in use. */
    val provider: String get() = backend?.provider ?: "cpu"

    private val root: File get() = File(DesktopDirs.data, "gpu")
    private val dir: File get() = File(root, backend?.packId ?: "none")
    private val complete: File get() = File(dir, "complete")

    val installed: Boolean get() = backend != null && complete.isFile

    /** What the pack takes on disk, for the remove button. */
    val installedBytes: Long
        get() = dir.listFiles().orEmpty().sumOf { it.length() }

    val downloadBytes: Long get() = pieces().sumOf { it.size }

    // --- what this machine has ------------------------------------------------------

    /**
     * DirectML can't run Kokoro as it is published: its decoder's upsampling
     * layers (ConvTranspose with output_padding, e.g. /N.1/pool/ConvTranspose)
     * are refused with E_INVALIDARG by every ONNX Runtime DirectML from 1.17
     * to 1.24, and the refusal ends the process. Off until that is solved.
     */
    private const val DIRECTML_OFFERED = false

    val support: Support by lazy {
        val cuda = detectCuda()
        if (cuda is Support.Ready || !Os.isWindows || !DIRECTML_OFFERED) cuda
        // An NVIDIA card too old for CUDA 12 still has DirectX 12, and so has
        // every AMD and Intel card of the last decade.
        else detectDirectMl() ?: cuda
    }

    /**
     * The first real display adapter Windows lists. DirectML runs on the one
     * DirectX puts first, which on a laptop is usually the integrated one, so
     * the name is only for the reader's information.
     */
    private fun detectDirectMl(): Support? {
        val names = runCatching {
            val p = ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-CimInstance Win32_VideoController).Name",
            ).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }.getOrNull() ?: return null
        val real = names.filterNot { n ->
            listOf("Microsoft Basic", "Remote", "Virtual", "Parsec", "Meta").any { n.contains(it, ignoreCase = true) }
        }
        val name = real.firstOrNull() ?: return null
        return Support.Ready(Card(name, 0.0, ""), Backend.DIRECTML)
    }

    private fun detectCuda(): Support {
        if (!Os.isWindows && !Os.isLinux) return Support.None
        // The driver's own library is the sign an NVIDIA driver is installed at
        // all; nvidia-smi, which comes with it, says which card and how new.
        val smi = if (Os.isWindows) File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32\\nvidia-smi.exe").path
                  else "nvidia-smi"
        val line = runCatching {
            val p = ProcessBuilder(smi, "--query-gpu=name,compute_cap,driver_version", "--format=csv,noheader")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return Support.None }
            out.lineSequence().firstOrNull { it.isNotBlank() }
        }.getOrNull() ?: return Support.None
        val parts = line.split(',').map { it.trim() }
        if (parts.size < 3) return Support.None
        val card = Card(parts[0], parts[1].toDoubleOrNull() ?: 0.0, parts[2])
        val driverMajor = card.driver.substringBefore('.').toIntOrNull() ?: 0
        // CUDA 12 runs on Maxwell (5.0) and later, with a driver from its own
        // generation or newer: 528 on Windows, 525 on Linux.
        val minDriver = if (Os.isWindows) 528 else 525
        return when {
            card.computeCapability < 5.0 ->
                Support.Unsuitable(card, "This card is older than CUDA 12 supports.")
            driverMajor < minDriver ->
                Support.Unsuitable(card, "The graphics driver (${card.driver}) is too old; version $minDriver or newer is needed.")
            else -> Support.Ready(card, Backend.CUDA)
        }
    }

    // --- the switch -------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        enabled // make sure the stored value has been read first
        prefs.putBoolean("enabled", on)
        _enabled.value = on
        // Switched on again: voices that failed before get another try, in
        // case the driver has changed since.
        if (on) {
            problem = null
            prefs.remove(PROBES_FAILED)
        }
    }

    /** Takes effect for this run only if narration hasn't started yet. */
    val takesEffectNow: Boolean get() = !nativeLoaded

    // --- getting it -------------------------------------------------------------------

    private enum class Kind { TAR_BZ2, WHEEL, ZIP }

    private const val DIRECTML_URL =
        "https://github.com/mtgims/NovelScraper/releases/download/gpu-directml-1/" +
            "sherpa-onnx-v1.13.8-directml-ort1.24.4-win-x64.zip"
    private const val DIRECTML_SHA256 = "PENDING"
    private const val DIRECTML_SIZE = 0L

    private class Piece(
        val label: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val kind: Kind,
        /** The name to keep an archive entry under, or null to leave it out. */
        val pick: (String) -> String?,
    )

    private fun pieces(): List<Piece> = when (backend) {
        Backend.CUDA -> cudaPieces()
        Backend.DIRECTML -> listOf(
            // Built by this repository's DirectML workflow (sherpa-onnx's Java
            // binding against ONNX Runtime DirectML) and kept on a release of
            // its own; it carries its own binding, built with it.
            Piece(
                "DirectML narration libraries",
                DIRECTML_URL, DIRECTML_SHA256, DIRECTML_SIZE, Kind.ZIP,
            ) { name -> name.substringAfterLast('/').takeIf { it.endsWith(".dll") } },
        )
        null -> emptyList()
    }

    private fun cudaPieces(): List<Piece> = when {
        Os.isWindows -> listOf(
            Piece(
                "ONNX Runtime with CUDA",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/" +
                    "sherpa-onnx-v1.13.8-cuda-12.x-cudnn-9.x-onnxruntime1.28.2-win-x64-cuda.tar.bz2",
                "066c5b54dbafaa1388001a9c9837ac1374dbba6d6678f193ca06aa0d8e94d8c3",
                595_017_373, Kind.TAR_BZ2,
            ) { name ->
                name.substringAfterLast('/').takeIf {
                    "/lib/" in name && it in setOf("onnxruntime.dll", "onnxruntime_providers_cuda.dll", "onnxruntime_providers_shared.dll")
                }
            },
            wheel("CUDA runtime", "https://files.pythonhosted.org/packages/59/df/e7c3a360be4f7b93cee39271b792669baeb3846c58a4df6dfcf187a7ffab/nvidia_cuda_runtime_cu12-12.9.79-py3-none-win_amd64.whl",
                "8e018af8fa02363876860388bd10ccb89eb9ab8fb0aa749aaf58430a9f7c4891", 3_591_604),
            wheel("cuBLAS", "https://files.pythonhosted.org/packages/20/e2/fc9a0e985249d873150276d5afb02e39a66817fedbf1a385724393e505ed/nvidia_cublas_cu12-12.9.2.10-py3-none-win_amd64.whl",
                "623f43027d40d44ceadf0043f002bd25cf353e8f13ce90b9a87057019f560661", 553_162_896),
            wheel("cuFFT", "https://files.pythonhosted.org/packages/20/ee/29955203338515b940bd4f60ffdbc073428f25ef9bfbce44c9a066aedc5c/nvidia_cufft_cu12-11.4.1.4-py3-none-win_amd64.whl",
                "8e5bfaac795e93f80611f807d42844e8e27e340e0cde270dcb6c65386d795b80", 200_067_309),
            wheel("cuDNN", "https://files.pythonhosted.org/packages/3d/90/0bd6e586701b3a890fd38aa71c387dab4883d619d6e5ad912ccbd05bfd67/nvidia_cudnn_cu12-9.10.2.21-py3-none-win_amd64.whl",
                "c6288de7d63e6cf62988f0923f96dc339cea362decb1bf5b3141883392a7d65e", 692_992_268),
        )
        Os.isLinux -> listOf(
            Piece(
                "ONNX Runtime with CUDA",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/" +
                    "sherpa-onnx-v1.13.8-cuda-12.x-cudnn-9.x-onnxruntime1.28.2-linux-x64-gpu.tar.bz2",
                "2132af1ae8c84c84f86ac091e4311839287851d2bf5e660332ebce3898a1e0b2",
                393_010_348, Kind.TAR_BZ2,
            ) { name ->
                name.substringAfterLast('/').takeIf { "/lib/" in name && it.startsWith("libonnxruntime") }
            },
            wheel("CUDA runtime", "https://files.pythonhosted.org/packages/bc/46/a92db19b8309581092a3add7e6fceb4c301a3fd233969856a8cbf042cd3c/nvidia_cuda_runtime_cu12-12.9.79-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl",
                "25bba2dfb01d48a9b59ca474a1ac43c6ebf7011f1b0b8cc44f54eb6ac48a96c3", 3_493_179),
            wheel("cuBLAS", "https://files.pythonhosted.org/packages/cb/c0/0a517bfe63ccd3b92eb254d264e28fca3c7cab75d07daea315250fb1bf73/nvidia_cublas_cu12-12.9.2.10-py3-none-manylinux_2_27_x86_64.whl",
                "e4f53a8ca8c5d6e8c492d0d0a3d565ecb59a751b19cfdaa4f6da0ab2104c1702", 581_240_110),
            wheel("cuFFT", "https://files.pythonhosted.org/packages/95/f4/61e6996dd20481ee834f57a8e9dca28b1869366a135e0d42e2aa8493bdd4/nvidia_cufft_cu12-11.4.1.4-py3-none-manylinux2014_x86_64.manylinux_2_17_x86_64.whl",
                "c67884f2a7d276b4b80eb56a79322a95df592ae5e765cf1243693365ccab4e28", 200_877_592),
            wheel("cuDNN", "https://files.pythonhosted.org/packages/ba/51/e123d997aa098c61d029f76663dedbfb9bc8dcf8c60cbd6adbe42f76d049/nvidia_cudnn_cu12-9.10.2.21-py3-none-manylinux_2_27_x86_64.whl",
                "949452be657fa16687d0930933f032835951ef0892b37d2d53824d1a84dc97a8", 706_758_467),
        )
        else -> emptyList()
    }

    /** NVIDIA's own wheels: only the libraries, not the headers or the Python. */
    private fun wheel(label: String, url: String, sha256: String, size: Long) =
        Piece(label, url, sha256, size, Kind.WHEEL) { name ->
            val file = name.substringAfterLast('/')
            val library = if (Os.isWindows) name.endsWith(".dll") && "/bin/" in name
                          else "/lib/" in name && Regex("""\.so(\.\d+)*$""").containsMatchIn(file)
            // Two libraries the wheels carry that ONNX Runtime never asks for.
            file.takeIf { library && !file.startsWith("nvblas") && !file.startsWith("cufftw") && !file.startsWith("libnvblas") && !file.startsWith("libcufftw") }
        }

    fun cancel() { cancelled = true }

    /**
     * Fetches the pack, checks every file against its published SHA-256 and
     * unpacks only the libraries. Blocking: run it off the main thread.
     */
    fun download() {
        cancelled = false
        val parts = pieces()
        if (parts.isEmpty()) { _state.value = State.Failed("Not available on this system."); return }
        val total = parts.sumOf { it.size }
        val pack = backend ?: run { _state.value = State.Failed("No graphics card to use."); return }
        val tmpDir = File(root, "${pack.packId}.tmp")
        val archive = File(DesktopDirs.cache, "gpu-download.part")
        try {
            root.mkdirs(); DesktopDirs.cache.mkdirs()
            // Room for the unpacked pack plus the largest archive while it unpacks.
            val needed = (if (pack == Backend.CUDA) 2_700_000_000L else 300_000_000L) + parts.maxOf { it.size }
            if (root.usableSpace in 1 until needed) {
                _state.value = State.Failed("Not enough free space: about ${needed / 1_000_000_000 + 1} GB is needed.")
                return
            }
            // Anything left from an earlier pack, or from a download cut short.
            root.listFiles().orEmpty().filter { it.name != pack.packId }.forEach { it.deleteRecursively() }
            tmpDir.deleteRecursively(); tmpDir.mkdirs()

            val http = Net.client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
            var done = 0L
            for (piece in parts) {
                _state.value = State.Downloading(done, total, piece.label)
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(piece.url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("${piece.label}: HTTP ${resp.code}")
                    resp.body!!.byteStream().use { input ->
                        archive.outputStream().buffered(1 shl 20).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var last = 0L
                            var got = 0L
                            while (true) {
                                if (cancelled) throw InterruptedException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n); digest.update(buf, 0, n)
                                got += n
                                if (got - last >= 2_000_000) {
                                    last = got
                                    _state.value = State.Downloading(done + got, total, piece.label)
                                }
                            }
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != piece.sha256) throw IllegalStateException("${piece.label} didn't download correctly (checksum mismatch).")
                done += piece.size
                _state.value = State.Unpacking(piece.label)
                unpack(piece, archive, tmpDir)
                archive.delete()
            }
            // The CUDA runtime goes with the app's own binding; DirectML brings one
            // built against it.
            if (pack == Backend.CUDA) copyBinding(tmpDir)
            File(tmpDir, "complete").writeText(pack.packId)
            dir.deleteRecursively()
            if (!tmpDir.renameTo(dir)) throw IllegalStateException("Couldn't put the files in place.")
            problem = null
            setEnabled(true)
            _state.value = State.Idle
            Log.i(TAG, "pack ${pack.packId} installed (${installedBytes / 1_000_000} MB)")
        } catch (e: InterruptedException) {
            tmpDir.deleteRecursively(); archive.delete()
            _state.value = State.Idle
        } catch (e: Exception) {
            tmpDir.deleteRecursively(); archive.delete()
            Log.w(TAG, "download failed: ${e.message}")
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun unpack(piece: Piece, archive: File, into: File) {
        val links = mutableMapOf<String, String>()
        when (piece.kind) {
            Kind.WHEEL, Kind.ZIP -> ZipInputStream(archive.inputStream().buffered(1 shl 20)).use { zip ->
                while (true) {
                    if (cancelled) throw InterruptedException()
                    val entry = zip.nextEntry ?: break
                    val keep = piece.pick(entry.name) ?: continue
                    File(into, keep).outputStream().use { zip.copyTo(it, 1 shl 20) }
                }
            }
            Kind.TAR_BZ2 -> BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 20)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        if (cancelled) throw InterruptedException()
                        val entry = tar.nextEntry ?: break
                        val keep = piece.pick(entry.name) ?: continue
                        // A library under several names (libx.so -> libx.so.1.2) is
                        // kept once, and copied to its other names afterwards.
                        if (entry.isSymbolicLink) { links[keep] = entry.linkName.substringAfterLast('/'); continue }
                        if (entry.isDirectory) continue
                        File(into, keep).outputStream().use { tar.copyTo(it, 1 shl 20) }
                    }
                }
            }
        }
        for ((name, target) in links) {
            val source = File(into, target)
            if (source.isFile && !File(into, name).exists()) source.copyTo(File(into, name))
        }
    }

    /** The app's own sherpa-onnx binding, which goes beside the CUDA runtime so
     *  both load from the one folder. */
    private fun copyBinding(into: File) {
        val (arch, file) = if (Os.isWindows) "win-x64" to "sherpa-onnx-jni.dll" else "linux-x64" to "libsherpa-onnx-jni.so"
        val stream = GpuVoice::class.java.getResourceAsStream("/sherpa-onnx/native/$arch/$file")
            ?: throw IllegalStateException("The app's narration library wasn't found.")
        stream.use { input -> File(into, file).outputStream().use { input.copyTo(it) } }
    }

    /** Deletes the pack. Files in use this run are left for the next start. */
    fun remove() {
        setEnabled(false)
        if (active) {
            prefs.putBoolean("remove-pending", true)
            return
        }
        root.deleteRecursively()
        prefs.putBoolean("remove-pending", false)
    }

    val removalPending: Boolean get() = prefs.getBoolean("remove-pending", false)

    // --- using it ---------------------------------------------------------------------

    /**
     * Loads the pack's libraries in place of the processor-only ones, if the
     * reader turned it on and it is all there. Called once, before narration
     * first touches sherpa-onnx; false means the processor is used.
     */
    @Synchronized
    fun prepare(): Boolean {
        if (nativeLoaded) return active
        nativeLoaded = true
        if (removalPending) {
            runCatching { root.deleteRecursively() }
            prefs.putBoolean("remove-pending", false)
        }
        if (!enabled.value || !installed || support !is Support.Ready) return false
        active = loadPack()
        return active
    }

    /**
     * Whether [spec] has been heard to speak through the pack, finding out the
     * first time in a separate process.
     *
     * A graphics driver or ONNX Runtime can fail in ways that end the whole
     * process rather than throwing: DirectML turning down one of Kokoro's
     * layers does exactly that, halfway through the first sentence. So a new
     * combination of pack and voice is tried once in a throwaway copy of the
     * app, and only one that came back with audio is used here. The answer is
     * kept, and forgotten when the pack is switched on again.
     */
    fun probe(spec: TtsModels.Spec, modelDir: String): Boolean {
        val pack = backend ?: return false
        val key = "${pack.packId}/${File(modelDir).name}"
        if (key in prefs.getStringSet(PROBES_OK).orEmpty()) return true
        if (key in prefs.getStringSet(PROBES_FAILED).orEmpty()) { problem = PROBE_FAILED; return false }
        val command = probeCommand() ?: return false
        Log.i(TAG, "trying ${File(modelDir).name} on the graphics card in a separate process")
        val log = File(DesktopDirs.cache, "gpu-probe.log")
        val ok = runCatching {
            DesktopDirs.cache.mkdirs()
            val p = ProcessBuilder(command + listOf(PROBE_ARG, spec.kind, spec.onnx, modelDir))
                .directory(DesktopDirs.cache)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); false } else p.exitValue() == 0
        }.getOrDefault(false)
        val set = if (ok) PROBES_OK else PROBES_FAILED
        prefs.putStringSet(set, prefs.getStringSet(set).orEmpty() + key)
        Log.i(TAG, "the graphics card ${if (ok) "spoke" else "couldn't speak"} with ${File(modelDir).name}")
        if (!ok) problem = PROBE_FAILED
        return ok
    }

    /** This app again, as a separate process: its launcher when installed, the
     *  Java running it otherwise. */
    private fun probeCommand(): List<String>? {
        System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }?.let { return listOf(it) }
        val java = File(System.getProperty("java.home"), if (Os.isWindows) "bin/java.exe" else "bin/java")
        if (!java.isFile) return null
        return listOf(java.path, "-cp", System.getProperty("java.class.path"), "com.novelscraper.app.MainKt")
    }

    const val PROBE_ARG = "--gpu-probe"
    private const val PROBES_OK = "probes-ok"
    private const val PROBES_FAILED = "probes-failed"
    private const val PROBE_FAILED =
        "The graphics card couldn't narrate with this voice when it was tried, so narration uses the processor."

    /** The separate process [probe] starts: loads the pack, speaks one short
     *  sentence and says how it went through its exit code. */
    fun runProbe(args: List<String>): Int {
        val (kind, onnx, modelDir) = args.takeIf { it.size >= 3 } ?: return 5
        if (!installed || support !is Support.Ready || !loadPack()) return 4
        return try {
            val spec = TtsModels.Spec(dir = File(modelDir).name, url = "", kind = kind, onnx = onnx, required = emptyList())
            val tts = buildModel(spec, modelDir, threads = 1, provider = provider)
            val samples = tts.generate("This is a test of the voice.", 0, 1.0f)
            tts.release()
            if (samples.size > 1000) 0 else 2
        } catch (t: Throwable) {
            System.err.println("probe failed: ${t.message}")
            3
        }
    }

    /** Puts the pack's libraries where sherpa-onnx will load them from. */
    private fun loadPack(): Boolean {
        return try {
            if (Os.isWindows) {
                // cuDNN is asked for by name, from inside ONNX Runtime, and loads
                // its own parts by name in turn: the folder has to be somewhere
                // Windows looks.
                Kernel32Dll.SetDllDirectory(WString(dir.absolutePath))
            } else {
                preloadLinux()
            }
            System.load(File(dir, System.mapLibraryName("onnxruntime")).absolutePath)
            System.load(File(dir, System.mapLibraryName("sherpa-onnx-jni")).absolutePath)
            System.setProperty("sherpa_onnx.native.path", dir.absolutePath)
            Log.i(TAG, "the graphics card's libraries are loaded (${backend?.packId})")
            true
        } catch (t: Throwable) {
            problem = "The GPU files wouldn't load (${t.message}); narration uses the processor."
            Log.w(TAG, problem!!)
            false
        }
    }

    /** Called when the engine couldn't start on the card after all. */
    fun failed(t: Throwable) {
        active = false
        problem = "Narration couldn't start on the graphics card, so it uses the processor. (${t.message?.take(200)})"
        Log.w(TAG, problem!!)
    }

    /**
     * On Linux a library asked for by name is looked for only where the process
     * was told to look when it started, so NVIDIA's libraries are loaded first,
     * in whatever order their own dependencies allow: once loaded, a request by
     * name finds them.
     */
    private fun preloadLinux() {
        var left = dir.listFiles().orEmpty()
            .filter { it.name.contains(".so") && !it.name.startsWith("libonnxruntime") && !it.name.startsWith("libsherpa") }
        while (left.isNotEmpty()) {
            val next = left.filter { runCatching { System.load(it.absolutePath) }.isFailure }
            if (next.size == left.size) break
            left = next
        }
    }

    @Suppress("FunctionName")
    private interface Kernel32Api : StdCallLibrary {
        fun SetDllDirectory(path: WString): Boolean
    }

    private val Kernel32Dll: Kernel32Api by lazy {
        Native.load("kernel32", Kernel32Api::class.java, W32APIOptions.DEFAULT_OPTIONS)
    }
}
