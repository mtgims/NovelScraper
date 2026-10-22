package com.novelscraper.app.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.novelscraper.app.sync.SyncQr
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

actual val canScanCodes: Boolean
    get() = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

@Composable
actual fun CodeScannerDialog(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted = true
        else {
            showToast("Scanning needs the camera. The code can be typed in instead.", long = true)
            onDismiss()
        }
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraQrReader(onScanned)
            // Where to hold the code; the whole picture is read, this only aims.
            Box(
                Modifier.align(Alignment.Center).size(240.dp)
                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
            )
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Point the camera at the sync code on the other device: Settings, Sync, Show sync code.",
                    color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Button(onClick = onDismiss) { Text("Cancel") }
            }
            Text(
                "Scan a sync code", color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 24.dp),
            )
        }
    }
}

/** The back camera's picture, read for a QR code until one is found. */
@Composable
private fun CameraQrReader(onScanned: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onScanned)
    val view = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView: a SurfaceView sits behind its window, and this one
            // is in a dialog, over the app's own window.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(owner) {
        val worker = Executors.newSingleThreadExecutor()
        val found = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrElse {
                Log.w("Scan", "no camera: ${it.message}"); return@addListener
            }
            provider = p
            preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(worker) { image ->
                        val text = image.use { if (found.get()) null else readQr(it) }
                        if (text != null && found.compareAndSet(false, true)) main.execute { latest(text) }
                    }
                }
            try {
                p.unbindAll()
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                // No back camera (some tablets): any camera will do.
                runCatching {
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }.onFailure {
                    Log.w("Scan", "camera didn't start: ${e.message}")
                    showToast("The camera didn't start.")
                }
            }
        }, main)

        onDispose {
            disposed = true
            analysis?.clearAnalyzer()
            provider?.unbind(*listOfNotNull(preview, analysis).toTypedArray())
            worker.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** A QR code's text in [image], from its brightness plane. */
private fun readQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buf = plane.buffer
    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
    return SyncQr.decode(bytes, plane.rowStride, image.width, image.height)
}
