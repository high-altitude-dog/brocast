package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.beachsync.proto.Edition
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryItem
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.PearsEngine
import org.beachsync.proto.Session
import org.beachsync.proto.editionAt
import org.beachsync.proto.upcomingEdition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Hand-off 63: the SET LIST button on Now playing and the Set list screen. Running order comes from the edition at the ear, with start
 * times worked out from flowtime (the same for everyone); All sets is every set in any edition the station has published. Track lists and
 * the per-block source log don't exist yet, so the sheet leaves out Tracks and Where it came from (h58 adds the log).
 * Reads only what the library already publishes; never touches the audio thread.
 */

/** One set's place in the running order: when it's first heard, in UTC ns. */
private class Slot(val item: LibraryItem, val startNs: Long)

/** Today's played sets (oldest first), the set at the ear, and the rest of one loop after it. Null when nothing is published. */
private class Order(val earlier: List<Slot>, val now: Slot?, val next: List<Slot>, val edition: Edition, val againNs: Long)

private const val S = 1_000_000_000L

private fun runningOrder(editions: List<Edition>, dMs: Long, utcNow: Long): Order? {
    val d = dMs * 1_000_000L
    val t = utcNow - d   // the stream time heard now
    val ed = editionAt(editions, t) ?: return null
    val n = ed.items.size
    if (n == 0 || ed.totalSeconds <= 0) return null
    val started = t >= ed.startNs
    val secs = if (started) Math.floorDiv(t - ed.startNs, S) else 0L
    var left = Math.floorMod(secs + ed.fromS, ed.totalSeconds)
    var i = 0
    while (left >= ed.items[i].seconds) { left -= ed.items[i].seconds; i++ }
    val nowStart = ed.startNs + (secs - left) * S + d
    val next = ArrayList<Slot>()
    var at = nowStart
    for (k in 0 until n) {
        val item = ed.items[(i + k) % n]
        next.add(Slot(item, at)); at += item.seconds * S
    }
    // Earlier today: back from Now, no further than the edition's start or local midnight.
    val earlier = ArrayList<Slot>()
    if (started) {
        val day = Calendar.getInstance().apply { timeInMillis = utcNow / 1_000_000L; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis * 1_000_000L
        val floor = maxOf(ed.startNs + d, day)
        var start = nowStart
        var k = 1
        while (k <= 200) {
            val item = ed.items[Math.floorMod(i - k, n)]
            start -= item.seconds * S
            if (start < floor) break
            earlier.add(0, Slot(item, start)); k++
        }
    }
    return Order(earlier, if (started) next.first() else null, if (started) next.drop(1) else next, ed, nowStart + ed.totalSeconds * S)
}

private val HM = SimpleDateFormat("HH:mm", Locale.UK)
private fun hm(utcNs: Long) = HM.format(Date(utcNs / 1_000_000L))
internal fun lengthText(s: Long) = if (s >= 3600) "${s / 3600} h ${"%02d".format(s % 3600 / 60)} m" else if (s >= 60) "${s / 60} min" else "$s s"
private fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "${b / 1_000_000} MB"

private fun setColour(item: LibraryItem): Color =
    runCatching { Color(android.graphics.Color.parseColor(item.color.trim().let { if (it.startsWith("#")) it else "#$it" })) }.getOrNull() ?: Ink.track

/** Flag and artist, from the artist registry when the station has one. */
private fun artistLine(lib: LibraryStore, item: LibraryItem): String {
    val reg = lib.artistOf(item.id)
    val artist = item.artist.ifEmpty { reg?.first ?: "" }
    val flags = reg?.second.orEmpty().joinToString("") { org.beachsync.proto.flagOf(it) }
    return if (flags.isNotEmpty() && artist.isNotEmpty()) "$flags $artist" else artist
}

private enum class Have { HERE, ARRIVING, NOT_YET }

private fun have(lib: LibraryStore, item: LibraryItem): Have = when {
    lib.file(item.id).exists() -> Have.HERE
    lib.partPct in 0..99 && lib.partTitle == item.title -> Have.ARRIVING
    else -> Have.NOT_YET
}

/** The 44 px button that replaces the Library bar at the foot of the Now playing box. */
@Composable
internal fun SetListButton(lib: LibraryStore?, open: () -> Unit) {
    val have = lib?.haveSets ?: 0
    val total = lib?.totalSets ?: 0
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val count = when { total == 0 -> lib?.status ?: "Not downloaded yet"; else -> "$have of $total" }
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(if (pressed) Ink.track else Ink.bg, shape).border(Tokens.Stroke.card.dp, Ink.grid, shape)
            .clickable(press, null, role = Role.Button, onClick = open).padding(horizontal = 12.dp)
            .semantics { contentDescription = if (total > 0) "Set list: $have of $total sets on this phone. Open the set list" else "Set list. Open the set list" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(14.dp)) {
            val w = 1.6.dp.toPx()
            for (r in 0..2) {
                val y = size.height * (0.2f + r * 0.3f)
                drawCircle(Ink.accent, w * 0.8f, Offset(w, y))
                drawLine(Ink.accent, Offset(w * 3.2f, y), Offset(size.width, y), w, StrokeCap.Round)
            }
        }
        BasicText("SET LIST", style = Tokens.Text.cardLabel.style(Ink.text).copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.12.sp * 12))
        Box(Modifier.weight(1f).height(3.dp).background(Ink.track, CircleShape)) {
            if (total > 0) Box(Modifier.fillMaxWidth((have.toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(Ink.accent, CircleShape))
        }
        BasicText(count, style = Tokens.Text.valueSmall.style(Ink.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText("›", style = Tokens.Text.bodyStrong.style(Ink.textSecondary))
    }
}

@Composable
fun SetListScreen(state: BenchState, back: () -> Unit, openKept: () -> Unit, passOn: (String) -> Unit) {
    rememberTick(1000L).value
    val lib = state.library
    val utcNow = FlowTime.fit()?.utcAt(Session.nowBoot()) ?: (System.currentTimeMillis() * 1_000_000L)
    val all = lib?.editions?.flatMap { it.items }?.distinctBy { it.id }.orEmpty()
    val order = lib?.let { runningOrder(it.editions, it.dMs, utcNow) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var earlierOpen by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var bpm by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(all.map { it.id }) {
        if (lib == null) return@LaunchedEffect
        withContext(Dispatchers.IO) { for (it in all) if (lib.file(it.id).exists()) loadSetBpm(lib, it) }
        bpm = all.mapNotNull { i -> setBpm(i.id)?.let { i.id to it } }.toMap()
    }
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Now playing", back)
            BasicText("SET LIST", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
            BasicText(PearsEngine.STATION_NAME, style = Tokens.Text.screenTitle.style().copy(fontSize = 28.sp))
            if (lib == null || all.isEmpty()) {
                BasicText(lib?.status ?: "No set list yet: the station hasn't sent one.", style = Tokens.Text.body.style(Ink.textSecondary))
                return@Screen
            }
            val here = all.filter { lib.file(it.id).exists() }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                BasicText("${here.size} of ${all.size} sets on this phone", Modifier.weight(1f), Tokens.Text.body.style())
                BasicText(gb(here.sumOf { lib.file(it.id).length() }), style = Tokens.Text.valueSmall.style(Ink.textSecondary))
            }
            Box(Modifier.fillMaxWidth().height(4.dp).background(Ink.track, CircleShape)) {
                Box(Modifier.fillMaxWidth(here.size.toFloat() / all.size).fillMaxHeight().background(Ink.accent, CircleShape))
            }
            BasicText(
                if (here.size == all.size) "Every set is on this phone. It plays with no network at all." else "Each set arrives before it plays. The rest are fetched as the order reaches them.",
                style = Tokens.Text.small.style(Ink.textSecondary),
            )
            Choice(listOf("Running order", "All ${all.size} sets"), tab, { tab = it })
            if (tab == 0) {
                if (order == null) BasicText("Nothing is on air yet.", style = Tokens.Text.body.style(Ink.textSecondary))
                else {
                    if (order.earlier.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { earlierOpen = !earlierOpen }
                                .drawBehindDashed().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicText(if (earlierOpen) "⌃" else "⌄", Modifier.width(24.dp), Tokens.Text.body.style(Ink.textSecondary))
                            BasicText("Earlier today · ${order.earlier.size} played since ${hm(order.earlier.first().startNs)}", style = Tokens.Text.body.style(Ink.textSecondary))
                        }
                        if (earlierOpen) for (slot in order.earlier) SetRow(lib, slot.item, hm(slot.startNs), bpm[slot.item.id], null, null, dim = true) { sheet = slot.item.id }
                    }
                    order.now?.let { now ->
                        SetRow(lib, now.item, hm(now.startNs), bpm[now.item.id], "NOW · ENDS ${hm(now.startNs + now.item.seconds * S)}", null) { sheet = now.item.id }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(if (order.now != null) "UP NEXT" else "STARTS", Modifier.weight(1f), Tokens.Text.cardLabel.style())
                        BasicText("same times for everyone", style = Tokens.Text.small.style(Ink.textSecondary))
                    }
                    var prev = order.now?.startNs ?: utcNow
                    for (slot in order.next) {
                        SetRow(lib, slot.item, hm(slot.startNs), bpm[slot.item.id], null, hm(prev)) { sheet = slot.item.id }
                        prev = slot.startNs
                    }
                    val ed = order.edition
                    val loop = ed.totalSeconds
                    val upcoming = upcomingEdition(lib.editions, lib.dMs, utcNow)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.grid))
                    BasicText(
                        (if (ed.number > 0) "Edition ${ed.number} · " else "") + "loops every ${lengthText(loop)} · starts again at ${hm(order.againNs)}" +
                            (upcoming?.let { " · a new order takes over at ${hm(it.startNs + lib.dMs * 1_000_000L)}" } ?: ""),
                        style = Tokens.Text.small.style(Ink.textSecondary),
                    )
                }
            } else {
                val today = order?.edition?.items?.map { it.id }?.toSet().orEmpty()
                val notYet = all.size - here.size
                Choice(listOf("All", "On this phone · ${here.size}", "Not yet · $notYet"), filter, { filter = it })
                val shown = all.filter { when (filter) { 1 -> lib.file(it.id).exists(); 2 -> !lib.file(it.id).exists(); else -> true } }.sortedBy { it.title.lowercase() }
                Card {
                    for (item in shown) SetRow(lib, item, null, null, null, null, compact = true, outOfOrder = item.id !in today) { sheet = item.id }
                    if (shown.isEmpty()) BasicText("Nothing here.", style = Tokens.Text.body.style(Ink.textSecondary))
                }
                if (notYet > 0) BasicText("Sets not in today's order are fetched when an edition puts them in.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        sheet?.let { id -> all.firstOrNull { it.id == id } }?.let { item ->
            val slot = order?.let { o -> (listOfNotNull(o.now) + o.next).firstOrNull { it.item.id == item.id } }
            SetSheet(lib!!, item, slot?.startNs?.let(::hm), bpm[item.id], { sheet = null }, { sheet = null; openKept() }) { sheet = null; passOn(item.id) }
        }
    }
}

/** A dashed 1 px outline, for Earlier today. */
private fun Modifier.drawBehindDashed(): Modifier = this.then(Modifier.drawBehind {
    val w = 1.dp.toPx()
    drawRoundRect(Ink.grid, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), androidx.compose.ui.geometry.CornerRadius(Tokens.Radius.card.dp.toPx()),
        style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
})

/**
 * One set: start time, set-colour bar, photo, name, flag and artist, length and BPM, and its status on this phone.
 * [nowLabel] marks the set at the ear; [byTime] is when a missing set should have arrived by (the start of the set before it).
 */
@Composable
private fun SetRow(lib: LibraryStore, item: LibraryItem, time: String?, bpm: Int?, nowLabel: String?, byTime: String?, dim: Boolean = false, compact: Boolean = false, outOfOrder: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    val now = nowLabel != null
    val box = when {
        compact -> Modifier
        now -> Modifier.background(Ink.accent.copy(alpha = 0.07f), shape).border(1.5.dp, Ink.accent, shape)
        else -> Modifier.background(Ink.surface, shape).border(Tokens.Stroke.card.dp, Ink.border, shape)
    }
    val status = have(lib, item)
    val detail = lengthText(item.seconds) + (bpm?.let { " · $it BPM" } ?: "")
    Row(
        Modifier.fillMaxWidth().then(box).clickable(role = Role.Button, onClick = onClick).padding(horizontal = if (compact) 0.dp else 12.dp, vertical = if (compact) 8.dp else 12.dp)
            .semantics { contentDescription = listOfNotNull(time?.let { "Plays $it" }, nowLabel, item.title, item.artist.ifEmpty { null }, detail, when (status) { Have.HERE -> "on this phone"; Have.ARRIVING -> "arriving, ${lib.partPct}%"; Have.NOT_YET -> "not on this phone yet" }).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fade = if (dim) Ink.textSecondary else Ink.text
        if (time != null) {
            BasicText(time, Modifier.width(52.dp), Tokens.Text.valueSmall.style(fade).copy(fontSize = 14.sp))
            Box(Modifier.padding(end = 10.dp).width(4.dp).height(40.dp).background(setColour(item), RoundedCornerShape(2.dp)))
        }
        ArtistThumb(lib, item, 44.dp, onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (now) BasicText(nowLabel!!, style = Tokens.Text.cardLabel.style(Ink.accent).copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            BasicText(item.title, style = Tokens.Text.bodyStrong.style(fade), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val artist = artistLine(lib, item)
            if (compact) BasicText(listOf(artist, lengthText(item.seconds)).filter { it.isNotEmpty() }.joinToString(" · ") + if (outOfOrder) " · not in today's order" else "",
                style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            else {
                if (artist.isNotEmpty()) BasicText(artist, style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(detail, style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp), maxLines = 1)
            }
        }
        if (!dim) StatusMark(status, lib.partPct, if (compact) null else byTime)
    }
}

/** On this phone: a check in the station colour. Arriving: a ring filling with the percentage. Not yet: a dashed ring, with when it's due by. */
@Composable
private fun StatusMark(status: Have, pct: Int, byTime: String?) {
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (status) {
            Have.ARRIVING -> BasicText("$pct%", style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp))
            Have.NOT_YET -> byTime?.let { BasicText("by $it", style = Tokens.Text.small.style(Ink.textSecondary)) }
            Have.HERE -> {}
        }
        Canvas(Modifier.size(22.dp)) {
            val w = 1.8.dp.toPx()
            val r = size.minDimension / 2 - w
            val c = Offset(size.width / 2, size.height / 2)
            when (status) {
                Have.HERE -> {
                    drawCircle(Ink.accent, r, c, style = Stroke(w))
                    val p = androidx.compose.ui.graphics.Path().apply { moveTo(c.x - r * 0.45f, c.y); lineTo(c.x - r * 0.1f, c.y + r * 0.35f); lineTo(c.x + r * 0.5f, c.y - r * 0.35f) }
                    drawPath(p, Ink.accent, style = Stroke(w, cap = StrokeCap.Round))
                }
                Have.ARRIVING -> {
                    drawCircle(Ink.track, r, c, style = Stroke(w))
                    drawArc(Ink.accent, -90f, 360f * pct / 100f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(w * 1.4f, cap = StrokeCap.Round))
                }
                Have.NOT_YET -> drawCircle(Ink.muted, r, c, style = Stroke(w * 0.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))))
            }
        }
    }
}

/** Tap a set: what it is, when it plays and whether it's here. Nothing in it jumps the radio ahead. */
@Composable
private fun SetSheet(lib: LibraryStore, item: LibraryItem, playsAt: String?, bpm: Int?, close: () -> Unit, openKept: () -> Unit, passOn: () -> Unit) {
    Sheet(close) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtistThumb(lib, item, 88.dp) {}
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(item.title, style = Tokens.Text.screenTitle.style(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    artistLine(lib, item).takeIf { it.isNotEmpty() }?.let { BasicText(it, style = Tokens.Text.body.style(Ink.textSecondary)) }
                    BasicText(listOfNotNull(playsAt?.let { "plays $it" }, lengthText(item.seconds), bpm?.let { "$it BPM" }).joinToString(" · "), style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp))
                }
            }
            val here = lib.file(item.id)
            val status = have(lib, item)
            BasicText(
                when (status) {
                    Have.HERE -> "✓ On this phone · ${gb(here.length())} · checked"
                    Have.ARRIVING -> "Arriving · ${lib.partPct}%"
                    Have.NOT_YET -> "Not on this phone yet · fetched before it plays"
                },
                style = Tokens.Text.small.style(if (status == Have.HERE) Ink.accent else Ink.textSecondary),
            )
            NavRow("Keep", "Kept already: every set stays on this phone in the beta", openKept)
            if (status == Have.HERE) NavRow("Pass it on", "Show a code for the person next to you", passOn)
        }
    }
}
