package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.beachsync.proto.Lane
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.News
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** News is cyan everywhere (the same as the running-order news card). */
private val newsInk get() = Lane.NET.color()

/** Settings › Listening row: `On · BBC World Service · music to 25%`. */
internal fun newsSummary(lib: LibraryStore?): String {
    if (!News.on) return "Off · the button stops the music"
    val st = News.stationNews(lib)
    if (News.source == News.Source.STATION && st?.choice == "none") return "This station has no news · pick one below"
    val name = News.feed(lib)?.first ?: "No feed yet"
    return "On · $name · music to ${News.underPct}%"
}

private fun bulletinLabel(publishedMs: Long): String {
    if (publishedMs <= 0) return "latest bulletin"
    val hm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(publishedMs))
    val ageH = (System.currentTimeMillis() - publishedMs) / 3_600_000L
    return if (ageH >= 2) "$hm · $ageH h old" else if (ageH >= 1) "$hm · 1 h old" else "$hm bulletin"
}

private fun mmss(ms: Int) = "${ms / 60000}:${"%02d".format(ms / 1000 % 60)}"

/** The card under the track row, above the clock: Getting, Playing, Ending. Shown only while news is on. */
@Composable
internal fun NewsCard() {
    rememberTick(100).value
    val phase = News.phase
    if (phase == News.Phase.IDLE) return
    val cyan = newsInk
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(cyan.copy(alpha = 0.10f), Color.Transparent)), shape).border(1.dp, cyan.copy(alpha = 0.6f), shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(cyan, CircleShape))
            Spacer(Modifier.width(8.dp))
            BasicText("NEWS · ${News.sourceName.uppercase()}${if (News.preview) " · PREVIEW" else ""}", Modifier.weight(1f), Tokens.Text.cardLabel.style(cyan), maxLines = 1)
            BasicText(bulletinLabel(News.publishedMs), style = Tokens.Text.small.style(Ink.textSecondary))
        }
        when (phase) {
            News.Phase.GETTING -> {
                BasicText("Getting the latest news…", style = Tokens.Text.bodyStrong.style())
                val mb = { b: Long -> "%.1f".format(b / 1e6) }
                val of = if (News.totalBytes > 0) "${mb(News.gotBytes)} of ${mb(News.totalBytes)} MB" else if (News.gotBytes > 0) "${mb(News.gotBytes)} MB" else "Asking the news service"
                BasicText("$of · music stays up until it starts", style = Tokens.Text.small.style(Ink.muted))
            }
            else -> {
                val dur = News.durationMs.coerceAtLeast(1)
                val pos = if (phase == News.Phase.ENDING) dur else News.positionMs.coerceAtMost(dur)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wave(pos.toFloat() / dur, cyan, Modifier.weight(1f).height(28.dp))
                    Spacer(Modifier.width(12.dp))
                    BasicText("${mmss(pos)} / ${mmss(dur)}", style = Tokens.Text.valueSmall.style())
                }
                if (phase == News.Phase.ENDING) BasicText("Music coming back up · ${(News.endingLeftMs + 999) / 1000} s", style = Tokens.Text.small.style(Ink.textSecondary))
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("Press your headphone button to stop · music at ${News.underPct}%", Modifier.weight(1f), Tokens.Text.small.style(Ink.textSecondary))
                    Spacer(Modifier.width(10.dp))
                    BasicText("Stop", Modifier.border(1.dp, cyan, RoundedCornerShape(50)).clickable(role = Role.Button) { News.stop() }.padding(horizontal = 18.dp, vertical = 10.dp), Tokens.Text.bodyStrong.style(cyan))
                }
            }
        }
    }
}

/** A fixed bar pattern standing in for the bulletin's shape: it fills as the bulletin plays. */
@Composable
private fun Wave(progress: Float, ink: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = 40
        val step = size.width / n
        for (i in 0 until n) {
            val h = size.height * (0.3f + 0.7f * (((i * 37) % 11) / 10f))
            drawRoundRect(if (i.toFloat() / n < progress) ink else ink.copy(alpha = 0.25f), Offset(i * step, (size.height - h) / 2), Size(step * 0.55f, h), CornerRadius(2f))
        }
    }
}

