package org.beachsync.proto.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beachsync.proto.BeatGrid
import org.beachsync.proto.BeatTrack
import org.beachsync.proto.CalSource
import org.beachsync.proto.Calibration
import org.beachsync.proto.FlowTime
import org.beachsync.proto.OutputIcon
import org.beachsync.proto.Renderer
import org.beachsync.proto.Session
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Hand-off 34: the Sync view, for checking phones side by side by eye and ear (the camera test card is for exact numbers). The outer disc sweeps once per
 * flowtime second and the screen flashes white for 2 frames at 12 o'clock; the inner disc sweeps once per beat of the music. With nothing playing, a tick
 * run gives the click at each second (the app's own tick, at the channel's pitch, as on the test card). Two nudges: Screen moves only the picture; Audio
 * changes what is heard, is removed on leaving, and can be kept as calibration. It reads flowtime and the beat grid; the audio nudge goes through
 * Session's existing diagnostic offset, so nothing here touches the audio thread.
 */

private val SECOND_NS = 1_000_000_000L
private val READOUT = DateTimeFormatter.ofPattern("HH:mm:ss")

@Composable
fun SyncScope(state: BenchState, actions: BenchActions, back: () -> Unit, openLine: () -> Unit = {}, openTestCard: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var mode by remember { mutableIntStateOf(2) }   // Seconds, Beats, Both
    var clickOn by remember { mutableStateOf(true) }
    var screenMs by remember { mutableDoubleStateOf(0.0) }
    var flashLeft by remember { mutableIntStateOf(0) }
    var frame by remember { mutableLongStateOf(Session.nowBoot()) }
    // Each frame: the time, and 2 frames of white from the first frame of each new flowtime second. Never more than once a second.
    LaunchedEffect(Unit) {
        var lastSecond = Long.MIN_VALUE
        while (true) withFrameNanos {
            frame = Session.nowBoot()
            if (flashLeft > 0) flashLeft--
            val utc = FlowTime.fit()?.utcAt(frame)?.plus(Math.round((ScreenPrefs.screenLagMs - screenMs) * 1e6))
            if (utc != null) {
                val second = Math.floorDiv(utc, SECOND_NS)
                if (lastSecond != Long.MIN_VALUE && second != lastSecond && clickOn && !ScreenPrefs.noFlashing) flashLeft = 2
                lastSecond = second
            }
        }
    }
    // The click: with nothing playing, a tick run at this phone's pitch, for as long as the view is open. With music, the music is the sound.
    DisposableEffect(clickOn) {
        val before = state.stream to state.pitch
        val started = clickOn && state.session == null
        if (started) {
            state.stream = false
            state.pitch = if (state.channel == Renderer.RIGHT) "3000" else "2000"
            actions.listen(clicks = true)
        }
        onDispose {
            if (started) {
                if (state.session != null) actions.startOrStop()
                state.stream = before.first; state.pitch = before.second
            }
        }
    }
    // Leaving always removes the audio nudge; the toast offers to keep it. Declared after the click run, so it's undone first.
    val sessionNow by rememberUpdatedState(state.session)
    DisposableEffect(Unit) {
        onDispose {
            val s = sessionNow
            val n = s?.diagnosticOffsetMs ?: 0.0
            if (s != null && n != 0.0) { s.setDiagnosticOffsetMs(0.0); SyncNudge.offer(n) }
        }
    }

    val s = state.session
    val fit = FlowTime.fit()
    val utc = fit?.utcAt(frame)?.plus(Math.round((ScreenPrefs.screenLagMs - screenMs) * 1e6))   // this phone's saved screen lag (hand-off 39), then the nudge
    val refresh = view.display?.refreshRate ?: 60f
    val nudge = s?.diagnosticOffsetMs ?: 0.0
    val beat = rememberBeat(state, s, utc, mode != 0)
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Sync view", back) {
                BasicText("${when (state.channel) { Renderer.LEFT -> "L"; Renderer.RIGHT -> "R"; else -> "L+R" }} · ${refresh.roundToInt()} Hz", Modifier.padding(end = 4.dp), Tokens.Text.clockLocal.style(Ink.textSecondary).copy(fontSize = 13.sp))
            }
            if (nudge != 0.0) NudgedBanner(nudge) { s?.setDiagnosticOffsetMs(0.0) }
            Readout(utc, fit?.errAt(frame), state.localTime)
            Dial(mode, utc, beat)
            Legend(mode, beat)
            Choice(listOf("Seconds", "Beats", "Both", "Line"), mode, { if (it == 3) openLine() else mode = it })
            SwitchRow(
                "Flash and click at 0",
                when {
                    ScreenPrefs.noFlashing -> "No flashing is on, so no flash here. The click still plays."
                    s?.stream != null -> "Screen white 2 frames, once a second. Music is playing, so it's the sound to listen to."
                    else -> "Screen white 2 frames and a click, once a second"
                },
                clickOn,
            ) { clickOn = it }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NudgeBox("Screen", screenMs, false, "Moves only the picture", true, Modifier.weight(1f)) { screenMs = it }
                NudgeBox("Audio", nudge, true, if (s == null) "Needs something playing" else "Changes what you hear. Not saved.", s != null, Modifier.weight(1f)) { s?.setDiagnosticOffsetMs(it) }
            }
            Reading(nudge, { SyncNudge.sheet = nudge }) { s?.setDiagnosticOffsetMs(0.0) }
            BasicText(
                "Lay both phones side by side. The one whose hand hasn't reached 12 o'clock is behind. Each tick is 100 ms; the hand moves 3.6° per 10 ms, so 10 ms is the smallest gap worth reading by eye.",
                style = Tokens.Text.small.style(Ink.textSecondary),
            )
            SeeingAGap(refresh, openTestCard)
        }
        if (flashLeft > 0) Box(Modifier.fillMaxSize().background(Color.White))
    }
}

