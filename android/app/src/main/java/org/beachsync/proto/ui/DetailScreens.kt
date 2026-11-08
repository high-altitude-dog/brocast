package org.beachsync.proto.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Lane
import org.beachsync.proto.NudgeControl
import org.beachsync.proto.NudgeMode
import org.beachsync.proto.R
import org.beachsync.proto.Session
import org.beachsync.proto.Source
import org.beachsync.proto.fmtDuration
import org.beachsync.proto.fmtSigned
import org.beachsync.proto.gapWord
import org.beachsync.proto.tooFastNote
import org.beachsync.proto.nudgeWord
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow

private const val SCREEN_TICK_MS = 1000L
private const val TIMELINE_NS = 15 * 60 * 1_000_000_000L
private const val SAFETY_LIMIT_US = 50_000.0    // 50 ms, from the badge rules in design/ui-spec.md

/** The back arrow and title every detail screen shares, with room for one icon button. */
@Composable
internal fun ScreenHeader(title: String, back: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier.size(Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = back).padding(14.dp)
                .semantics { contentDescription = "Back" },
        ) {
            val w = size.width
            val line = 2.dp.toPx()
            drawLine(Ink.text, Offset(w * 0.65f, w * 0.1f), Offset(w * 0.25f, w * 0.5f), line, StrokeCap.Round)
            drawLine(Ink.text, Offset(w * 0.25f, w * 0.5f), Offset(w * 0.65f, w * 0.9f), line, StrokeCap.Round)
        }
        BasicText(title, Modifier.weight(1f), Tokens.Text.screenTitle.style())
        trailing()
    }
}

@Composable
internal fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

// ---- Event log ----

@Composable
fun EventLogScreen(state: BenchState, lane: Lane?, back: () -> Unit) {
    val boot = rememberTick(SCREEN_TICK_MS).value
    var filter by remember(lane) { mutableStateOf(lane) }
    val all = state.eventLog?.recent(200).orEmpty()
    val clipboard = LocalClipboardManager.current
    Screen {
        ScreenHeader("Event log", back) {
            Canvas(
                Modifier.size(Tokens.Space.touchTarget.dp).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.control.dp))
                    .clickable(role = Role.Button) {
                        clipboard.setText(AnnotatedString(all.joinToString("\n") { "${eventTime(it)} ${it.lane.name} ${it.text}" }))
                    }.padding(14.dp).semantics { contentDescription = "Copy the log" },
            ) {
                val w = size.width
                val line = Stroke(1.6.dp.toPx())
                drawRoundRect(Ink.text, Offset(0f, 0f), Size(w * 0.7f, w * 0.7f), style = line)
                drawRoundRect(Ink.text, Offset(w * 0.3f, w * 0.3f), Size(w * 0.7f, w * 0.7f), style = line)
            }
        }
        BasicText("This run · ${all.size} events · newest first · still readable after Stop", style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", filter == null, null) { filter = null }
            for (l in Lane.entries) Chip(l.name.lowercase().replaceFirstChar { it.uppercase() }, filter == l, l) { filter = l }
        }
        Timeline(all, boot)
        val shown = all.filter { filter == null || it.lane == filter }
        Card {
            if (shown.isEmpty()) BasicText("No events yet", style = Tokens.Text.body.style(Ink.muted))
            for (e in shown) {
                Row {
                    BasicText(eventTime(e), Modifier.width(64.dp), Tokens.Text.valueSmall.style(Ink.muted))
                    BasicText("${e.lane.mark()} ${e.lane.name}", Modifier.width(72.dp), Tokens.Text.valueSmall.style(e.lane.color()).copy(fontSize = 10.sp, letterSpacing = 1.sp))
                    BasicText(e.text, Modifier.weight(1f), Tokens.Text.body.style())
                }
            }
        }
        BasicText("Times are flowtime on Teddington · Greenwich time (GMT or BST). Before there is a time source, events show --:--:--.", style = Tokens.Text.small.style(Ink.muted))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, lane: Lane?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Row(
        Modifier.heightIn(min = Tokens.Space.touchTarget.dp).then(if (on) Modifier.background(Ink.text, shape) else Modifier.border(Tokens.Stroke.control.dp, Ink.grid, shape))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lane != null) {
            BasicText(lane.mark(), style = Tokens.Text.body.style(lane.color()))
            Spacer(Modifier.width(6.dp))
        }
        BasicText(label, style = Tokens.Text.body.style(if (on) Ink.bg else Ink.text).copy(fontWeight = if (on) FontWeight.Bold else null))
    }
}

