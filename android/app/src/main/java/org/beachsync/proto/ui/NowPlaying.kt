package org.beachsync.proto.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beachsync.proto.FlowTime
import org.beachsync.proto.R
import org.beachsync.proto.Session
import org.beachsync.proto.Source
import org.beachsync.proto.fmtDuration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin

private const val SECOND_MS = 1000L
private const val SECOND_NS = 1_000_000_000L
private const val LIMIT_NS = 5_000_000.0    // 5 ms, from the badge rules in design/ui-spec.md

/** A tick just after each flowtime second begins, so the digits change on time without updating more than once a second. */
@Composable
private fun rememberSecondTick(): State<Long> = produceState(Session.nowBoot()) {
    while (true) {
        val fit = FlowTime.fit()
        val leftNs = if (fit != null) SECOND_NS - Math.floorMod(fit.utcAt(Session.nowBoot()), SECOND_NS) else SECOND_NS
        delay(leftNs / 1_000_000L + 1L)
        withFrameNanos { }
        value = Session.nowBoot()
    }
}

/** Where an error falls on the design's log scale, from 1 µs (0) to 10 ms (1). */
private fun logScale(ns: Double) = if (ns.isNaN() || ns <= 0.0) 0f else ((log10(ns) - 3.0) / 4.0).coerceIn(0.0, 1.0).toFloat()

@Composable
fun NowPlaying(state: BenchState, actions: BenchActions, openLog: () -> Unit, openNudge: () -> Unit, openBench: () -> Unit) {
    val boot by rememberSecondTick()
    val s = state.session
    val listen = {
        if (s == null) state.stream = true
        actions.startOrStop()
    }
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Masthead(s, openBench)
        BasicText(s?.stream?.manifest?.source?.ifEmpty { null } ?: "Beach Sync Radio", style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("Live", style = Tokens.Text.body.style(Ink.textSecondary).copy(fontSize = 13.sp))
        Dial(s, boot)
        SyncBadge(s, boot)
        // There's no Play control in the design yet. This is the test bench's button until there is.
        RunButton(s != null, listen, "PLAY", "STOP")
        EventRow(state.eventLog, SECOND_MS, openLog)
        Budget(s, boot)
        NudgeRow(state, openNudge, SECOND_MS)
        Row(Modifier.fillMaxWidth().height(IntrinsicTile), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val gold = when (s?.gps?.gold) { true -> "Gold · "; false -> "Silver · "; null -> "" }
            Tile("SOURCE", FlowTime.chosen?.name?.takeIf { s != null } ?: "—", if (s == null) "" else if (s.gps.active) "$gold${s.gps.used} sats" else "Satellites off", Modifier.weight(1f))
            Tile("HEADPHONES", "${s?.s?.lagMs ?: state.lag} ms", "typed", Modifier.weight(1f))
            Tile("AHEAD", "—", "downloaded", Modifier.weight(1f))
        }
        Card { SatellitesRow(state.satellites, actions::setSatellites, "Position stays on this phone · off = Privacy mode") }
    }
}

private val IntrinsicTile = 84.dp

/** Holding the station name opens the test bench: the developer way in, with nothing on screen for listeners to trip over. */
@Composable
private fun Masthead(s: Session?, openBench: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).pointerInput(Unit) { detectTapGestures(onLongPress = { openBench() }) }.semantics { contentDescription = "Beach Sync Radio. Hold to open the test bench." },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText("BEACH SYNC RADIO", style = Tokens.Text.eyebrow.style())
            val d = s?.stream?.manifest?.dMs
            BasicText(if (d != null) "Live · D ${d / 1000} s" else "Live · D —", style = Tokens.Text.bodyStrong.style())
        }
        // These screens aren't built yet, so the buttons are dimmed and do nothing.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (icon in HeaderIcon.entries) IconBox(icon)
        }
    }
}

private enum class HeaderIcon(val label: String) { TIME("Time signals, not available yet"), CALIBRATION("Calibration, not available yet"), ARCHIVE("Archive, not available yet"), SETTINGS("Settings, not available yet") }

