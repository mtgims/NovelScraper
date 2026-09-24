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
 * Narration on an NVIDIA graphics card, through CUDA.
 *
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
 * Whatever goes wrong, narration falls back to the processor: a pack that
 * won't load, a driver too old, a card CUDA doesn't know.
 */
object GpuVoice {

    private const val TAG = "GpuVoice"

    /** Everything the pack is made of; a change here is a new pack. */
    private const val PACK_ID = "cuda12-sherpa1.13.8-ort1.28.2-cudnn9.10.2"

    private val prefs by lazy { settingsStore("tts-gpu") }

    /** An NVIDIA card, as the driver describes it. */
    data class Card(val name: String, val computeCapability: Double, val driver: String)

    sealed interface Support {
        data class Ready(val card: Card) : Support
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

    /** True when narration is running through CUDA this run. */
    @Volatile var active = false
        private set

    @Volatile private var cancelled = false

    private val root: File get() = File(DesktopDirs.data, "gpu")
    private val dir: File get() = File(root, PACK_ID)
    private val complete: File get() = File(dir, "complete")

    val installed: Boolean get() = complete.isFile

    /** What the pack takes on disk, for the remove button. */
    val installedBytes: Long
        get() = dir.listFiles().orEmpty().sumOf { it.length() }

    val downloadBytes: Long get() = pieces().sumOf { it.size }

    // --- what this machine has ------------------------------------------------------

    val support: Support by lazy { detect() }

    private fun detect(): Support {
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
            else -> Support.Ready(card)
        }
    }

    // --- the switch -------------------------------------------------------------------

    fun setEnabled(on: Boolean) {
        enabled // make sure the stored value has been read first
        prefs.putBoolean("enabled", on)
        _enabled.value = on
    }

    /** Takes effect for this run only if narration hasn't started yet. */
    val takesEffectNow: Boolean get() = !nativeLoaded

    // --- getting it -------------------------------------------------------------------

    private enum class Kind { TAR_BZ2, WHEEL }

    private class Piece(
        val label: String,
        val url: String,
        val sha256: String,
        val size: Long,
        val kind: Kind,
        /** The name to keep an archive entry under, or null to leave it out. */
        val pick: (String) -> String?,
    )

    private fun pieces(): List<Piece> = when {
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
        val tmpDir = File(root, "$PACK_ID.tmp")
        val archive = File(DesktopDirs.cache, "gpu-download.part")
        try {
            root.mkdirs(); DesktopDirs.cache.mkdirs()
            // Room for the unpacked pack plus the largest archive while it unpacks.
            val needed = 2_700_000_000L + parts.maxOf { it.size }
            if (root.usableSpace in 1 until needed) {
                _state.value = State.Failed("Not enough free space: about ${needed / 1_000_000_000 + 1} GB is needed.")
                return
            }
            // Anything left from an earlier pack, or from a download cut short.
            root.listFiles().orEmpty().filter { it.name != PACK_ID }.forEach { it.deleteRecursively() }
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
            copyBinding(tmpDir)
            File(tmpDir, "complete").writeText(PACK_ID)
            dir.deleteRecursively()
            if (!tmpDir.renameTo(dir)) throw IllegalStateException("Couldn't put the files in place.")
            problem = null
            setEnabled(true)
            _state.value = State.Idle
            Log.i(TAG, "pack $PACK_ID installed (${installedBytes / 1_000_000} MB)")
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
            Kind.WHEEL -> ZipInputStream(archive.inputStream().buffered(1 shl 20)).use { zip ->
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
            active = true
            Log.i(TAG, "narration will use the graphics card ($PACK_ID)")
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