/** The design's marks for the last 15 minutes. Its error trace, tapping and pinching need history the app doesn't keep yet. */
@Composable
private fun Timeline(events: List<org.beachsync.proto.Event>, boot: Long) {
    Card {
        BasicText("TIMELINE · 15 MIN", style = Tokens.Text.cardLabel.style())
        Row {
            Column(Modifier.width(62.dp)) {
                for (l in Lane.entries) Box(Modifier.height(22.dp), Alignment.CenterStart) {
                    BasicText(l.name, style = Tokens.Text.valueSmall.style(l.color()).copy(fontSize = 10.sp, letterSpacing = 1.sp))
                }
            }
            Canvas(Modifier.weight(1f).height(88.dp)) {
                val row = size.height / Lane.entries.size
                Lane.entries.forEachIndexed { i, _ ->
                    val y = row * (i + 0.5f)
                    drawLine(Ink.track, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val r = 4.dp.toPx()
                for (e in events) {
                    val age = boot - e.boot
                    if (age < 0 || age > TIMELINE_NS) continue
                    val at = Offset(size.width * (1f - age.toFloat() / TIMELINE_NS), row * (e.lane.ordinal + 0.5f))
                    laneMark(e.lane, at, r)
                }
            }
        }
    }
}

/** Marks, not only colours, tell the lanes apart. */
private fun DrawScope.laneMark(lane: Lane, at: Offset, r: Float) {
    val c = lane.color()
    when (lane) {
        Lane.CLOCK -> drawCircle(c, r, at)
        Lane.AUDIO -> drawRect(c, Offset(at.x - r, at.y - r), Size(r * 2, r * 2))
        Lane.NET -> drawPath(Path().apply { moveTo(at.x, at.y - r); lineTo(at.x + r, at.y + r); lineTo(at.x - r, at.y + r); close() }, c)
        Lane.OUTPUT -> drawCircle(c, r, at, style = Stroke(1.5.dp.toPx()))
    }
}

// ---- Stream gap ----

private fun periodToProgress(s: Double) = (ln(s / NudgeControl.MIN_PERIOD_S) / ln(NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S)).toFloat()

private fun progressToPeriod(p: Float) = NudgeControl.MIN_PERIOD_S * (NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S).pow(p.toDouble())

@Composable
fun StreamGapScreen(state: BenchState, actions: BenchActions, back: () -> Unit) {
    val boot by rememberUpdatedState(rememberTick(SCREEN_TICK_MS).value)
    val s = state.session
    val n = s?.nudge
    val gap = n?.gapAt(boot) ?: 0L
    val closing = n != null && n.state.closing && gap != 0L
    val manual = state.nudgeMode == NudgeMode.MANUAL
    val period = if (n != null && gap != 0L) n.effectivePeriodS(abs(gap)) else state.nudgePeriodS ?: NudgeControl.RECOMMENDED_S
    val rate = if (closing) n.state.rate else if (gap != 0L) gap / (period * 1e9) else 0.0   // signed: positive speeds the audio up
    Screen {
        ScreenHeader("Stream gap", back)
        Card {
            BasicText("HELD GAP", style = Tokens.Text.cardLabel.style())
            BasicText(if (gap == 0L) "In step" else fmtDuration(gap.toDouble()), style = Tokens.Text.hero.style())
            if (gap != 0L) BasicText(gapWord(gap) + (if (closing) " · " + nudgeWord(rate).lowercase() else ""), style = Tokens.Text.bodyStrong.style(Ink.accent))
            if (gap != 0L) {
                val why = state.eventLog?.recent(200).orEmpty().firstOrNull { it.lane == Lane.CLOCK }
                if (why != null) BasicText("Latest clock event at ${eventTime(why)}: ${why.text}", style = Tokens.Text.body.style())
            }
            val fit = FlowTime.fit()
            if (s != null && fit != null) BasicText("Separate from flowtime's own error, ±${fmtDuration(fit.errAt(boot))}", style = Tokens.Text.small.style())
        }
        Row(Modifier.fillMaxWidth().border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.control.dp))) {
            for (mode in NudgeMode.entries) {
                val on = mode == state.nudgeMode
                Box(
                    Modifier.weight(1f).heightIn(min = Tokens.Space.touchTarget.dp).background(if (on) Ink.text else Color.Transparent, RoundedCornerShape(Tokens.Radius.control.dp))
                        .clickable(role = Role.RadioButton) { actions.setNudgeMode(mode) },
                    Alignment.Center,
                ) {
                    BasicText(mode.name.lowercase().replaceFirstChar { it.uppercase() }, style = Tokens.Text.bodyStrong.style(if (on) Ink.bg else Ink.text))
                }
            }
        }
        Card {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                BasicText("CLOSE IT OVER", style = Tokens.Text.cardLabel.style())
                BasicText("%.0f s".format(Locale.ROOT, period), style = Tokens.Text.value.style(Ink.accent))
            }
            val fastest = if (gap != 0L) NudgeControl.fastestS(gap) else 0.0
            Box(Modifier.alpha(if (manual) 1f else 0.45f)) {
                PeriodSlider(periodToProgress(period), if (fastest > NudgeControl.MIN_PERIOD_S) periodToProgress(fastest) else 0f, periodToProgress(NudgeControl.RECOMMENDED_S), manual) { actions.setNudgePeriod(progressToPeriod(it)) }
            }
            SliderLabels()
            BasicText(
                if (fastest > NudgeControl.MIN_PERIOD_S) tooFastNote(gap, fastest)
                else "Log scale. rec. marks the recommended period.",
                style = Tokens.Text.small.style(),
            )
            if (manual) BasicText(
                "Use recommended",
                Modifier.heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button) { actions.setNudgePeriod(null) }.padding(vertical = 14.dp),
                Tokens.Text.body.style(Ink.accent),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InsetTile("TIME LEFT", if (gap == 0L) "—" else "%.1f s".format(Locale.ROOT, if (closing) n.secondsLeft(boot) else period), "", Modifier.weight(1f))
                InsetTile("PITCH CHANGE", if (rate == 0.0) "—" else fmtPitch(rate), if (rate == 0.0) "" else "%.1f ¢".format(Locale.ROOT, abs(1200 * log2(1 + rate))), Modifier.weight(1f))
            }
            if (gap != 0L) Preview(gap, period)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val usable = manual && gap != 0L && n != null
            Box(
                Modifier.weight(2f).heightIn(min = 52.dp).alpha(if (usable) 1f else 0.4f).background(Ink.accent, RoundedCornerShape(Tokens.Radius.pill.dp))
                    .clickable(usable, role = Role.Button) { val now = Session.nowBoot(); if (n!!.state.closing) n.pause(now) else n.go(now) },
                Alignment.Center,
            ) { BasicText(if (closing) "PAUSE" else "▶ GO", style = Tokens.Text.badge.style(Ink.onAccent).copy(fontSize = 15.sp)) }
            Box(
                Modifier.weight(1f).heightIn(min = 52.dp).alpha(if (usable) 1f else 0.4f).border(Tokens.Stroke.emphasis.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.pill.dp))
                    .clickable(usable, role = Role.Button) { n!!.jump(Session.nowBoot()) },
                Alignment.Center,
            ) { BasicText("Jump now", style = Tokens.Text.bodyStrong.style()) }
        }
        BasicText("Go turns into Pause while the gap closes. Jump fixes it at once, with a short fade. Auto closes every gap at the recommended period.", style = Tokens.Text.small.style())
    }
}