/** Where the music is in its beat: from the set's own grid, worked out after download, or the live tracker until that's ready. */
internal class BeatNow(val frac: Double, val number: Int, val bpm: Double)

@Composable
internal fun rememberBeat(state: BenchState, s: Session?, utc: Long?, wanted: Boolean): BeatNow? {
    val stream = s?.stream
    val track = remember(stream, wanted) { if (wanted) stream?.let { BeatTrack(it) } else null }
    DisposableEffect(track) { track?.start(); onDispose { track?.stopNow() } }
    val m = stream?.manifest
    if (!wanted || utc == null || m == null) return null
    val rate = 48000.0
    val pos = (utc - m.startNs - m.dMs * 1_000_000L).toDouble() * rate / 1e9
    val lib = state.library
    val setBeat = if (s.s.library && lib != null) libraryBeat(lib, utc, pos) else null
    if (setBeat != null) return BeatNow(setBeat.beatFrac, setBeat.beatInBar + 1, setBeat.bpm)
    val grid = track?.grids?.let { BeatGrid.resolve(BeatTrack.windowAt(pos.toLong()), it) } ?: return null
    val x = (pos - grid.phaseSample) / grid.periodSamples
    val k = Math.floor(x)
    return BeatNow(x - k, Math.floorMod(k.toLong() - grid.barOffset, 4L).toInt() + 1, 60 * rate / grid.periodSamples)
}

@Composable
private fun NudgedBanner(nudge: Double, reset: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Row(
        Modifier.fillMaxWidth().background(Ink.warn.copy(alpha = 0.12f).compositeOver(Ink.bg), shape).border(Tokens.Stroke.card.dp, Ink.warn, shape).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("⚠", Modifier.padding(end = 10.dp), Tokens.Text.bodyStrong.style(Ink.warn))
        BasicText(
            buildAnnotatedString {
                append("Audio nudged ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(signedMs(nudge)) }
                append(" · this screen only")
            },
            Modifier.weight(1f), Tokens.Text.body.style(),
        )
        BasicText("Reset", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = reset).padding(horizontal = 12.dp, vertical = 12.dp), Tokens.Text.bodyStrong.style(Ink.warn))
    }
}

