package org.beachsync.proto.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.beachsync.proto.FlowTime
import org.beachsync.proto.aheadSeconds
import org.beachsync.proto.editionAt
import org.beachsync.proto.playingAt
import org.beachsync.proto.shownAt
import org.beachsync.proto.silentForSeconds
import org.beachsync.proto.R
import org.beachsync.proto.Session
import org.beachsync.proto.Source
import org.beachsync.proto.fmtDuration
import org.beachsync.proto.fmtSigned
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val SECOND_MS = 1000L
private const val SECOND_NS = 1_000_000_000L
private const val LIMIT_NS = 5_000_000.0    // 5 ms, from the badge rules in design/ui-spec.md

/** A tick just after each flowtime second begins, so the digits change on time without updating more than once a second. */
@Composable
private fun rememberSecondTick(): State<Long> = produceState(Session.nowBoot()) {
    while (true) {
        val fit = FlowTime.fit()
        val leftNs = if (fit != null) SECOND_NS - Math.floorMod(fit.utcAt(Session.nowBoot()), SECOND_NS) else SECOND_NS
        delay(leftNs / 1_000_000L + 1L)
        withFrameNanos { }
        value = Session.nowBoot()
    }
}

/** Where an error falls on the design's log scale, from 1 µs (0) to 10 ms (1). */
private fun logScale(ns: Double) = if (ns.isNaN() || ns <= 0.0) 0f else ((log10(ns) - 3.0) / 4.0).coerceIn(0.0, 1.0).toFloat()

@Composable
fun NowPlaying(state: BenchState, actions: BenchActions, openLog: () -> Unit, openNudge: () -> Unit, openBench: () -> Unit, openSettings: () -> Unit, openSignals: () -> Unit, openCalibration: () -> Unit, openSatellites: () -> Unit, openStereo: () -> Unit, openArtist: () -> Unit, openSwarm: () -> Unit, openBuffer: () -> Unit) {
    val boot by rememberSecondTick()
    val s = state.session
    val listen = {
        if (s == null) { state.stream = true; actions.listen() } else actions.startOrStop()
    }
    val lockedByDownload = s == null && (state.library?.let { l -> playingAt(l.editions, l.dMs, System.currentTimeMillis() * 1_000_000L)?.let { !l.file(it.first.id).exists() } } ?: false)
    // The output lag's uncertainty is part of the sync error (hand-off 12): from this output's saved test, else the shared table.
    val out = actions.output()
    val cal = actions.savedCalibration(out) ?: actions.tableCalibration(out)
    val lagUncNs = cal.plusMinusMs * 1e6
    // What the library has at the ear: the track, how far the downloads reach, and whether the set due now is still arriving.
    val lib = state.library
    val utcNow = FlowTime.fit()?.utcAt(boot) ?: (System.currentTimeMillis() * 1_000_000L)
    val silentS = lib?.let { silentForSeconds(it.editions, it.dMs, utcNow) } ?: 0L   // nothing is on air yet: the next set's sound is still on its way
    val playing = lib?.let { shownAt(it.editions, it.dMs, utcNow) }
    val dueMissing = playing != null && !lib.file(playing.first.id).exists()
    val ahead = lib?.let { l -> editionAt(l.editions, utcNow)?.let { ed -> playing?.let { aheadSeconds(ed, it.first, it.second) { item -> l.file(item.id).exists() } } } }
    val everythingHere = lib != null && playing != null && !dueMissing && ahead == null
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Masthead(s, openBench, openSwarm, openSettings, openSignals, openCalibration)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The track and artist come from the library file the timeline has at the ear; the station name stands in before that.
            ArtistThumb(lib, playing?.first, openArtist)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                BasicText(playing?.first?.title?.ifEmpty { null } ?: s?.stream?.manifest?.source?.ifEmpty { null } ?: "Beach Sync Radio", style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (silentS > 0) BasicText("Starts in ${silentS / 60}:${"%02d".format(silentS % 60)}", style = Tokens.Text.small.style(Ink.accent).copy(fontSize = 13.sp), maxLines = 1)
                else playing?.first?.artist?.ifEmpty { null }?.let { BasicText(it, style = Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            StereoChip(state.channel, actions.output().kind == org.beachsync.proto.OutputKind.HEADPHONES, openStereo)
            KeptBadge()
        }
        LibraryLine(state.library)
        Dial(s, s?.gps ?: state.idleGps, boot, state.localTime, lagUncNs, openSatellites)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            PlayStopButton(s != null, lockedByDownload, listen)
            SyncBadge(s, boot, lagUncNs, if (s != null && dueMissing) (lib?.partPct ?: -1).coerceAtLeast(0) else if (lockedByDownload) (lib?.partPct ?: -1).coerceAtLeast(0) else null, Modifier.weight(1f))
        }
        EventRow(state.eventLog, SECOND_MS, openLog)
        SyncCard(s, boot, lagUncNs, out.kind.label.lowercase(), out.external, openSignals)
        NudgeRow(state, openNudge, SECOND_MS)
        Row(Modifier.fillMaxWidth().height(IntrinsicTile), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val gpsNow = s?.gps ?: state.idleGps
            val gold = when (gpsNow?.gold) { true -> "Gold · "; false -> "Silver · "; null -> "" }
            Tile("SOURCE", FlowTime.chosen?.name ?: "—", if (gpsNow != null && gpsNow.active) "$gold${gpsNow.used} sats" else "Satellites off", Modifier.weight(1f))
            // Named for the output in use: this lag also applies to a phone's own speaker.
            Tile(out.kind.label, org.beachsync.proto.fmtMillis(s?.s?.lagMs ?: state.lag.toDoubleOrNull() ?: 0.0), if (out.external) out.name else state.lagSource, Modifier.weight(1f))
            Tile("AHEAD", aheadText(lib != null && playing != null, everythingHere, ahead), if (everythingHere) "all downloaded" else "downloaded", Modifier.weight(1f).clickable(role = Role.Button, onClick = openBuffer))
        }
        Card { SatellitesRow(state.satellites, actions::setSatellites, "Position stays on this phone · off = Privacy mode") }
    }
}

