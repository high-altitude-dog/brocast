package org.beachsync.proto.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.beachsync.proto.BeatFeatures
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryItem
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.Session
import org.beachsync.proto.SetBeatAnalyser
import org.beachsync.proto.shownAt
import java.io.DataInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Hand-off 26: Kept. Everything whole on this phone (in the beta, every downloaded set), from real data: the library's files, each set's beat grid
 * (SetBeats) and the band energies the beat analyser keeps (BeatFeatures). Track lists, KEY and LOUDNESS don't exist yet, so they're left out, as the spec
 * says; Replay and Unkeep wait for the Archive and the end of the beta.
 */

/** What Kept shows for one set, worked out off the UI thread and cached. */
private class KeptInfo(
    val energy: FloatArray?, val tempo: FloatArray?, val bpmLow: Int?, val bpmHigh: Int?, val bpmMain: Int?, val peakS: Long?,
)

private object KeptCache { val map = HashMap<String, KeptInfo>() }

private const val POINTS = 160

private fun loadInfo(lib: LibraryStore, item: LibraryItem): KeptInfo {
    val grid = lib.beats(item.id, fresh = true)
    val bpms = grid?.windows?.map { it?.let { w -> (60 * 48000.0 / w.period).toFloat() } ?: Float.NaN }
    val good = bpms?.filter { !it.isNaN() }?.sorted().orEmpty()
    val tempo = bpms?.let { b -> FloatArray(POINTS) { i -> b[(i * b.size / POINTS).coerceAtMost(b.size - 1)] } }
    var energy: FloatArray? = null; var peak: Long? = null
    val f = lib.featuresFile(item.id)
    if (f.exists() && f.length() > 0) {
        val frames = (f.length() / 4 / BeatFeatures.BANDS).toInt()
        val sums = FloatArray(POINTS); val counts = IntArray(POINTS)
        DataInputStream(f.inputStream().buffered()).use { s ->
            for (fr in 0 until frames) {
                var e = 0f
                repeat(BeatFeatures.BANDS) { e += s.readFloat() }
                val i = (fr.toLong() * POINTS / frames).toInt().coerceAtMost(POINTS - 1)
                sums[i] += e; counts[i]++
            }
        }
        val raw = FloatArray(POINTS) { if (counts[it] > 0) sums[it] / counts[it] else 0f }
        val lo = raw.filter { it > 0f }.minOrNull() ?: 0f; val hi = raw.maxOrNull() ?: 1f
        energy = FloatArray(POINTS) { if (hi > lo) (0.15f + 0.85f * (raw[it] - lo) / (hi - lo)).coerceIn(0.1f, 1f) else 0.5f }
        peak = raw.indices.maxByOrNull { raw[it] }?.let { it.toLong() * item.seconds / POINTS }
    }
    fun pct(p: Double) = good.getOrNull((p * (good.size - 1)).roundToInt())?.roundToInt()
    return KeptInfo(energy, tempo, pct(0.1), pct(0.9), pct(0.5), peak)
}

private fun clock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
private fun mb(b: Long) = if (b >= 1_000_000_000) "%.2f GB".format(b / 1e9) else "${(b / 1e6).roundToInt()} MB"
private val DAY = SimpleDateFormat("d MMM", Locale.UK)

