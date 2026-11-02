package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.beachsync.proto.Lane
import org.beachsync.proto.NudgeControl
import org.beachsync.proto.NudgeMode
import org.beachsync.proto.Session
import org.beachsync.proto.fmtDuration
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/*
 * Temporary, plain versions of the event log and rate nudge screens, so those functions stay reachable from the
 * test bench. They use the design's theme but not its layouts; design/screens/ has the real designs, to be built next.
 */

@Composable
private fun PlainFrame(title: String, back: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.Space.stackGap.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp), verticalAlignment = Alignment.CenterVertically) {
            PlainButton("Back", true, onClick = back)
            Box(Modifier.padding(start = 12.dp)) { BasicText(title, style = Tokens.Text.screenTitle.style()) }
        }
        content()
    }
}

@Composable
private fun PlainButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = Tokens.Space.touchTarget.dp).alpha(if (enabled) 1f else 0.4f)
            .border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.control.dp))
            .clickable(enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        Alignment.Center,
    ) { BasicText(text, style = Tokens.Text.bodyStrong.style()) }
}

@Composable
fun PlainEventLog(state: BenchState, lane: Lane?, back: () -> Unit) {
    rememberTick(500L).value
    var filter by remember(lane) { mutableStateOf(lane) }
    val only = filter
    PlainFrame(if (only != null) "Event log · ${only.name}" else "Event log", back) {
        if (only != null) PlainButton("Show all", true) { filter = null }
        val events = state.eventLog?.recent(200).orEmpty().filter { only == null || it.lane == only }
        if (events.isEmpty()) {
            BasicText("No events yet", style = Tokens.Text.body.style(Ink.muted))
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (e in events) {
                        Row {
                            BasicText(eventTime(e), style = Tokens.Text.valueSmall.style(Ink.muted))
                            Box(Modifier.padding(horizontal = 8.dp)) {
                                BasicText("${e.lane.mark()} ${e.lane.name}", style = Tokens.Text.valueSmall.style(e.lane.color()))
                            }
                            BasicText(e.text, Modifier.weight(1f), Tokens.Text.body.style())
                        }
                    }
                }
            }
        }
    }
}

private fun periodToProgress(s: Double) = (ln(s / NudgeControl.MIN_PERIOD_S) / ln(NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S)).toFloat()

private fun progressToPeriod(p: Float) = NudgeControl.MIN_PERIOD_S * (NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S).pow(p.toDouble())

@Composable
fun PlainNudge(state: BenchState, actions: BenchActions, back: () -> Unit) {
    val boot by rememberUpdatedState(rememberTick(250L).value)
    val n = state.session?.nudge
    PlainFrame("Rate nudge", back) {
        Card {
            Segmented("Mode", listOf("Auto", "Manual"), if (state.nudgeMode == NudgeMode.AUTO) 0 else 1, true) {
                actions.setNudgeMode(if (it == 0) NudgeMode.AUTO else NudgeMode.MANUAL)
            }
            val period = state.nudgePeriodS
            BasicText("Period: " + if (period == null) "recommended" else "%.0f s".format(Locale.ROOT, period), style = Tokens.Text.body.style(Ink.textSecondary))
            LogSlider(periodToProgress(period ?: NudgeControl.RECOMMENDED_S), "Period, 1 second to 5 minutes") { actions.setNudgePeriod(progressToPeriod(it)) }
            val gap = n?.gapAt(boot) ?: 0L
            val closing = n != null && n.state.closing && gap != 0L
            val line = if (gap == 0L || n == null) "No gap" else {
                val secs = if (closing) n.secondsLeft(boot) else n.effectivePeriodS(gap)
                val rate = if (closing) abs(n.state.rate) else abs(gap) / (secs * 1e9)
                "Gap ${fmtDuration(gap.toDouble())} · " + (if (closing) "closing, %.0f s left" else "held, would take %.0f s").format(Locale.ROOT, secs) +
                    " · up to %.2f cents".format(Locale.ROOT, 1200 * log2(1 + rate))
            }
            BasicText(line, style = Tokens.Text.valueSmall.style())
            val manual = state.nudgeMode == NudgeMode.MANUAL
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainButton("Recommended", true, Modifier.weight(1.4f)) { actions.setNudgePeriod(null) }
                PlainButton(if (closing) "Pause" else "Go", manual && gap != 0L, Modifier.weight(1f)) {
                    val now = Session.nowBoot()
                    if (n != null) { if (n.state.closing) n.pause(now) else n.go(now) }
                }
                PlainButton("Jump", manual && gap != 0L, Modifier.weight(1f)) { n?.jump(Session.nowBoot()) }
            }
        }
    }
}

/** A real slider for screen readers as well as fingers. Compose's own lives in Material, which the app doesn't otherwise need. */
@Composable
private fun LogSlider(progress: Float, label: String, onChange: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    val change by rememberUpdatedState(onChange)
    val p = progress.coerceIn(0f, 1f)
    Canvas(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .semantics {
                contentDescription = label
                stateDescription = "%.0f seconds".format(Locale.ROOT, progressToPeriod(p))
                progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
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
        drawLine(Ink.grid, Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Ink.accent, Offset(0f, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(Ink.accent, 10.dp.toPx(), Offset(x, y))
    }
}