/** Shows a news failure as a toast for 4 s. */
@Composable
internal fun BoxScope.NewsToast() {
    rememberTick(250).value
    val text = News.notice ?: return
    if (android.os.SystemClock.uptimeMillis() - News.noticeAt > 4000) return
    Toast(text, null, {}, Modifier.align(Alignment.BottomCenter))
}

/** Settings › Listening › News on the headphone button. */
@Composable
fun NewsSettingsScreen(lib: LibraryStore?, back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { News.load(ctx) }
    var rev by remember { mutableIntStateOf(0) }
    rev.let { }
    val save = { News.save(ctx); rev++ }
    val cyan = newsInk
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenHeader("News on the button", back)
        BasicText("Press your headphones’ play/pause button while Brocast is playing: the music dips, the latest news bulletin plays over it, and the music comes back up. You stay in sync the whole time.", style = Tokens.Text.body.style(Ink.textSecondary))
        Card {
            SwitchRow("News on the headphone button", "On: play/pause gets the news. Off: the button stops the music, as before.", News.on) { News.on = it; save() }
            BasicText("News from", Modifier.padding(top = 6.dp), Tokens.Text.bodyStrong.style())
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val st = News.stationNews(lib)
                val stationLine = when {
                    st.choice == "none" -> "Station’s choice · none" to "This station has no news · pick one below"
                    st.url.isEmpty() -> "Station’s choice · ${st.name}" to "Not set up on the station yet"
                    else -> "Station’s choice · ${st.name}" to "Set by the station · hourly, about 5 min"
                }
                SourceOption(stationLine.first, stationLine.second, News.source == News.Source.STATION, cyan) { News.source = News.Source.STATION; save() }
                SourceOption("NPR News Now", "Hourly, about 5 min · public feed", News.source == News.Source.NPR, cyan) { News.source = News.Source.NPR; save() }
                SourceOption("Your own feed", "Any podcast RSS address", News.source == News.Source.OWN, cyan) { News.source = News.Source.OWN; save() }
                if (News.source == News.Source.OWN) {
                    var text by remember { mutableStateOf(News.ownUrl) }
                    BasicTextField(text, { text = it; News.ownUrl = it.trim(); save() },
                        Modifier.fillMaxWidth().border(1.dp, Ink.grid, RoundedCornerShape(10.dp)).padding(12.dp),
                        textStyle = Tokens.Text.small.style(), singleLine = true, cursorBrush = SolidColor(cyan),
                        decorationBox = { inner -> if (text.isEmpty()) BasicText("https://…/feed.xml", style = Tokens.Text.small.style(Ink.faint)); inner() })
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                BasicText("Music under the news", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
                BasicText("${News.underPct}%", style = Tokens.Text.bodyStrong.style())
            }
            LevelSlider(News.underPct, 50) { News.underPct = it; save() }
            Row(Modifier.fillMaxWidth()) {
                BasicText("0% · silent", Modifier.weight(1f), Tokens.Text.small.style(Ink.muted))
                BasicText("25%", Modifier.weight(1f), Tokens.Text.small.style(Ink.muted))
                BasicText("50%", style = Tokens.Text.small.style(Ink.muted))
            }
            BasicText("Fade down", Modifier.padding(top = 8.dp), Tokens.Text.bodyStrong.style())
            Choice(News.SPEEDS.indices.map { "${News.SPEEDS[it]} ${secs(News.DOWN_S[it])}" }, News.fadeDown, { News.fadeDown = it; save() })
            BasicText("Fade back up", Modifier.padding(top = 8.dp), Tokens.Text.bodyStrong.style())
            Choice(News.SPEEDS.indices.map { "${News.SPEEDS[it]} ${secs(News.UP_S[it])}" }, News.fadeUp, { News.fadeUp = it; save() })
            SwitchRow("Get the next one ready on Wi-Fi", "Downloads each new bulletin when it comes out, so it starts the moment you press. About 3 MB an hour.", News.prefetch) { News.prefetch = it; save() }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Try it", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
                rememberTick(250).value
                val busy = News.phase != News.Phase.IDLE
                BasicText(if (busy) "Stop" else "Play 10 s preview", Modifier.border(1.dp, Ink.grid, RoundedCornerShape(50)).clickable(role = Role.Button) { News.playPreview(ctx) }.padding(horizontal = 18.dp, vertical = 12.dp), Tokens.Text.body.style())
            }
        }
        BasicText("WHAT IT DOES TO THE MUSIC", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            FadeDiagram(News.underPct, News.DOWN_S[News.fadeDown], News.UP_S[News.fadeUp], cyan)
            BasicText("Only the volume changes. The set keeps playing underneath at its synced place, so when it comes back up you’re still with everyone.", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        BasicText("Your phone fetches the bulletin straight from the news service, which sees your internet address. Nothing goes through the station or other listeners. Needs an internet connection.", style = Tokens.Text.small.style(Ink.muted))
    }
    NewsToast()
    }
}

