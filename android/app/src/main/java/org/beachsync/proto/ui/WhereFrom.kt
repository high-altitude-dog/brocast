package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.beachsync.proto.BlockSources
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.Session
import org.beachsync.proto.flagOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Hand-off 58: Where it came from. One square per second of a set, coloured by who sent the block it's in, from the engine's
 * per-block source log (`<id>.src`, BlockSources). Listener, Country and Route views; the top three of a view get blue, orange and
 * aqua, the rest slate, the station striped grey. Seconds a set had before the mesh (from the server) have no sender.
 * Reads files on Dispatchers.IO every 2 s while the set is arriving; never touches the audio thread.
 */

private val BLUE = Color(0xFF3987E5)
private val ORANGE = Color(0xFFD95926)
private val AQUA = Color(0xFF199E70)
private val SLATE = Color(0xFF4A525C)
private val STATION = Color(0xFF8B95A1)
private val MISSING = Color(0xFF161A20)
private val BEFORE = Color(0xFF262B33)
private val TOP = listOf(BLUE, ORANGE, AQUA)

/** One colour of a view: its sources (indexes into BlockSources.sources, 255 = overflow), name, sub-line and block count. */
private class Group(val id: String, val name: String, val flag: String?, val sub: String, val idx: Set<Int>, val blocks: Int, val station: Boolean, var colour: Color = SLATE)

private fun whereRouteWords(route: String?) = when (route) { "lan" -> "same Wi-Fi"; "relayed" -> "relayed"; null -> "route unknown"; else -> "across the internet" }
private fun whereCountryName(code: String): String = if (code.length == 2) Locale("", code).getDisplayCountry(Locale.UK).ifEmpty { code } else if (code == "hidden") "Country hidden" else "Country unknown"

/** The groups of [view] (0 listener, 1 country, 2 route), biggest first, coloured; the station last. */
private fun groups(src: BlockSources, view: Int): List<Group> {
    val counts = src.counts()
    val peers = src.sources.withIndex().filter { !it.value.station }
    val station = src.sources.withIndex().filter { it.value.station }.map { it.index + 1 }.toSet()
    val raw: List<Group> = when (view) {
        0 -> peers.map { (i, s) -> Group(s.key, s.nym?.let(::nymCase) ?: "A listener · ${s.key.take(4)}", s.country.takeIf { it.length == 2 }?.let(::flagOf), whereRouteWords(s.route), setOf(i + 1), counts[i + 1], false) } +
            listOfNotNull(if (counts[255] > 0) Group("more", "More listeners", null, "past the first 254", setOf(255), counts[255], false) else null)
        1 -> peers.groupBy { it.value.country }.map { (c, l) ->
            Group(c, whereCountryName(c), c.takeIf { it.length == 2 }?.let(::flagOf), "${l.size} listener${if (l.size == 1) "" else "s"}", l.map { it.index + 1 }.toSet(), l.sumOf { counts[it.index + 1] }, false)
        }
        else -> peers.groupBy { whereRouteWords(it.value.route) }.map { (r, l) ->
            Group(r, r.replaceFirstChar { it.uppercase() }, null, "${l.size} listener${if (l.size == 1) "" else "s"}", l.map { it.index + 1 }.toSet(), l.sumOf { counts[it.index + 1] }, false)
        }
    }
    val sorted = raw.filter { it.blocks > 0 }.sortedByDescending { it.blocks }
    sorted.forEachIndexed { i, g -> g.colour = TOP.getOrElse(i) { SLATE } }
    val st = if (station.isEmpty()) null else Group("station", "The station", null, "fills gaps nobody else has", station, station.sumOf { counts[it] }, true, STATION)
    return sorted + listOfNotNull(st?.takeIf { it.blocks > 0 })
}

