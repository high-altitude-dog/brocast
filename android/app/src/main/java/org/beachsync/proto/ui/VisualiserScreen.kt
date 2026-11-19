package org.beachsync.proto.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.STREAM_EPOCH_NS
import org.beachsync.proto.Session
import org.beachsync.proto.segmentAt
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Debug only (visualiser repo, Phase 5 spike): the visualiser's node look in a WebView, driven by flowtime.
 *
 * The page comes from the APK's assets (`visualiser/`, copied by the debug build from ../../visualiser/looks) and the set's `.viz` and
 * `.nodes` from the library folder, both on a fake https origin through [WebViewClient.shouldInterceptRequest]: nothing goes over a network.
 * No run is needed and nothing is played: the set at the ear and its position come from the library plan and [FlowTime.fit], as Now playing
 * does with no run. Every display frame, on the UI thread, the file seconds at this frame's boot time are compared with the line last sent;
 * `setFlowtimeBoot(s, bootNs, rate)` is called only when they differ by more than 0.5 ms (a new fit) or the page asks. Never the audio thread.
 */
private const val ORIGIN = "https://appassets.androidplatform.net"
private val LIB_FILE = Regex("[0-9a-f]{32}\\.(viz|nodes)")

private class FlowLine(val id: String, val s: Double, val boot: Long, val rate: Double)

private class Bridge(private val logFile: File) {
    @Volatile var ready = false
    @JavascriptInterface fun bootNs(): Double = android.os.SystemClock.elapsedRealtimeNanos().toDouble()
    @JavascriptInterface fun ready() { ready = true }
    /** `?report=1`: JSON arrays of log items, one per line. Called on the WebView's bridge thread, never the UI thread. */
    @JavascriptInterface fun log(body: String) { synchronized(this) { FileOutputStream(logFile, true).use { it.write((body + "\n").toByteArray()) } } }
}

/** Where the ear is in the library's timeline at [boot]: the set id and seconds into its file, or null between sets or before flowtime. */
internal fun earAt(lib: LibraryStore, boot: Long): Pair<org.beachsync.proto.LibraryItem, Double>? {
    val utc = FlowTime.fit()?.utcAt(boot) ?: return null
    val ear = utc - lib.dMs * 1_000_000L - STREAM_EPOCH_NS
    val n = Math.floorDiv(ear, 1_000_000_000L)
    val (item, block) = segmentAt(lib.editions, n) ?: return null
    return item to block + (ear - n * 1_000_000_000L) / 1e9
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VisualiserScreen(state: BenchState, query: String, fromSeam: Boolean = false, back: () -> Unit) {
    val lib = state.library
    // Entered from the Live Seam: black until the page draws, a handle to pull it back down, and home again when the music has no visual.
    var pageReady by remember { mutableStateOf(false) }
    val cover by androidx.compose.animation.core.animateFloatAsState(if (fromSeam && !pageReady) 1f else 0f, androidx.compose.animation.core.tween(350), label = "cover")
    var left by remember { mutableStateOf(false) }
    val leave = { if (!left) { left = true; back() } }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(8000); pageReady = true }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bridge = remember { Bridge(File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "vis-log.jsonl")) }
    var view by remember { mutableStateOf<WebView?>(null) }
    var shownId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("Waiting for flowtime") }
    var missing by remember { mutableStateOf(false) }
    val sent = remember { object { var line: FlowLine? = null } }

    LaunchedEffect(lib) {
        if (lib == null) { note = "No library"; return@LaunchedEffect }
        while (true) withFrameNanos {
            val boot = Session.nowBoot()
            val at = earAt(lib, boot)
            if (at == null) { if (fromSeam && FlowTime.fit() != null) leave(); note = if (FlowTime.fit() == null) "Waiting for flowtime" else "Between sets"; return@withFrameNanos }
            val (item, s) = at
            val id = item.id
            val w = view ?: return@withFrameNanos
            if (id != shownId) {
                shownId = id; sent.line = null; bridge.ready = false; missing = false
                val title = item.title.ifEmpty { id }
                if (!lib.visFile(id, "nodes").exists() || !lib.visFile(id, "viz").exists()) { if (fromSeam) leave(); missing = true; note = "No visual for $title yet: $id.viz and .nodes go in the library folder"; w.loadUrl("about:blank"); return@withFrameNanos }
                note = ""
                val t = java.net.URLEncoder.encode(title, "UTF-8")
                w.loadUrl("$ORIGIN/vis/looks/index.html?clock=app&set=$id&title=$t&viz=/lib/$id.viz&nodes=/lib/$id.nodes&t=${"%.3f".format(s)}" + if (query.isEmpty()) "" else "&$query")
                return@withFrameNanos
            }
            if (missing) return@withFrameNanos
            note = ""
            if (!bridge.ready) return@withFrameNanos
            pageReady = true
            val rate = 1.0 + (FlowTime.fit()?.slope ?: 0.0)
            val l = sent.line
            if (l == null || l.id != id || abs(l.s + (boot - l.boot) / 1e9 * l.rate - s) > 0.0005 || abs(l.rate - rate) > 1e-7) {
                sent.line = FlowLine(id, s, boot, rate)
                w.evaluateJavascript("window.setFlowtimeBoot&&setFlowtimeBoot(${"%.6f".format(s)},$boot,${"%.9f".format(rate)})", null)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize().safeDrawingPadding(), factory = { c ->
            WebView.setWebContentsDebuggingEnabled(true)
            WebView(c).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true // the page remembers which Effects are switched on
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(bridge, "Brocast")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                        val u = r.url
                        if (u.host != "appassets.androidplatform.net") return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), "".byteInputStream())
                        val p = u.path ?: return null
                        val body = when {
                            p.startsWith("/vis/") && !p.contains("..") -> runCatching { c.assets.open("visualiser/" + p.removePrefix("/vis/")) }.getOrNull()
                            p.startsWith("/lib/") && LIB_FILE.matches(p.removePrefix("/lib/")) -> lib?.visFile(p.removePrefix("/lib/").substringBefore('.'), p.substringAfterLast('.'))?.takeIf { it.exists() }?.inputStream()
                            else -> null
                        } ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), "".byteInputStream())
                        val mime = when (p.substringAfterLast('.')) { "html" -> "text/html"; "js", "mjs" -> "text/javascript"; "css" -> "text/css"; "json" -> "application/json"; "png" -> "image/png"; else -> "application/octet-stream" }
                        return WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), body)
                    }
                }
                view = this
            }
        }, onRelease = { it.destroy(); view = null })
        if (note.isNotEmpty()) BasicText(note, Modifier.align(Alignment.Center).safeDrawingPadding().padding(24.dp), Tokens.Text.body.style(Ink.textSecondary))
        if (cover > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = cover)))
        if (fromSeam) Box(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().fillMaxWidth().height(56.dp)
                .pointerInput(Unit) {
                    var pulled = 0f
                    detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                        change.consume()
                        pulled += dy
                        if (pulled > 48.dp.toPx()) leave()
                    }
                }
                .clickable(role = Role.Button, onClickLabel = "Back to Now Playing") { leave() }
                .semantics { contentDescription = "Back to Now Playing" },
            Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = 8.dp).size(width = 44.dp, height = 4.dp).background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(2.dp)))
        }
    }
}