private fun secs(s: Float) = if (s % 1f == 0f) "${s.toInt()} s" else "$s s"

@Composable
private fun SourceOption(title: String, detail: String, on: Boolean, ink: Color, pick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().border(1.dp, if (on) ink else Ink.grid, shape).selectable(on, role = Role.RadioButton, onClick = pick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, if (on) ink else Ink.faint, CircleShape), Alignment.Center) { if (on) Box(Modifier.size(10.dp).background(ink, CircleShape)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
        }
    }
}

@Composable
private fun LevelSlider(value: Int, max: Int, onChange: (Int) -> Unit) {
    var width by remember { mutableFloatStateOf(1f) }
    val set = { x: Float -> onChange((x / width * max).toInt().coerceIn(0, max)) }
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .pointerInput(Unit) { detectTapGestures { set(it.x) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> set(change.position.x) } },
    ) {
        width = size.width
        val y = size.height / 2
        val x = size.width * value / max
        drawLine(Ink.track, Offset(0f, y), Offset(size.width, y), 4.dp.toPx())
        drawLine(Ink.accent, Offset(0f, y), Offset(x, y), 4.dp.toPx())
        drawCircle(Ink.text, 11.dp.toPx(), Offset(x.coerceIn(11.dp.toPx(), size.width - 11.dp.toPx()), y))
    }
}

/** 100% → fade down → bulletin at the level → fade back up, to the settings' timings. */
@Composable
private fun FadeDiagram(pct: Int, down: Float, up: Float, ink: Color) {
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val bulletin = 6f
        val total = 0.6f + down + bulletin + up + 0.6f
        val sx = size.width / total
        val top = 12.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val yOf = { level: Float -> bottom - (bottom - top) * level }
        val low = pct / 100f
        val x0 = 0.6f * sx; val x1 = x0 + down * sx; val x2 = x1 + bulletin * sx; val x3 = x2 + up * sx
        drawRect(ink.copy(alpha = 0.12f), Offset(x1, top), Size(x2 - x1, bottom - top))
        val path = Path().apply {
            moveTo(0f, yOf(1f)); lineTo(x0, yOf(1f))
            for (i in 1..20) { val t = i / 20f; lineTo(x0 + (x1 - x0) * t, yOf(low + (1 - low) * kotlin.math.cos(t * Math.PI / 2).toFloat())) }
            lineTo(x2, yOf(low))
            for (i in 1..20) { val t = i / 20f; lineTo(x2 + (x3 - x2) * t, yOf(low + (1 - low) * kotlin.math.sin(t * Math.PI / 2).toFloat())) }
            lineTo(size.width, yOf(1f))
        }
        drawPath(path, Ink.accent, style = Stroke(2.dp.toPx()))
        drawLine(Ink.grid, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        drawContext.canvas.nativeCanvas.apply {
            val p = android.graphics.Paint().apply { color = android.graphics.Color.GRAY; textSize = 11.dp.toPx(); isAntiAlias = true }
            drawText("100%", 0f, yOf(1f) - 4.dp.toPx(), p)
            drawText("$pct%", 0f, yOf(low) - 4.dp.toPx(), p)
            drawText("${secs(down)}", (x0 + x1) / 2, yOf((1 + low) / 2), p)
            drawText("${secs(up)}", (x2 + x3) / 2, yOf((1 + low) / 2), p)
            drawText("press", 0f, size.height - 2.dp.toPx(), p)
            drawText("ends", x2 - 12.dp.toPx(), size.height - 2.dp.toPx(), p)
            p.color = android.graphics.Color.rgb(0x56, 0xD4, 0xDD)
            drawText("BULLETIN", (x1 + x2) / 2 - 28.dp.toPx(), (top + bottom) / 2, p)
        }
    }
}
