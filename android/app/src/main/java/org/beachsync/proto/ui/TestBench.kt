package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beachsync.proto.Event
import org.beachsync.proto.EventLog
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Lane
import org.beachsync.proto.NudgeMode
import org.beachsync.proto.Policy
import org.beachsync.proto.Renderer
import org.beachsync.proto.Session
import org.beachsync.proto.StatusPart
import org.beachsync.proto.fmtDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The test bench's settings, as Compose state. MainActivity loads and saves them. */
class BenchState {
    var url by mutableStateOf("")
    var stream by mutableStateOf(false)
    var policy by mutableStateOf(Policy.AUTO)
    var channel by mutableStateOf(Renderer.BOTH)
    var pitch by mutableStateOf("2000")
    var lag by mutableStateOf("0")
    var satellites by mutableStateOf(true)
    var localTime by mutableStateOf(true)
    var nudgeMode by mutableStateOf(NudgeMode.AUTO)
    var nudgePeriodS by mutableStateOf<Double?>(null)
    var session by mutableStateOf<Session?>(null)
    var eventLog by mutableStateOf<EventLog?>(null)
    var earcup by mutableStateOf<org.beachsync.proto.EarcupRun?>(null)
    var lagSource by mutableStateOf("typed")
    var library by mutableStateOf<org.beachsync.proto.LibraryStore?>(null)
}

interface BenchActions {
    fun startOrStop()
    fun setSatellites(on: Boolean)
    fun setLocalTime(on: Boolean)
    /** Like startOrStop, but playing with the value calibration chose for the output in use. */
    fun listen()
    fun output(): org.beachsync.proto.AudioOutput
    fun savedCalibration(o: org.beachsync.proto.AudioOutput): org.beachsync.proto.Calibration?
    fun tableCalibration(o: org.beachsync.proto.AudioOutput): org.beachsync.proto.Calibration
    fun saveCalibration(o: org.beachsync.proto.AudioOutput, c: org.beachsync.proto.Calibration)
    fun earcupValue(pass: org.beachsync.proto.EarcupOutcome.Pass): org.beachsync.proto.Calibration
    fun startEarcup(o: org.beachsync.proto.AudioOutput)
    fun cancelEarcup()
    fun resetEarcup()
    fun setNudgeMode(mode: NudgeMode)
    fun setNudgePeriod(seconds: Double?)
}

/** A clock tick for the screen. It waits for a frame, so it stops while the app isn't visible. */
@Composable
fun rememberTick(ms: Long): State<Long> = produceState(Session.nowBoot(), ms) {
    while (true) {
        delay(ms)
        withFrameNanos { }
        value = Session.nowBoot()
    }
}

private const val CLOCK_MS = 100L    // the design's cap of ten UI updates a second
internal const val STATUS_MS = 500L
private const val LOCKED_ALPHA = 0.55f
internal val UK = ZoneId.of("Europe/London")
internal val CLOCK_DIGITS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(UK)
internal val CLOCK_ZONE = DateTimeFormatter.ofPattern("z", Locale.UK).withZone(UK)
internal val LOCAL_DIGITS = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.UK)

/** "22:43:29", the flowtime of an event in UK time, or dashes before there was a time source. */
fun eventTime(e: Event): String = e.utc?.let { CLOCK_DIGITS.format(Instant.ofEpochSecond(0, it)) } ?: "--:--:--"

