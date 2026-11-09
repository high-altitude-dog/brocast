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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.KeptLedger
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.MeshView
import org.beachsync.proto.PearsEngine
import org.beachsync.proto.STREAM_EPOCH_NS
import org.beachsync.proto.Session
import org.beachsync.proto.keptTime
import org.beachsync.proto.playingAt

/** Silent reactions (hand-off 53): fire, hands up, love, wow, party, wave. */
val EMOJI = listOf("🔥", "🙌", "❤️", "😮", "🎉", "👋")

/** Settings › Listening: Show reactions, Send reactions (both on). Settings › Delivery: Thank-you notes (on). Kept on the phone. */
object ReactPrefs {
    var show by mutableStateOf(true); private set
    var send by mutableStateOf(true); private set
    var thanks by mutableStateOf(true); private set
    private var loaded = false
    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences("react", Context.MODE_PRIVATE)
        show = p.getBoolean("show", true); send = p.getBoolean("send", true); thanks = p.getBoolean("thanks", true); loaded = true
    }
    fun set(ctx: Context, show: Boolean = this.show, send: Boolean = this.send, thanks: Boolean = this.thanks) {
        this.show = show; this.send = send; this.thanks = thanks
        ctx.getSharedPreferences("react", Context.MODE_PRIVATE).edit().putBoolean("show", show).putBoolean("send", send).putBoolean("thanks", thanks).apply()
    }
}

/** `1.4 k` above 100, else the number. */
private fun count(n: Int) = when { n >= 100_000 -> "${n / 1000} k"; n >= 1000 -> "%.1f k".format(n / 1000.0); n > 100 -> "${(n + 5) / 10 * 10}"; else -> "$n" }

/** Milliseconds from flowtime [utc] to the next beat 1 at the ear, from the set's beat grid; null without one. */
fun msToNextBar(lib: LibraryStore, utc: Long): Long? {
    val (item, offS) = playingAt(lib.editions, lib.dMs, utc) ?: return null
    val frac = Math.floorMod(utc - lib.dMs * 1_000_000L - STREAM_EPOCH_NS, 1_000_000_000L) / 1e9
    val p = lib.beats(item.id)?.at((offS + frac) * 48000.0) ?: return null
    return (((4 - p.beatInBar) - p.beatFrac) * 60_000.0 / p.bpm).toLong()
}

/** The tray under the mesh card on Now playing. */
@Composable
fun ReactionTray(v: MeshView, lib: LibraryStore?) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ReactPrefs.load(ctx) }
    if (!ReactPrefs.send && !ReactPrefs.show) return
    val tick = rememberTick(100).value
    var sent by remember { mutableStateOf<Pair<Int, Long>?>(null) }   // which, and when (uptime ms)
    val now = android.os.SystemClock.uptimeMillis()
    val cooling = sent?.let { now - it.second < 3000 } == true
    // Others' reactions float up on the next beat 1, so everyone's land together.
    val floats = remember { ArrayList<Triple<Int, Long, Float>>() }   // emoji, when it shows (uptime ms), x position 0–1
    val seen = remember { HashSet<String>() }
    if (ReactPrefs.show && !ScreenPrefs.noFlashing) for (r in v.reactRecent) {
        val id = r.key8 + r.emoji + ((System.currentTimeMillis() - r.agoMs) / 500)
        if (r.mine || !seen.add(id)) continue
        val utc = FlowTime.fit()?.utcAt(Session.nowBoot()) ?: (System.currentTimeMillis() * 1_000_000L)
        floats.add(Triple(r.emoji, now + (lib?.let { msToNextBar(it, utc) } ?: 0L), ((r.key8.hashCode() and 0xFFFF) % 100) / 100f))
        if (seen.size > 500) seen.clear()
    }
    floats.removeAll { now - it.second > 1200 }
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(Modifier.fillMaxWidth().background(Ink.surface, shape).border(Tokens.Stroke.card.dp, Ink.border, shape).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("REACT", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            if (ReactPrefs.show) {
                val top = v.reactCounts.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(2)
                BasicText(if (top.isEmpty()) "quiet · last min" else top.joinToString(" · ") { "${EMOJI[it.index]} ${count(it.value)}" } + " · last min", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        Box {
            if (ReactPrefs.send) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (i in EMOJI.indices) {
                    val mine = sent?.first == i && cooling
                    Column(
                        Modifier.alpha(if (cooling && !mine) 0.35f else 1f).clickable(enabled = !cooling, role = Role.Button) { sent = i to android.os.SystemClock.uptimeMillis(); PearsEngine.react(i) }
                            .semantics { contentDescription = "React ${listOf("fire", "hands up", "love", "wow", "party", "wave")[i]}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(48.dp).border(if (mine) 2.dp else 1.dp, if (mine) Ink.accent else Ink.border, CircleShape), Alignment.Center) {
                            BasicText(EMOJI[i], style = Tokens.Text.body.style().copy(fontSize = 22.sp))
                        }
                        if (ReactPrefs.show) BasicText(if (v.reactCounts[i] > 0) count(v.reactCounts[i]) else " ", style = Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 10.sp))
                    }
                }
            }
            for ((e, at, x) in floats) {
                val t = (now - at) / 1200f
                if (t < 0f) continue
                BasicText(EMOJI[e], Modifier.fillMaxWidth(x.coerceIn(0.05f, 0.95f)).wrapContentWidth(Alignment.End).offset(y = (-40 * t).dp).alpha(1f - t), Tokens.Text.body.style().copy(fontSize = 20.sp))
            }
        }
        if (ReactPrefs.send && cooling) BasicText("Sent · lands on the next beat 1 for everyone", style = Tokens.Text.small.style(Ink.accent))
    }
    tick.let { }
}

