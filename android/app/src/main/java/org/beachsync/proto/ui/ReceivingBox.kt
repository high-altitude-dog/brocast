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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.LibraryStore
import java.util.Locale

/**
 * Receiving (hand-off 57): under the clock while a set is arriving. A map of its blocks (160 squares), who is sending it, the rate over the
 * last minute and when it will be ready. Folds away 4 s after the set is complete. Tap opens Swarm.
 * Until the engine reports per-block sources (MeshSupply), the map fills in order and the sender is "the station" or "listeners" as a whole.
 */
@Composable
internal fun ReceivingBox(lib: LibraryStore?, openSwarm: () -> Unit, openWhere: (String) -> Unit = {}) {
    lib ?: return
    rememberTick(250).value
    val now = android.os.SystemClock.uptimeMillis()
    val hist = remember { ArrayDeque<Pair<Long, Long>>() }
    var doneAt by remember { mutableStateOf<Long?>(null) }
    var lastTitle by remember { mutableStateOf("") }
    val active = lib.partPct >= 0 && lib.partTotal > 0
    if (active) {
        doneAt = null; lastTitle = lib.partTitle
        if (hist.lastOrNull()?.let { now - it.first >= 1000 } != false) { hist.addLast(now to lib.partBytes); while (hist.size > 61) hist.removeFirst() }
    } else if (lastTitle.isNotEmpty() && doneAt == null) doneAt = now
    val ready = !active && doneAt != null && now - doneAt!! < 4000
    if (!active && !ready) { hist.clear(); return }
    val total = lib.partTotal.coerceAtLeast(1)
    val frac = if (ready) 1f else (lib.partBytes.toFloat() / total).coerceIn(0f, 1f)
    val rates = hist.zipWithNext { a, b -> (b.second - a.second).coerceAtLeast(0) * 1000.0 / (b.first - a.first).coerceAtLeast(1) }
    val rate = rates.takeLast(5).average().takeIf { !it.isNaN() } ?: 0.0
    val eta = if (rate > 1) ((total - lib.partBytes) / rate).toLong() else -1
    val metered = lib.status.contains("mobile", true) || lib.status.contains("paused", true)
    val from = if (lib.partMesh) "listeners, peer-to-peer" else "the station"
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().background(Ink.surface, shape).border(1.dp, Ink.accent.copy(alpha = 0.5f), shape).clickable(role = Role.Button, onClick = openSwarm)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
            .semantics { contentDescription = if (ready) "${lastTitle} is ready." else "Receiving ${lastTitle} from $from, ${(frac * 100).toInt()} percent. Opens Swarm." },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (ready) "READY" else if (metered) "PAUSED" else "RECEIVING", style = Tokens.Text.cardLabel.style(Ink.accent).copy(fontSize = 10.sp))
            Spacer(Modifier.width(8.dp))
            BasicText(lastTitle, Modifier.weight(1f), Tokens.Text.small.style(Ink.text).copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!ready) {
                BasicText(mbps(rate), style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 11.sp))
                Spacer(Modifier.width(6.dp))
                // The last minute's rate.
                Canvas(Modifier.size(width = 44.dp, height = 14.dp)) {
                    if (rates.size >= 2) {
                        val hi = rates.max().coerceAtLeast(1.0)
                        val p = Path()
                        rates.forEachIndexed { i, r -> val x = size.width * i / (rates.size - 1); val y = size.height * (1 - (r / hi).toFloat()); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
                        drawPath(p, Ink.accent, style = Stroke(1.2.dp.toPx()))
                    }
                }
            }
        }
        // The block map: 160 squares, 20 × 8. Arrived (station colour), just arrived (brighter, 2 s), asked for (outlined), still to come.
        val recent = hist.firstOrNull { now - it.first <= 2000 }?.second?.let { (it.toFloat() / total).coerceIn(0f, 1f) } ?: frac
        // Tap the map: Where it came from (hand-off 58); elsewhere in the box still opens Swarm.
        Canvas(Modifier.fillMaxWidth().height(70.dp).clickable(role = Role.Button) { lib.partId.takeIf { it.isNotEmpty() }?.let(openWhere) ?: openSwarm() }) {
            val cols = 20; val rowsN = 8; val gap = 2.dp.toPx()
            val w = (size.width - gap * (cols - 1)) / cols; val h = (size.height - gap * (rowsN - 1)) / rowsN
            val have = (frac * 160).toInt(); val fresh = (recent * 160).toInt()
            for (i in 0 until 160) {
                val o = Offset((i % cols) * (w + gap), (i / cols) * (h + gap)); val sz = Size(w, h); val cr = CornerRadius(1.5.dp.toPx())
                when {
                    i < fresh -> drawRoundRect(Ink.accent.copy(alpha = 0.75f), o, sz, cr)
                    i < have -> drawRoundRect(Ink.accent, o, sz, cr)
                    !metered && i < have + 4 -> drawRoundRect(Ink.accent.copy(alpha = 0.6f), o, sz, cr, style = Stroke(1.dp.toPx()))
                    else -> drawRoundRect(Ink.track, o, sz, cr)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (lib.partMesh) "From listeners, peer-to-peer" else "From the station", Modifier.weight(1f), Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 11.sp))
            BasicText(
                when {
                    ready -> "Every block checked ✓"
                    metered -> "Waits for Wi-Fi"
                    else -> "${(frac * 160).toInt()} of 160 checked" + (if (eta >= 0) " · ready in ${if (eta >= 60) "${eta / 60} min" else "$eta s"}" else "")
                },
                style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 11.sp),
            )
        }
    }
}

private fun mbps(bytesPerS: Double) = if (bytesPerS >= 1e6) String.format(Locale.ROOT, "%.1f MB/s", bytesPerS / 1e6) else "${(bytesPerS / 1e3).toInt()} kB/s"