@Composable
fun TestBench(state: BenchState, actions: BenchActions, openLog: (Lane?) -> Unit, openNudge: () -> Unit) {
    val running = state.session != null
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Header(state)
        ClockCard(state)
        Card {
            Locked(running) {
                Segmented("Mode", listOf("Tick", "Stream"), if (state.stream) 1 else 0, !running) { state.stream = it == 1 }
                Segmented("Time source", listOf("Auto", "GPS only", "NTP only"), state.policy.ordinal, !running) { state.policy = Policy.entries[it] }
                Segmented("Stream channel", listOf("Both", "Left", "Right"), state.channel, !running) { state.channel = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Server", Modifier.width(92.dp), Tokens.Text.body.style(Ink.textSecondary))
                    Spacer(Modifier.width(10.dp))
                    Field(state.url, !running, KeyboardType.Uri, Modifier.weight(1f), Tokens.Text.valueSmall.style()) { state.url = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    UnitField("TICK PITCH", state.pitch, "Hz", !running, Modifier.weight(1f)) { state.pitch = it }
                    UnitField("HEADPHONE LAG", state.lag, "ms", !running, Modifier.weight(1f)) { state.lag = it }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 2.dp).background(Ink.track).heightIn(min = 1.dp, max = 1.dp))
            SatellitesRow(state.satellites, actions::setSatellites)
        }
        RunButton(running, actions::startOrStop)
        EventRow(state.eventLog) { openLog(null) }
        NudgeRow(state, openNudge)
        StatusGrid(state, openLog)
    }
}

@Composable
private fun Header(state: BenchState) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText("PROTOTYPE", style = Tokens.Text.eyebrow.style())
            BasicText("Test bench", style = Tokens.Text.screenTitle.style())
        }
        val s = state.session
        val tint = if (s != null) Ink.accent else Ink.faint
        val tick by rememberTick(STATUS_MS)
        Row(
            Modifier.border(Tokens.Stroke.emphasis.dp, tint, RoundedCornerShape(Tokens.Radius.pill.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s != null) RunningDot() else Box(Modifier.size(8.dp).background(Ink.muted, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            BasicText(if (s != null) "RUNNING" else "STOPPED", style = Tokens.Text.badge.style(if (s != null) Ink.text else Ink.textSecondary).copy(fontSize = 12.sp))
            if (s != null) {
                val seconds = (tick - s.startBoot).coerceAtLeast(0) / 1_000_000_000L
                Spacer(Modifier.width(8.dp))
                BasicText("%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60), style = Tokens.Text.valueSmall.style())
            }
        }
    }
}

/** The accent dot with a soft glow, static so it costs nothing per frame. */
@Composable
private fun RunningDot() {
    Box(
        Modifier.size(8.dp).drawBehind {
            val r = size.minDimension / 2
            val glow = r + 10.dp.toPx()
            drawCircle(Brush.radialGradient(0f to Ink.accent.copy(alpha = 0.6f), 1f to Color.Transparent, center = center, radius = glow), glow)
            drawCircle(Ink.accent, r)
        },
    )
}

@Composable
private fun ClockCard(state: BenchState) {
    val boot = rememberTick(CLOCK_MS).value   // read here, always: read only once a fit exists, this function would never redraw
    val fit = FlowTime.fit()
    val s = state.session
    val utc = if (s != null && fit != null) fit.utcAt(boot) else null
    Card {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText("TEDDINGTON · GREENWICH", style = Tokens.Text.cardLabel.style())
                val timeStyle = Tokens.Text.clock.style().copy(fontSize = 24.sp)
                if (utc == null) {
                    BasicText("--:--:--.---", style = timeStyle)
                    BasicText(" ", style = Tokens.Text.clockLocal.style())
                } else {
                    val now = Instant.ofEpochSecond(0, utc)
                    BasicText("%s.%03d".format(Locale.ROOT, CLOCK_DIGITS.format(now), now.nano / 1_000_000), style = timeStyle)
                    BasicText(zoneLine(now), style = Tokens.Text.clockLocal.style())
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                if (s == null) {
                    BasicText("No run", style = Tokens.Text.value.style(Ink.muted))
                } else {
                    val err = s.syncErrorNs(boot)
                    val tint = when {
                        !s.gps.active -> Ink.textSecondary
                        err.isNaN() || err > WARN_ABOVE_NS -> Ink.warn
                        else -> Ink.accent
                    }
                    val figure = if (err.isNaN()) "" else " ±" + fmtDuration(err)
                    BasicText(s.sourceName(boot) + figure, style = Tokens.Text.value.style(tint))
                    BasicText(s.sourceDetail(), style = Tokens.Text.small.style())
                    val from = s.tighteningFromNs()
                    if (!from.isNaN() && fit != null) TighteningLine(from, fit.errAt(boot))
                }
            }
        }
    }
}

private const val WARN_ABOVE_NS = 5_000_000.0   // 5 ms, from the badge rules in design/ui-spec.md

/** "TIGHTENING ±11.4 → ±0.40 ms", flowtime's own error, with the first unit dropped when both figures share it. */
@Composable
internal fun TighteningLine(fromNs: Double, toNs: Double) {
    val from = fmtDuration(fromNs)
    val to = fmtDuration(toNs)
    val figures = "±" + (if (from.substringAfter(' ') == to.substringAfter(' ')) from.substringBefore(' ') else from) + " → ±" + to
    BasicText(
        buildAnnotatedString {
            append("TIGHTENING ")
            pushStyle(SpanStyle(fontFamily = Tokens.Text.valueSmall.style().fontFamily, fontWeight = FontWeight.Normal, letterSpacing = 0.sp, color = Ink.muted))
            append(figures)
            pop()
        },
        style = Tokens.Text.small.style(Ink.accent).copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.12.em),
    )
}

