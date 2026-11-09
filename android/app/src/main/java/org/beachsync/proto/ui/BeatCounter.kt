package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.SetBeatAnalyser
import org.beachsync.proto.Session
import org.beachsync.proto.segmentAt
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hand-off 25: bar, beat and tempo under the clock, from the set's own grid ([org.beachsync.proto.SetBeats]). The stream sample at flowtime is, by
 * definition, the one reaching the listener's ears (the output lag is already allowed for), so every phone lights beat 1 together.
 * It redraws every display frame, on the UI thread, reading only what the timing code already publishes. Shown only while the library is playing.
 */
@Composable
fun BeatCounter(state: BenchState, open: () -> Unit) {
    val s = state.session ?: return
    val lib = state.library ?: return
    if (!s.s.library) return
    var frame by remember { mutableLongStateOf(Session.nowBoot()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = Session.nowBoot() } }
    val m = s.stream?.manifest ?: return
    val fit = FlowTime.fit() ?: return
    val utc = fit.utcAt(frame)
    val pos = (utc - m.startNs - m.dMs * 1_000_000L).toDouble() * 48000.0 / 1e9
    val n = Math.floorDiv(Math.floor(pos).toLong(), 48000L)
    val (item, block) = segmentAt(lib.editions, n) ?: return
    val grid = lib.beats(item.id)
    val p = grid?.at(block * 48000.0 + (pos - n * 48000.0))
    // A new tempo shows in accent for one bar.
    val seen = remember { object { var bpm = 0; var accentBar = Long.MIN_VALUE } }
    val bpm = p?.bpm?.roundToInt() ?: 0
    if (p != null) {
        if (seen.bpm != 0 && abs(bpm - seen.bpm) >= 2) seen.accentBar = p.bar
        seen.bpm = bpm
    }
    val flash = ScreenPrefs.flashBar && p != null && p.beatInBar == 0 && p.sinceBeatSamples < 0.12 * 48000   // a setting, off by default (hand-offs 27, 29)
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    val working = SetBeatAnalyser.current()?.working?.takeIf { it.first == item.title }?.second ?: 0
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(Ink.surface, shape)
            .border(if (flash) 2.dp else Tokens.Stroke.card.dp, if (flash) Ink.accent else Ink.border, shape)
            .clickable(role = Role.Button, onClick = open).padding(horizontal = 14.dp)
            .semantics { contentDescription = if (p != null) "Bar ${p.bar + 1}, beat ${p.beatInBar + 1}, $bpm beats per minute. Opens Buffer." else if (grid == null) "Working out the beats, $working percent" else "No steady beat" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(64.dp)) {
            BasicText(if (grid == null) "BEATS" else "BAR", style = Tokens.Text.cardLabel.style().copy(fontSize = 10.sp))
            BasicText(if (grid == null) "$working%" else p?.let { "${it.bar + 1}" } ?: "—", style = Tokens.Text.value.style().copy(fontSize = 18.sp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0..3) BeatCell(i, p, Modifier.weight(if (i == 0) 1.35f else 1f))
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
            if (p == null) BasicText(if (grid == null) "working\nthem out" else "no steady\nbeat", style = Tokens.Text.small.style(Ink.textSecondary).copy(textAlign = TextAlign.End, fontSize = 11.sp))
            else {
                BasicText("BPM", style = Tokens.Text.cardLabel.style().copy(fontSize = 10.sp))
                BasicText("$bpm", style = Tokens.Text.value.style(if (p.bar == seen.accentBar) Ink.accent else Ink.text).copy(fontSize = 18.sp))
            }
        }
    }
}

@Composable
private fun BeatCell(i: Int, p: org.beachsync.proto.SetBeats.Pos?, modifier: Modifier) {
    val lit = p != null && p.beatInBar == i
    val played = p != null && i < p.beatInBar
    val r = 7.dp
    Box(
        modifier.height(34.dp).drawBehind {
            val cr = CornerRadius(r.toPx())
            when {
                p == null -> drawRoundRect(Ink.faint, cornerRadius = cr, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                lit -> {
                    drawRoundRect(Ink.accent, cornerRadius = cr)
                    // The beat's phase: how far to the next beat.
                    val y = size.height - 6.dp.toPx(); val x0 = 6.dp.toPx()
                    drawLine(Ink.bg, Offset(x0, y), Offset(x0 + (size.width - 2 * x0) * p.beatFrac.toFloat(), y), 2.dp.toPx(), StrokeCap.Round)
                }
                played -> { drawRoundRect(Ink.accent.copy(alpha = 0.22f), cornerRadius = cr); drawRoundRect(Ink.accent.copy(alpha = 0.45f), cornerRadius = cr, style = Stroke(1.dp.toPx())) }
                else -> drawRoundRect(if (i == 0) Ink.accent else Ink.grid, cornerRadius = cr, style = Stroke(1.dp.toPx()))
            }
        },
        Alignment.Center,
    ) { BasicText("${i + 1}", style = Tokens.Text.valueSmall.style(if (lit) Ink.bg else if (p == null) Ink.muted else Ink.textSecondary).copy(fontSize = 13.sp)) }
}