@Composable
private fun IconBox(icon: HeaderIcon) {
    Canvas(
        Modifier.size(Tokens.Space.touchTarget.dp).alpha(0.45f).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.control.dp)).padding(12.dp)
            .semantics { contentDescription = icon.label },
    ) {
        val w = size.width
        val line = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            HeaderIcon.TIME -> {
                drawCircle(Ink.text, w * 0.45f, style = line)
                drawLine(Ink.text, center, Offset(w * 0.5f, w * 0.22f), line.width, StrokeCap.Round)
                drawLine(Ink.text, center, Offset(w * 0.7f, w * 0.6f), line.width, StrokeCap.Round)
            }
            HeaderIcon.CALIBRATION -> {
                drawArc(Ink.text, 180f, 180f, false, Offset(w * 0.08f, w * 0.1f), Size(w * 0.84f, w * 0.84f), style = line)
                drawRoundRect(Ink.text, Offset(w * 0.04f, w * 0.55f), Size(w * 0.2f, w * 0.36f), CornerRadius(w * 0.06f))
                drawRoundRect(Ink.text, Offset(w * 0.76f, w * 0.55f), Size(w * 0.2f, w * 0.36f), CornerRadius(w * 0.06f))
            }
            HeaderIcon.ARCHIVE -> {
                drawRoundRect(Ink.text, Offset(w * 0.04f, w * 0.12f), Size(w * 0.92f, w * 0.24f), CornerRadius(w * 0.04f), style = line)
                drawRoundRect(Ink.text, Offset(w * 0.12f, w * 0.36f), Size(w * 0.76f, w * 0.52f), CornerRadius(w * 0.04f), style = line)
                drawLine(Ink.text, Offset(w * 0.38f, w * 0.54f), Offset(w * 0.62f, w * 0.54f), line.width, StrokeCap.Round)
            }
            HeaderIcon.SETTINGS -> {
                for ((y, x) in listOf(0.3f to 0.66f, 0.7f to 0.34f)) {
                    drawLine(Ink.text, Offset(0f, w * y), Offset(w, w * y), line.width, StrokeCap.Round)
                    drawCircle(Ink.bg, w * 0.13f, Offset(w * x, w * y))
                    drawCircle(Ink.text, w * 0.13f, Offset(w * x, w * y), style = line)
                }
            }
        }
    }
}