private val IntrinsicTile = 84.dp

/** In the beta everything is kept, so this shows that and can't be turned off. It deliberately has no tap action. */
@Composable
private fun KeptBadge() {
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Row(
        Modifier.height(Tokens.Space.touchTarget.dp).border(Tokens.Stroke.card.dp, Ink.border, shape).padding(horizontal = 14.dp)
            .semantics { contentDescription = "Kept. Every set stays on this phone, and this can't be turned off in the beta." },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.size(width = 10.dp, height = 14.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(size.width / 2f, size.height * 0.72f); lineTo(0f, size.height); close()
            }
            drawPath(path, Ink.text)
        }
        BasicText("Kept", style = Tokens.Text.body.style(Ink.text))
    }
}

/** The whole library's download state: a thin bar and "38 of 52 sets", or "52 sets · all here" when it is complete. */
@Composable
private fun LibraryLine(lib: org.beachsync.proto.LibraryStore?) {
    val have = lib?.haveSets ?: 0
    val total = lib?.totalSets ?: 0
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("LIBRARY", style = Tokens.Text.cardLabel.style())
        Box(Modifier.weight(1f).height(3.dp).background(Ink.track, CircleShape)) {
            if (total > 0) Box(Modifier.fillMaxWidth((have.toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(Ink.muted, CircleShape))
        }
        BasicText(
            when { total == 0 -> lib?.status ?: "Not downloaded yet"; have == total -> "$total sets · all here"; else -> "$have of $total sets" },
            style = Tokens.Text.valueSmall.style(Ink.textSecondary), maxLines = 1,
        )
    }
}

/** A 52 px round button: Stop (a white square, outlined) while playing, Play (a dark triangle on accent) when stopped. */
@Composable
private fun PlayStopButton(playing: Boolean, disabled: Boolean, onClick: () -> Unit) {
    // While the set due now is still downloading, Play is dimmed and does nothing.
    val mod = Modifier.size(52.dp).alpha(if (disabled) 0.45f else 1f).then(if (disabled) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)).semantics { contentDescription = if (playing) "Stop" else if (disabled) "Play, not yet: the set due now is downloading" else "Play" }
    Box(if (playing) mod.border(Tokens.Stroke.emphasis.dp, Ink.text, CircleShape) else if (disabled) mod.border(Tokens.Stroke.emphasis.dp, Ink.muted, CircleShape) else mod.background(Ink.accent, CircleShape), Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            if (playing) drawRoundRect(Ink.text, cornerRadius = CornerRadius(2.dp.toPx()))
            else drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(size.width * 0.12f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.12f, size.height); close() }, Ink.onAccent)
        }
    }
}

