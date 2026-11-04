package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.beachsync.proto.AudioOutput
import org.beachsync.proto.CalSource
import org.beachsync.proto.Calibration
import org.beachsync.proto.Earcup
import org.beachsync.proto.EarcupFault
import org.beachsync.proto.EarcupOutcome
import org.beachsync.proto.EarcupRun
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TICK_MS = 250L

private fun msText(v: Double) = org.beachsync.proto.fmtMillis(v)

private fun sourceWord(s: CalSource) = when (s) {
    CalSource.TABLE -> "TABLE"; CalSource.EARCUP -> "EARCUP"; CalSource.HANDSHAKE -> "HANDSHAKE"; CalSource.TYPED -> "TYPED"; CalSource.NONE -> "NOT MEASURED"
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun Pill(label: String, primary: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Box(
        modifier.heightIn(min = 52.dp).alpha(if (enabled) 1f else 0.4f).then(if (primary) Modifier.background(Ink.accent, shape) else Modifier.border(Tokens.Stroke.emphasis.dp, Ink.grid, shape))
            .clickable(enabled, role = Role.Button, onClick = onClick),
        Alignment.Center,
    ) { BasicText(label, style = Tokens.Text.bodyStrong.style(if (primary) Ink.onAccent else Ink.text)) }
}

/** One home for calibration. The two-phone test and typed values come later, so their rows and buttons show that. */
@Composable
fun CalibrationPage(state: BenchState, actions: BenchActions, back: () -> Unit, openEarcup: () -> Unit) {
    rememberTick(1000L).value
    val output: AudioOutput = actions.output()
    val saved: Calibration? = actions.savedCalibration(output)
    val table: Calibration = actions.tableCalibration(output)
    val inUse = saved ?: table
    val running = state.session != null
    Page {
        ScreenHeader("Calibration", back)
        Card {
            BasicText(output.name, style = Tokens.Text.bodyStrong.style(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            BasicText(output.route, style = Tokens.Text.small.style(Ink.textSecondary))
            if (inUse.source == CalSource.NONE) {
                BasicText("Not measured", style = Tokens.Text.hero.style().copy(fontSize = Tokens.Text.clock.size.sp()))
                BasicText("▲ NOT MEASURED", style = Tokens.Text.badge.style(Ink.warn))
            } else {
                BasicText(msText(inUse.lagMs) + "  ±" + "%.1f".format(Locale.ROOT, inUse.plusMinusMs), style = Tokens.Text.value.style().copy(fontSize = Tokens.Text.clock.size.sp()))
                BasicText(sourceWord(inUse.source), style = Tokens.Text.badge.style(Ink.accent))
            }
            BasicText("Audio is sent this much early, so it reaches your ears in time with everyone else.", style = Tokens.Text.body.style())
        }
        if (output.route.startsWith("Bluetooth")) BasicText("Bluetooth delay can change each time headphones connect, so a saved value may not hold. If the sync sounds off, test again.", style = Tokens.Text.small.style(Ink.textSecondary))
        BasicText("SOURCES · ONE IS IN USE", Modifier.padding(top = 4.dp), Tokens.Text.cardLabel.style())
        Card {
            SourceRow("Shared table", "This phone with this output", table.takeIf { it.source == CalSource.TABLE }, inUse.source == CalSource.TABLE)
            SourceRow("Earcup test", "This phone", saved, inUse.source == CalSource.EARCUP)
            SourceRow("Two-phone test", "Not available yet", null, false)
            SourceRow("Typed", "Not available yet", null, false)
        }
        Pill("Earcup test · about 15 s", true, output.external && !running, onClick = openEarcup)
        if (running) BasicText("Stop playback first: the test needs the speaker and headphones to itself.", style = Tokens.Text.small.style(Ink.warn))
        else if (!output.external) BasicText("Connect your headphones first.", style = Tokens.Text.small.style(Ink.warn))
        BasicText("The mic is on only during a test. No audio leaves the phone.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

@Composable
private fun SourceRow(title: String, detail: String, value: Calibration?, used: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style())
        }
        BasicText(if (value != null) msText(value.lagMs) else "—", style = Tokens.Text.valueSmall.style())
        Spacer(Modifier.width(8.dp))
        BasicText(if (used) "IN USE" else if (value == null) "not yet" else "", style = Tokens.Text.small.style(if (used) Ink.accent else Ink.muted))
    }
}

@Composable
fun EarcupPage(state: BenchState, actions: BenchActions, back: () -> Unit) {
    rememberTick(TICK_MS).value
    val run: EarcupRun? = state.earcup
    val output = actions.output()
    Page {
        ScreenHeader("Earcup test", { actions.cancelEarcup(); back() })
        val outcome = run?.outcome
        when {
            run == null -> Card {
                BasicText("1 · PLACE THE CUP", style = Tokens.Text.cardLabel.style())
                BasicText("Testing ${output.name}", style = Tokens.Text.bodyStrong.style())
                BasicText("Hold one ear cup, or a bud, against the bottom of the phone, over the mic. The phone clicks through its speaker, then your headphones. Turn the volume up first.", style = Tokens.Text.body.style())
                BasicText("Needs the mic for about 15 s. Nothing is recorded or sent.", style = Tokens.Text.small.style(Ink.textSecondary))
                Pill("Start", true, output.external) { actions.startEarcup(output) }
            }
            outcome == null -> Card {
                BasicText("2 · LISTENING", style = Tokens.Text.cardLabel.style())
                val phase = run.phase
                Stage("Speaker clicks", phase == EarcupRun.Phase.SPEAKER, phase != EarcupRun.Phase.SPEAKER)
                Stage("Headphone clicks", phase == EarcupRun.Phase.HEADPHONES, phase == EarcupRun.Phase.ANALYSING)
                val total = 2 * EarcupRun.legSeconds()
                val elapsed = (System.currentTimeMillis() - run.startedMs) / 1000.0
                Box(Modifier.fillMaxWidth().height(6.dp).background(Ink.track, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth((elapsed / total).coerceIn(0.0, 1.0).toFloat()).height(6.dp).background(Ink.accent, RoundedCornerShape(3.dp)))
                }
                BasicText(if (phase == EarcupRun.Phase.ANALYSING) "Working it out…" else "Keep still · %.0f s left".format(Locale.ROOT, (total - elapsed).coerceAtLeast(0.0)), style = Tokens.Text.small.style())
                Pill("Cancel", false) { actions.cancelEarcup() }
            }
            outcome is EarcupOutcome.Pass -> EarcupResult(outcome, output, actions)
            outcome is EarcupOutcome.Fail -> Failure(
                when (outcome.fault) { EarcupFault.TOO_QUIET -> "Too quiet"; EarcupFault.TOO_NOISY -> "Too noisy"; EarcupFault.DISAGREE -> "Readings disagree" },
                when (outcome.fault) {
                    EarcupFault.TOO_QUIET -> "Press the cup closer to the mic and turn the volume up."
                    EarcupFault.TOO_NOISY -> "Find a quieter spot, then try again."
                    EarcupFault.DISAGREE -> "Clicks gave ${org.beachsync.proto.fmtMillis(outcome.lowMs, 0)} to ${org.beachsync.proto.fmtMillis(outcome.highMs, 0)}. Hold still and try again."
                },
                actions, back,
            )
            outcome is EarcupOutcome.Problem -> Failure("Test couldn't run", outcome.text, actions, back)
        }
    }
}

@Composable
private fun Failure(title: String, fix: String, actions: BenchActions, back: () -> Unit) {
    Card {
        BasicText("▲ $title", style = Tokens.Text.bodyStrong.style(Ink.warn))
        BasicText(fix, style = Tokens.Text.body.style())
        BasicText("Nothing was saved.", style = Tokens.Text.small.style())
        Pill("Try again", true) { actions.resetEarcup() }
        Pill("Cancel", false) { actions.resetEarcup(); back() }
    }
}

@Composable
private fun Stage(label: String, now: Boolean, done: Boolean) {
    val tint: Color = if (now || done) Ink.accent else Ink.muted
    BasicText((if (done) "✓ " else if (now) "● " else "○ ") + label, style = Tokens.Text.body.style(if (now || done) Ink.text else Ink.muted).copy(color = if (now || done) Ink.text else tint))
}

private fun dirWord(v: Double, pos: String, neg: String) = if (v >= 0) pos else neg
private fun wholeMs(v: Double) = org.beachsync.proto.fmtMillis(abs(v), 0)

/** Another peak at least half as strong as the main one, well away from it, means the match isn't one clear click. */
private fun severalPeaks(profile: FloatArray, peakMs: Double): Boolean {
    val per = profile.size / (Earcup.BEFORE_MS + Earcup.AFTER_MS)
    val top = profile.maxOrNull() ?: return false
    val near = (20 * per).toInt()
    val at = ((peakMs + Earcup.BEFORE_MS) * per).toInt()
    for (i in 1 until profile.size - 1) {
        if (abs(i - at) > near && profile[i] >= 0.5f * top && profile[i] >= profile[i - 1] && profile[i] >= profile[i + 1]) return true
    }
    return false
}

/** Hand-off 11. No signs anywhere: direction is always a word. Sharing isn't built yet, so there's no share switch. */
@Composable
private fun EarcupResult(outcome: EarcupOutcome.Pass, output: AudioOutput, actions: BenchActions) {
    val value = actions.earcupValue(outcome)
    val speakerLag = value.lagMs - outcome.laterMs
    val speakerRow = org.beachsync.proto.LatencyTable.lookup(org.beachsync.proto.RouteKey(android.os.Build.MODEL, "speaker"))
    var done by remember(outcome) { mutableStateOf(false) }
    val unusual = outcome.laterMs < 0
    val pm = value.plusMinusMs
    val tryAgainOnly = pm > 15.0
    val tint = if (unusual) Ink.warn else Ink.accent
    val portable = output.kind == org.beachsync.proto.OutputKind.PORTABLE_SPEAKER
    val who = if (portable) "speaker" else "headphones"
    val mono = Tokens.Text.value.style().fontFamily
    BasicText("${output.name.uppercase()} · ${output.route.uppercase()}", style = Tokens.Text.cardLabel.style())
    val headline = androidx.compose.ui.text.buildAnnotatedString {
        append(if (unusual) "Your $who played " else "Your $who plays ")
        pushStyle(androidx.compose.ui.text.SpanStyle(color = tint, fontFamily = mono)); append(wholeMs(outcome.laterMs) + " " + dirWord(outcome.laterMs, "later", "earlier")); pop()
        append(" than the phone's ${if (portable) "own " else ""}speaker.")
    }
    BasicText(headline, style = Tokens.Text.bodyStrong.style().copy(fontSize = Tokens.Text.clock.size.sp() * 0.7f))
    val (mark, word, markColor) = when {
        unusual -> Triple("▲", "UNUSUAL RESULT", Ink.warn)
        pm > 15.0 -> Triple("✕", "TRY AGAIN", Ink.warn)
        pm >= 5.0 -> Triple("▲", "UNSTEADY", Ink.warn)
        else -> Triple("●", "GOOD", Ink.accent)
    }
    BasicText("$mark $word  ±" + org.beachsync.proto.fmtMillis(pm), style = Tokens.Text.bodyStrong.style(markColor))
    GapTimeline(outcome.laterMs, tint)
    if (unusual) BasicText("Bluetooth headphones are almost always later. Press the cup flat over the mic and try again.", style = Tokens.Text.body.style(Ink.warn))
    Card {
        BasicText(if (unusual) "WHAT SAVING WOULD DO" else "WHAT GETS SAVED", style = Tokens.Text.cardLabel.style())
        SavedRow("Phone speaker's own delay", if (speakerRow.source == CalSource.TABLE) "from the shared table for ${android.os.Build.MODEL}" else "not in the shared table", msText(speakerLag), false)
        SavedRow("${who.replaceFirstChar { it.uppercase() }} vs speaker", "measured just now by the mic", wholeMs(outcome.laterMs) + " " + dirWord(outcome.laterMs, "later", "earlier"), false)
        SavedRow("Saved delay for this $who", "the value Brocast will use", wholeMs(value.lagMs), true)
        val sentence = androidx.compose.ui.text.buildAnnotatedString {
            append(if (unusual) "Brocast would send audio " else "Brocast will send audio ")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = tint, fontFamily = mono)); append(wholeMs(value.lagMs) + " " + dirWord(value.lagMs, "early", "late")); pop()
            append(if (unusual) "." else ", so it reaches your ears on time.")
        }
        BasicText(sentence, style = Tokens.Text.body.style())
    }
    Card {
        val several = severalPeaks(outcome.headphones.profile, outcome.headphones.peakMs)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("MIC CHECK", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            BasicText(if (several) "▲ SEVERAL PEAKS" else "✓ ONE CLEAR PEAK", style = Tokens.Text.cardLabel.style(if (several) Ink.warn else Ink.accent))
        }
        BasicText("How long after each $who click left the phone the mic heard it. Good: one sharp peak. Several peaks or a flat line: try again.", style = Tokens.Text.small.style())
        MatchPeak(outcome.headphones.profile, outcome.headphones.peakMs)
    }
    BasicText("Applies when playback next starts.", style = Tokens.Text.small.style(Ink.textSecondary))
    if (tryAgainOnly) {
        BasicText("Nothing was saved.", style = Tokens.Text.small.style())
        Pill("Try again", true) { actions.resetEarcup() }
    } else {
        Pill("Try again", unusual) { actions.resetEarcup() }
        Pill(if (done) "Saved" else "Save", !unusual, !done) { actions.saveCalibration(output, value); done = true }
    }
}

@Composable
private fun SavedRow(title: String, detail: String, value: String, strong: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style())
        }
        BasicText(value, style = Tokens.Text.valueSmall.style(if (strong) Ink.accent else Ink.text))
    }
}