/** "GMT · local 23:43:51 CET", with the local part left out when it matches UK time. */
private fun zoneLine(now: Instant): String {
    val zone = CLOCK_ZONE.format(now)
    val local = ZoneId.systemDefault()
    if (local.rules.getOffset(now) == UK.rules.getOffset(now)) return zone
    return "$zone · local " + shortZone(LOCAL_DIGITS.withZone(local).format(now))
}

/** Zones with no abbreviation read as "GMT-05:00", which squeezes the card, so this makes it "GMT-5". */
internal fun shortZone(line: String): String {
    val at = line.indexOf("GMT")
    if (at < 0 || line.length < at + 9) return line
    val minutes = line.substring(at + 7, at + 9)
    return line.substring(0, at + 4) + line.substring(at + 4, at + 6).trimStart('0').ifEmpty { "0" } + if (minutes == "00") "" else ":$minutes"
}

@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Ink.surface, RoundedCornerShape(Tokens.Radius.card.dp))
            .border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.card.dp))
            .padding(vertical = 10.dp, horizontal = Tokens.Space.cardPaddingHorizontal.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.rowGap.dp),
    ) { content() }
}

@Composable
private fun Locked(locked: Boolean, content: @Composable () -> Unit) {
    Column(Modifier.alpha(if (locked) LOCKED_ALPHA else 1f), verticalArrangement = Arrangement.spacedBy(Tokens.Space.rowGap.dp)) { content() }
}

@Composable
fun Segmented(label: String, options: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.width(92.dp), Tokens.Text.body.style(Ink.textSecondary))
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.weight(1f).border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.control.dp))
                .selectableGroup(),
        ) {
            options.forEachIndexed { i, option ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).heightIn(min = Tokens.Space.touchTarget.dp)
                        .background(if (on) Ink.text else Color.Transparent, segmentShape(i, options.size))
                        .drawBehind {
                            if (i > 0) drawLine(Ink.grid, Offset.Zero, Offset(0f, size.height), Tokens.Stroke.control.dp.toPx())
                        }
                        .selectable(on, enabled, Role.RadioButton) { onSelect(i) },
                    Alignment.Center,
                ) {
                    BasicText(option, style = Tokens.Text.body.style(if (on) Ink.bg else Ink.text).copy(fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null))
                }
            }
        }
    }
}

private fun segmentShape(i: Int, n: Int): RoundedCornerShape {
    val r = Tokens.Radius.control.dp
    val none = 0.dp
    return RoundedCornerShape(if (i == 0) r else none, if (i == n - 1) r else none, if (i == n - 1) r else none, if (i == 0) r else none)
}

@Composable
private fun Field(value: String, enabled: Boolean, keyboard: KeyboardType, modifier: Modifier, style: TextStyle, onChange: (String) -> Unit) {
    BasicTextField(
        value, onChange, modifier.heightIn(min = Tokens.Space.touchTarget.dp).background(Ink.bg, RoundedCornerShape(Tokens.Radius.control.dp))
            .border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.control.dp)).padding(horizontal = 10.dp, vertical = 13.dp),
        enabled = enabled, singleLine = true, textStyle = style, cursorBrush = SolidColor(Ink.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    )
}

