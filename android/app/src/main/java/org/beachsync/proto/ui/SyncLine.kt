package org.beachsync.proto.ui

import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Renderer
import org.beachsync.proto.monoToBootNs
import kotlin.math.abs

private const val MM_PER_MS = 0.2f
private const val SWEEP_MS = 500f
private const val CARD_MM = 85.6f

/**
 * Sync view, line mode (hand-off 39): on each tick a line leaves the start mark and falls at 0.2 mm per ms for 100 mm, then waits. Phones
 * lined up by their start marks show their time gap as a height gap; the one behind has the higher line. The speed is physical, from the
 * screen's vertical density (or the card check's), so phones of any size agree. Drawn for this frame's time plus this phone's screen lag;
 * only the picture moves, never the sound.
 */
@Composable
fun SyncLineScreen(state: BenchState, back: () -> Unit) {
    val view = LocalView.current
    val ctx = LocalContext.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(Unit) { ScreenPrefs.load(ctx) }
    val reportedDpi = ctx.resources.displayMetrics.ydpi
    val dpi = if (ScreenPrefs.cardDpi > 0f) ScreenPrefs.cardDpi else reportedDpi
    val pxPerMm = dpi / 25.4f
    var beats by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var cardCheck by remember { mutableStateOf(ScreenPrefs.cardDpi == 0f) }
    var lag by remember { mutableStateOf(ScreenPrefs.screenLagMs) }
    // Frame time from the display's vsync (monotonic), moved onto the boot clock once.
    val monoOff = remember { monoToBootNs() }
    var frameBoot by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameBoot = it + monoOff } }
    val utc = FlowTime.fit()?.utcAt(frameBoot)?.plus(Math.round(lag * 1e6))
    val beat = rememberBeat(state, state.session, utc, beats)
    // Milliseconds since the last tick: a flowtime second, or the music's beat when there is one.
    val sinceMs = when {
        utc == null -> null
        beats && beat != null -> (beat.frac * 60_000.0 / beat.bpm).toFloat()
        else -> (Math.floorMod(utc, 1_000_000_000L) / 1e6).toFloat()
    }
    val trail = remember { FloatArray(4) { -1f } }   // the last 3 frames' line heights, then the latest; plain memory, not state
    val label = when (state.channel) { Renderer.LEFT -> "L"; Renderer.RIGHT -> "R"; else -> "A" }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onPress = { if (withTimeoutOrNull(2000) { tryAwaitRelease() } == null) back() },
                onTap = { if (!cardCheck) controls = !controls },
            )
        }.semantics { contentDescription = "Sync line. Hold 2 seconds to leave; tap for controls." },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val top = 96.dp.toPx()
            val travel = 100f * pxPerMm
            val left = 34.dp.toPx(); val right = size.width - 34.dp.toPx()
            // Rulers on both edges: every 10 ms (2 mm), longer at 50, labelled every 100.
            for (ms in 0..500 step 10) {
                val y = top + ms * MM_PER_MS * pxPerMm
                val len = (if (ms % 100 == 0) 16 else if (ms % 50 == 0) 11 else 6).dp.toPx()
                drawLine(Ink.textSecondary, Offset(0f, y), Offset(len, y), 1.dp.toPx())
                drawLine(Ink.textSecondary, Offset(size.width - len, y), Offset(size.width, y), 1.dp.toPx())
                if (ms % 100 == 0) lineText("$ms", 18.dp.toPx(), y + 4.dp.toPx(), 10f, Ink.textSecondary, Paint.Align.LEFT)
            }
            drawLine(Ink.accent, Offset(left, top), Offset(right, top), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            lineText("START · align here", size.width / 2, top - 6.dp.toPx(), 10f, Ink.accent)
            drawLine(Ink.textSecondary, Offset(left, top + travel), Offset(right, top + travel), 1.dp.toPx())
            lineText("500 ms · 100 mm · waits here for the next ${if (beats) "beat" else "second"}", size.width / 2, top + travel + 16.dp.toPx(), 10f, Ink.textSecondary)
            if (sinceMs != null) {
                val ms = sinceMs.coerceAtMost(SWEEP_MS)
                val y = top + ms * MM_PER_MS * pxPerMm
                // A faint trail of the last 3 frames, so a frame's step doesn't read as a gap between phones.
                if (y != trail[3]) { trail[2] = trail[1]; trail[1] = trail[0]; trail[0] = trail[3]; trail[3] = y }
                for (i in 0..2) { val ty = trail[i]; if (ty >= top && ty < y) drawLine(Color.White.copy(alpha = floatArrayOf(0.25f, 0.12f, 0.06f)[i]), Offset(left, ty), Offset(right, ty), 4.dp.toPx()) }
                drawLine(Color.White, Offset(left, y), Offset(right, y), 4.dp.toPx())
                lineText("+${ms.toInt()} ms", right, y - 8.dp.toPx(), 11f, Color.White, Paint.Align.RIGHT)
            } else lineText("Waiting for the clock", size.width / 2, top + travel / 2, 13f, Ink.textSecondary)
        }
        Column(Modifier.safeDrawingPadding().padding(horizontal = 40.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val tick = if (beats) beat?.let { "BEAT ${it.number} · ${it.bpm.toInt()} BPM" } ?: "BEAT · none yet, so each second" else "EACH SECOND"
            BasicText("$label · $tick", style = Tokens.Text.cardLabel.style(Color.White))
            BasicText("0.2 mm per ms · 10 ms = 2 mm", style = Tokens.Text.small.style(Ink.textSecondary))
            BasicText("screen lag ${signed(lag)}", style = Tokens.Text.small.style(if (lag != 0.0) Ink.warn else Ink.textSecondary))
        }
        if (!controls && !cardCheck) BasicText("Hold 2 s to leave · tap for controls", Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(16.dp), Tokens.Text.small.style(Ink.textSecondary))
        if (controls) Controls(beats, { beats = it }, lag, { lag = it }, { ScreenPrefs.setScreenLag(ctx, lag); controls = false }, { lag = 0.0; ScreenPrefs.setScreenLag(ctx, 0.0) }, { controls = false; cardCheck = true }, Modifier.align(Alignment.BottomCenter))
        if (cardCheck) CardCheck(reportedDpi, { ScreenPrefs.setCardDpi(ctx, it); cardCheck = false }) { cardCheck = false }
    }
}