/** Swarm: who reacted in the last second, by short key, for a ring and a small emoji at their node. */
fun pulses(v: MeshView?): Map<String, Pair<Int, Float>> =
    if (!ReactPrefs.show || ScreenPrefs.noFlashing) emptyMap()
    else v?.reactRecent?.filter { !it.mine && it.agoMs < 1000 }?.associate { it.key8 to (it.emoji to it.agoMs / 1000f) }.orEmpty()

/** Swarm's headline (hand-off 53): today, explained, and this week's bars. Hidden when receive-only. */
@Composable
fun KeptHeadline(v: MeshView) {
    if (!v.sharing) return
    val ctx = LocalContext.current
    rememberTick(10_000).value
    val week = KeptLedger.week(ctx)
    val today = week.last()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            if (today.people == 0) "Nobody has needed music from you yet today. When they do, it shows here."
            else "You kept ${today.people} ${if (today.people == 1) "person" else "people"} listening for ${keptTime(today.seconds)} today",
            style = if (today.people == 0) Tokens.Text.body.style(Ink.textSecondary) else Tokens.Text.screenTitle.style().copy(fontSize = 20.sp),
        )
        BasicText("Music you passed on that they played, counted on this phone only. Never named, never sent.", style = Tokens.Text.small.style(Ink.textSecondary))
        val max = week.maxOf { it.seconds }.coerceAtLeast(1)
        Canvas(Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = "This week: " + week.joinToString { "${it.people} people" } }) {
            val bw = size.width / 7f
            week.forEachIndexed { i, d ->
                val h = (size.height - 2.dp.toPx()) * d.seconds / max
                drawRoundRect(if (i == 6) Ink.accent else Ink.track, Offset(i * bw + bw * 0.2f, size.height - h.coerceAtLeast(2.dp.toPx())), Size(bw * 0.6f, h.coerceAtLeast(2.dp.toPx())), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            }
        }
    }
}

/** The mesh card's third line (hand-off 53): `Helped 3 listeners · 2 h 10 m today`, or null when there's nothing to say. */
fun helpedLine(ctx: Context, v: MeshView): String? {
    if (!v.sharing) return null
    val d = KeptLedger.week(ctx).last()
    return if (d.people == 0) null else "${d.people} ${if (d.people == 1) "listener" else "listeners"} · ${keptTime(d.seconds)} today"
}

/** Once a day, after the first hour passed on, only while the app is open (hand-off 53). */
@Composable
fun androidx.compose.foundation.layout.BoxScope.ThankYouNote() {
    val ctx = LocalContext.current
    val v = PearsEngine.view ?: return
    rememberTick(30_000).value
    var showing by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(Unit) { ReactPrefs.load(ctx) }
    if (showing == null && ReactPrefs.thanks && v.sharing) {
        val d = KeptLedger.week(ctx).last()
        val p = ctx.getSharedPreferences("react", Context.MODE_PRIVATE)
        if (d.seconds >= 3600 && p.getString("thanked", "") != d.date.toString()) {
            p.edit().putString("thanked", d.date.toString()).apply()
            showing = "You kept ${d.people} ${if (d.people == 1) "person" else "people"} listening today" to android.os.SystemClock.uptimeMillis()
        }
    }
    showing?.let { (text, at) -> if (android.os.SystemClock.uptimeMillis() - at < 6000) Toast(text, null, {}, Modifier.align(Alignment.BottomCenter)) }
}