/** Two clicks on a time line, speaker then headphones. Small gaps are drawn wider so the labels fit: it's a sketch. */
@Composable
private fun GapTimeline(laterMs: Double, tint: Color) {
    val label = "Speaker click, then headphone click ${wholeMs(laterMs)} ${dirWord(laterMs, "later", "earlier")}"
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = label }) {
        val w = size.width
        val span = (abs(laterMs) / 300.0).coerceIn(0.25, 0.7).toFloat() * w
        val speakerX = if (laterMs >= 0) w * 0.1f else w * 0.1f + span
        val phonesX = if (laterMs >= 0) speakerX + span else w * 0.1f
        drawLine(Ink.muted, Offset(0f, 38.dp.toPx()), Offset(w, 38.dp.toPx()), 1.dp.toPx())
        drawLine(Ink.text, Offset(speakerX, 12.dp.toPx()), Offset(speakerX, 38.dp.toPx()), 3.dp.toPx())
        drawLine(tint, Offset(phonesX, 12.dp.toPx()), Offset(phonesX, 38.dp.toPx()), 3.dp.toPx())
        val y = 48.dp.toPx()
        drawLine(tint, Offset(speakerX, y), Offset(phonesX, y), 1.5.dp.toPx())
        val head = 4.dp.toPx() * (if (phonesX >= speakerX) 1f else -1f)
        drawLine(tint, Offset(phonesX, y), Offset(phonesX - head, y - 3.dp.toPx()), 1.5.dp.toPx())
        drawLine(tint, Offset(phonesX, y), Offset(phonesX - head, y + 3.dp.toPx()), 1.5.dp.toPx())
    }
}