/** Holding the station name opens the test bench: the developer way in, with nothing on screen for listeners to trip over. */
@Composable
private fun Masthead(s: Session?, openBench: () -> Unit, openSwarm: () -> Unit, openSettings: () -> Unit, openSignals: () -> Unit, openCalibration: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).pointerInput(Unit) { detectTapGestures(onLongPress = { openBench() }, onTap = { openSwarm() }) }.semantics { contentDescription = "${DemoMesh.NYM}, ${DemoMesh.LISTENERS} listeners linked, demo data. Tap for the swarm; hold to open the test bench." },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // The design wants your nym and "N listeners linked" here, opening Swarm. Neither exists yet (see design/feedback.md), so only the name stays. D lives on the Test bench.
            BasicText(DemoMesh.NYM, style = Tokens.Text.eyebrow.style())
            BasicText("● ${DemoMesh.LISTENERS} listeners linked · demo", style = Tokens.Text.small.style(Ink.accent))
        }
        // Archive isn't built, so its button is dimmed and does nothing.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (icon in HeaderIcon.entries) IconBox(icon, when (icon) { HeaderIcon.SETTINGS -> openSettings; HeaderIcon.TIME -> openSignals; HeaderIcon.CALIBRATION -> openCalibration; else -> null })
        }
    }
}

private enum class HeaderIcon(val label: String) { TIME("Time signals"), CALIBRATION("Calibration"), ARCHIVE("Archive, not available yet"), SETTINGS("Settings") }

@Composable
private fun IconBox(icon: HeaderIcon, onClick: (() -> Unit)?) {
    Canvas(
        Modifier.size(Tokens.Space.touchTarget.dp).alpha(if (onClick != null) 1f else 0.45f).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.control.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(12.dp)
            .semantics { contentDescription = icon.label },
    ) {
        val w = size.width
        val line = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
        when (icon) {
            HeaderIcon.TIME -> {
                drawCircle(Ink.text, w * 0.45f, style = line)
                drawLine(Ink.text, center, Offset(w * 0.5f, w * 0.22f), line.width, StrokeCap.Round)
                drawLine(Ink.text, center, Offset(w * 0.7f, w * 0.6f), line.width, StrokeCap.Round)
            }
            HeaderIcon.CALIBRATION -> {
                drawArc(Ink.text, 180f, 180f, false, Offset(w * 0.08f, w * 0.1f), Size(w * 0.84f, w * 0.84f), style = line)
                drawRoundRect(Ink.text, Offset(w * 0.04f, w * 0.55f), Size(w * 0.2f, w * 0.36f), CornerRadius(w * 0.06f))
                drawRoundRect(Ink.text, Offset(w * 0.76f, w * 0.55f), Size(w * 0.2f, w * 0.36f), CornerRadius(w * 0.06f))
            }
            HeaderIcon.ARCHIVE -> {
                drawRoundRect(Ink.text, Offset(w * 0.04f, w * 0.12f), Size(w * 0.92f, w * 0.24f), CornerRadius(w * 0.04f), style = line)
                drawRoundRect(Ink.text, Offset(w * 0.12f, w * 0.36f), Size(w * 0.76f, w * 0.52f), CornerRadius(w * 0.04f), style = line)
                drawLine(Ink.text, Offset(w * 0.38f, w * 0.54f), Offset(w * 0.62f, w * 0.54f), line.width, StrokeCap.Round)
            }
            HeaderIcon.SETTINGS -> {
                for ((y, x) in listOf(0.3f to 0.66f, 0.7f to 0.34f)) {
                    drawLine(Ink.text, Offset(0f, w * y), Offset(w, w * y), line.width, StrokeCap.Round)
                    drawCircle(Ink.bg, w * 0.13f, Offset(w * x, w * y))
                    drawCircle(Ink.text, w * 0.13f, Offset(w * x, w * y), style = line)
                }
            }
        }
    }
}

