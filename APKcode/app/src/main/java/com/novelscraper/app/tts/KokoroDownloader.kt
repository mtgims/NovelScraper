package com.novelscraper.app.tts

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/**
 * Downloads + extracts the Kokoro model package into the app's filesDir on first
 * use. Blocking — run off the main thread. Extraction is atomic: files land in a
 * temp dir which is renamed into place only on full success, so a killed download
 * never leaves a half-model that [KokoroEngine.isModelReady] would accept.
 */
object KokoroDownloader {
    const val URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_1.tar.bz2"

    // Fallback total for the progress bar when the CDN omits Content-Length.
    private const val APPROX_BYTES = 141_000_000L

    sealed interface Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress
        data object Extracting : Progress
        data object Done : Progress
        data class Failed(val message: String) : Progress
    }

    fun download(context: Context, client: OkHttpClient, onProgress: (Progress) -> Unit) {
        val finalDir = KokoroEngine.modelDir(context)
        val tmpTar = File(context.cacheDir, "kokoro.tar.bz2")
        val tmpDir = File(context.filesDir, "${KokoroEngine.MODEL_DIR_NAME}.tmp")
        try {
            tmpDir.deleteRecursively()

            // 1. download the archive.
            val req = Request.Builder().url(URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onProgress(Progress.Failed("Download failed (HTTP ${resp.code})")); return
                }
                val body = resp.body ?: run { onProgress(Progress.Failed("Empty response")); return }
                val total = if (body.contentLength() > 0) body.contentLength() else APPROX_BYTES
                body.byteStream().use { input ->
                    tmpTar.outputStream().buffered().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var acc = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            acc += n
                            if (acc - lastEmit >= 1_000_000) {
                                onProgress(Progress.Downloading(acc, total)); lastEmit = acc
                            }
                        }
                    }
                }
            }

            // 2. extract (bzip2 -> tar), stripping the top-level dir.
            onProgress(Progress.Extracting)
            tmpDir.mkdirs()
            val canonRoot = tmpDir.canonicalPath + File.separator
            BZip2CompressorInputStream(tmpTar.inputStream().buffered()).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isEmpty()) continue
                        val outFile = File(tmpDir, rel)
                        // zip-slip guard: reject entries that escape the target dir.
                        if (!outFile.canonicalPath.startsWith(canonRoot)) continue
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { tar.copyTo(it) }
                        }
                    }
                }
            }
            tmpTar.delete()

            // 3. atomic swap into place.
            finalDir.deleteRecursively()
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                tmpDir.deleteRecursively()
            }

            onProgress(
                if (KokoroEngine.isModelReady(context)) Progress.Done
                else Progress.Failed("Model incomplete after extraction"),
            )
        } catch (t: Throwable) {
            tmpDir.deleteRecursively()
            tmpTar.delete()
            onProgress(Progress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }
}