@Composable
private fun UnitField(label: String, value: String, unit: String, enabled: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = 10.sp))
        Row(
            Modifier.heightIn(min = Tokens.Space.touchTarget.dp).background(Ink.bg, RoundedCornerShape(Tokens.Radius.control.dp))
                .border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.control.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value, onChange, Modifier.weight(1f), enabled = enabled, singleLine = true,
                textStyle = Tokens.Text.valueSmall.style().copy(fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                cursorBrush = SolidColor(Ink.accent), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.width(6.dp))
            BasicText(unit, style = Tokens.Text.small.style().copy(fontSize = 12.sp))
        }
    }
}

/** Never locked: this stays live whatever else is happening. */
@Composable
internal fun SatellitesRow(on: Boolean, onChange: (Boolean) -> Unit, detail: String = "Never locked · position stays on phone") {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).toggleable(on, role = Role.Switch, onValueChange = onChange),
        Arrangement.SpaceBetween, Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText("Use satellites", style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style())
        }
        SatellitesSwitch(on)
    }
}

/** The track and thumb, the same on every screen. Off is an outline with a smaller thumb, so it reads without colour. */
@Composable
fun SatellitesSwitch(on: Boolean) {
    val shape = RoundedCornerShape(Tokens.Radius.switchTrack.dp)
    val track = Modifier.width(46.dp).height(26.dp)
    Row(
        (if (on) track.background(Ink.accent, shape).padding(2.dp)
        else track.border(Tokens.Stroke.emphasis.dp, Ink.faint, shape).padding(horizontal = 3.5.dp)),
        horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (on) 22.dp else 18.dp).background(if (on) Ink.bg else Ink.muted, CircleShape))
    }
}

@Composable
internal fun RunButton(running: Boolean, onClick: () -> Unit, startLabel: String = "START RUN", stopLabel: String = "STOP RUN") {
    val tint = if (running) Ink.text else Ink.onAccent
    var base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    base = if (running) base.border(Tokens.Stroke.emphasis.dp, Ink.text, RoundedCornerShape(Tokens.Radius.pill.dp))
    else base.background(Ink.accent, RoundedCornerShape(Tokens.Radius.pill.dp))
    Row(
        base.clickable(role = Role.Button, onClick = onClick),
        Arrangement.Center, Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(12.dp)) {
            if (running) drawRoundRect(tint, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            else drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0f, 0f); lineTo(size.width, size.height / 2); lineTo(0f, size.height); close() }, tint)
        }
        Spacer(Modifier.width(10.dp))
        BasicText(if (running) stopLabel else startLabel, style = Tokens.Text.badge.style(tint).copy(fontSize = 15.sp))
    }
}

@Composable
private fun LinkRow(onClick: () -> Unit, minHeight: Dp = Tokens.Space.touchTarget.dp, chevronTint: Color = Ink.text, chevronSize: Dp = 16.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp))
            .border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
        Spacer(Modifier.width(8.dp))
        Chevron(chevronTint, chevronSize)
    }
}

@Composable
private fun Chevron(tint: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = 2.dp.toPx() * 2 / 3
        drawLine(tint, Offset(w * 0.375f, w * 0.21f), Offset(w * 0.667f, w * 0.5f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.667f, w * 0.5f), Offset(w * 0.375f, w * 0.79f), stroke, StrokeCap.Round)
    }
}

@Composable
internal fun EventRow(log: EventLog?, tickMs: Long = STATUS_MS, openLog: () -> Unit) {
    rememberTick(tickMs).value
    val last = log?.latest()
    LinkRow(openLog) {
        if (last == null) {
            BasicText("No events yet", style = Tokens.Text.body.style(Ink.muted))
        } else {
            BasicText(eventTime(last), style = Tokens.Text.valueSmall.style(Ink.muted))
            Spacer(Modifier.width(8.dp))
            BasicText("${last.lane.mark()} ${last.lane.name}", style = Tokens.Text.valueSmall.style(last.lane.color()).copy(fontSize = 10.sp, letterSpacing = 1.sp))
            Spacer(Modifier.width(8.dp))
            BasicText(last.text, Modifier.weight(1f).padding(vertical = 8.dp), Tokens.Text.body.style())
        }
    }
}