@Composable
private fun Readout(utc: Long?, errNs: Double?, local: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        val text = if (utc == null) "--:--:--.---" else {
            val zone = if (local) ZoneId.systemDefault() else UK
            READOUT.withZone(zone).format(Instant.ofEpochSecond(0, utc)) + "." + "%03d".format(Locale.ROOT, Math.floorMod(utc, SECOND_NS) / 1_000_000L)
        }
        BasicText(
            buildAnnotatedString {
                append(text.substringBefore('.'))
                withStyle(SpanStyle(color = Ink.muted)) { append(".") }
                append(text.substringAfter('.'))
            },
            Modifier.weight(1f), Tokens.Text.clock.style().copy(fontSize = 34.sp), maxLines = 1,
        )
        Column(horizontalAlignment = Alignment.End) {
            BasicText("FLOWTIME", style = Tokens.Text.cardLabel.style())
            BasicText(
                if (utc == null || errNs == null) "NTP · getting time" else "${FlowTime.chosen?.name ?: "NTP"} · ±${org.beachsync.proto.fmtDuration(errNs)}",
                style = Tokens.Text.clockLocal.style(Ink.text).copy(fontSize = 13.sp), maxLines = 1,
            )
        }
    }
}

/** The two-disc dial: seconds outside, beats inside. Before a time source answers, the hand waits at 12; with no steady beat, the inner disc shows "—". */
@Composable
private fun Dial(mode: Int, utc: Long?, beat: BeatNow?) {
    val outer = mode != 1
    val inner = mode != 0
    val second = if (utc == null) 0f else Math.floorMod(utc, SECOND_NS) / 1e9f
    val innerBase = Ink.accent.copy(alpha = 0.10f).compositeOver(Ink.bg)
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) {
            val r = size.minDimension / 2f
            val rIn = if (outer) r * 0.56f else r
            fun at(turn: Float, radius: Float): Offset {
                val a = (turn * 2.0 * Math.PI - Math.PI / 2).toFloat()
                return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
            }
            if (outer) {
                drawCircle(Ink.grid, r)
                drawArc(Ink.text, -90f, second * 360f, true, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r))
                for (i in 0 until 10) {
                    val heavy = i == 0
                    drawLine(Ink.bg, at(i / 10f, r * if (heavy) 0.9f else 0.94f), at(i / 10f, r), (if (heavy) 3.dp else 1.5.dp).toPx())
                }
                if (utc != null) drawLine(Ink.accent, at(second, rIn), at(second, r), 2.5.dp.toPx(), StrokeCap.Round)
            }
            if (inner) {
                if (outer) drawCircle(Ink.bg, rIn + 6.dp.toPx())
                drawCircle(innerBase, rIn)
                if (beat != null) drawArc(Ink.accent, -90f, beat.frac.toFloat() * 360f, true, Offset(center.x - rIn, center.y - rIn), Size(2 * rIn, 2 * rIn))
            }
        }
        if (inner) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(beat?.number?.toString() ?: "—", style = Tokens.Text.hero.style(Ink.text).copy(fontSize = if (outer) 44.sp else 64.sp, textAlign = TextAlign.Center))
            BasicText("BEAT", style = Tokens.Text.cardLabel.style(Ink.muted))
        }
    }
}

@Composable
private fun Legend(mode: Int, beat: BeatNow?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mode != 1) LegendItem(Ink.text, "outer: each second, flash at 12 o'clock", Modifier.weight(1f))
        if (mode != 0) LegendItem(Ink.accent, if (beat == null) "inner: each beat · no steady beat" else "inner: each beat · %.0f BPM".format(Locale.ROOT, beat.bpm), Modifier.weight(1f))
    }
}

@Composable
private fun LegendItem(colour: Color, text: String, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp, end = 6.dp).size(10.dp).background(colour))
        BasicText(text, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/** One nudge, in steps of 10 and 1 ms. The Audio box is amber: it changes what is heard. */
@Composable
private fun NudgeBox(title: String, valueMs: Double, audio: Boolean, caption: String, enabled: Boolean, modifier: Modifier, set: (Double) -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    val tint = if (audio) Ink.warn else null
    Column(
        modifier.border(Tokens.Stroke.card.dp, if (audio && enabled) Ink.warn else Ink.border, shape).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, Modifier.weight(1f), Tokens.Text.bodyStrong.style())
            BasicText(signedMs(valueMs), style = Tokens.Text.clockLocal.style(if (valueMs != 0.0) tint ?: Ink.text else Ink.text).copy(fontSize = 15.sp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((label, step) in listOf("−10" to -10.0, "−1" to -1.0, "+1" to 1.0, "+10" to 10.0)) {
                BasicText(
                    label,
                    Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).clickable(enabled, role = Role.Button) { set(valueMs + step) }.padding(vertical = 12.dp),
                    Tokens.Text.clockLocal.style(if (enabled) Ink.text else Ink.faint).copy(fontSize = 13.sp, textAlign = TextAlign.Center), maxLines = 1,
                )
            }
        }
        BasicText(caption, style = Tokens.Text.small.style(if (audio && enabled) Ink.warn else Ink.textSecondary))
    }
}

