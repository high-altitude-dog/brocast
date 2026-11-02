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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beachsync.proto.Event
import org.beachsync.proto.EventLog
import org.beachsync.proto.FlowTime
import org.beachsync.proto.NudgeMode
import org.beachsync.proto.Policy
import org.beachsync.proto.Renderer
import org.beachsync.proto.Session
import org.beachsync.proto.fmtDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The test bench's settings, as Compose state. MainActivity loads and saves them. */
class BenchState {
    var url by mutableStateOf("")
    var stream by mutableStateOf(false)
    var policy by mutableStateOf(Policy.AUTO)
    var channel by mutableStateOf(Renderer.BOTH)
    var pitch by mutableStateOf("2000")
    var lag by mutableStateOf("0")
    var satellites by mutableStateOf(true)
    var nudgeMode by mutableStateOf(NudgeMode.AUTO)
    var nudgePeriodS by mutableStateOf<Double?>(null)
    var session by mutableStateOf<Session?>(null)
    var eventLog by mutableStateOf<EventLog?>(null)
}

interface BenchActions {
    fun startOrStop()
    fun setSatellites(on: Boolean)
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
private const val STATUS_MS = 500L
private const val LOCKED_ALPHA = 0.55f
private val UK = ZoneId.of("Europe/London")
private val CLOCK_DIGITS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(UK)
private val CLOCK_ZONE = DateTimeFormatter.ofPattern("z", Locale.UK).withZone(UK)
private val LOCAL_DIGITS = DateTimeFormatter.ofPattern("HH:mm:ss z", Locale.UK)

/** "22:43:29", the flowtime of an event in UK time, or dashes before there was a time source. */
fun eventTime(e: Event): String = e.utc?.let { CLOCK_DIGITS.format(Instant.ofEpochSecond(0, it)) } ?: "--:--:--"

@Composable
fun TestBench(state: BenchState, actions: BenchActions, openLog: () -> Unit, openNudge: () -> Unit) {
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
        EventRow(state.eventLog, openLog)
        NudgeLink(state, openNudge)
        StatusGrid(state)
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
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Spacer(Modifier.width(8.dp))
            BasicText(if (s != null) "RUNNING" else "STOPPED", style = Tokens.Text.badge.style().copy(fontSize = 12.sp))
            if (s != null) {
                val seconds = (tick - s.startBoot).coerceAtLeast(0) / 1_000_000_000L
                Spacer(Modifier.width(8.dp))
                BasicText("%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60), style = Tokens.Text.valueSmall.style())
            }
        }
    }
}

@Composable
private fun ClockCard(state: BenchState) {
    val boot by rememberTick(CLOCK_MS)
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
                    if (!from.isNaN()) BasicText("TIGHTENING from ±" + fmtDuration(from), style = Tokens.Text.small.style(Ink.accent))
                }
            }
        }
    }
}

private const val WARN_ABOVE_NS = 5_000_000.0   // 5 ms, from the badge rules in design/ui-spec.md

/** "GMT · local 23:43:51 CET", with the local part left out when it matches UK time. */
private fun zoneLine(now: Instant): String {
    val zone = CLOCK_ZONE.format(now)
    val local = ZoneId.systemDefault()
    if (local.rules.getOffset(now) == UK.rules.getOffset(now)) return zone
    return "$zone · local " + LOCAL_DIGITS.withZone(local).format(now)
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
private fun SatellitesRow(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).toggleable(on, role = Role.Switch, onValueChange = onChange),
        Arrangement.SpaceBetween, Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText("Use satellites", style = Tokens.Text.bodyStrong.style())
            BasicText("Never locked · position stays on phone", style = Tokens.Text.small.style())
        }
        Row(
            Modifier.width(46.dp).heightIn(min = 26.dp, max = 26.dp).background(if (on) Ink.accent else Ink.grid, RoundedCornerShape(Tokens.Radius.switchTrack.dp)).padding(2.dp),
            horizontalArrangement = if (on) Arrangement.End else Arrangement.Start,
        ) {
            Box(Modifier.size(22.dp).background(if (on) Ink.onAccent else Ink.muted, CircleShape))
        }
    }
}

@Composable
private fun RunButton(running: Boolean, onClick: () -> Unit) {
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
        BasicText(if (running) "STOP RUN" else "START RUN", style = Tokens.Text.badge.style(tint).copy(fontSize = 15.sp))
    }
}

@Composable
private fun LinkRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp))
            .border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { content() }
        Spacer(Modifier.width(8.dp))
        Chevron(Ink.text, 16.dp)
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
private fun EventRow(log: EventLog?, openLog: () -> Unit) {
    rememberTick(STATUS_MS).value
    val last = log?.latest()
    LinkRow(openLog) {
        if (last == null) {
            BasicText("No events yet", style = Tokens.Text.body.style(Ink.muted))
        } else {
            BasicText(eventTime(last), style = Tokens.Text.valueSmall.style(Ink.muted))
            Spacer(Modifier.width(8.dp))
            BasicText("${last.lane.mark()} ${last.lane.name}", style = Tokens.Text.valueSmall.style(last.lane.color()).copy(fontSize = 10.sp, letterSpacing = 1.sp))
            Spacer(Modifier.width(8.dp))
            BasicText(last.text, Modifier.weight(1f), Tokens.Text.body.style(), overflow = TextOverflow.Ellipsis, maxLines = 1)
        }
    }
}

/** Not in the hand-off yet: the only route to the rate nudge controls until that screen is built. */
@Composable
private fun NudgeLink(state: BenchState, openNudge: () -> Unit) {
    val boot by rememberTick(STATUS_MS)
    val gap = state.session?.nudge?.gapAt(boot) ?: 0L
    val mode = if (state.nudgeMode == NudgeMode.AUTO) "Auto" else "Manual"
    LinkRow(openNudge) {
        BasicText("Rate nudge · $mode · " + if (gap == 0L) "no gap" else "gap " + fmtDuration(gap.toDouble()), style = Tokens.Text.body.style())
    }
}

@Composable
private fun StatusGrid(state: BenchState) {
    val boot by rememberTick(STATUS_MS)
    val s = state.session
    Card {
        if (s == null) {
            BasicText("Stopped. Set up both phones the same way, then press Start on each.", style = Tokens.Text.body.style(Ink.muted))
            return@Card
        }
        val mono = Tokens.Text.valueSmall.style().copy(fontSize = 11.5.sp)
        for (row in s.statusRows(boot)) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(row.label, Modifier.width(58.dp), Tokens.Text.cardLabel.style().copy(fontSize = 10.sp, letterSpacing = 1.4.sp))
                Spacer(Modifier.width(10.dp))
                BasicText(
                    buildAnnotatedString {
                        for (part in row.parts) {
                            if (part.mono) {
                                pushStyle(SpanStyle(fontFamily = mono.fontFamily, color = if (part.good) Ink.accent else Ink.text))
                                append(part.text)
                                pop()
                            } else append(part.text)
                        }
                    },
                    Modifier.weight(1f), Tokens.Text.body.style().copy(fontSize = 11.5.sp),
                )
            }
        }
    }
}