@Composable
fun WhereFromScreen(lib: LibraryStore?, setId: String, back: () -> Unit, openSwarm: () -> Unit) {
    val item = lib?.editions?.flatMap { it.items }?.firstOrNull { it.id == setId }
    var src by remember(setId) { mutableStateOf<BlockSources?>(null) }
    var loaded by remember(setId) { mutableStateOf(false) }
    LaunchedEffect(setId) {
        while (true) {
            src = withContext(Dispatchers.IO) { lib?.let { BlockSources.read(it.sourcesFile(setId)) } }
            loaded = true
            if (lib == null || lib.file(setId).exists()) break // whole: nothing more will arrive
            delay(2000)
        }
    }
    var view by remember { mutableIntStateOf(0) }
    var lit by remember(view) { mutableStateOf<String?>(null) }
    var picked by remember(setId) { mutableStateOf<Int?>(null) }
    var showAll by remember(view) { mutableStateOf(false) }
    var proof by remember { mutableStateOf<Int?>(null) }
    val utcNow = FlowTime.fit()?.utcAt(Session.nowBoot()) ?: (System.currentTimeMillis() * 1_000_000L)
    val startNs = remember(setId, lib) { lib?.let { nextStartNs(it, setId, utcNow) } }
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("", back)
            BasicText("WHERE IT CAME FROM", style = Tokens.Text.eyebrow.style())
            BasicText(item?.title ?: setId, style = Tokens.Text.screenTitle.style(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val s = src
            if (item == null || lib == null) { BasicText("This set isn't in the station's list any more.", style = Tokens.Text.body.style(Ink.textSecondary)); return@Screen }
            if (s == null) {
                BasicText(
                    if (!loaded) "Reading…" else if (lib.file(setId).exists()) "This set came straight from the station's server, before peer-to-peer, so there's no record of who sent each block." else "Nothing has arrived for this set yet.",
                    style = Tokens.Text.body.style(Ink.textSecondary),
                )
                Footer(); return@Screen
            }
            val whole = lib.file(setId).exists()
            val seconds = maxOf(1, minOf(item.seconds.toInt(), s.blocks * s.blockSeconds))
            val counts = s.counts()
            val viaMesh = s.blocks - counts[0]
            val mb = item.bytes / 1_000_000
            BasicText(
                (if (whole) "%,d blocks".format(s.blocks) else "%,d of %,d blocks · still arriving".format(viaMesh, s.blocks)) +
                    " · $mb MB · one square is one second",
                style = Tokens.Text.small.style(Ink.textSecondary),
            )
            val gs = groups(s, view)
            val listenerBlocks = gs.filter { !it.station }.sumOf { it.blocks }
            val listeners = s.sources.count { !it.station }
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(if (viaMesh == 0) "–" else "${listenerBlocks * 100 / viaMesh}%", style = Tokens.Text.screenTitle.style().copy(fontSize = 34.sp))
                Spacer(Modifier.width(10.dp))
                BasicText("from $listeners listener${if (listeners == 1) "" else "s"}, peer-to-peer", Modifier.padding(bottom = 5.dp), Tokens.Text.body.style(Ink.textSecondary))
            }
            if (counts[0] > 0 && whole) BasicText("%,d blocks were on this phone before peer-to-peer.".format(counts[0]), style = Tokens.Text.small.style(Ink.textSecondary))
            ShareBar(gs, lit)
            Choice(listOf("Listener", "Country", "Route"), view, { view = it; picked = null })
            val colourOf = remember(gs) { IntArray(256).also { a -> gs.forEach { g -> g.idx.forEach { i -> a[i] = gs.indexOf(g) + 1 } } } }
            BlockMap(s, seconds, gs, colourOf, whole, lit, picked, startNs, onPick = { picked = it }) { sec -> proof = sec }
            val shown = if (showAll || view != 0) gs else gs.filter { it.colour != SLATE || it.station }
            val rest = gs.filter { it.colour == SLATE && !it.station }
            Column(Modifier.padding(top = 6.dp)) {
                for (g in shown.filter { !it.station }) LegendRow(g, viaMesh, lit, onTap = { if (lit == g.id) { if (view == 0) openSwarm() else lit = null } else lit = g.id })
                if (!showAll && view == 0 && rest.isNotEmpty()) {
                    val r = Group("rest", "${rest.size} other listener${if (rest.size == 1) "" else "s"}", null, "tap to list them", rest.flatMap { it.idx }.toSet(), rest.sumOf { it.blocks }, false)
                    LegendRow(r, viaMesh, lit, onTap = { showAll = true })
                }
                gs.firstOrNull { it.station }?.let { g -> LegendRow(g, viaMesh, lit, onTap = { lit = if (lit == g.id) null else g.id }) }
            }
            Footer()
        }
        proof?.let { sec -> BlockProvenanceSheet(sec, startNs ?: utcNow, setId) { proof = null } }
    }
}