/** The by-ear reading, from the audio nudge's sign: a phone that needed to play earlier was behind. */
@Composable
private fun Reading(nudge: Double, keep: () -> Unit, reset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            buildAnnotatedString {
                append("This phone, by your ears: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(if (nudge == 0.0) "no nudge yet" else "${wholeMs(abs(nudge))} ${if (nudge > 0) "behind" else "ahead"}")
                }
            },
            Modifier.weight(1f), Tokens.Text.body.style(),
        )
        BasicText("Keep as calibration", Modifier.width(120.dp).heightIn(min = 44.dp).clickable(nudge != 0.0, role = Role.Button, onClick = keep).padding(horizontal = 8.dp, vertical = 4.dp), Tokens.Text.body.style(if (nudge != 0.0) Ink.accent else Ink.faint))
        BasicText("Reset", Modifier.heightIn(min = 44.dp).clickable(nudge != 0.0, role = Role.Button, onClick = reset).padding(horizontal = 8.dp, vertical = 12.dp), Tokens.Text.body.style(if (nudge != 0.0) Ink.text else Ink.faint))
    }
}

/** Screens draw in steps. Only this phone's rate is known here; the other phone shows its own in its header. */
@Composable
private fun SeeingAGap(refresh: Float, openTestCard: () -> Unit) {
    Card {
        BasicText("SEEING A GAP?", style = Tokens.Text.cardLabel.style())
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                BasicText("THIS SCREEN", style = Tokens.Text.cardLabel.style(Ink.muted).copy(fontSize = 10.sp))
                BasicText("%.0f Hz · %.1f ms a frame".format(Locale.ROOT, refresh, 1000f / refresh), style = Tokens.Text.clockLocal.style(Ink.text).copy(fontSize = 13.sp))
            }
            Column(Modifier.weight(1f)) {
                BasicText("THE OTHER PHONE", style = Tokens.Text.cardLabel.style(Ink.muted).copy(fontSize = 10.sp))
                BasicText("In its header", style = Tokens.Text.clockLocal.style(Ink.textSecondary).copy(fontSize = 13.sp))
            }
        }
        BasicText(
            "Screens draw in steps, and each one adds its own delay. A gap you see that's under about one frame of the slower screen may be the screens, not the sound. Trust your ears: the clicks are what you hear. For exact numbers, use the camera test card.",
            style = Tokens.Text.small.style(Ink.textSecondary),
        )
        BasicText("Camera test card ›", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = openTestCard).padding(vertical = 12.dp), Tokens.Text.body.style(Ink.accent))
    }
}

private fun signedMs(ms: Double) = if (ms == 0.0) "0 ms" else (if (ms > 0) "+" else "−") + wholeMs(abs(ms))

private fun wholeMs(ms: Double) = "%.0f ms".format(Locale.ROOT, ms)

/**
 * What leaving the Sync view with an audio nudge leaves behind, shown over any page: the Keep-it toast for 10 s, and the keep-as-calibration sheet.
 * [sheet] is the nudge being kept, in ms.
 */
object SyncNudge {
    var offered by mutableStateOf<Double?>(null)
        private set
    var offeredAt by mutableLongStateOf(0L)
        private set
    var sheet by mutableStateOf<Double?>(null)

    fun offer(ms: Double) { offered = ms; offeredAt = SystemClock.uptimeMillis() }
    fun dismiss() { offered = null }
}

