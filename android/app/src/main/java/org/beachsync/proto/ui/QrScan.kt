package org.beachsync.proto.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * The in-app QR scanner (23 Nov rule: no Google Play dependency). CameraX preview + ZXing core (plain Java) on the Y plane, also
 * tried inverted. One request at a time: [start] shows [QrScanHost] over the page, and the callback gets the raw text, or null if
 * closed. MainActivity draws the host over whichever page is up.
 */
object QrScan {
    internal var request by mutableStateOf<((String?) -> Unit)?>(null)
    fun start(done: (String?) -> Unit) { request = done }
}

@Composable
fun QrScanHost() {
    val done = QrScan.request ?: return
    val finish = { r: String? -> QrScan.request = null; done(r) }
    BackHandler { finish(null) }
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        if (granted) Camera { finish(it) }
        else Column(Modifier.align(Alignment.Center).padding(horizontal = Tokens.Space.screenPaddingSide.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText(if (denied) "Brocast needs the camera to scan a code. Allow it in Settings › Apps › Brocast › Permissions, or go back." else "Waiting for camera permission…", style = Tokens.Text.body.style(Ink.textSecondary))
            if (denied) PillButton("Try again", true) { ask.launch(Manifest.permission.CAMERA) }
        }
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(Tokens.Space.screenPaddingSide.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (granted) BasicText("Point the camera at a Brocast code", Modifier.fillMaxWidth(), Tokens.Text.small.style(Ink.text).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            PillButton("Close", false) { finish(null) }
        }
    }
}

@Composable
private fun Camera(found: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val exec = remember { Executors.newSingleThreadExecutor() }
    val latest by rememberUpdatedState(found)
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
    AndroidView({ c ->
        val view = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            var done = false
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().also {
                it.setAnalyzer(exec) { img ->
                    val text = if (done) null else decodeQr(img)
                    if (text != null) { done = true; view.post { latest(text) } }
                }
            }
            runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(c))
        view
    }, Modifier.fillMaxSize(), onRelease = { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } })
}

private val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)) }

/** The Y (brightness) plane as is, then inverted; null when there's no code in the frame. */
private fun decodeQr(img: ImageProxy): String? = img.use {
    val p = it.planes[0]; val buf = p.buffer; val w = it.width; val h = it.height; val stride = p.rowStride
    val y = ByteArray(stride * h).also { b -> buf.get(b, 0, minOf(b.size, buf.remaining())) }
    val src = PlanarYUVLuminanceSource(y, stride, h, 0, 0, w, h, false)
    runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text }.getOrNull()
        ?: runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(src.invert()))).text }.getOrNull()
}
