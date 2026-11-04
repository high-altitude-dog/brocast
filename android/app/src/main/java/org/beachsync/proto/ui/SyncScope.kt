package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Session
import java.util.Locale

/**
 * The sync view: a circle that fills clockwise from grey to white once per flowtime second, with the whole screen flashing white as the sweep passes 0 degrees.
 * Put two phones side by side and the one that flashes first is ahead. The flash is one per second, below the usual photosensitivity threshold, but it
 * can be switched off. Two temporary diagnostic nudges: the audio (changes what is heard, and ends with the session) and the screen (shifts only the picture,
 * to allow for a display's own delay). An engineering prototype; the designed version is for Claude Desktop.
 */
@Composable
fun SyncScope(state: BenchState, back: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var frame by remember { mutableLongStateOf(Session.nowBoot()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = Session.nowBoot() } }
    var screenMs by remember { mutableDoubleStateOf(0.0) }
    var flashOn by remember { androidx.compose.runtime.mutableStateOf(true) }
    val s = state.session
    val fit = FlowTime.fit()
    val utc = if (s != null && fit != null) fit.utcAt(frame) - Math.round(screenMs * 1e6) else null
    val refresh = view.display?.refreshRate ?: 60f
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Sync view", back)
            if (utc == null) {
                Card {
                    BasicText("No clock yet.", style = Tokens.Text.bodyStrong.style())
                    BasicText("Press Play on Now playing, wait for the badge to say IN SYNC, then open this view on each phone.", style = Tokens.Text.body.style())
                }
            } else {
                val ofSecond = Math.floorMod(utc, 1_000_000_000L)
                val sweep = ofSecond / 1e9f
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val d = minOf(size.width, size.height) * 0.9f
                    val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                    drawCircle(Color(0xFF3A3F47), d / 2f, center)
                    drawArc(Color.White, -90f, sweep * 360f, true, tl, Size(d, d))
                }
                ClockLine(s!!, frame)
                BasicText("%03d.%02d ms into the second · screen refresh %.1f ms".format(Locale.ROOT, ofSecond / 1_000_000L, ofSecond / 10_000L % 100, 1000f / refresh), style = Tokens.Text.small.style(Ink.textSecondary))
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("Flash at the top of each second", Modifier.weight(1f), Tokens.Text.body.style())
                        Pill2(if (flashOn) "ON" else "OFF", flashOn) { flashOn = !flashOn }
                    }
                }
                Nudge("SCREEN (shifts only the picture; + is later)", screenMs, "later", "earlier") { screenMs = it }
                Nudge("AUDIO (changes what you hear; ends with the session)", s!!.diagnosticOffsetMs, "earlier", "later") { s.setDiagnosticOffsetMs(it) }
                BasicText("Lay the phones side by side. The one that flashes first is ahead. Use the audio nudge to line up what you hear, then read the offset needed.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        // The flash: the whole screen white for the first 50 ms of each flowtime second.
        if (flashOn && utc != null && Math.floorMod(utc, 1_000_000_000L) < 50_000_000L) Box(Modifier.fillMaxSize().background(Color.White))
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
        BasicText(if (valueMs == 0.0) "0 ms" else "%+.0f ms (%s)".format(Locale.ROOT, valueMs, if (valueMs > 0) plusWord else minusWord), style = Tokens.Text.value.style())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, step) in listOf("−10" to -10.0, "−1" to -1.0, "+1" to 1.0, "+10" to 10.0)) {
                BasicText(label, Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { set(valueMs + step) }.padding(vertical = 12.dp), Tokens.Text.bodyStrong.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
            }
            BasicText("0", Modifier.weight(1f).heightIn(min = 44.dp).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).clickable(role = Role.Button) { set(0.0) }.padding(vertical = 12.dp), Tokens.Text.bodyStrong.style(Ink.textSecondary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}
