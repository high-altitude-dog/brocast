package org.beachsync.proto.ui

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Session
import org.beachsync.proto.editionAt
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Made-up swarm and buffer data, so the Swarm and Buffer screens can be designed and redesigned before the mesh exists (user, 4 Nov).
 * Everything here is replaced when the Pears spike links it for real. Every screen that shows it says so on screen.
 */
object DemoMesh {
    const val NYM = "AMBER FIG HERON"
    const val LISTENERS = 12
    const val LISTENING = "≈ 1.2 M"   // the mesh's estimate of everyone listening, always with ≈ (hand-off 21)
    const val AHEAD_TEXT = "6 h 12 m"
    class Peer(val key: String, val relayed: Boolean, val toYouKbps: Int, val fromYouKbps: Int)
    val peers = listOf(
        Peer("3f9a1c07", false, 410, 0), Peer("b21e55d0", false, 220, 180), Peer("07c4aa91", true, 0, 90), Peer("e8d3120b", false, 530, 0),
        Peer("5a60f7e2", false, 0, 260), Peer("91bb04c3", true, 140, 0), Peer("2d7e8ff5", false, 310, 120), Peer("c40913ab", false, 0, 0),
        Peer("6ef2b718", false, 180, 0), Peer("a8051d6c", true, 0, 0), Peer("14c9e3d4", false, 260, 210), Peer("7b3a9052", false, 90, 0),
    )
    const val STATIONS = 2
    const val FOUND_NOT_CONNECTED = 5
    const val SENT_TODAY_MB = 612
}