/** The Brocast clock. The design's sub-second dial is left out, because this screen updates once a second. */
@Composable
private fun Dial(s: Session?, boot: Long) {
    val fit = FlowTime.fit()
    val utc = if (s != null && fit != null) fit.utcAt(boot) else null
    val now = utc?.let { Instant.ofEpochSecond(0, it) }
    val err = s?.syncErrorNs(boot) ?: Double.NaN
    val flowErr = if (s != null && fit != null) fmtDuration(fit.errAt(boot)) else "—"
    val resources = LocalContext.current.resources
    val top = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.space_grotesk); textAlign = Paint.Align.CENTER; letterSpacing = 0.18f } }
    val bottom = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrains_mono); textAlign = Paint.Align.CENTER; letterSpacing = 0.08f } }
    val topPath = remember { android.graphics.Path() }
    val bottomPath = remember { android.graphics.Path() }
    Box(Modifier.fillMaxWidth().height(214.dp), Alignment.Center) {
        Canvas(Modifier.size(214.dp).semantics { contentDescription = "Flowtime error $flowErr" }) {
            val c = center
            fun at(angle: Float, r: Float) = Offset(c.x + r * sin(Math.toRadians(angle.toDouble())).toFloat(), c.y - r * cos(Math.toRadians(angle.toDouble())).toFloat())
            val ring = 76.dp.toPx()
            for (i in 0 until 60) {
                val a = i * 6f
                drawLine(if (i == 0) Ink.accent else Ink.faint, at(a, 80.dp.toPx()), at(a, if (i % 5 == 0) 87.dp.toPx() else 84.dp.toPx()), if (i == 0) 2.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(Ink.track, ring, c, style = Stroke(2.dp.toPx()))
            if (now != null) {
                val seconds = now.epochSecond % 60
                val box = Size(ring * 2, ring * 2)
                val corner = Offset(c.x - ring, c.y - ring)
                drawArc(Ink.accent, -90f, seconds * 6f, false, corner, box, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val inner = 66.dp.toPx()
                val width = 1.5.dp.toPx() + logScale(err) * 10.dp.toPx()
                drawCircle(Ink.accent.copy(alpha = 0.22f), inner, c, style = Stroke(width))
                drawCircle(Ink.accent.copy(alpha = 0.55f), inner, c, style = Stroke(1.dp.toPx()))
            }
            drawIntoCanvas { canvas ->
                top.textSize = Tokens.Text.bezelTop.size.sp.toPx(); top.color = Ink.textSecondary.toArgb()
                bottom.textSize = Tokens.Text.bezelBottom.size.sp.toPx(); bottom.color = Ink.accent.toArgb()
                val rt = 92.dp.toPx()
                topPath.rewind(); topPath.addArc(c.x - rt, c.y - rt, c.x + rt, c.y + rt, -170f, 160f)
                canvas.nativeCanvas.drawTextOnPath("TEDDINGTON · GREENWICH", topPath, 0f, 0f, top.apply { textAlign = Paint.Align.CENTER })
                val rb = 101.dp.toPx()
                bottomPath.rewind(); bottomPath.addArc(c.x - rb, c.y - rb, c.x + rb, c.y + rb, 170f, -160f)
                canvas.nativeCanvas.drawTextOnPath("FLOWTIME ±$flowErr", bottomPath, 0f, 0f, bottom)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(now?.let { CLOCK_ZONE.format(it) } ?: " ", style = Tokens.Text.clockZone.style())
            BasicText(now?.let { CLOCK_DIGITS.format(it) } ?: "--:--:--", style = Tokens.Text.clock.style())
            val local = ZoneId.systemDefault()
            val different = now != null && local.rules.getOffset(now) != UK.rules.getOffset(now)
            BasicText(if (different) shortZone(LOCAL_DIGITS.withZone(local).format(now)) + " · local" else " ", style = Tokens.Text.clockLocal.style().copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

private class Badge(val words: String, val mark: String, val tint: Color, val figure: String, val detail: String)

/** The states with data today. REJOINING, CLOCK CHECK FAILED and SILENT · OVER LIMIT need detection the app doesn't do yet. */
private fun badge(s: Session?, boot: Long): Badge {
    if (s == null) return Badge("STOPPED", "■", Ink.textSecondary, "", "")
    val err = s.syncErrorNs(boot)
    val figure = if (err.isNaN()) "" else "±" + fmtDuration(err)
    val over = err.isNaN() || err > LIMIT_NS
    val grade = when (s.gps.gold) { true -> "GPS · Gold"; false -> "GPS · Silver"; null -> "GPS" }
    return when {
        FlowTime.fit() == null -> Badge("NOT IN SYNC", "✕", Ink.danger, "", "No time source")
        s.stream != null && s.stream.manifest == null -> Badge("NOT IN SYNC", "✕", Ink.danger, "", "No stream")
        !s.gps.active -> Badge("PRIVACY MODE", "⊘", Ink.textSecondary, figure, FlowTime.chosen?.name ?: "")
        FlowTime.chosen == Source.NTP -> Badge("INTERNET TIME", "▲", Ink.warn, figure, "NTP")
        s.sourceName(boot) != "GPS" -> Badge("HOLDING", "○", if (over) Ink.warn else Ink.accent, figure, "Holdover")
        else -> Badge("IN SYNC", "●", if (over) Ink.warn else Ink.accent, figure, grade)
    }
}

@Composable
private fun SyncBadge(s: Session?, boot: Long) {
    val b = badge(s, boot)
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(b.tint.copy(alpha = 0.08f), shape).border(Tokens.Stroke.emphasis.dp, b.tint, shape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("${b.mark} ${b.words}", style = Tokens.Text.badge.style(if (b.tint == Ink.accent) Ink.text else b.tint), maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(b.figure, style = Tokens.Text.value.style(), maxLines = 1)
            Spacer(Modifier.width(10.dp))
            BasicText(b.detail, style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1)
        }
        val from = s?.tighteningFromNs() ?: Double.NaN
        val fit = FlowTime.fit()
        if (!from.isNaN() && fit != null) TighteningLine(from, fit.errAt(boot))
    }
}

/** Headphone lag and screenlag have no uncertainty to show yet, so their bars are empty and the total is flowtime and any gap. */
@Composable
private fun Budget(s: Session?, boot: Long) {
    val flow = if (s != null) FlowTime.fit()?.errAt(boot) ?: Double.NaN else Double.NaN
    val total = s?.syncErrorNs(boot) ?: Double.NaN
    Card {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            BasicText("SYNC ERROR BUDGET", style = Tokens.Text.cardLabel.style())
            BasicText(if (total.isNaN()) "—" else "±" + fmtDuration(total), style = Tokens.Text.valueSmall.style(Ink.accent))
        }
        BudgetRow("Flowtime", flow, Ink.accent, Ink.text)
        BudgetRow("Headphone lag", Double.NaN, Ink.warn, Ink.text)
        BudgetRow("Total", total, Ink.text, Ink.text)
        BudgetRow("Screenlag", Double.NaN, Ink.muted, Ink.muted)
    }
}

@Composable
private fun BudgetRow(label: String, ns: Double, bar: Color, words: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(100.dp), Tokens.Text.body.style(words))
        Box(Modifier.weight(1f).height(6.dp).background(Ink.track, CircleShape)) {
            if (!ns.isNaN()) Box(Modifier.fillMaxWidth(logScale(ns).coerceAtLeast(0.02f)).fillMaxHeight().background(bar, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(if (ns.isNaN()) "—" else "±" + fmtDuration(ns), Modifier.width(66.dp), Tokens.Text.valueSmall.style(words))
    }
}

@Composable
private fun Tile(label: String, value: String, detail: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = min(Tokens.Text.cardLabel.size, 9.5f).sp, letterSpacing = 0.8.sp), maxLines = 1)
        BasicText(value, style = Tokens.Text.value.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(detail, style = Tokens.Text.small.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