/** The match strength by delay from the output stamp, with its one peak: a second peak would mean an unreliable result. */
@Composable
private fun MatchPeak(profile: FloatArray, peakMs: Double) {
    val label = "Match strength by delay, with one peak at ${peakMs.roundToInt()} ms"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = label }) {
            val top = profile.maxOrNull()?.takeIf { it > 0f } ?: 1f
            val n = profile.size
            val step = maxOf(1, n / size.width.toInt().coerceAtLeast(1))
            val line = Path()
            var i = 0
            while (i < n) {
                val x = size.width * i / n
                var most = 0f
                for (k in i until minOf(n, i + step)) most = maxOf(most, abs(profile[k]))
                val y = size.height * (1f - most / top)
                if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                i += step
            }
            drawPath(line, Ink.accent, style = Stroke(1.5.dp.toPx()))
            val peakX = size.width * ((peakMs + Earcup.BEFORE_MS) / (Earcup.BEFORE_MS + Earcup.AFTER_MS)).toFloat()
            drawLine(Ink.text, Offset(peakX, 0f), Offset(peakX, size.height), 1.dp.toPx())
        }
        BasicText(org.beachsync.proto.fmtMillis(peakMs, 0) + " after the click left the phone", style = Tokens.Text.small.style())
    }
}

private fun Float.sp() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