@Composable
private fun InsetTile(label: String, value: String, extra: String, modifier: Modifier) {
    Column(
        modifier.background(Ink.bg, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = 9.5.sp), maxLines = 1)
        BasicText(if (extra.isEmpty()) value else "$value  $extra", style = Tokens.Text.value.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A real slider for screen readers as well as fingers. Compose's own lives in Material, which the app doesn't otherwise need. */
@Composable
private fun PeriodSlider(progress: Float, tooFast: Float, recommended: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val p = progress.coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .semantics {
                contentDescription = "Period, 1 second to 5 minutes"
                stateDescription = "%.0f seconds".format(Locale.ROOT, progressToPeriod(p))
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                if (enabled) setProgress { change(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()
                    drag(down.id) { c ->
                        change((c.position.x / width).coerceIn(0f, 1f))
                        c.consume()
                    }
                }
            },
    ) {
        val y = size.height / 2
        val x = size.width * p
        val thick = 6.dp.toPx()
        val start = size.width * tooFast
        drawLine(Ink.track, Offset(0f, y), Offset(size.width, y), thick, StrokeCap.Round)
        if (tooFast > 0f) drawLine(Ink.muted, Offset(0f, y), Offset(start, y), thick, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        if (x > start) drawLine(Ink.accent, Offset(start, y), Offset(x, y), thick)
        val rec = size.width * recommended
        drawLine(Ink.text, Offset(rec, y - 8.dp.toPx()), Offset(rec, y + 8.dp.toPx()), 1.5.dp.toPx())
        drawCircle(Ink.accent, 10.dp.toPx(), Offset(x, y))
    }
}

@Composable
private fun SliderLabels() {
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val style = Tokens.Text.valueSmall.style(Ink.muted).copy(fontSize = 10.sp)
        val rec = periodToProgress(NudgeControl.RECOMMENDED_S)
        BasicText("1 s", style = style)
        BasicText("10 s", Modifier.offset(x = maxWidth * periodToProgress(10.0) - 12.dp), style)
        BasicText("rec. 1 min", Modifier.offset(x = maxWidth * rec - 28.dp), style.copy(color = Ink.text))
        BasicText("5 min", Modifier.align(Alignment.TopEnd), style)
    }
}

@Composable
private fun Preview(gap: Long, period: Double) {
    val label = "Gap falls from ${fmtDuration(gap.toDouble())} to zero in %.0f s".format(Locale.ROOT, period)
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = label }) {
        val end = size.width * 0.8f
        val line = 2.dp.toPx()
        drawLine(Ink.faint, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx())
        drawPath(Path().apply { moveTo(0f, 2f); lineTo(end, size.height - 1f); lineTo(0f, size.height - 1f); close() }, Ink.accent.copy(alpha = 0.14f))
        drawLine(Ink.accent, Offset(0f, 2f), Offset(end, size.height - 1f), line)
        drawLine(Ink.accent, Offset(end, size.height - 1f), Offset(size.width, size.height - 1f), line)
    }
}