@Composable
internal fun DemoBanner(text: String = "Made up until the Pears spike links the real swarm.") {
    Row(Modifier.fillMaxWidth().background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.warn, RoundedCornerShape(Tokens.Radius.tile.dp)).padding(10.dp)) {
        BasicText("DEMO DATA  ", style = Tokens.Text.cardLabel.style(Ink.warn))
        BasicText(text, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

@Composable
private fun StatTile(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxHeight().background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = 9.5.sp), maxLines = 1)
        BasicText(value, style = Tokens.Text.value.style(), maxLines = 1)
        BasicText(detail, style = Tokens.Text.small.style(), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.label(text: String, x: Float, y: Float, size: Float, color: Color, align: Paint.Align = Paint.Align.CENTER) {
    drawIntoCanvas { c ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size.sp.toPx(); this.color = color.toArgb(); textAlign = align; typeface = android.graphics.Typeface.MONOSPACE }
        c.nativeCanvas.drawText(text, x, y, p)
    }
}

/** A made-up but steady loudness curve, so the waveform looks like music and never changes between frames. */
private fun loudness(i: Int): Float {
    val a = (sin(i * 0.37) * 0.5 + 0.5).toFloat()
    val b = (sin(i * 1.91 + 1.3) * 0.5 + 0.5).toFloat()
    return (0.25f + 0.5f * a + 0.25f * b).coerceIn(0.1f, 1f)
}

private val CLOCK_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

/** Swarm (demo data): you in the middle, the station on top, listeners round the edge, blocks riding each lane. */
@Composable
fun SwarmScreen(back: () -> Unit) {
    val phase by rememberInfiniteTransition(label = "swarm").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val peers = DemoMesh.peers
    val connected = peers.size
    val relayed = peers.count { it.relayed }
    val toYou = peers.sumOf { it.toYouKbps }
    val fromYou = peers.sumOf { it.fromYouKbps }
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Swarm", back)
        DemoBanner()
        BasicText("● Sharing · ${peers.count { it.fromYouKbps > 0 }} listeners", style = Tokens.Text.bodyStrong.style(Ink.accent))
        Canvas(Modifier.fillMaxWidth().height(330.dp).semantics { contentDescription = "The swarm: you in the centre, $connected listeners round you and ${DemoMesh.STATIONS} station servers above. Demo data." }) {
            val me = Offset(size.width / 2f, 190.dp.toPx())
            val rx = size.width * 0.40f; val ry = 100.dp.toPx()
            fun lane(from: Offset, to: Offset, perp: Float, active: Boolean, relayed: Boolean, filled: Boolean, k: Int) {
                val dx = to.x - from.x; val dy = to.y - from.y; val len = hypot(dx, dy)
                val ux = dx / len; val uy = dy / len
                val ox = -uy * perp; val oy = ux * perp
                val a = Offset(from.x + ux * 24.dp.toPx() + ox, from.y + uy * 24.dp.toPx() + oy)
                val b = Offset(to.x - ux * 12.dp.toPx() + ox, to.y - uy * 12.dp.toPx() + oy)
                val col = if (active) Ink.accent else Ink.faint
                drawLine(col, a, b, 1.5.dp.toPx(), pathEffect = if (relayed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
                val head = 5.dp.toPx()
                drawLine(col, b, Offset(b.x - ux * head - uy * head * 0.6f, b.y - uy * head + ux * head * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(col, b, Offset(b.x - ux * head + uy * head * 0.6f, b.y - uy * head - ux * head * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
                if (active) {
                    val t = (phase + k * 0.17f) % 1f
                    val p = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    val alpha = if (t < 0.15f) t / 0.15f else if (t > 0.85f) (1f - t) / 0.15f else 1f
                    val s = 5.dp.toPx()
                    if (filled) drawRect(Ink.accent.copy(alpha = alpha), Offset(p.x - s / 2, p.y - s / 2), Size(s, s))
                    else drawRect(Ink.accent.copy(alpha = alpha), Offset(p.x - s / 2, p.y - s / 2), Size(s, s), style = Stroke(1.dp.toPx()))
                }
            }
            // Station seed servers on top: only a lane to you, because phones never upload to the station.
            for (i in 0 until DemoMesh.STATIONS) {
                val s = Offset(size.width * (0.35f + 0.30f * i), 28.dp.toPx())
                lane(s, me, 0f, true, false, true, i)
                drawRect(Ink.text, Offset(s.x - 9.dp.toPx(), s.y - 9.dp.toPx()), Size(18.dp.toPx(), 18.dp.toPx()))
                label("STATION", s.x, s.y - 14.dp.toPx(), 9f, Ink.textSecondary)
            }
            // Found but not connected: grey dots on an outer ring.
            for (i in 0 until DemoMesh.FOUND_NOT_CONNECTED) {
                val a = Math.toRadians(200.0 + i * 28.0)
                drawCircle(Ink.faint, 3.dp.toPx(), Offset(me.x + (rx + 26.dp.toPx()) * cos(a).toFloat(), me.y + (ry + 26.dp.toPx()) * sin(a).toFloat() * 0.95f))
            }
            peers.forEachIndexed { i, p ->
                val a = Math.toRadians(-90.0 + 15.0 + i * (330.0 / connected))
                val pos = Offset(me.x + rx * cos(a).toFloat(), me.y + ry * sin(a).toFloat())
                lane(pos, me, 3.dp.toPx(), p.toYouKbps > 0, p.relayed, true, i)       // inner lane: to you
                lane(me, pos, -3.dp.toPx(), p.fromYouKbps > 0, p.relayed, false, i + 5) // outer lane: from you
                drawCircle(Ink.surface, 9.dp.toPx(), pos); drawCircle(Ink.textSecondary, 9.dp.toPx(), pos, style = Stroke(1.5.dp.toPx()))
                label(p.key.take(4), pos.x, pos.y + 22.dp.toPx(), 9f, Ink.textSecondary)
            }
            drawCircle(Ink.accent.copy(alpha = 0.18f), 24.dp.toPx(), me)
            drawCircle(Ink.accent, 12.dp.toPx(), me)
            label("YOU", me.x, me.y + 30.dp.toPx(), 9f, Ink.text)
        }
        Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("CONNECTED", "$connected", "$relayed relayed", Modifier.weight(1f))
            StatTile("TO YOU", "${toYou} kb/s", "${(100 * peers.count { it.toYouKbps > 0 } / connected)}% from listeners", Modifier.weight(1f))
            StatTile("FROM YOU", "${fromYou} kb/s", "to ${peers.count { it.fromYouKbps > 0 }} listeners", Modifier.weight(1f))
        }
        Card {
            BasicText("PASSED ON TODAY", style = Tokens.Text.cardLabel.style())
            val hours = DemoMesh.SENT_TODAY_MB / 72.0
            BasicText("${DemoMesh.SENT_TODAY_MB} MB · about ${"%.1f".format(hours)} h of listening for others", style = Tokens.Text.body.style())
        }
        Card {
            BasicText("BUSIEST LISTENERS", style = Tokens.Text.cardLabel.style())
            for (p in peers.sortedByDescending { it.toYouKbps + it.fromYouKbps }.take(4)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText(p.key, style = Tokens.Text.valueSmall.style())
                    BasicText(if (p.relayed) "relayed" else "direct", style = Tokens.Text.small.style(Ink.textSecondary))
                    BasicText("↓${p.toYouKbps}  ↑${p.fromYouKbps} kb/s", style = Tokens.Text.small.style(Ink.textSecondary))
                }
            }
        }
        BasicText("Listeners are found by key, from anywhere, never by location, and can see your network address.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/** Buffer (demo data for the download state): the music around the playhead on one time axis, with the frontier and blocks arriving. */
@Composable
fun BufferScreen(state: BenchState, back: () -> Unit, openSwarm: () -> Unit) {
    val phase by rememberInfiniteTransition(label = "buffer").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val boot = Session.nowBoot()
    val lib = state.library
    val item = nowPlayingItem(lib, boot)
    val utc = FlowTime.fit()?.utcAt(boot) ?: (System.currentTimeMillis() * 1_000_000L)
    var picked by remember { mutableStateOf<Int?>(null) }
    val upNext = lib?.let { l -> editionAt(l.editions, utc)?.items?.let { items -> items.indexOf(item).let { i -> if (i < 0) emptyList() else items.drop(i + 1) + items.take(i) } } }.orEmpty().take(3)
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Buffer", back)
        DemoBanner()
        BasicText(item?.title ?: "Beach Sync Radio", style = Tokens.Text.bodyStrong.style(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        item?.artist?.ifEmpty { null }?.let { BasicText(it, style = Tokens.Text.small.style(Ink.textSecondary)) }
        BasicText("● Filling · ${DemoMesh.AHEAD_TEXT} ahead", style = Tokens.Text.bodyStrong.style(Ink.accent))
        BasicText("Tap any block near the playhead to see where it came from and why it can be trusted.", style = Tokens.Text.small.style(Ink.textSecondary))
        Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(Unit) {
            detectTapGestures { p -> val v = p.x / (size.width / 390f); if (v >= 60f && v < 204f) picked = ((v - 60f) / 12f).toInt() }
        }.semantics { contentDescription = "Buffer timeline: five seconds played, the next twelve seconds block by block, then everything downloaded to the frontier six hours ahead, then not yet. Demo data." }) {
            val f = size.width / 390f
            val mid = 74.dp.toPx(); val h = 44.dp.toPx()
            fun x(v: Float) = v * f
            // Played: four thin bars a second, in muted grey.
            for (i in 0 until 20) drawRect(Ink.muted, Offset(x(i * 3f), mid - h * 0.5f * loudness(i)), Size(2f * f, h * loudness(i)))
            // Near: one tile per second block, four accent bars each.
            for (s in 0 until 12) {
                drawRect(Ink.accent.copy(alpha = 0.10f), Offset(x(60f + s * 12f) + 0.5f, mid - h / 2f), Size(11f * f, h))
                for (b in 0 until 4) { val l = loudness(100 + s * 4 + b); drawRect(Ink.accent, Offset(x(60f + s * 12f + b * 3f), mid - h * 0.5f * l), Size(2f * f, h * l)) }
            }
            picked?.let { s -> drawRect(Ink.text, Offset(x(60f + s * 12f) - 1.dp.toPx(), mid - h / 2f - 3.dp.toPx()), Size(11f * f + 2.dp.toPx(), h + 6.dp.toPx()), style = Stroke(1.5.dp.toPx())) }
            // Downloaded, far: a loudness band at 55%, on a log axis.
            var px = x(204f)
            while (px < x(330f)) { val l = loudness((px / 3f).toInt() + 300); drawRect(Ink.accent.copy(alpha = 0.55f), Offset(px, mid - h * 0.5f * l), Size(2.2f * f, h * l)); px += 3f * f }
            // Not yet: a dashed rail.
            drawLine(Ink.faint, Offset(x(330f), mid), Offset(x(390f), mid), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            // Frontier and playhead.
            drawLine(Ink.accent, Offset(x(330f), mid - h / 2 - 8.dp.toPx()), Offset(x(330f), mid + h / 2 + 8.dp.toPx()), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            drawLine(Ink.text, Offset(x(60f), mid - h / 2 - 10.dp.toPx()), Offset(x(60f), mid + h / 2 + 8.dp.toPx()), 2.dp.toPx())
            label(CLOCK_FMT.format(Instant.ofEpochSecond(0, utc)), x(60f), mid - h / 2 - 16.dp.toPx(), 9f, Ink.text)
            label("FRONTIER +${DemoMesh.AHEAD_TEXT}", size.width, mid - h / 2 - 16.dp.toPx(), 9f, Ink.accent, Paint.Align.RIGHT)
            // Blocks in flight: from the station (solid) and from listeners (outline), easing onto the frontier.
            for (k in 0 until 3) {
                val t = (phase + k / 3f) % 1f
                val ease = 1f - (1f - t) * (1f - t)
                val cx = x(390f) - (x(390f) - x(330f)) * ease
                val s = 9.dp.toPx() * (1f - 0.4f * ease); val y = mid + h / 2 + 22.dp.toPx() + (k % 2) * 2.dp.toPx()
                if (k != 1) drawRect(Ink.accent.copy(alpha = 1f - 0.5f * ease), Offset(cx - s / 2, y - s / 2), Size(s, s))
                else drawRect(Ink.accent, Offset(cx - s / 2, y - s / 2), Size(s, s), style = Stroke(1.dp.toPx()))
            }
            // Axis labels: seconds in the near zone, then log.
            val axis = listOf(0f to "−5 s", 120f to "+5 s", 180f to "+10 s", 225f to "1 m", 262f to "10 m", 300f to "1 h", 322f to "6 h")
            for ((v, text) in axis) label(text, x(v), size.height - 6.dp.toPx(), 8.5f, Ink.textSecondary, if (v == 0f) Paint.Align.LEFT else Paint.Align.CENTER)
            label("24 h", size.width, size.height - 6.dp.toPx(), 8.5f, Ink.textSecondary, Paint.Align.RIGHT)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("▮ played", style = Tokens.Text.small.style(Ink.muted)); BasicText("▮ downloaded", style = Tokens.Text.small.style(Ink.accent))
            BasicText("▯ not yet", style = Tokens.Text.small.style(Ink.faint)); BasicText("■ station  □ listeners", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("AHEAD", DemoMesh.AHEAD_TEXT, "no gaps", Modifier.weight(1f))
            StatTile("FILLING", "12×", "real time", Modifier.weight(1f))
            StatTile("FROM STATION", "62%", "the rest from listeners", Modifier.weight(1f).clickable(role = Role.Button, onClick = openSwarm))
        }
        Card {
            BasicText("UP NEXT", style = Tokens.Text.cardLabel.style())
            if (upNext.isEmpty()) BasicText("Nothing after this set yet.", style = Tokens.Text.small.style(Ink.textSecondary))
            for (u in upNext) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BasicText(u.title, Modifier.weight(1f), Tokens.Text.body.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText("✓ ready", style = Tokens.Text.small.style(Ink.accent))
                }
            }
        }
    }
    picked?.let { BlockProvenanceSheet(it, utc, item?.id ?: "set") { picked = null } }
    }
}
