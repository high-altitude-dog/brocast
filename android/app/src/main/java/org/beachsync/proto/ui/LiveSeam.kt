package org.beachsync.proto.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.Session
import org.beachsync.proto.SetBeats
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The Live Seam (Test features only): a strip in the Now Playing card, shown while music plays and the set at the ear has its whole visual
 * on this phone. It draws that set's own nodes as they sound, on flowtime. Tap or swipe up to open the visualiser, landing on a bar line;
 * the visualiser folds back into the strip on the way out. Nothing here touches the audio.
 */
private const val SPAN_S = 3.2
private const val STILL_NODES = 420
private val COLOURS = mapOf(
    "kick" to Color(0xFFFF5A36), "snare" to Color(0xFFFFB62E), "hats" to Color(0xFF49D8FF), "bass" to Color(0xFF7C8CFF),
    "vocals" to Color(0xFFFF6FB5), "melodic" to Color(0xFF3FE0A0), "noise" to Color(0xFFB8E04A),
)

internal class SeamNodes(val id: String, val t: FloatArray, val inst: ByteArray, val y: FloatArray, val amp: FloatArray, val seconds: Float, val colours: List<Color>)

/** The nodes the strip needs, or null unless the file is complete: a file still being copied has arrays that run past its end. */
private fun loadSeamNodes(id: String, f: File): SeamNodes? = runCatching {
    val raw = f.readBytes()
    val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    if (raw.size < 12 || String(raw, 0, 4, Charsets.US_ASCII) != "BNOD") return null
    val headerSize = b.getInt(8)
    val header = JSONObject(String(raw, 12, headerSize, Charsets.UTF_8))
    val base = 12 + headerSize
    val fields = header.getJSONArray("fields")
    val offsets = HashMap<String, Int>()
    for (i in 0 until fields.length()) {
        val field = fields.getJSONObject(i)
        if (base.toLong() + field.getLong("offset") + field.getLong("bytes") > raw.size) return null
        offsets[field.getString("name")] = base + field.getInt("offset")
    }
    val n = header.getInt("nodes")
    val xyzScale = header.getDouble("xyzScale").toFloat()
    val ampScale = header.getDouble("ampScale").toFloat()
    val names = header.getJSONArray("instruments")
    val colours = List(names.length()) { COLOURS[names.getJSONObject(it).getString("name")] ?: Color.White }
    val (ot, oi, oxyz, oa) = listOf("t", "inst", "xyz", "amp").map { offsets.getValue(it) }
    SeamNodes(
        id,
        FloatArray(n) { b.getFloat(ot + it * 4) },
        ByteArray(n) { raw[oi + it] },
        FloatArray(n) { (b.getShort(oxyz + it * 6 + 2) / xyzScale).coerceIn(-1f, 1f) },
        FloatArray(n) { (b.getShort(oa + it * 2).toInt() and 0xFFFF) / ampScale },
        header.getDouble("seconds").toFloat(),
        colours,
    )
}.getOrNull()

/** Kept across the trip into the visualiser, so the strip is there at once for the fold back. */
internal object SeamPortal {
    var returning by mutableStateOf(false)
    var cache: SeamNodes? = null
    var cacheKey = ""   // which file the cache holds: the id, plus .ft when drawn from the htdemucs_ft render
}

@Stable
internal class SeamState(val still: Boolean) {
    var nodes by mutableStateOf(SeamPortal.cache)
    var flow by mutableDoubleStateOf(0.0)
    var bounds by mutableStateOf<Rect?>(null)
    val reveal = Animatable(if (SeamPortal.returning || still) 1f else 0f)
    val portal = Animatable(if (SeamPortal.returning) 1f else 0f)
    var opening = false
    var cued: String? = if (SeamPortal.returning) SeamPortal.cache?.id else null
}

