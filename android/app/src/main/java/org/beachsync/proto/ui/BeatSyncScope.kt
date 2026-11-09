package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import org.beachsync.proto.BeatGrid
import org.beachsync.proto.BeatTrack
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Session
import java.util.Locale

/**
 * The same circle as the sync view, but following the music's beat grid: it sweeps once per beat (or once per bar) and the whole screen flashes white on the beat,
 * or on the bar's first beat. The grid comes from the music itself, at the position being heard, in windows aligned to the stream, so two phones playing the same
 * stream should draw the same grid. With the audio nudge you can see how far a phone's sound is from its own flash. Needs music playing (the phone can be muted).
 * An engineering prototype.
 */
@Composable
fun BeatSyncScope(state: BenchState, back: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    var frame by remember { mutableLongStateOf(Session.nowBoot()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = Session.nowBoot() } }
    var screenMs by remember { mutableDoubleStateOf(0.0) }
    var flashOn by remember { mutableStateOf(true) }
    var perBar by remember { mutableStateOf(false) }
    val s = state.session
    val stream = s?.stream
    val track = remember(stream) { stream?.let { BeatTrack(it) } }
    DisposableEffect(track) { track?.start(); onDispose { track?.stopNow() } }
    val m = stream?.manifest
    val fit = FlowTime.fit()
    val utc = if (s != null && fit != null && m != null) fit.utcAt(frame) - Math.round(screenMs * 1e6) else null
    val rate = 48000.0
    val pos = if (utc != null && m != null) (utc - m.startNs - m.dMs * 1_000_000L).toDouble() * rate / 1e9 else Double.NaN
    // The set's own grid, worked out after download, comes first; the live tracker is only a fallback until it's ready.
    val lib = state.library
    val setBeat = if (s != null && s.s.library && lib != null && utc != null && !pos.isNaN()) libraryBeat(lib, utc, pos) else null
    val gridMap = track?.grids
    val window = if (pos.isNaN()) 0L else BeatTrack.windowAt(pos.toLong())
    val known = gridMap != null && (0L..2L).all { gridMap.containsKey(window + it) }
    val grid = gridMap?.let { BeatGrid.resolve(window, it) }
    // Where we are in the grid.
    var beatFrac = 0.0; var beatInBar = 0; var sinceBeat = Double.POSITIVE_INFINITY
    if (setBeat != null) {
        beatFrac = setBeat.beatFrac; beatInBar = setBeat.beatInBar; sinceBeat = setBeat.sinceBeatSamples
    } else if (grid != null && !pos.isNaN()) {
        val x = (pos - grid.phaseSample) / grid.periodSamples
        val k = Math.floor(x)
        beatFrac = x - k
        beatInBar = Math.floorMod(k.toLong() - grid.barOffset, 4L).toInt()
        sinceBeat = beatFrac * grid.periodSamples
    }
    val haveBeat = setBeat != null || grid != null
    val sweep = if (!haveBeat) 0f else if (perBar) ((beatInBar + beatFrac) / 4.0).toFloat() else beatFrac.toFloat()
    val flashNow = flashOn && haveBeat && sinceBeat < 0.05 * rate && (!perBar || beatInBar == 0)
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Beat sync view", back)
            if (utc == null || stream == null || m == null || track == null) {
                Card {
                    BasicText("Needs music.", style = Tokens.Text.bodyStrong.style())
                    BasicText("Press Play on Now playing so the stream is running (the phone can be muted), then open this view.", style = Tokens.Text.body.style())
                }
            } else {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                    val d = minOf(size.width, size.height) * 0.9f
                    val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                    drawCircle(Color(0xFF3A3F47), d / 2f, center)
                    drawArc(Color.White, -90f, sweep * 360f, true, tl, Size(d, d))
                }
                BasicText(
                    when {
                        setBeat != null -> "%.1f beats per minute · bar %d · beat %d of 4 · worked out in advance".format(Locale.ROOT, setBeat.bpm, setBeat.bar + 1, beatInBar + 1)
                        grid != null -> "%.1f beats per minute · beat %d of 4 · confidence %.0f%%".format(Locale.ROOT, 60 * rate / grid.periodSamples, beatInBar + 1, grid.confidence * 100)
                        known -> "No clear beat in this stretch of music."
                        else -> "Listening for the beat… (needs about 16 s of music)"
                    } + (org.beachsync.proto.SetBeatAnalyser.current()?.working?.let { (title, pct) -> if (setBeat == null) "\nFinding the beats and bars in $title: $pct%" else "" } ?: ""),
                    style = Tokens.Text.small.style(Ink.textSecondary),
                )
                ClockLine(s, frame)
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("Flash on the beat", Modifier.weight(1f), Tokens.Text.body.style())
                        Pill2(if (flashOn) "ON" else "OFF", flashOn) { flashOn = !flashOn }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("One sweep per", Modifier.weight(1f), Tokens.Text.body.style())
                        Pill2(if (perBar) "BAR" else "BEAT", true) { perBar = !perBar }
                    }
                }
                Nudge("SCREEN (shifts only the picture; + is later)", screenMs, "later", "earlier") { screenMs = it }
                Nudge("AUDIO (changes what you hear; ends with the session)", s.diagnosticOffsetMs, "earlier", "later") { s.setDiagnosticOffsetMs(it) }
                BasicText("The beat grid is worked out from the music itself, so both phones should draw the same one. If the flash and what you hear are apart, the audio nudge shows by how much.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        // The flash covers the whole screen, over everything else.
        if (flashNow) Box(Modifier.fillMaxSize().background(Color.White))
    }
}

/** The beat at stream sample [pos], from the playing set's own grid, or null if that set's grid isn't worked out yet. */
internal fun libraryBeat(lib: org.beachsync.proto.LibraryStore, utc: Long, pos: Double): org.beachsync.proto.SetBeats.Pos? {
    val n = Math.floorDiv(Math.floor(pos).toLong(), 48000L)
    val (item, block) = org.beachsync.proto.segmentAt(lib.editions, n) ?: return null
    return lib.beats(item.id)?.at(block * 48000.0 + (pos - n * 48000.0))
}
