package org.beachsync.proto.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.beachsync.proto.MeshMoments
import org.beachsync.proto.MeshView
import org.beachsync.proto.PearsEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val STAGES = listOf(
    "Finding the station" to "Looking up its key on the peer-to-peer network",
    "Getting the set list" to "The station's signed catalog: what plays, when",
    "First music arrived" to "5 s ready, verified. Playback starts here",
    "Rest of the sets" to "Carries on in the background while you listen; usually 30 to 70 s on home Wi-Fi",
)

/**
 * First listen (hand-offs 35 and 36): the stages of starting a station, each ticked with its time, on Now playing until the first music has
 * arrived; then it folds away and the rest carries on in Buffer. The station's key words wait on the station directory (hand-off 33).
 */
@Composable
fun FirstListenCard(m: MeshMoments, openSwarm: () -> Unit) {
    val spin by rememberInfiniteTransition(label = "first").animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    var keepTrying by remember { mutableStateOf(false) }
    val t = m.elapsedS
    val current = m.stageS.indexOfFirst { it == null }
    val lost = m.stageS[0] == null && t >= 60
    Card(Modifier.semantics { contentDescription = "Starting ${PearsEngine.STATION_NAME}: ${STAGES.getOrNull(current)?.first ?: "done"}, ${"%.1f".format(t)} s." }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Ink.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp)), Alignment.Center) { BasicText("BS", style = Tokens.Text.bodyStrong.style(Ink.accent)) }
            Spacer(Modifier.width(10.dp))
            BasicText(PearsEngine.STATION_NAME, Modifier.weight(1f), Tokens.Text.bodyStrong.style())
            // The ring fills over the usual 8 s.
            Box(Modifier.size(56.dp), Alignment.Center) {
                Canvas(Modifier.size(56.dp)) {
                    val w = 3.dp.toPx()
                    drawArc(Ink.grid, 0f, 360f, false, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w), style = Stroke(w))
                    drawArc(if (t > 20) Ink.warn else Ink.accent, -90f, (360f * (t / 8.0).coerceAtMost(1.0)).toFloat(), false, Offset(w, w), Size(size.width - 2 * w, size.height - 2 * w), style = Stroke(w, cap = StrokeCap.Round))
                }
                BasicText("${"%.1f".format(t)} s", style = Tokens.Text.valueSmall.style())
            }
        }
        BasicText("usually 7 to 8 s", Modifier.fillMaxWidth(), Tokens.Text.small.style(Ink.textSecondary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
        STAGES.forEachIndexed { i, (title, detail) ->
            val done = m.stageS[i]
            val now = i == current && !lost
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp), Alignment.Center) {
                    when {
                        done != null -> Box(Modifier.size(20.dp).border(2.dp, Ink.accent, androidx.compose.foundation.shape.CircleShape), Alignment.Center) { BasicText("✓", style = Tokens.Text.small.style(Ink.accent)) }
                        now -> Canvas(Modifier.size(18.dp)) { drawArc(Ink.accent, spin, 270f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round)) }
                        else -> Box(Modifier.size(18.dp).border(1.5.dp, Ink.faint, androidx.compose.foundation.shape.CircleShape))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(title, style = Tokens.Text.bodyStrong.style(if (done != null || now) Ink.text else Ink.muted))
                    BasicText(detail, style = Tokens.Text.small.style(if (done != null || now) Ink.textSecondary else Ink.muted))
                }
                done?.let { BasicText("${"%.1f".format(it)} s", style = Tokens.Text.valueSmall.style(Ink.textSecondary)) }
            }
        }
        when {
            lost -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("Can't find ${PearsEngine.STATION_NAME} right now.", style = Tokens.Text.bodyStrong.style())
                BasicText("Check you're online; if you are, the station may be off and no listener has it yet. Brocast keeps looking in the background, and this card ticks on when it appears.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            t > 20 && !keepTrying -> Column(
                Modifier.fillMaxWidth().border(1.dp, Ink.warn, RoundedCornerShape(Tokens.Radius.card.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BasicText("Taking longer than usual · ${t.toInt()} s", style = Tokens.Text.bodyStrong.style(Ink.warn))
                BasicText(
                    when (current) {
                        0 -> "Still looking up the station's key. Sometimes this network blocks peer-to-peer."
                        1 -> "Found the station, but no one has sent its set list yet. Sometimes this network blocks peer-to-peer."
                        else -> "Found the set list, but the first music hasn't arrived yet."
                    },
                    style = Tokens.Text.small.style(Ink.textSecondary),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BasicText("Keep trying", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { keepTrying = true }.padding(vertical = 12.dp), Tokens.Text.body.style(Ink.accent))
                    BasicText("What's happening?", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = openSwarm).padding(vertical = 12.dp), Tokens.Text.body.style(Ink.accent))
                }
            }
        }
    }
}

private val SINCE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun rate(kbps: Int) = if (kbps >= 8000) "%.1f MB/s".format(kbps / 8000.0) else "${(kbps + 4) / 8} kB/s"

/**
 * The Mesh card on Now playing (hand-off 37): status, a small live map, and Connected, In and Passed on. Tap opens Swarm. With the station
 * offline it takes the accent border and tint, in place of the banner Mesh moments had. Shown only while the engine runs.
 */