private fun signed(ms: Double) = if (ms == 0.0) "0 ms" else (if (ms > 0) "+" else "−") + "%.0f ms".format(abs(ms))

private fun DrawScope.lineText(text: String, x: Float, y: Float, sp: Float, color: Color, align: Paint.Align = Paint.Align.CENTER) {
    val paint = Paint().apply { this.color = color.toArgb(); textSize = sp * density; textAlign = align; isAntiAlias = true; typeface = android.graphics.Typeface.MONOSPACE }
    drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, paint) }
}

@Composable
private fun Controls(beats: Boolean, setBeats: (Boolean) -> Unit, lag: Double, setLag: (Double) -> Unit, save: () -> Unit, reset: () -> Unit, openCard: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp).background(Ink.surface, RoundedCornerShape(Tokens.Radius.card.dp))
            .pointerInput(Unit) { detectTapGestures { } }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("TICK", style = Tokens.Text.cardLabel.style())
        Choice(listOf("Each beat", "Each second"), if (beats) 0 else 1, { setBeats(it == 0) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("This phone's screen lag", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
            BasicText(signed(lag), style = Tokens.Text.valueSmall.style(if (lag != 0.0) Ink.warn else Ink.text))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in listOf(-10.0, -1.0, 1.0, 10.0)) PillButton(if (d > 0) "+${d.toInt()}" else "−${(-d).toInt()}", false) { setLag(lag + d) }
        }
        BasicText("Moves only this phone's line, never the sound. Saved per phone and used by the dial too.", style = Tokens.Text.small.style(Ink.textSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save ${signed(lag)} for this phone", true, save)
            PillButton("Reset", false, reset)
        }
        BasicText("Card check: measure this screen's density", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = openCard).padding(vertical = 12.dp), Tokens.Text.body.style(Ink.accent))
    }
}

/** Lay an 85.6 mm bank or ID card along the screen from the start mark and drag the end mark to its far edge: that gives the real density. */
@Composable
private fun CardCheck(reportedDpi: Float, use: (Float) -> Unit, skip: () -> Unit) {
    var endPx by remember { mutableFloatStateOf(-1f) }
    val startPx = with(androidx.compose.ui.platform.LocalDensity.current) { 150.dp.toPx() }
    var moved by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); endPx = (endPx + drag.y).coerceAtLeast(startPx + 50f); moved++ } }) {
            if (endPx < 0f) endPx = startPx + CARD_MM * reportedDpi / 25.4f
            drawLine(Ink.accent, Offset(0f, startPx), Offset(size.width, startPx), 2.dp.toPx())
            drawLine(Color.White, Offset(0f, endPx), Offset(size.width, endPx), 3.dp.toPx())
            lineText("card's near edge here", size.width / 2, startPx - 8.dp.toPx(), 11f, Ink.accent)
            lineText("drag to the card's far edge", size.width / 2, endPx + 20.dp.toPx(), 11f, Color.White)
        }
        val measured = (endPx - startPx) / CARD_MM * 25.4f
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("CARD CHECK", style = Tokens.Text.cardLabel.style(Color.White))
            BasicText("Lay a bank or ID card (85.6 mm) along the screen from the line, then drag the white line to its far edge.", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (endPx > 0f) BasicText("Reported ${reportedDpi.toInt()} dpi · measured ${measured.toInt()} dpi · ${"%+.1f".format((measured / reportedDpi - 1) * 100)}%", style = Tokens.Text.body.style(Color.White))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Use ${measured.toInt()} dpi", true) { use(measured) }
                PillButton("Use reported", false) { use(reportedDpi) }
                PillButton("Skip", false, skip)
            }
        }
    }
}