/** The Brocast clock. The design's sub-second dial is left out, because this screen updates once a second. */
@Composable
private fun Dial(s: Session?, gps: org.beachsync.proto.GpsTime?, boot: Long, showLocal: Boolean, lagUncNs: Double, openSatellites: () -> Unit) {
    val fit = FlowTime.fit()
    val utc = if (fit != null) fit.utcAt(boot) else null
    val now = utc?.let { Instant.ofEpochSecond(0, it) }
    val err = s?.syncErrorNs(boot, lagUncNs) ?: fit?.errAt(boot)?.let { sqrt(it * it + lagUncNs * lagUncNs) } ?: Double.NaN
    val flowErr = if (fit != null) fmtDuration(fit.errAt(boot)) else "—"
    val resources = LocalContext.current.resources
    val top = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.space_grotesk); textAlign = Paint.Align.CENTER; letterSpacing = 0.18f } }
    val bottom = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = resources.getFont(R.font.jetbrains_mono); textAlign = Paint.Align.CENTER; letterSpacing = 0.08f } }
    val topPath = remember { android.graphics.Path() }
    val bottomPath = remember { android.graphics.Path() }
    Box(Modifier.fillMaxWidth().height(214.dp), Alignment.Center) {
        Canvas(Modifier.size(214.dp).semantics { contentDescription = "Flowtime error $flowErr" }) {
            val c = center
            fun at(angle: Float, r: Float) = Offset(c.x + r * sin(Math.toRadians(angle.toDouble())).toFloat(), c.y - r * cos(Math.toRadians(angle.toDouble())).toFloat())
            val ring = 76.dp.toPx()
            for (i in 0 until 60) {
                val a = i * 6f
                drawLine(if (i == 0) Ink.accent else Ink.faint, at(a, 80.dp.toPx()), at(a, if (i % 5 == 0) 87.dp.toPx() else 84.dp.toPx()), if (i == 0) 2.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(Ink.track, ring, c, style = Stroke(2.dp.toPx()))
            if (now != null) {
                val seconds = now.epochSecond % 60
                val box = Size(ring * 2, ring * 2)
                val corner = Offset(c.x - ring, c.y - ring)
                drawArc(Ink.accent, -90f, seconds * 6f, false, corner, box, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                val inner = 66.dp.toPx()
                val width = 1.5.dp.toPx() + logScale(err) * 10.dp.toPx()
                drawCircle(Ink.accent.copy(alpha = 0.22f), inner, c, style = Stroke(width))
                drawCircle(Ink.accent.copy(alpha = 0.55f), inner, c, style = Stroke(1.dp.toPx()))
            }
            drawIntoCanvas { canvas ->
                top.textSize = Tokens.Text.bezelTop.size.sp.toPx(); top.color = Ink.textSecondary.toArgb()
                bottom.textSize = Tokens.Text.bezelBottom.size.sp.toPx(); bottom.color = Ink.accent.toArgb()
                val rt = 92.dp.toPx()
                topPath.rewind(); topPath.addArc(c.x - rt, c.y - rt, c.x + rt, c.y + rt, -170f, 160f)
                canvas.nativeCanvas.drawTextOnPath("TEDDINGTON · GREENWICH", topPath, 0f, 0f, top.apply { textAlign = Paint.Align.CENTER })
                val rb = 101.dp.toPx()
                bottomPath.rewind(); bottomPath.addArc(c.x - rb, c.y - rb, c.x + rb, c.y + rb, 170f, -160f)
                canvas.nativeCanvas.drawTextOnPath("FLOWTIME ±$flowErr", bottomPath, 0f, 0f, bottom)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(now?.let { CLOCK_ZONE.format(it) } ?: " ", style = Tokens.Text.clockZone.style())
            BasicText(now?.let { CLOCK_DIGITS.format(it) } ?: "--:--:--", style = Tokens.Text.clock.style())
            SourceGlyph(gps, boot, openSatellites)
            val local = ZoneId.systemDefault()
            val different = showLocal && now != null && local.rules.getOffset(now) != UK.rules.getOffset(now)
            BasicText(if (different) shortZone(LOCAL_DIGITS.withZone(local).format(now)) + " · local" else " ", style = Tokens.Text.clockLocal.style().copy(fontSize = 10.sp), maxLines = 1)
        }
    }
}

private class Badge(val words: String, val mark: String, val tint: Color, val figure: String, val detail: String)

/** The states with data today. REJOINING, CLOCK CHECK FAILED and SILENT · OVER LIMIT need detection the app doesn't do yet. */
private fun badge(s: Session?, boot: Long, lagUncNs: Double, downloadPct: Int?): Badge {
    if (downloadPct != null) return Badge("DOWNLOADING $downloadPct%", "↓", Ink.textSecondary, "", "set due now")
    if (s == null) {
        val fit = FlowTime.fit()
        return Badge("STOPPED", "■", Ink.textSecondary, if (fit != null) "±" + fmtDuration(fit.errAt(boot)) else "", if (fit != null) "clock ready" else "getting time")
    }
    val err = s.syncErrorNs(boot, lagUncNs)
    val figure = if (err.isNaN()) "" else "±" + fmtDuration(err)
    val over = err.isNaN() || err > LIMIT_NS
    val grade = when (s.gps.gold) { true -> "GPS · Gold"; false -> "GPS · Silver"; null -> "GPS" }
    return when {
        FlowTime.fit() == null -> Badge("NOT IN SYNC", "✕", Ink.danger, "", "No time source")
        s.clockCheckFailed -> Badge("CLOCK CHECK FAILED", "✕", Ink.danger, figure, "GPS ≠ NTP")
        s.stream != null && s.stream.manifest == null -> Badge("NOT IN SYNC", "✕", Ink.danger, "", "No stream")
        !s.gps.active -> Badge("PRIVACY MODE", "⊘", Ink.textSecondary, figure, FlowTime.chosen?.name ?: "")
        FlowTime.chosen == Source.NTP -> Badge("INTERNET TIME", "▲", Ink.warn, figure, "NTP")
        s.sourceName(boot) != "GPS" -> Badge("HOLDING", "○", if (over) Ink.warn else Ink.accent, figure, "Holdover")
        else -> Badge("IN SYNC", "●", if (over) Ink.warn else Ink.accent, figure, grade)
    }
}

@Composable
private fun SyncBadge(s: Session?, boot: Long, lagUncNs: Double, downloadPct: Int?, modifier: Modifier = Modifier) {
    val b = badge(s, boot, lagUncNs, downloadPct)
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(b.tint.copy(alpha = 0.08f), shape).border(Tokens.Stroke.emphasis.dp, b.tint, shape).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText("${b.mark} ${b.words}", style = Tokens.Text.badge.style(if (b.tint == Ink.accent) Ink.text else b.tint), maxLines = 1)
            Spacer(Modifier.weight(1f))
            BasicText(b.figure, style = Tokens.Text.value.style(), maxLines = 1)
            Spacer(Modifier.width(10.dp))
            BasicText(b.detail, style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1)
        }
        val from = s?.tighteningFromNs() ?: Double.NaN
        val fit = FlowTime.fit()
        if (!from.isNaN() && fit != null) TighteningLine(from, fit.errAt(boot))
    }
}

/**
 * The Sync card (hand-off 14): what the sync error means for this listener. The window shows where this phone's sound most likely is
 * against the Brocast clock; the bar shows which part of the error is largest. Screenlag belongs with the light show, later.
 */
@Composable
private fun SyncCard(s: Session?, boot: Long, lagUncNs: Double, outputWord: String, outputExternal: Boolean, open: () -> Unit) {
    val flow = FlowTime.fit()?.errAt(boot) ?: Double.NaN
    val half = if (flow.isNaN()) Double.NaN else sqrt(flow * flow + lagUncNs * lagUncNs)
    val total = s?.syncErrorNs(boot, lagUncNs) ?: half
    val held = if (s != null) s.nudge.gapAt(boot).toDouble() else 0.0
    Card(Modifier.clickable(role = Role.Button, onClick = open)) {
        BasicText("SYNC", style = Tokens.Text.cardLabel.style())
        if (total.isNaN() || half.isNaN()) {
            BasicText("Getting time from the satellites or the internet.", style = Tokens.Text.body.style(Ink.textSecondary))
            return@Card
        }
        BasicText((if (s == null) "Playing now, you'd hear it within ±" else "You hear it within ±") + fmtDuration(total) + " of everyone's clock", style = Tokens.Text.bodyStrong.style(Ink.accent))
        SyncWindow(held, half)
        // One bar split by each part's share of the total, squared; then one sentence naming the largest part.
        val lagShare = (lagUncNs * lagUncNs / (flow * flow + lagUncNs * lagUncNs)).toFloat().coerceIn(0f, 1f)
        Row(Modifier.fillMaxWidth().height(6.dp).background(Ink.accent, CircleShape)) {
            if (lagShare > 0f) Box(Modifier.weight(lagShare).fillMaxHeight().background(Ink.warn))
            if (lagShare < 1f) Spacer(Modifier.weight(1f - lagShare))
        }
        val sentence = if (lagUncNs >= flow)
            "Mostly your $outputWord's delay estimate (±${fmtDuration(lagUncNs)}). Clock ±${fmtDuration(flow)}." + if (outputExternal) " An earcup test can tighten it." else ""
        else "Mostly the clock (±${fmtDuration(flow)}). Your $outputWord's delay estimate adds ±${fmtDuration(lagUncNs)}. More satellites can tighten it."
        BasicText(sentence, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/**
 * The window on a logarithmic scale (the user's decision, 4 Nov): from 1 s earlier to 1 s later, with marks at 1 ms, 10 ms and 100 ms each side.
 * It stays readable whether the error is microseconds or hundreds of milliseconds. The band is centred on the held gap.
 */
@Composable
private fun SyncWindow(heldNs: Double, halfNs: Double) {
    val centre = heldNs   // dragging (positive) is heard later
    // Signed log: 0 at the clock, ±1 at ±1 s, with 1 ms, 10 ms and 100 ms evenly between (the knee is 100 µs).
    fun pos(ns: Double): Float = (Math.signum(ns) * log10(1.0 + abs(ns) / 1e5) / 4.0).toFloat().coerceIn(-1f, 1f)
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "Sync window, logarithmic: most likely between ${fmtSigned(centre - halfNs)} and ${fmtSigned(centre + halfNs)} from the Brocast clock" }) {
        val w = size.width; val y = 14.dp.toPx()
        fun x(ns: Double) = w / 2f + pos(ns) * (w / 2f)
        drawLine(Ink.track, Offset(0f, y), Offset(w, y), 2.dp.toPx(), StrokeCap.Round)
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (limit in listOf(-5e6, 5e6)) drawLine(Ink.muted, Offset(x(limit), y - 8.dp.toPx()), Offset(x(limit), y + 8.dp.toPx()), 1.dp.toPx(), pathEffect = dash)
        for (m in listOf(1e6, 1e7, 1e8)) for (sign in listOf(-1.0, 1.0)) drawLine(Ink.faint, Offset(x(sign * m), y - 4.dp.toPx()), Offset(x(sign * m), y + 4.dp.toPx()), 1.dp.toPx())
        drawLine(Ink.text, Offset(w / 2f, y - 10.dp.toPx()), Offset(w / 2f, y + 10.dp.toPx()), 2.dp.toPx())
        val l = x(centre - halfNs); val r = x(centre + halfNs)
        drawRoundRect(Ink.accent.copy(alpha = 0.45f), Offset(l, y - 5.dp.toPx()), Size(maxOf(r - l, 3.dp.toPx()), 10.dp.toPx()), CornerRadius(5.dp.toPx()))
        drawCircle(Ink.accent, 3.5.dp.toPx(), Offset(x(centre), y))
        drawIntoCanvas { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.sp.toPx(); color = Ink.textSecondary.toArgb(); typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
            val ly = y + 26.dp.toPx()
            for ((m, label) in listOf(1e6 to "1 ms", 1e7 to "10 ms", 1e8 to "100 ms")) for (sign in listOf(-1.0, 1.0)) c.nativeCanvas.drawText(label, x(sign * m), ly, p)
            p.color = Ink.text.toArgb(); c.nativeCanvas.drawText("CLOCK", w / 2f, ly, p)
            p.color = Ink.textSecondary.toArgb(); p.textAlign = Paint.Align.LEFT; c.nativeCanvas.drawText("EARLIER", 0f, y + 40.dp.toPx(), p)
            p.textAlign = Paint.Align.RIGHT; c.nativeCanvas.drawText("LATER", w, y + 40.dp.toPx(), p)
        }
    }
}

@Composable
private fun Tile(label: String, value: String, detail: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = min(Tokens.Text.cardLabel.size, 9.5f).sp, letterSpacing = 0.8.sp), maxLines = 1)
        BasicText(value, style = Tokens.Text.value.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(detail, style = Tokens.Text.small.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


/** The time source as a picture: a mini constellation for GPS, an hourglass for holdover, a cell tower for internet time. Tapping it opens Satellites. */
@Composable
private fun SourceGlyph(gps: org.beachsync.proto.GpsTime?, boot: Long, open: () -> Unit) {
    val onGps = FlowTime.chosen == Source.GPS && gps != null && gps.active
    val kind = when {
        FlowTime.fit() == null -> "WAIT"   // the first second or two after the app opens, before internet time answers
        onGps && boot - gps!!.lastBoot <= 3_000_000_000L -> "GPS"
        onGps -> "HOLD"
        else -> "NTP"
    }
    val sats = gps?.sats.orEmpty()
    val words = when (kind) {
        "GPS" -> "GPS · ${sats.count { it.used }} of ${sats.size}"
        "HOLD" -> "HOLDING · ${((boot - gps!!.lastBoot) / 1_000_000_000L).coerceAtLeast(0)} s"
        "NTP" -> "NTP"
        else -> "NTP · getting time"
    }
    Column(Modifier.clickable(enabled = kind == "GPS" || kind == "HOLD", onClick = open).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 84.dp, height = 30.dp).semantics { contentDescription = words }) {
            val c = center
            val rx = size.width / 2f - 4.dp.toPx(); val ry = size.height / 2f - 3.dp.toPx()
            when (kind) {
                "GPS", "HOLD" -> {
                    drawOval(Ink.faint, Offset(c.x - rx, c.y - ry), Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
                    val dots = sats.map { sat ->
                        val d = (90f - sat.elevationDeg.coerceIn(0f, 90f)) / 90f
                        val a = Math.toRadians(sat.azimuthDeg.toDouble())
                        Offset(c.x + rx * d * sin(a).toFloat(), c.y - ry * d * cos(a).toFloat())
                    }
                    val lit = if (kind == "GPS") sats.indices.filter { sats[it].used } else emptyList()
                    for ((i, j) in mstEdges(lit.map { dots[it] })) drawLine(Ink.accent.copy(alpha = 0.35f), dots[lit[i]], dots[lit[j]], 1.dp.toPx())
                    for (i in dots.indices) drawCircle(if (i in lit) Ink.accent else Ink.muted.copy(alpha = if (kind == "HOLD") 0.4f else 1f), if (i in lit) 2.2.dp.toPx() else 1.6.dp.toPx(), dots[i])
                    if (kind == "HOLD") {
                        val h = 7.dp.toPx(); val w = 5.dp.toPx()
                        val top = androidx.compose.ui.graphics.Path().apply { moveTo(c.x - w, c.y - h); lineTo(c.x + w, c.y - h); lineTo(c.x, c.y); close() }
                        val bottom = androidx.compose.ui.graphics.Path().apply { moveTo(c.x - w, c.y + h); lineTo(c.x + w, c.y + h); lineTo(c.x, c.y); close() }
                        drawPath(top, Ink.accent); drawPath(bottom, Ink.accent)
                    }
                }
                else -> {
                    val tint = if (kind == "NTP") Ink.textSecondary else Ink.muted
                    val h = size.height - 4.dp.toPx()
                    drawLine(tint, Offset(c.x, c.y - h / 2), Offset(c.x - 5.dp.toPx(), c.y + h / 2), 1.5.dp.toPx())
                    drawLine(tint, Offset(c.x, c.y - h / 2), Offset(c.x + 5.dp.toPx(), c.y + h / 2), 1.5.dp.toPx())
                    if (kind == "NTP") for (k in 1..2) {
                        val r = k * 5.dp.toPx()
                        drawArc(tint, -50f, 100f, false, Offset(c.x - r, c.y - h / 2 - r + 3.dp.toPx()), Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
                        drawArc(tint, 130f, 100f, false, Offset(c.x - r, c.y - h / 2 - r + 3.dp.toPx()), Size(r * 2, r * 2), style = Stroke(1.dp.toPx()))
                    } else if (kind == "NONE") {
                        drawLine(Ink.danger, Offset(c.x + 9.dp.toPx(), c.y - 5.dp.toPx()), Offset(c.x + 17.dp.toPx(), c.y + 3.dp.toPx()), 1.5.dp.toPx())
                        drawLine(Ink.danger, Offset(c.x + 17.dp.toPx(), c.y - 5.dp.toPx()), Offset(c.x + 9.dp.toPx(), c.y + 3.dp.toPx()), 1.5.dp.toPx())
                    }
                }
            }
        }
        BasicText(words, style = Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 10.sp), maxLines = 1)
    }
}

/** AHEAD: hours and minutes already on the phone beyond the playhead, or "All" when the whole library is here. */
private fun aheadText(known: Boolean, all: Boolean, seconds: Long?): String = when {
    !known -> "—"
    all -> "All"
    seconds == null -> "—"
    seconds >= 3600 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
    else -> "${seconds / 60} min"
}

/** `L LEFT` or `R RIGHT` beside Kept, only while this phone plays one side; it opens Stereo pair. */
@Composable
private fun StereoChip(channel: Int, headphones: Boolean, open: () -> Unit) {
    if (channel == org.beachsync.proto.Renderer.BOTH || headphones) return
    val word = if (channel == org.beachsync.proto.Renderer.LEFT) "L LEFT" else "R RIGHT"
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    BasicText(word, Modifier.padding(end = 8.dp).height(Tokens.Space.touchTarget.dp).border(Tokens.Stroke.card.dp, Ink.accent, shape).clickable(role = Role.Button, onClick = open).padding(horizontal = 12.dp).wrapContentHeight(Alignment.CenterVertically), Tokens.Text.bodyStrong.style(Ink.accent))
}