// ---- Time signals ----

/** The app asks one NPL server at a time, so the design's several NTP nodes and rows are one. Satellite positions aren't collected yet. */
@Composable
fun TimeSignalsScreen(state: BenchState, actions: BenchActions, openSatellites: () -> Unit, back: () -> Unit) {
    val boot = rememberTick(SCREEN_TICK_MS).value
    val s = state.session
    val fit = FlowTime.fit()
    var picked by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.fillMaxSize()) {
    Screen {
        ScreenHeader("Time signals", back)
        Card {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                BasicText("SOURCES", style = Tokens.Text.cardLabel.style())
                val chosen = FlowTime.chosen
                BasicText(
                    if (s != null && chosen != null && fit != null) "${chosen.name} in charge · ±${fmtDuration(fit.errAt(boot))}" else "No source",
                    style = Tokens.Text.valueSmall.style(if (s != null && chosen != null) Ink.accent else Ink.muted),
                )
            }
            SourcesField(s, boot, picked) { picked = it }
            BasicText("Node size = accuracy. Tap a node to see what it is.", style = Tokens.Text.small.style())
        }
        Card {
            BasicText("SKY", style = Tokens.Text.cardLabel.style())
            SatellitesRow(state.satellites, actions::setSatellites, "Position stays on this phone · Off = Privacy mode")
            BasicText("Your chip and sky in Satellites", Modifier.clickable(onClick = openSatellites).padding(vertical = 6.dp), Tokens.Text.body.style(Ink.accent))
            if (s != null && s.gps.active) {
                BasicText("${s.gps.used} used / ${s.gps.seen} seen", style = Tokens.Text.value.style())
                val grade = when (s.gps.gold) { true -> "Gold · "; false -> "Silver · "; null -> "" }
                BasicText("${grade}full tracking", style = Tokens.Text.small.style(Ink.textSecondary))
            } else BasicText("—", style = Tokens.Text.value.style(Ink.muted))
        }
        Card {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                BasicText("NTP SERVERS", style = Tokens.Text.cardLabel.style())
                BasicText("round trip · vs GPS", style = Tokens.Text.small.style())
            }
            val ntp = s?.ntp
            if (ntp == null || ntp.samples == 0) BasicText(if (ntp == null) "—" else "Asking ${ntp.host}", style = Tokens.Text.body.style(Ink.muted))
            else Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(ntp.host, style = Tokens.Text.valueSmall.style().copy(fontSize = 13.sp))
                    BasicText("S${ntp.stratum} · ${ntp.refId} · in use", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                BasicText(fmtDuration(ntp.rttMs * 1e6), Modifier.padding(horizontal = 8.dp), Tokens.Text.valueSmall.style())
                BasicText(fmtSigned(ntp.minusGpsUs * 1e3), style = Tokens.Text.valueSmall.style())
            }
        }
        Card {
            val us = s?.ntp?.minusGpsUs ?: Double.NaN
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("SAFETY CHECK", style = Tokens.Text.cardLabel.style())
                    BasicText(if (us.isNaN()) "GPS vs NTP —" else "GPS vs NTP ${fmtDuration(abs(us) * 1e3)}", style = Tokens.Text.body.style())
                }
                when {
                    us.isNaN() -> BasicText("—", style = Tokens.Text.badge.style(Ink.muted))
                    abs(us) > SAFETY_LIMIT_US -> BasicText("✕ FAILED", style = Tokens.Text.badge.style(Ink.danger))
                    else -> BasicText("PASSED", style = Tokens.Text.badge.style(Ink.accent))
                }
            }
        }
    }
    picked?.let { SourceSheet(it, s, boot, openSatellites) { picked = null } }
    }
}