/** Seconds to the next bar line at [s] seconds into the set, or null with no beat grid there. */
private fun toBarLine(lib: LibraryStore, id: String, s: Double): Pair<Double, Double>? {
    val pos = lib.beats(id)?.at(s * SetBeats.RATE) ?: return null
    if (pos.bpm <= 0.0) return null
    val beat = 60.0 / pos.bpm
    return ((4 - pos.beatInBar) - pos.beatFrac) * beat to beat * 4
}

@Composable
internal fun rememberSeam(lib: LibraryStore?, playing: Boolean): SeamState {
    val ctx = LocalContext.current
    val still = remember(ScreenPrefs.noFlashing) {
        ScreenPrefs.noFlashing || runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    val seam = remember(still) { SeamState(still) }
    // Which set is at the ear, and whether its whole visual is here. Looked at again every 2 s, so a visual that arrives mid-set shows up.
    LaunchedEffect(lib, playing) {
        if (lib == null || !playing) { seam.nodes = null; return@LaunchedEffect }
        while (true) {
            val id = earAt(lib, Session.nowBoot())?.first?.id
            val ready = id != null && lib.hasVisual(id)
            // Settings > Stems in the visualiser (reported through the bridge): the strip follows the page when the set has an ft render.
            val ft = ready && ctx.getSharedPreferences("visualiser", 0).getString("stems", null) == "htdemucs_ft" && lib.visFile(id!!, "ft.nodes").let { it.exists() && it.length() > 0 }
            val key = if (ft) "$id.ft" else id
            when {
                !ready -> seam.nodes = null
                seam.nodes?.id != id || SeamPortal.cacheKey != key -> {
                    val loaded = SeamPortal.cache?.takeIf { it.id == id && SeamPortal.cacheKey == key } ?: withContext(Dispatchers.IO) { loadSeamNodes(id!!, lib.visFile(id, if (ft) "ft.nodes" else "nodes")) }
                    SeamPortal.cacheKey = key!!
                    SeamPortal.cache = loaded
                    seam.nodes = loaded
                }
            }
            delay(2000)
        }
    }
    // The cue: once per set, the nodes spill across the strip from the next bar line, over one bar.
    val shownId = seam.nodes?.id
    LaunchedEffect(shownId) {
        if (shownId == null || lib == null || seam.still || seam.cued == shownId) return@LaunchedEffect
        seam.cued = shownId
        seam.reveal.snapTo(0f)
        val at = earAt(lib, Session.nowBoot())
        val bar = at?.let { toBarLine(lib, shownId, it.second) }
        delay(((bar?.first ?: 0.0) * 1000).toLong())
        seam.reveal.animateTo(1f, tween(((bar?.second ?: 1.9) * 1000).toInt(), easing = LinearEasing))
    }
    LaunchedEffect(lib, shownId) {
        if (lib == null || shownId == null) return@LaunchedEffect
        while (true) withFrameNanos { earAt(lib, Session.nowBoot())?.let { seam.flow = it.second } }
    }
    // Coming back from the visualiser: fold into the strip, or simply fade if the strip has gone.
    LaunchedEffect(Unit) {
        if (!SeamPortal.returning) return@LaunchedEffect
        withTimeoutOrNull(500) { snapshotFlow { seam.bounds }.first { it != null } }
        seam.portal.animateTo(0f, tween(if (seam.still) 200 else 450, easing = FastOutSlowInEasing))
        SeamPortal.returning = false
    }
    return seam
}

private fun DrawScope.drawNodes(n: SeamNodes, flow: Double, reveal: Float, still: Boolean, area: Rect) {
    val grow = sqrt(area.height / 44.dp.toPx())
    if (still) {
        val step = max(1, n.t.size / STILL_NODES)
        for (i in n.t.indices step step) {
            val x = area.left + area.width * (n.t[i] / n.seconds)
            drawCircle(n.colours[n.inst[i].toInt()].copy(alpha = 0.7f), (1.dp.toPx() + 1.5.dp.toPx() * sqrt(n.amp[i])) * grow, Offset(x, area.center.y - area.height * 0.38f * n.y[i]))
        }
        return
    }
    var lo = 0
    var hi = n.t.size
    val from = (flow - SPAN_S).toFloat()
    while (lo < hi) { val m = (lo + hi) ushr 1; if (n.t[m] < from) lo = m + 1 else hi = m }
    var i = lo
    while (i < n.t.size && n.t[i] <= flow) {
        val age = (flow - n.t[i]).toFloat()
        val across = 1f - age / SPAN_S.toFloat()
        if (across <= reveal) {
            val at = Offset(area.left + area.width * across, area.center.y - area.height * 0.38f * n.y[i])
            val r = (1.2.dp.toPx() + 3.4.dp.toPx() * sqrt(n.amp[i])) * grow
            drawCircle(n.colours[n.inst[i].toInt()].copy(alpha = across.pow(0.6f)), r, at)
            if (age < 0.12f) drawCircle(Color.White.copy(alpha = 1f - age / 0.12f), r * 0.55f, at)
        }
        i++
    }
}

/** The strip itself; it takes no room until the set at the ear has its whole visual. */
@Composable
internal fun LiveSeam(seam: SeamState, lib: LibraryStore?, open: () -> Unit) {
    val scope = rememberCoroutineScope()
    val enter: () -> Unit = enter@{
        val n = seam.nodes ?: return@enter
        if (seam.opening) return@enter
        seam.opening = true
        // The opening lasts until the next bar line that leaves it time to be seen.
        val bar = lib?.let { toBarLine(it, n.id, seam.flow) }
        val seconds = when {
            seam.still -> 0.2
            bar == null -> 0.45
            bar.first < 0.35 -> bar.first + bar.second
            else -> bar.first
        }
        scope.launch {
            seam.portal.animateTo(1f, tween((min(seconds, 2.4) * 1000).toInt(), easing = FastOutSlowInEasing))
            open()
        }
    }
    AnimatedVisibility(seam.nodes != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier.fillMaxWidth().height(44.dp).onGloballyPositioned { seam.bounds = it.boundsInRoot() }
                .clip(shape).background(Color.Black, shape).border(Tokens.Stroke.card.dp, Ink.grid, shape)
                .pointerInput(Unit) {
                    var pulled = 0f
                    detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                        pulled += dy
                        if (pulled < -24.dp.toPx()) { change.consume(); enter() }
                    }
                }
                .clickable(role = Role.Button, onClick = enter)
                .semantics { contentDescription = "Enter the set. Opens the visualiser for the music playing now" },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                seam.nodes?.let { drawNodes(it, seam.flow, seam.reveal.value, seam.still, Rect(Offset.Zero, size)) }
            }
            Box(
                Modifier.align(Alignment.CenterEnd).height(44.dp)
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f), Color.Black)))
                    .padding(start = 28.dp, end = 12.dp),
                Alignment.Center,
            ) {
                BasicText("ENTER THE SET ↑", style = Tokens.Text.cardLabel.style().copy(color = Ink.text, fontSize = 10.sp))
            }
        }
    }
}

/** Over the whole screen while the strip opens into the visualiser or folds back; swallows touches meanwhile. */
@Composable
internal fun SeamPortalLayer(seam: SeamState) {
    val p = seam.portal.value
    if (p <= 0f) return
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        },
    ) {
        val full = Rect(Offset.Zero, size)
        val from = seam.bounds
        if (seam.still || from == null) { drawRect(Color.Black.copy(alpha = p)); return@Canvas }
        val area = Rect(
            from.left + (full.left - from.left) * p, from.top + (full.top - from.top) * p,
            from.right + (full.right - from.right) * p, from.bottom + (full.bottom - from.bottom) * p,
        )
        val corner = 10.dp.toPx() * (1f - p)
        drawRoundRect(Color.Black, area.topLeft, Size(area.width, area.height), CornerRadius(corner))
        clipRect(area.left, area.top, area.right, area.bottom) { seam.nodes?.let { drawNodes(it, seam.flow, 1f, false, area) } }
    }
}