@Composable
fun KeptScreen(state: BenchState, back: () -> Unit, openArtist: () -> Unit) {
    val lib = state.library
    val items = lib?.editions?.flatMap { it.items }?.distinctBy { it.id }.orEmpty()
    val kept = items.filter { lib!!.file(it.id).exists() }
    val finishing = items.filter { !lib!!.file(it.id).exists() }
    var infos by remember { mutableStateOf(KeptCache.map.toMap()) }
    LaunchedEffect(kept.map { it.id }) {
        withContext(Dispatchers.IO) { for (it in kept) if (KeptCache.map[it.id]?.bpmMain == null) KeptCache.map[it.id] = loadInfo(lib!!, it) }
        infos = KeptCache.map.toMap()
    }
    var sort by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val allBpm = kept.mapNotNull { infos[it.id]?.bpmMain }
    val lowest = ((kept.mapNotNull { infos[it.id]?.bpmLow }.minOrNull() ?: 110) - 2)
    val highest = ((kept.mapNotNull { infos[it.id]?.bpmHigh }.maxOrNull() ?: 130) + 2)
    var range by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val (from, to) = range ?: (lowest to highest)
    fun inRange(it: LibraryItem) = infos[it.id]?.let { i -> i.bpmLow != null && i.bpmHigh!! >= from && i.bpmLow <= to } ?: true
    val playing = lib?.let { l -> shownAt(l.editions, l.dMs, FlowTime.fit()?.utcAt(Session.nowBoot()) ?: (System.currentTimeMillis() * 1_000_000L))?.first?.id }
    val sorted = when (sort) {
        1 -> kept.sortedBy { infos[it.id]?.bpmMain ?: 999 }
        2 -> kept.sortedByDescending { it.seconds }
        3 -> kept.sortedBy { it.artist.lowercase() }
        else -> kept.sortedByDescending { lib!!.file(it.id).lastModified() }
    }
    Screen {
        ScreenHeader("Kept", back)
        BasicText("Everything you've kept, whole and verified on this phone. It plays with no network at all.", style = Tokens.Text.body.style(Ink.textSecondary))
        if (kept.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().border(1.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.card.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicText("Nothing kept yet", style = Tokens.Text.bodyStrong.style())
                BasicText("Sets and tracks appear here once they're whole and verified on this phone. The first usually lands within minutes of pressing Play.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Stat("KEPT", "${kept.size}", "${kept.size} sets", Modifier.weight(1f))
                Stat("LISTENING", kept.sumOf { it.seconds }.let { "${it / 3600} h ${it % 3600 / 60} m" }, "all play offline", Modifier.weight(1f))
                Stat("STORAGE", mb(kept.sumOf { lib!!.file(it.id).length() }), "on this phone", Modifier.weight(1f))
            }
            if (allBpm.isNotEmpty()) Card {
                Row(Modifier.fillMaxWidth()) {
                    BasicText("TEMPO MAP", Modifier.weight(1f), Tokens.Text.cardLabel.style())
                    BasicText("$from–$to BPM · ${kept.count { inRange(it) }} of ${kept.size}", style = Tokens.Text.valueSmall.style(Ink.accent).copy(fontSize = 12.sp))
                }
                TempoMap(kept, infos, lowest, highest, from, to)
                StepSlider("BPM range", highest - lowest + 1, to - lowest, { range = from to lowest + it }, listOf(0 to "$lowest", highest - lowest to "$highest"), { "${lowest + it} BPM" },
                    low = from - lowest, onLow = { range = lowest + it to to })
                BasicText("Each bar is a set across its tempo range, thicker when longer; the ring is where it sits most. Drag the handles to filter the list." +
                    if (range != null) "" else "", style = Tokens.Text.small.style(Ink.textSecondary))
                if (range != null) BasicText("Clear the filter", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { range = null }.wrapContentHeight(), Tokens.Text.body.style(Ink.accent))
            }
            Choice(listOf("Recent", "BPM", "Length", "Artist"), sort, { sort = it })
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BasicText("▬ energy", style = Tokens.Text.small.style(Ink.accent.copy(alpha = 0.6f)))
                BasicText("— tempo", style = Tokens.Text.small.style(Ink.text))
            }
            for (item in sorted) KeptCard(lib!!, item, infos[item.id], item.id == playing, inRange(item), open == item.id, { open = if (open == item.id) null else item.id }, openArtist)
        }
        if (finishing.isNotEmpty()) Row(
            Modifier.fillMaxWidth().border(1.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.card.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                BasicText("${finishing.size} still finishing: ${finishing.first().title}", style = Tokens.Text.bodyStrong.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText("Kept${if (lib!!.partPct in 0..99) ", ${lib.partPct}% built" else ", waiting to download"}. Shows here once whole and verified.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, detail: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = Tokens.Text.cardLabel.style())
        BasicText(value, style = Tokens.Text.value.style().copy(fontSize = 22.sp))
        BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

@Composable
private fun TempoMap(kept: List<LibraryItem>, infos: Map<String, KeptInfo>, lo: Int, hi: Int, from: Int, to: Int) {
    val onMap = kept.filter { infos[it.id]?.bpmLow != null }
    val longest = onMap.maxOfOrNull { it.seconds } ?: 1L
    Canvas(Modifier.fillMaxWidth().height((22 * onMap.size + 26).dp).semantics { contentDescription = "Tempo map of ${onMap.size} sets" }) {
        fun x(b: Float) = size.width * (b - lo) / (hi - lo).coerceAtLeast(1)
        drawRect(Ink.accent.copy(alpha = 0.08f), Offset(x(from.toFloat()), 0f), Size(x(to.toFloat()) - x(from.toFloat()), size.height - 20.dp.toPx()))
        onMap.forEachIndexed { i, item ->
            val inf = infos[item.id]!!
            val y = (11 + 22 * i).dp.toPx()
            val inside = inf.bpmHigh!! >= from && inf.bpmLow!! <= to
            val a = if (inside) 1f else 0.32f
            val th = (4 + 8 * item.seconds.toFloat() / longest).dp.toPx()
            val x0 = x(inf.bpmLow!!.toFloat()); val x1 = maxOf(x(inf.bpmHigh.toFloat()), x0 + th)
            drawLine(Ink.accent.copy(alpha = a), Offset(x0, y), Offset(x1, y), th, StrokeCap.Round)
            inf.bpmMain?.let { m -> drawCircle(Ink.bg.copy(alpha = a), th * 0.75f, Offset(x(m.toFloat()), y)); drawCircle(Ink.text.copy(alpha = a), th * 0.75f, Offset(x(m.toFloat()), y), style = Stroke(1.5.dp.toPx())) }
            monoText(item.title.take(22), if (x0 > size.width / 2) x0 - 6.dp.toPx() else x1 + 6.dp.toPx(), y + 3.dp.toPx(), 9f, Ink.textSecondary.copy(alpha = a),
                if (x0 > size.width / 2) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT)
        }
        val base = size.height - 16.dp.toPx()
        drawLine(Ink.grid, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        var t = (lo / 5 + 1) * 5
        while (t < hi) { monoText("$t", x(t.toFloat()), size.height - 2.dp.toPx(), 9f, Ink.muted); t += 5 }
    }
}

@Composable
private fun KeptCard(lib: LibraryStore, item: LibraryItem, info: KeptInfo?, playing: Boolean, inside: Boolean, expanded: Boolean, toggle: () -> Unit, openArtist: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    val working = SetBeatAnalyser.current()?.working?.takeIf { it.first == item.title }?.second
    val bpm = when {
        info?.bpmLow != null -> if (info.bpmLow == info.bpmHigh) "${info.bpmLow} BPM" else "${info.bpmLow}–${info.bpmHigh} BPM"
        info?.tempo != null -> "no steady BPM"
        working != null -> "BPM: working out, $working%"
        else -> "BPM: working out"
    }
    Column(
        Modifier.fillMaxWidth().alpha(if (inside) 1f else 0.32f).background(Ink.surface, shape)
            .border(if (expanded) 1.5.dp else Tokens.Stroke.card.dp, if (expanded) Ink.accent else Ink.border, shape)
            .clickable(role = Role.Button, onClick = toggle).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtistThumb(lib, item, openArtist)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(item.title, style = Tokens.Text.bodyStrong.style().copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(listOf(item.artist.ifEmpty { null }, "set").filterNotNull().joinToString(" · "), style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1)
                BasicText("${clock(item.seconds)} · $bpm · ${mb(item.bytes)}", style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 11.sp), maxLines = 1)
            }
            Bookmark()
        }
        Fingerprint(info, if (expanded) 72 else 40)
        if (expanded) {
            Row(Modifier.fillMaxWidth()) {
                info?.peakS?.let { Stat("PEAK", clock(it), "loudest stretch", Modifier.weight(1f)) }
                info?.bpmMain?.let { Stat("TEMPO", "$it", "BPM most of the time", Modifier.weight(1f)) }
                Stat("KEPT", DAY.format(Date(lib.file(item.id).lastModified())), "on this phone", Modifier.weight(1f))
            }
            BasicText("TRACK LIST NOT PUBLISHED YET", style = Tokens.Text.cardLabel.style())
            BasicText("The station hasn't published this set's track list. Tempo and energy come from the audio itself; track lines and titles appear when the list arrives.", style = Tokens.Text.small.style(Ink.textSecondary))
            BasicText("Replay · not synced comes with the Archive.", style = Tokens.Text.small.style(Ink.muted))
        }
        if (playing) BasicText("● Playing now", style = Tokens.Text.small.style(Ink.accent))
        else BasicText("✓ verified · plays offline · kept ${DAY.format(Date(lib.file(item.id).lastModified()))}", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/** Locked on for the beta: everything is kept, so it has no tap of its own here. */
@Composable
private fun Bookmark() {
    Canvas(Modifier.size(44.dp).semantics { contentDescription = "Kept" }) {
        val w = 12.dp.toPx(); val h = 16.dp.toPx(); val x = (size.width - w) / 2; val y = (size.height - h) / 2
        drawPath(Path().apply { moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h); lineTo(x + w / 2, y + h * 0.72f); lineTo(x, y + h); close() }, Ink.accent)
    }
}

/** The whole file's energy as a band, its tempo as a white line; dashed while the beats are still being worked out. */
@Composable
private fun Fingerprint(info: KeptInfo?, heightDp: Int) {
    Canvas(Modifier.fillMaxWidth().height(heightDp.dp)) {
        val mid = size.height / 2
        val e = info?.energy
        if (e == null) {
            drawLine(Ink.faint, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            return@Canvas
        }
        val step = size.width / e.size
        for (i in e.indices) {
            val h = e[i] * size.height * 0.9f
            drawRect(Ink.accent.copy(alpha = 0.35f), Offset(i * step, mid - h / 2), Size(step + 0.5f, h))
        }
        tempoLine(info.tempo, info.bpmLow, info.bpmHigh)
    }
}

private fun DrawScope.tempoLine(tempo: FloatArray?, lo: Int?, hi: Int?) {
    if (tempo == null || lo == null || hi == null) return
    val span = (hi - lo).coerceAtLeast(4).toFloat()
    val base = lo - (span * 0.15f)
    val step = size.width / tempo.size
    var prev: Offset? = null
    for (i in tempo.indices) {
        val b = tempo[i]
        if (b.isNaN()) { prev = null; continue }
        val p = Offset(i * step, size.height * (0.92f - 0.84f * ((b - base) / (span * 1.3f)).coerceIn(0f, 1f)))
        prev?.let { drawLine(Ink.text, it, p, 1.5.dp.toPx(), StrokeCap.Round) }
        prev = p
    }
}