@Composable
private fun Footer() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Ink.grid))
    Row(Modifier.padding(top = 8.dp)) {
        BasicText("✓", Modifier.width(24.dp), Tokens.Text.small.style(Ink.textSecondary))
        BasicText("Whoever sent it, every block was checked against the station's signature before it was kept. Tap a square for its proof.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

private fun DrawScope.stripes(r: Rect, c: Color, step: Float) {
    clipRect(r.left, r.top, r.right, r.bottom) {
        drawRect(c.copy(alpha = 0.35f), r.topLeft, r.size)
        var x = r.left - r.height
        while (x < r.right) { drawLine(c, Offset(x, r.bottom), Offset(x + r.height, r.top), step * 0.45f); x += step }
    }
}

@Composable
private fun ShareBar(gs: List<Group>, lit: String?) {
    val total = gs.sumOf { it.blocks }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val gap = 2.dp.toPx()
        var x = 0f
        val usable = size.width - gap * (gs.size - 1).coerceAtLeast(0)
        for (g in gs) {
            val w = usable * g.blocks / total
            val a = if (lit == null || lit == g.id) 1f else 0.22f
            if (g.station) stripes(Rect(x, 0f, x + w, size.height), STATION.copy(alpha = a), 3.dp.toPx())
            else drawRoundRect(g.colour.copy(alpha = a), Offset(x, 0f), Size(w, size.height), CornerRadius(3.dp.toPx()))
            x += w + gap
        }
    }
}

private val HMS = SimpleDateFormat("HH:mm:ss", Locale.UK)
private val HM = SimpleDateFormat("HH:mm", Locale.UK)

@Composable
private fun BlockMap(s: BlockSources, seconds: Int, gs: List<Group>, colourOf: IntArray, whole: Boolean, lit: String?, picked: Int?, startNs: Long?, onPick: (Int?) -> Unit, onProof: (Int) -> Unit) {
    val rows = (seconds + 59) / 60
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val label = 40.dp
        val cell = (maxWidth - label) / 60
        val height = cell * rows
        val pick = { o: Offset ->
            with(density) {
                val c = ((o.x - label.toPx()) / cell.toPx()).toInt(); val r = (o.y / cell.toPx()).toInt()
                if (c in 0 until 60 && r in 0 until rows && r * 60 + c < seconds) onPick(r * 60 + c)
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(height).clipToBounds()
                .pointerInput(seconds) { detectTapGestures { o -> pick(o) } }
                .pointerInput(seconds) { detectDragGestures(onDragStart = { pick(it) }) { ch, _ -> pick(ch.position) } },
        ) {
            val c = cell.toPx(); val gap = 0.9f / 4.2f * c; val sq = c - gap; val lx = label.toPx()
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.argb(160, 200, 205, 212); textSize = 7.5.sp.toPx(); typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
            for (r in 0 until rows step 10) {
                val t = if (startNs != null) HM.format(Date(startNs / 1_000_000L + r * 60_000L)) else "%d:%02d".format(r / 60, r % 60)
                drawContext.canvas.nativeCanvas.drawText(t, 0f, maxOf(r * c + sq, paint.textSize), paint) // row 0: kept inside the map
            }
            for (sec in 0 until seconds) {
                val b = (sec / s.blockSeconds).coerceAtMost(s.blocks - 1)
                val v = s.from[b].toInt() and 255
                val gi = colourOf[v]
                val g = if (gi > 0) gs[gi - 1] else null
                val x = lx + (sec % 60) * c; val y = (sec / 60) * c
                val a = if (lit == null || (g != null && lit == g.id) || (lit == "rest" && g != null && g.colour == SLATE && !g.station)) 1f else 0.22f
                when {
                    g == null -> drawRect(if (v == 0 && whole) BEFORE else MISSING, Offset(x, y), Size(sq, sq))
                    g.station -> stripes(Rect(x, y, x + sq, y + sq), STATION.copy(alpha = a), sq * 0.6f)
                    else -> drawRect(g.colour.copy(alpha = a), Offset(x, y), Size(sq, sq))
                }
            }
            picked?.let { p -> drawRect(Color.White, Offset(lx + (p % 60) * c - 1f, (p / 60) * c - 1f), Size(sq + 2f, sq + 2f), style = Stroke(1.5.dp.toPx())) }
        }
        picked?.let { p ->
            val b = (p / s.blockSeconds).coerceAtMost(s.blocks - 1)
            val v = s.from[b].toInt() and 255
            val src = s.sourceOf(b)
            val g = colourOf[v].takeIf { it > 0 }?.let { gs[it - 1] }
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().offset(y = cell * (p / 60 + 1) + 4.dp).background(Ink.surface, shape).border(1.dp, Ink.grid, shape)
                    .clickable(role = Role.Button) { onPick(null) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(g)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val name = when {
                        src == null -> if (v == 255) "One of many listeners" else if (whole) "On this phone before peer-to-peer" else "Not here yet"
                        src.station -> "The station"
                        else -> listOfNotNull(src.country.takeIf { it.length == 2 }?.let(::flagOf), src.nym?.let(::nymCase) ?: "A listener · ${src.key.take(4)}").joinToString(" ")
                    }
                    BasicText(name, style = Tokens.Text.bodyStrong.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val at = startNs?.let { "plays " + HMS.format(Date(it / 1_000_000L + p * 1000L)) } ?: "+%d:%02d:%02d".format(p / 3600, p / 60 % 60, p % 60)
                    val ms = s.msOf(b)?.let { if (it >= 1020) "≥ 1 s" else "$it ms" }
                    BasicText(listOfNotNull(at, ms, if (src != null) "✓ checked" else null).joinToString(" · "), style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp), maxLines = 1)
                }
                if (src != null) BasicText("Proof ›", Modifier.clickable(role = Role.Button) { onProof(p) }.padding(8.dp), Tokens.Text.body.style())
            }
        }
    }
}

