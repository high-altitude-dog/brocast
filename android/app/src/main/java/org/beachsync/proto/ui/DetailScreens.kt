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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
            SourcesField(s, boot, picked, state) { picked = it }
            BasicText("Signals land on the beat: the clock that keeps everyone in time also keeps the bars. Tap the satellites, the mast or the hourglass to see what each is. Sunrise and sunset for where you are, worked out on this phone.", style = Tokens.Text.small.style())
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
private fun SourcesField(s: Session?, boot: Long, picked: Int?, state: BenchState, onPick: (Int) -> Unit) {
    // Hand-off 52: a picture, not a node diagram. Satellites (GPS), a far-too-tall Alexandra Palace-style mast (NTP), the phone on the
    // ground (FLOWTIME) with an hourglass (hold), and the sun on its arc between this place's sunrise and sunset.
    val ctx = LocalContext.current
    val resources = ctx.resources
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrains_mono); textAlign = Paint.Align.CENTER } }
    val chosen = if (s != null) FlowTime.chosen else null
    val holding = chosen == Source.GPS && s?.sourceName(boot) != "GPS"
    val gps = FlowTime.gps?.takeIf { !holding }?.errAt(boot)
    val hold = FlowTime.gps?.takeIf { holding }?.errAt(boot)
    val ntp = FlowTime.ntp?.errAt(boot)
    val gpsLit = chosen == Source.GPS && !holding
    val ntpLit = chosen == Source.NTP
    // Where the sun is: the last known position (it never leaves the phone), else the time zone's main city, "approx.".
    val minute = System.currentTimeMillis() / 60_000L
    val place = remember(minute / 10) { lastPosition(ctx) }
    val (lat, lon) = place ?: org.beachsync.proto.Sun.zonePlace(java.util.TimeZone.getDefault())
    val nowMs = minute * 60_000L
    val zone = java.time.ZoneId.systemDefault()
    val localMidnightUtc = java.time.LocalDate.now(zone).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val day = remember(minute) { org.beachsync.proto.Sun.day(localMidnightUtc, lat, lon) }
    val hhmm = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString().take(5) }
    val sunFrac: Float? = when (day) {
        is org.beachsync.proto.Sun.Day.Times -> if (nowMs in day.riseMs..day.setMs) ((nowMs - day.riseMs).toFloat() / (day.setMs - day.riseMs)) else null
        org.beachsync.proto.Sun.Day.AlwaysUp -> ((nowMs - localMidnightUtc) % 86_400_000L) / 86_400_000f
        org.beachsync.proto.Sun.Day.NeverUp -> null
    }
    val night = sunFrac == null
    val warm = Color(0xFFFFD27A)
    // Tap places, as fractions: phone, satellites, hourglass, mast.
    val spots = listOf(0.45f to 0.74f, 0.2f to 0.22f, 0.33f to 0.8f, 0.8f to 0.45f)
    // Hand-off 61: the signals land on the beat. Phase comes from flowtime and the set's beat grid (the beat counter's numbers), redrawn
    // every frame only while the library plays; the frame clock stops when the screen is off or this page is gone. Stopped: a still picture.
    val playing = s != null && s.s.library
    var frame by remember { mutableLongStateOf(Session.nowBoot()) }
    LaunchedEffect(playing) { while (playing) withFrameNanos { frame = Session.nowBoot() } }
    val reduce = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) } || ScreenPrefs.noFlashing
    val utcNow = if (playing) FlowTime.fit()?.utcAt(frame) else null
    val here = utcNow?.let { beatHere(state, it) }
    val beatPos = here?.pos
    // No beat grid, or no steady beat: one pulse a second, no ring.
    val phase: Float? = when { here == null -> null; beatPos != null -> beatPos.beatFrac.toFloat(); else -> (Math.floorMod(utcNow, 1_000_000_000L) / 1e9).toFloat() }
    val beatInBar = beatPos?.beatInBar ?: -1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Canvas(Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(12.dp)).semantics { contentDescription = "Time sources as a picture, with ${chosen?.name ?: "none"} in charge. Tap the satellites, the mast or the hourglass." }
        .pointerInput(Unit) {
            detectTapGestures { p ->
                val at = spots.map { (fx, fy) -> Offset(size.width * fx, size.height * fy) }
                val i = at.indices.minByOrNull { (at[it] - p).getDistance() }
                if (i != null && (at[i] - p).getDistance() < 54.dp.toPx()) onPick(i)
            }
        }) {
        val w = size.width; val h = size.height; val ground = h * 0.86f
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(if (night) listOf(Color(0xFF05070C), Color(0xFF0D121A)) else listOf(Color(0xFF0F2236), Color(0xFF1B3550))))
        if (night) {
            for (k in 0 until 40) drawCircle(Color.White.copy(alpha = 0.25f + (k % 3) * 0.2f), (if (k % 7 == 0) 1.4f else 0.9f).dp.toPx(), Offset(((k * 73) % 97) / 97f * w, ((k * 37) % 61) / 61f * ground * 0.8f))
            val m = Offset(w * 0.9f, h * 0.18f)
            drawCircle(Color(0xFFDDE3EA), 10.dp.toPx(), m); drawCircle(Color(0xFF05070C), 9.dp.toPx(), Offset(m.x + 5.dp.toPx(), m.y - 3.dp.toPx()))
        }
        // The sun's dotted arc, sunrise on the left, sunset on the right; the sun placed by the time of day.
        val arcC = Offset(w * 0.5f, ground); val arx = w * 0.42f; val ary = h * 0.62f
        drawArc(Color.White.copy(alpha = 0.25f), 180f, 180f, false, Offset(arcC.x - arx, arcC.y - ary), Size(arx * 2, ary * 2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))))
        sunFrac?.let { f ->
            val a = Math.PI * (1 - f)
            val sp = Offset(arcC.x + arx * kotlin.math.cos(a).toFloat(), arcC.y - ary * kotlin.math.sin(a).toFloat())
            drawCircle(warm.copy(alpha = 0.25f), 15.dp.toPx(), sp); drawCircle(warm, 9.dp.toPx(), sp)
        }
        // Ground, slightly curved.
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, ground + 4.dp.toPx()); quadraticTo(w / 2, ground - 6.dp.toPx(), w, ground + 4.dp.toPx()); lineTo(w, h); lineTo(0f, h); close() }, Color(0xFF0A0C0F))
        val phone = Offset(w * 0.45f, h * 0.72f)
        fun link(from: Offset, lit: Boolean) = drawLine(if (lit) Ink.accent else Ink.faint, from, phone, if (lit) 1.8.dp.toPx() else 1.dp.toPx(), pathEffect = if (lit) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        // Satellites: four, top left.
        val sats = listOf(0.1f to 0.3f, 0.2f to 0.14f, 0.32f to 0.08f, 0.38f to 0.22f).map { (fx, fy) -> Offset(w * fx, h * fy) }
        for (sat in sats) {
            link(sat, gpsLit)
            val c = if (gpsLit) Ink.accent else Ink.faint
            drawRect(c, Offset(sat.x - 3.dp.toPx(), sat.y - 3.dp.toPx()), Size(6.dp.toPx(), 6.dp.toPx()))
            drawRect(c.copy(alpha = 0.7f), Offset(sat.x - 12.dp.toPx(), sat.y - 2.dp.toPx()), Size(7.dp.toPx(), 4.dp.toPx()))
            drawRect(c.copy(alpha = 0.7f), Offset(sat.x + 5.dp.toPx(), sat.y - 2.dp.toPx()), Size(7.dp.toPx(), 4.dp.toPx()))
        }
        // The mast: brick tower with arched windows, a lattice far too tall, aerial and three wave arcs.
        val mx = w * 0.8f
        val mc = if (ntpLit) Ink.accent else Ink.muted
        val towerTop = ground - 26.dp.toPx(); val tw = 34.dp.toPx()
        drawRect(Color(0xFF3A3F47), Offset(mx - tw / 2, towerTop), Size(tw, ground - towerTop))
        drawRect(mc, Offset(mx - tw / 2, towerTop), Size(tw, ground - towerTop), style = Stroke(1.dp.toPx()))
        for (k in 0..2) { val wx = mx - tw / 2 + 6.dp.toPx() + k * 9.dp.toPx(); drawRoundRect(Color(0xFF0A0C0F), Offset(wx, towerTop + 9.dp.toPx()), Size(5.dp.toPx(), 13.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(2.5f.dp.toPx())) }
        val mastTop = h * 0.14f; val baseHalf = 12.dp.toPx()
        val l = Offset(mx - baseHalf, towerTop); val r = Offset(mx + baseHalf, towerTop); val tip = Offset(mx, mastTop)
        drawLine(mc, l, tip, 1.4.dp.toPx()); drawLine(mc, r, tip, 1.4.dp.toPx())
        val rungs = 9
        for (k in 1 until rungs) {
            val t0 = k / rungs.toFloat(); val t1 = (k + 1) / rungs.toFloat()
            fun at(side: Offset, t: Float) = Offset(side.x + (tip.x - side.x) * t, side.y + (tip.y - side.y) * t)
            drawLine(mc, at(l, t0), at(r, t0), 0.8.dp.toPx())
            if (k < rungs - 1) { drawLine(mc, at(l, t0), at(r, t1), 0.6.dp.toPx()); drawLine(mc, at(r, t0), at(l, t1), 0.6.dp.toPx()) }
        }
        drawLine(mc, tip, Offset(tip.x, tip.y - 12.dp.toPx()), 1.4.dp.toPx())
        for (k in 1..3) { val rr = k * 7.dp.toPx(); drawArc(mc.copy(alpha = if (ntpLit) 1f else 0.6f), 220f, 100f, false, Offset(tip.x - rr, tip.y - 12.dp.toPx() - rr), Size(rr * 2, rr * 2), style = Stroke(1.2.dp.toPx())) }
        link(tip, ntpLit)
        // Hourglass: the phone's own clock carrying on.
        val hg = Offset(w * 0.33f, h * 0.79f); val hc = if (holding) Ink.accent else Ink.faint
        val hh = 9.dp.toPx(); val hw = 6.dp.toPx()
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(hg.x - hw, hg.y - hh); lineTo(hg.x + hw, hg.y - hh); lineTo(hg.x - hw, hg.y + hh); lineTo(hg.x + hw, hg.y + hh); close() }, hc, style = Stroke(1.5.dp.toPx()))
        // The phone, with a soft halo.
        if (chosen != null) drawCircle(Ink.accent.copy(alpha = 0.15f), 32.dp.toPx(), phone)
        val pw = 26.dp.toPx(); val ph = 44.dp.toPx()
        drawRoundRect(Color(0xFF0A0C0F), Offset(phone.x - pw / 2, phone.y - ph / 2), Size(pw, ph), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawRoundRect(if (chosen != null) Ink.accent else Ink.faint, Offset(phone.x - pw / 2, phone.y - ph / 2), Size(pw, ph), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))
        if (phase != null && chosen != null) {
            val ph1 = 1f - phase
            if (!reduce) {
                // Beat 1: a ring spreads over the ground from the phone, gone by 60% of the bar; the glow brightens.
                if (beatPos != null) {
                    val bar = (beatInBar + phase) / 4f
                    if (bar < 0.6f) {
                        val rx = (12 + 138 * bar / 0.6f).dp.toPx(); val ry = (4 + 22 * bar / 0.6f).dp.toPx()
                        drawOval(Ink.accent.copy(alpha = 0.6f * (1 - bar / 0.6f)), Offset(phone.x - rx, ground - ry), Size(rx * 2, ry * 2), style = Stroke(1.5.dp.toPx()))
                    }
                    if (beatInBar == 0) drawCircle(Ink.accent.copy(alpha = 0.35f * ph1), 34.dp.toPx(), phone)
                }
                // A pulse runs down each lit line, eased in, landing on the top of the phone on the beat.
                val top = Offset(phone.x, phone.y - ph / 2)
                val t = phase * phase
                fun pulse(from: Offset) {
                    val at = Offset(from.x + (top.x - from.x) * t, from.y + (top.y - from.y) * t)
                    drawCircle(Ink.accent.copy(alpha = 0.35f), 5.dp.toPx(), at); drawCircle(Color(0xFFF4FFE0), 2.dp.toPx(), at)
                }
                if (gpsLit) for (sat in sats) pulse(sat)
                if (ntpLit) pulse(tip)
                // The mast sends one arc a beat: bright when internet time leads.
                val ar = (4 + 30 * phase).dp.toPx(); val aerial = Offset(tip.x, tip.y - 12.dp.toPx())
                drawArc((if (ntpLit) Ink.accent.copy(alpha = 0.9f) else Ink.muted.copy(alpha = 0.35f)).copy(alpha = (if (ntpLit) 0.9f else 0.35f) * ph1), 200f, 140f, false, Offset(aerial.x - ar, aerial.y - ar), Size(ar * 2, ar * 2), style = Stroke(1.2.dp.toPx()))
                // The phone fills with accent and fades over the beat.
                drawRoundRect(Ink.accent.copy(alpha = 0.55f * ph1), Offset(phone.x - pw / 2 + 2.dp.toPx(), phone.y - ph / 2 + 2.dp.toPx()), Size(pw - 4.dp.toPx(), ph - 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            } else if (beatInBar == 0) {
                drawRoundRect(Ink.accent.copy(alpha = 0.2f), Offset(phone.x - pw / 2 + 2.dp.toPx(), phone.y - ph / 2 + 2.dp.toPx()), Size(pw - 4.dp.toPx(), ph - 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            }
        }
        // Four beat dots on the phone, the current beat lit (grey with no beat).
        if (phase != null) for (i in 0..3) {
            val c = if (beatPos != null && i == beatInBar) Ink.accent else Ink.faint
            drawCircle(c, 1.3.dp.toPx(), Offset(phone.x + (i - 1.5f) * 4.dp.toPx(), phone.y + ph / 2 - 6.dp.toPx()))
        }
        picked?.let { val (fx, fy) = spots[it]; drawCircle(Color.White, 30.dp.toPx(), Offset(w * fx, h * fy), style = Stroke(2.dp.toPx())) }
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            paint.textSize = 7.5f.sp.toPx(); paint.color = Ink.text.toArgb()
            nc.drawText("FLOW", phone.x, phone.y - 1.dp.toPx(), paint); nc.drawText("TIME", phone.x, phone.y + 8.dp.toPx(), paint)
            if (phase != null) {
                paint.textAlign = Paint.Align.LEFT; paint.textSize = 8.sp.toPx()
                val lx = phone.x + 22.dp.toPx()
                paint.color = (if (beatPos != null) Ink.accent else Ink.muted).toArgb()
                nc.drawText(if (beatPos != null) "BAR ${beatPos.bar + 1}" else "NO BEAT", lx, phone.y - 2.dp.toPx(), paint)
                paint.color = Ink.textSecondary.toArgb()
                nc.drawText(if (beatPos != null) "${beatPos.bpm.roundToInt()} BPM" else "1 s ticks", lx, phone.y + 9.dp.toPx(), paint)
                paint.textAlign = Paint.Align.CENTER
            }
            paint.textSize = 9.sp.toPx()
            fun label(t1: String, t2: String, x: Float, y: Float, lit: Boolean, align: Paint.Align = Paint.Align.CENTER) {
                paint.textAlign = align
                paint.color = (if (lit) Ink.accent else Ink.muted).toArgb(); nc.drawText(t1, x, y, paint)
                paint.color = (if (lit) Ink.text else Ink.muted).toArgb(); nc.drawText(t2, x, y + 11.sp.toPx(), paint)
                paint.textAlign = Paint.Align.CENTER
            }
            label("GPS", gps?.let { "±" + fmtDuration(it) } ?: "—", w * 0.2f, h * 0.43f, gpsLit)
            label("NTP", ntp?.let { "±" + fmtDuration(it) } ?: "—", mx + 18.dp.toPx(), h * 0.42f, ntpLit, Paint.Align.LEFT)
            label("HOLD", hold?.let { "±" + fmtDuration(it) } ?: "—", hg.x - 2.dp.toPx(), ground + 3.dp.toPx(), holding)
            // Sunrise and sunset under the ground line, warm yellow.
            paint.color = warm.toArgb(); paint.textSize = 9.sp.toPx()
            val approx = if (place == null) " approx." else ""
            when (day) {
                is org.beachsync.proto.Sun.Day.Times -> {
                    paint.textAlign = Paint.Align.LEFT; nc.drawText("↑ ${hhmm(day.riseMs)} SUNRISE$approx", 8.dp.toPx(), h - 6.dp.toPx(), paint)
                    paint.textAlign = Paint.Align.RIGHT; nc.drawText("↓ ${hhmm(day.setMs)} SUNSET$approx", w - 8.dp.toPx(), h - 6.dp.toPx(), paint)
                }
                org.beachsync.proto.Sun.Day.AlwaysUp -> { paint.textAlign = Paint.Align.LEFT; nc.drawText("Sun up all day$approx", 8.dp.toPx(), h - 6.dp.toPx(), paint) }
                org.beachsync.proto.Sun.Day.NeverUp -> { paint.textAlign = Paint.Align.LEFT; nc.drawText("Sun doesn't rise today$approx", 8.dp.toPx(), h - 6.dp.toPx(), paint) }
            }
            paint.textAlign = Paint.Align.CENTER
        }
    }
    }
}

/** The phone's last known position, if it has one and may read it: used only to place the sun, never sent anywhere. */
private fun lastPosition(ctx: android.content.Context): Pair<Double, Double>? {
    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
        ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    val lm = ctx.getSystemService(android.location.LocationManager::class.java)
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    } catch (_: SecurityException) { null }
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