@Composable
fun MeshCard(v: MeshView, openSwarm: () -> Unit) {
    val phase by rememberInfiniteTransition(label = "meshcard").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val m = v.moments
    val offline = m?.stationOfflineSinceMs != null
    val listeners = v.listenerPeers
    val starting = m != null && !m.firstMusicDone
    val (status, statusColor) = when {
        starting -> "STARTING" to Ink.accent
        offline -> "FROM LISTENERS" to Ink.accent
        !v.sharing -> "RECEIVE-ONLY" to Ink.textSecondary
        listeners.isNotEmpty() -> "SHARING" to Ink.accent
        else -> "RECEIVING" to Ink.accent
    }
    val total = v.stations.size + listeners.size
    val relayed = v.peers.count { it.path == "relayed" }
    val connected = when {
        starting -> "stage ${m!!.stageS.indexOfFirst { it == null } + 1} of 4 · ${"%.1f".format(m.elapsedS)} s"
        offline -> "${listeners.size} · all listeners"
        total == 0 -> "0 · looking"
        listeners.isEmpty() -> "$total · the station"
        listeners.size <= 2 -> "$total · " + (if (v.stations.isNotEmpty()) "station + " else "") + listeners.joinToString(" and ") { it.key.take(4) }
        else -> "$total · $relayed relayed"
    }
    val inKbps = v.peers.sumOf { it.toYouKbps }
    val fromListeners = listeners.sumOf { it.toYouKbps }
    val inLine = when {
        inKbps == 0 -> "—"
        fromListeners == 0 -> "${rate(inKbps)}  from the station"
        else -> "${rate(inKbps)}  ${100 * fromListeners / inKbps}% listeners"
    }
    val passed = "${v.upBytes / 1_000_000L} MB" + (m?.let { " since " + SINCE.format(Instant.ofEpochMilli(it.startedMs).atZone(ZoneId.systemDefault())) } ?: " today")
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        Modifier.fillMaxWidth().background(if (offline) Ink.accent.copy(alpha = 0.10f) else Ink.surface, shape)
            .border(if (offline) 1.dp else Tokens.Stroke.card.dp, if (offline) Ink.accent else Ink.border, shape)
            .clickable(role = Role.Button, onClick = openSwarm).padding(12.dp)
            .semantics { contentDescription = "Mesh, $status. Connected $connected. In $inLine. Passed on $passed. Opens Swarm." },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("MESH", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            BasicText("● $status", style = Tokens.Text.cardLabel.style(statusColor))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(104.dp, 62.dp)) {
                val me = Offset(size.width / 2, size.height / 2 + 4.dp.toPx())
                val st = Offset(10.dp.toPx(), 9.dp.toPx())
                fun moving(from: Offset, to: Offset, k: Int) {
                    val t = (phase + k * 0.23f) % 1f
                    val p = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                    drawCircle(Ink.accent.copy(alpha = if (t < 0.2f) t / 0.2f else if (t > 0.8f) (1 - t) / 0.2f else 1f), 1.8.dp.toPx(), p)
                }
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))
                if (v.stations.isNotEmpty() || offline) {
                    drawLine(if (offline) Ink.faint else Ink.textSecondary, st, me, 1.dp.toPx(), pathEffect = if (offline) dash else null)
                    val sq = 9.dp.toPx()
                    if (offline) drawRect(Ink.textSecondary, Offset(st.x - sq / 2, st.y - sq / 2), Size(sq, sq), style = Stroke(1.dp.toPx(), pathEffect = dash))
                    else { drawRect(Ink.text, Offset(st.x - sq / 2, st.y - sq / 2), Size(sq, sq)); if (v.stations.any { it.toYouKbps > 0 }) moving(st, me, 0) }
                }
                listeners.take(8).forEachIndexed { i, p ->
                    val a = Math.toRadians(-20.0 + i * 360.0 / maxOf(1, minOf(8, listeners.size)))
                    val near = if (p.path == "lan") 0.6f else 1f
                    val pos = Offset(me.x + 38.dp.toPx() * near * Math.cos(a).toFloat(), me.y + 22.dp.toPx() * near * Math.sin(a).toFloat())
                    drawLine(Ink.textSecondary, pos, me, 1.dp.toPx(), pathEffect = if (p.path == "relayed") dash else null)
                    if (p.path == "lan") drawCircle(Ink.accent.copy(alpha = 0.35f), 4.dp.toPx(), pos)
                    drawCircle(Ink.textSecondary, 4.dp.toPx(), pos, style = Stroke(1.dp.toPx()))
                    if (p.toYouKbps > 0) moving(pos, me, i + 1)
                    if (p.fromYouKbps > 0) moving(me, pos, i + 5)
                }
                drawCircle(Ink.accent.copy(alpha = 0.2f), 9.dp.toPx(), me)
                drawCircle(Ink.accent, 5.dp.toPx(), me)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                for ((label, value) in listOf("Connected" to connected, "In" to inLine, "Passed on" to passed)) Row {
                    BasicText(label, Modifier.width(76.dp), Tokens.Text.small.style(Ink.textSecondary))
                    BasicText(value, style = Tokens.Text.small.style())
                }
            }
        }
    }
}