@Composable
private fun Swatch(g: Group?) {
    Canvas(Modifier.size(18.dp)) {
        val r = Rect(Offset.Zero, size)
        when {
            g == null -> drawRoundRect(MISSING, cornerRadius = CornerRadius(3.dp.toPx()))
            g.station -> stripes(r, STATION, 4.dp.toPx())
            else -> drawRoundRect(g.colour, cornerRadius = CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
private fun LegendRow(g: Group, total: Int, lit: String?, onTap: () -> Unit) {
    val a = if (lit == null || lit == g.id) 1f else 0.35f
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onTap).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Swatch(g)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(listOfNotNull(g.flag, g.name).joinToString(" "), style = Tokens.Text.body.style(Ink.text.copy(alpha = a)), maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(g.sub, style = Tokens.Text.small.style(Ink.textSecondary.copy(alpha = a)))
        }
        BasicText("%,d".format(g.blocks), Modifier.width(64.dp), Tokens.Text.valueSmall.style(Ink.textSecondary.copy(alpha = a)).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
        BasicText("${if (total == 0) 0 else (g.blocks * 100 + total / 2) / total}%", Modifier.width(52.dp), Tokens.Text.valueSmall.style(Ink.text.copy(alpha = a)).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
    }
}

/** Settings › Where it came from (hand-off 63): pick a set; sets with a record first. */
@Composable
fun WhereFromPickScreen(lib: LibraryStore?, back: () -> Unit, open: (String) -> Unit) {
    val all = lib?.editions?.flatMap { it.items }?.distinctBy { it.id }.orEmpty()
    var withLog by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(all.size) { withLog = withContext(Dispatchers.IO) { all.filter { lib?.sourcesFile(it.id)?.exists() == true }.map { it.id }.toSet() } }
    Screen {
        ScreenHeader("Settings", back)
        BasicText("WHERE IT CAME FROM", style = Tokens.Text.eyebrow.style())
        BasicText("Pick a set", style = Tokens.Text.screenTitle.style())
        BasicText("Which listener sent each block. Sets that came from the station's server before peer-to-peer have no record.", style = Tokens.Text.small.style(Ink.textSecondary))
        val w = withLog ?: return@Screen
        for (it in all.sortedByDescending { w.contains(it.id) }) {
            NavRow(it.title, if (w.contains(it.id)) "Peer-to-peer record" else "No record: came before peer-to-peer") { open(it.id) }
        }
    }
}