@Composable
private fun SourcesField(s: Session?, boot: Long, picked: Int?, onPick: (Int) -> Unit) {
    val resources = LocalContext.current.resources
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrains_mono); textAlign = Paint.Align.CENTER } }
    val chosen = if (s != null) FlowTime.chosen else null
    val holding = chosen == Source.GPS && s?.sourceName(boot) != "GPS"
    val gps = if (s != null && !holding) FlowTime.gps?.errAt(boot) else null
    val hold = if (s != null && holding) FlowTime.gps?.errAt(boot) else null
    val ntp = if (s != null) FlowTime.ntp?.errAt(boot) else null
    val host = s?.ntp?.host?.substringBefore(".co.uk") ?: "NTP"
    Canvas(Modifier.fillMaxWidth().height(190.dp).semantics { contentDescription = "Time sources, with ${chosen?.name ?: "none"} in charge. Tap a node to see what it is." }
        .pointerInput(Unit) {
            detectTapGestures { p ->
                // Flowtime, GPS, Hold, NTP: the same places as the drawing below; each node with its label is about a 44 px target.
                val at = listOf(0.5f to 0.45f, 0.16f to 0.2f, 0.16f to 0.68f, 0.84f to 0.4f).map { (fx, fy) -> Offset(size.width * fx, size.height * fy) }
                val i = at.indices.minByOrNull { (at[it] - p).getDistance() }
                if (i != null && (at[i] - p).getDistance() < 44.dp.toPx()) onPick(i)
            }
        }) {
        val flow = Offset(size.width * 0.5f, size.height * 0.45f)
        fun node(name: String, err: Double?, at: Offset, lit: Boolean) {
            val r = (22f - (if (err == null) 1f else ((log10(err.coerceAtLeast(1e3)) - 3.0) / 4.0).coerceIn(0.0, 1.0).toFloat()) * 10f).dp.toPx()
            drawLine(if (lit) Ink.accent else Ink.faint, at, flow, if (lit) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            drawCircle(Ink.surface, r, at)
            if (lit) drawCircle(Ink.accent.copy(alpha = 0.2f), r, at)
            drawCircle(if (lit) Ink.accent else Ink.faint, r, at, style = Stroke(1.5.dp.toPx()))
            drawIntoCanvas { c ->
                paint.textSize = 10.sp.toPx()
                paint.color = Ink.text.toArgb()
                c.nativeCanvas.drawText(name, at.x, at.y + r + 14.dp.toPx(), paint)
                paint.color = (if (lit) Ink.accent else Ink.muted).toArgb()
                c.nativeCanvas.drawText(if (err != null) "±" + fmtDuration(err) else "—", at.x, at.y + r + 28.dp.toPx(), paint)
            }
        }
        val ring = listOf(0.5f to 0.45f, 0.16f to 0.2f, 0.16f to 0.68f, 0.84f to 0.4f)
        picked?.let { val (fx, fy) = ring[it]; drawCircle(Ink.text, if (it == 0) 40.dp.toPx() else 27.dp.toPx(), Offset(size.width * fx, size.height * fy), style = Stroke(2.dp.toPx())) }
        node("GPS", gps, Offset(size.width * 0.16f, size.height * 0.2f), chosen == Source.GPS && !holding)
        node("HOLD", hold, Offset(size.width * 0.16f, size.height * 0.68f), holding)
        node(host, ntp, Offset(size.width * 0.84f, size.height * 0.4f), chosen == Source.NTP)
        if (chosen != null) drawCircle(Ink.accent.copy(alpha = 0.2f), 36.dp.toPx(), flow)
        drawCircle(Ink.bg, 29.dp.toPx(), flow)
        drawCircle(if (chosen != null) Ink.accent else Ink.faint, 29.dp.toPx(), flow, style = Stroke(2.dp.toPx()))
        drawIntoCanvas { c ->
            paint.textSize = 10.sp.toPx(); paint.color = Ink.text.toArgb()
            c.nativeCanvas.drawText("FLOW", flow.x, flow.y - 2.dp.toPx(), paint)
            c.nativeCanvas.drawText("TIME", flow.x, flow.y + 11.dp.toPx(), paint)
        }
    }
}