@Composable
fun SyncNudgeOverlays(state: BenchState, actions: BenchActions) {
    val offered = SyncNudge.offered
    if (offered != null) {
        LaunchedEffect(SyncNudge.offeredAt) { delay(10_000); SyncNudge.dismiss() }
        Box(Modifier.fillMaxSize(), Alignment.BottomCenter) {
            Toast("Audio nudge of ${signedMs(offered)} removed. Timing is back to your calibration.", "Keep it", { SyncNudge.dismiss(); SyncNudge.sheet = offered })
        }
    }
    SyncNudge.sheet?.let { KeepSheet(it, state, actions) }
}

@Composable
private fun KeepSheet(nudge: Double, state: BenchState, actions: BenchActions) {
    val out = actions.output()
    val cal = actions.savedCalibration(out) ?: actions.tableCalibration(out)
    val from = state.session?.lagInUseMs ?: cal.lagMs
    val to = from + nudge
    val whose = when (out.icon) {
        OutputIcon.SPEAKER -> "Your phone speaker's lag"
        OutputIcon.BT_SPEAKER -> "Your speaker's lag"
        OutputIcon.OTHER -> "This output's lag"
        else -> "Your headphones' lag"
    }
    Sheet({ SyncNudge.sheet = null }) {
        BasicText("Keep ${signedMs(nudge)} as calibration?", style = Tokens.Text.screenTitle.style().copy(fontSize = 19.sp))
        BasicText(
            buildAnnotatedString {
                append("$whose goes from ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(wholeMs(from)) }
                append(" to ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(wholeMs(to)) }
                append(", marked \"adjusted by ear\". Only do this if the other phone was calibrated with a test, not by ear too.")
            },
            style = Tokens.Text.body.style(Ink.textSecondary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1.6f)) {
                PillButton("Keep ${wholeMs(to)}", true) {
                    actions.saveCalibration(out, Calibration(to, cal.plusMinusMs, CalSource.BY_EAR, System.currentTimeMillis()))
                    // The run keeps playing where it was nudged; the saved value takes over at the next start.
                    state.session?.let { if (it.diagnosticOffsetMs != 0.0) it.keepDiagnostic() else it.keepOffsetMs(nudge) }
                    SyncNudge.sheet = null
                }
            }
            Box(Modifier.weight(1f)) { PillButton("Not now", false) { SyncNudge.sheet = null } }
        }
    }
}

/** The phone's own account of its clock, so a gap between phones can be weighed against what each claims. */
@Composable
internal fun ClockLine(s: Session, boot: Long) {
    val fit = FlowTime.fit()
    val gold = when (s.gps.gold) { true -> " Gold"; false -> " Silver"; null -> "" }
    BasicText(
        if (fit == null) "Clock: none yet" else "Clock: ${s.sourceName(boot)}$gold · ±${org.beachsync.proto.fmtDuration(fit.errAt(boot))} · ${s.gps.used} satellites in use",
        style = Tokens.Text.small.style(Ink.accent),
    )
}

@Composable
internal fun Pill2(label: String, on: Boolean, onClick: () -> Unit) {
    BasicText(label, Modifier.clickable(role = Role.Button, onClick = onClick).border(1.dp, if (on) Ink.accent else Ink.border, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp), Tokens.Text.bodyStrong.style(if (on) Ink.accent else Ink.textSecondary))
}

/** Buttons in steps of 10 and 1 ms. [plusWord] and [minusWord] say what a positive and a negative value do. */
@Composable
internal fun Nudge(title: String, valueMs: Double, plusWord: String, minusWord: String, set: (Double) -> Unit) {
    Card {
        BasicText(title, style = Tokens.Text.cardLabel.style())
        BasicText(if (valueMs == 0.0) "0 µs" else (if (valueMs > 0) "+" else "") + org.beachsync.proto.fmtMillis(valueMs, 0) + " (" + (if (valueMs > 0) plusWord else minusWord) + ")", style = Tokens.Text.value.style())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, step) in listOf("−10" to -10.0, "−1" to -1.0, "+1" to 1.0, "+10" to 10.0)) {
                BasicText(label, Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { set(valueMs + step) }.padding(vertical = 12.dp), Tokens.Text.bodyStrong.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            }
            BasicText("0", Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { set(0.0) }.padding(vertical = 12.dp), Tokens.Text.bodyStrong.style(Ink.textSecondary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}