private fun partsText(parts: List<StatusPart>) = buildAnnotatedString {
    val mono = Tokens.Text.valueSmall.style().fontFamily
    for (part in parts) {
        if (part.mono) {
            pushStyle(SpanStyle(fontFamily = mono, color = if (part.good) Ink.accent else Ink.text))
            append(part.text)
            pop()
        } else append(part.text)
    }
}

/** "+0.014%": the playback rate change while a gap closes. Very slow closes keep more places, so they don't read as zero. */
internal fun fmtPitch(rate: Double): String {
    val percent = abs(rate) * 100
    val format = when { percent >= 0.1 -> "%.2f%%"; percent >= 0.01 -> "%.3f%%"; else -> "%.4f%%" }
    return (if (rate < 0) "−" else "+") + format.format(Locale.ROOT, percent)
}

@Composable
internal fun NudgeRow(state: BenchState, openNudge: () -> Unit, tickMs: Long = STATUS_MS) {
    val boot by rememberTick(tickMs)
    val manual = state.nudgeMode == NudgeMode.MANUAL
    val s = state.session
    val gap = s?.nudge?.gapAt(boot) ?: 0L
    LinkRow(openNudge, minHeight = 48.dp, chevronTint = Ink.muted, chevronSize = 14.dp) {
        BasicText(
            if (manual) "MANUAL" else "AUTO",
            Modifier.border(Tokens.Stroke.card.dp, if (manual) Ink.accent else Ink.faint, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            Tokens.Text.badge.style(if (manual) Ink.accent else Ink.text).copy(fontSize = 11.sp),
        )
        Spacer(Modifier.width(10.dp))
        if (s == null || gap == 0L) {
            BasicText(if (s == null) "no run" else "in step", style = Tokens.Text.body.style(Ink.muted).copy(fontSize = 13.sp))
        } else {
            BasicText(fmtDuration(gap.toDouble()), style = Tokens.Text.valueSmall.style().copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(10.dp))
            val closing = s.nudge.state
            val small = Tokens.Text.small.style(Ink.muted).copy(fontSize = 11.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(
                    if (closing.closing) org.beachsync.proto.nudgeWord(closing.rate) + " · " + fmtDuration(s.nudge.secondsLeft(boot) * 1e9) + " left" else org.beachsync.proto.gapWord(gap) + " · paused",
                    style = small, overflow = TextOverflow.Ellipsis, maxLines = 1,
                )
                BasicText(
                    if (closing.closing) "pitch " + fmtPitch(closing.rate) else "period %.0f s".format(Locale.ROOT, s.nudge.effectivePeriodS(abs(gap))),
                    style = small, overflow = TextOverflow.Ellipsis, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun StatusGrid(state: BenchState, openLog: (Lane?) -> Unit) {
    val boot by rememberTick(STATUS_MS)
    val s = state.session
    Card {
        if (s == null) {
            BasicText("Stopped. Set up both phones the same way, then press Start on each.", style = Tokens.Text.body.style(Ink.muted))
            return@Card
        }
        val text = Tokens.Text.body.style().copy(fontSize = 11.5.sp)
        for (row in s.statusRows(boot)) {
            val tappable = if (row.error == null) Modifier else
                Modifier.heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button) { openLog(row.errorLane) }
            Row(Modifier.fillMaxWidth().then(tappable), verticalAlignment = Alignment.CenterVertically) {
                BasicText(row.label, Modifier.width(58.dp), Tokens.Text.cardLabel.style().copy(fontSize = 10.sp, letterSpacing = 1.4.sp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(partsText(row.parts), style = text)
                    row.error?.let { error ->
                        BasicText("▲ $error", style = text.copy(color = Ink.warn), overflow = TextOverflow.Ellipsis, maxLines = 1)
                    }
                }
            }
        }
    }
}