/** Tap a source (user, 5 Nov): what the node is, in plain words, then its live details. */
@Composable
private fun SourceSheet(which: Int, s: Session?, boot: Long, openSatellites: () -> Unit, close: () -> Unit) {
    val chosen = if (s != null) FlowTime.chosen else null
    val holding = chosen == Source.GPS && s?.sourceName(boot) != "GPS"
    val gpsFit = FlowTime.gps
    val ntpFit = FlowTime.ntp
    val gps = s?.gps
    val ntp = s?.ntp
    fun ago(b: Long) = if (b == 0L) "—" else "${((boot - b) / 1_000_000_000L).coerceAtLeast(0)} s ago"
    fun err(f: org.beachsync.proto.ClockFit?) = f?.let { "±" + fmtDuration(it.errAt(boot)) } ?: "—"
    val (name, stateWord, definition) = when (which) {
        0 -> Triple("Flowtime", if (chosen != null) "in charge" else "waiting for a source", "The shared clock every Brocast phone plays to: UK time, Teddington · Greenwich. It always follows the most accurate source.")
        1 -> Triple("GPS", when { chosen == Source.GPS && !holding -> "in charge"; gpsFit != null -> "standing by"; else -> "off" }, "Time from navigation satellites, read from this phone's GPS chip. The best a phone can get, usually well under a millisecond.")
        2 -> Triple("Hold", if (holding) "in charge" else "standing by", "This phone's own clock, carrying on from the last satellite fix. It takes over the moment GPS drops out, and stays very good for a while; its error grows slowly with time and temperature until internet time is better.")
        else -> Triple("Internet time server (NTP)", when { chosen == Source.NTP -> "in charge"; ntp != null && ntp.samples > 0 -> "in use"; else -> "standing by" }, "A time server reached over the internet. S1 means one step from an atomic clock; NPL is the UK's National Physical Laboratory.")
    }
    val rows: List<Pair<String, String>> = when (which) {
        0 -> listOf("Source in charge" to (if (holding) "Hold" else chosen?.name ?: "none"), "Error" to err(FlowTime.fit()), "Hand-overs this session" to "${FlowTime.switches}")
        1 -> listOf(
            "Satellites" to (if (gps != null && gps.active) "${gps.used} of ${gps.seen}" else "off"),
            "Link" to when (gps?.gold) { true -> "Gold"; false -> "Silver"; null -> "—" },
            "Last fix" to ago(gps?.lastBoot ?: 0L), "Error" to err(gpsFit),
        )
        2 -> {
            val growth = gpsFit?.growth ?: Double.NaN
            val now = gpsFit?.errAt(boot)
            val handOver = if (gpsFit != null && ntpFit != null && growth > 0) {
                val ns = (ntpFit.errAt(boot) / 1.2 - now!!) / growth
                if (ns <= 0) "now" else "in about ${(ns / 60e9).roundToInt().coerceAtLeast(1)} min"
            } else "—"
            listOf(
                "Error now" to err(gpsFit),
                "Growing by" to (if (growth.isNaN()) "—" else "about ${fmtDuration(growth * 1e9)} a second"),
                "Last satellite fix" to ago(gps?.lastBoot ?: 0L),
                "Would hand over to internet time" to handOver,
            )
        }
        else -> listOf(
            "Server" to (ntp?.host ?: "—"), "Stratum" to (ntp?.let { "S${it.stratum}" } ?: "—"), "Reference" to (ntp?.refId?.ifEmpty { null } ?: "—"),
            "Round trip" to (ntp?.rttMs?.takeIf { !it.isNaN() }?.let { fmtDuration(it * 1e6) } ?: "—"),
            "Difference from GPS" to (ntp?.minusGpsUs?.takeIf { !it.isNaN() }?.let { fmtSigned(it * 1e3) } ?: "—"),
            "Error" to err(ntpFit),
        )
    }
    Sheet(close) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(name, style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp))
            BasicText("  $stateWord", Modifier.weight(1f), Tokens.Text.small.style(if (stateWord == "in charge") Ink.accent else Ink.textSecondary))
            BasicText("✕", Modifier.size(44.dp).clickable(role = Role.Button, onClick = close).padding(12.dp), Tokens.Text.bodyStrong.style(Ink.textSecondary))
        }
        BasicText(definition, style = Tokens.Text.body.style())
        for ((k, v) in rows) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(k, Modifier.weight(1f), Tokens.Text.body.style(Ink.textSecondary))
            BasicText(v, style = Tokens.Text.valueSmall.style())
        }
        if (s == null) BasicText("Live details appear while the music plays.", style = Tokens.Text.small.style(Ink.muted))
        if (which == 1 || which == 2) BasicText("How satellites set this clock", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { close(); openSatellites() }.padding(vertical = 12.dp), Tokens.Text.body.style(Ink.accent))
    }
}
