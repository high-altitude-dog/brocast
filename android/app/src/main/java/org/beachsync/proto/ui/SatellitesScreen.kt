package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.FlowTime
import org.beachsync.proto.GpsFitter
import org.beachsync.proto.fmtMillis
import org.beachsync.proto.RangeTrack
import org.beachsync.proto.Sat
import org.beachsync.proto.SatFacts
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** What sets the limit on this phone's GPS clock. Hand-off 13. */
private enum class Limit { CHIP, SIGNAL, LINK, NO_RAW }

/** The shortest tree joining the points (Prim's), as index pairs: the constellation lines. */
internal fun mstEdges(pts: List<Offset>): List<Pair<Int, Int>> {
    if (pts.size < 2) return emptyList()
    val inTree = BooleanArray(pts.size).also { it[0] = true }
    val edges = ArrayList<Pair<Int, Int>>()
    repeat(pts.size - 1) {
        var bi = -1; var bj = -1; var best = Float.MAX_VALUE
        for (i in pts.indices) if (inTree[i]) for (j in pts.indices) if (!inTree[j]) {
            val d = hypot(pts[i].x - pts[j].x, pts[i].y - pts[j].y)
            if (d < best) { best = d; bi = i; bj = j }
        }
        edges.add(bi to bj); inTree[bj] = true
    }
    return edges
}

private fun compass(az: Float): String {
    val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return names[(((az % 360f) + 360f) % 360f / 22.5f + 0.5f).toInt() % 16]
}

@Composable
fun SatellitesScreen(state: BenchState, actions: BenchActions, back: () -> Unit, openSignals: () -> Unit) {
    val boot = rememberTick(1000L).value
    // The satellites run from app open (the idle clock) as well as during a run, and only while the switch is on.
    val gps = (state.session?.gps ?: state.idleGps)?.takeIf { it.active }
    var picked by remember { mutableStateOf<String?>(null) }
    val tracks = remember { HashMap<String, RangeTrack>() }
    // 3D or Flat (hand-off 42): the choice is remembered.
    val prefs = LocalContext.current.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
    var flat by remember { mutableStateOf(prefs.getBoolean("sky_flat", false)) }
    val setFlat = { f: Boolean -> flat = f; prefs.edit().putBoolean("sky_flat", f).apply() }
    Screen {
        ScreenHeader("Satellites", back)
        if (gps == null) {
            Card {
                BasicText("Satellites aren't running.", style = Tokens.Text.bodyStrong.style())
                BasicText("Turn on Use satellites to start them. This screen then shows your chip and your sky.", style = Tokens.Text.body.style())
            }
        } else {
            val stamp = gps.erUncNs.takeIf { !it.isNaN() }?.div(1e6) ?: 0.0
            val signal = gps.biasUncNs.takeIf { !it.isNaN() }?.div(1e6) ?: 0.0
            val gold = gps.gold
            val link = if (gold == false) GpsFitter.SILVER_LINK_NS / 1e6 else 0.01
            val noRaw = !gps.reportsRaw
            val largest = when {
                noRaw -> Limit.NO_RAW
                stamp >= signal && stamp >= link -> Limit.CHIP
                signal >= link -> Limit.SIGNAL
                else -> Limit.LINK
            }
            val total = FlowTime.gps?.errAt(boot)?.div(1e6)?.takeIf { it.isFinite() } ?: (2 * max(stamp, max(signal, link)))
            // Under 1 ms in total: nothing is holding it back, so no row is tagged LIMIT (hand-off 13, "Very accurate").
            val accurate = !noRaw && total < 1.0
            Verdict(largest, total, stamp, accurate)
            val sats = gps.sats
            // Each satellite's distance, once a second while this screen is open, for the rate on its card.
            remember(boot) { sats.forEach { s -> tracks.getOrPut(s.label) { RangeTrack() }.add(boot / 1e9, rangeOf(s)) } }
            val used = sats.count { it.used }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BasicText("YOUR SKY", Modifier.weight(1f), Tokens.Text.cardLabel.style())
                BasicText("$used in use of ${sats.size}", style = Tokens.Text.valueSmall.style(Ink.accent))
            }
            if (flat) {
                BasicText("Show in 3D", Modifier.clickable { setFlat(false) }.padding(vertical = 6.dp), Tokens.Text.body.style(Ink.accent))
                Sky(sats, picked, { picked = it })
            } else Sky3D(sats, gps.position, picked, { picked = it }) { setFlat(true) }
            BasicText("● GPS   ◆ Galileo   ▲ GLONASS   ■ BeiDou   ○ in view, not used", style = Tokens.Text.small.style(Ink.textSecondary))
            if (flat) BasicText("Tap any satellite for its details. Lines join the ones in use, like a constellation.", style = Tokens.Text.small.style(Ink.textSecondary))
            picked?.let { label -> sats.firstOrNull { it.label == label }?.let { SatCard(it, tracks[label]?.rateKmS()) { picked = null } } }
            if (!noRaw) {
                BasicText("WHAT LIMITS IT", Modifier.padding(top = 4.dp), Tokens.Text.cardLabel.style())
                Card {
                    val top = max(stamp, max(signal, link)).coerceAtLeast(1e-6)
                    LimitRow("Chip time stamp", "the chip's own declared uncertainty", stamp, top, !accurate && largest == Limit.CHIP)
                    LimitRow("Satellite signal", "how well the satellites agree", signal, top, !accurate && largest == Limit.SIGNAL)
                    LimitRow(
                        "Link to the Brocast clock", if (gold == false) "Silver: stamped when the report arrives" else "Gold: stamped with the boot clock",
                        link, top, !accurate && largest == Limit.LINK,
                    )
                    BasicText("The total, about ±${fmtMillis(total)}, is a 95% range; the chip stamp's ±${fmtMillis(stamp)} is one standard deviation.", style = Tokens.Text.small.style())
                }
                Card {
                    BasicText("Will more satellites help?", style = Tokens.Text.bodyStrong.style())
                    BasicText(
                        if (accurate) "Not needed. Timing is already under 1 ms, and nothing is holding it back." else when (largest) {
                            Limit.SIGNAL -> "Yes. The signal is the limit, so more satellites or a clearer sky will help. Open sky is best."
                            Limit.CHIP -> "No, not on this phone. The limit is inside the chip. With 9 satellites or 15, it stays at about ±${fmtMillis(total)}. Open sky helps only when the signal is the limit."
                            else -> "No, not on this phone. The link between the chip and the clock is the limit, and more satellites won't change it."
                        },
                        style = Tokens.Text.body.style(),
                    )
                }
            }
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(if (gold == false) "Silver link" else if (gold == true) "Gold link" else "Link", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
                    if (gold != null) BasicText(if (gold) "GOLD" else "SILVER", Modifier.border(1.dp, Ink.accent, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp), Tokens.Text.cardLabel.style(Ink.accent))
                }
                BasicText(
                    "Gold: the chip stamps each report with the phone's boot clock, so GPS time and the Brocast clock line up within microseconds. Silver: it doesn't, so Brocast estimates the link, which adds about a millisecond or more.",
                    style = Tokens.Text.body.style(),
                )
                val leap = when (gps.leapFromChip) { true -> "leap seconds from the chip"; false -> "leap seconds assumed"; null -> "leap seconds not known yet" }
                BasicText("${gps.chip} · raw clock reports: ${if (gps.reportsRaw) "yes" else "no"} · $leap", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        SatellitesRow(state.satellites, actions::setSatellites, "Off: Privacy mode. Brocast uses internet time instead.")
        BasicText(
            "Compare with internet time in Time signals",
            Modifier.clickable(onClick = openSignals).padding(vertical = 8.dp),
            Tokens.Text.body.style(Ink.accent),
        )
    }
}

@Composable
private fun Verdict(limit: Limit, total: Double, stamp: Double, accurate: Boolean) {
    val signal = accurate || limit == Limit.SIGNAL
    val title = if (accurate) "Your phone's GPS clock is accurate to about ±${fmtMillis(total)}." else when (limit) {
        Limit.CHIP -> "Your phone's GPS chip limits timing to about ±${fmtMillis(total)}."
        Limit.SIGNAL -> "Timing is limited by the satellite signal: about ±${fmtMillis(total)}."
        Limit.LINK -> "Your phone links GPS time to its clock less directly: about ±${fmtMillis(total)}."
        Limit.NO_RAW -> "This phone's GPS chip doesn't share its clock."
    }
    val reason = if (accurate) "Nothing is holding it back." else when (limit) {
        Limit.CHIP -> "The chip says each time stamp it gives the Brocast clock may be off by up to ${fmtMillis(stamp)}. Brocast can't do better than that, however many satellites it sees."
        Limit.SIGNAL -> "More satellites or a clearer sky will help."
        Limit.LINK -> "More satellites won't change it."
        Limit.NO_RAW -> "Brocast uses internet time instead. The sky still shows, but Use satellites can't improve timing on this phone."
    }
    val edge = if (signal) Ink.accent else Ink.warn
    Row(Modifier.fillMaxWidth().border(1.dp, edge, RoundedCornerShape(12.dp)).background(Ink.surface, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(if (signal) "✓" else "!", style = Tokens.Text.bodyStrong.style(edge))
        // Very accurate: one paragraph, "Nothing is holding it back." running on after the figure (user, 8 Nov), so the box is shorter.
        if (accurate) BasicText(
            androidx.compose.ui.text.buildAnnotatedString {
                append(title); append(" ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Ink.textSecondary, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal)); append(reason); pop()
            },
            style = Tokens.Text.bodyStrong.style(),
        )
        else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(reason, style = Tokens.Text.body.style(Ink.textSecondary))
        }
    }
}

@Composable
private fun LimitRow(title: String, detail: String, value: Double, top: Double, limit: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(title, style = Tokens.Text.bodyStrong.style())
                    if (limit) BasicText("LIMIT", Modifier.background(Ink.warn, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp), Tokens.Text.cardLabel.style(Ink.onAccent))
                }
                BasicText(detail, style = Tokens.Text.small.style())
            }
            BasicText("±${fmtMillis(value)}", style = Tokens.Text.valueSmall.style())
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Ink.track, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth((value / top).coerceIn(0.02, 1.0).toFloat()).height(6.dp).background(if (limit) Ink.warn else Ink.muted, RoundedCornerShape(3.dp)))
        }
    }
}

/** The sky: horizon circle, 30 and 60 degree rings, N E S W, one mark per satellite and the ones in use joined by their shortest tree. */
@Composable
private fun Sky(sats: List<Sat>, picked: String?, pick: (String?) -> Unit) {
    val measurer = rememberTextMeasurer()
    val used = sats.count { it.used }
    val label = "Sky view with ${sats.size} satellites, $used in use"
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).background(Ink.surface, RoundedCornerShape(12.dp)).semantics { contentDescription = label }
            .pointerInput(sats) {
                detectTapGestures { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = size.width / 2f - 28.dp.toPx()
                    val hit = sats.minByOrNull { hypot(skyAt(it, c, r).x - tap.x, skyAt(it, c, r).y - tap.y) }
                    val near = hit != null && hypot(skyAt(hit, c, r).x - tap.x, skyAt(hit, c, r).y - tap.y) <= 22.dp.toPx()
                    pick(if (near) hit.label else null)
                }
            },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.width / 2f - 28.dp.toPx()
        val thin = Stroke(1.dp.toPx())
        drawCircle(Ink.grid, r, c, style = thin)
        drawCircle(Ink.grid, r * 2f / 3f, c, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
        drawCircle(Ink.grid, r / 3f, c, style = Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
        drawLine(Ink.grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
        drawLine(Ink.grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
        val small = TextStyle(color = Ink.textSecondary, fontSize = 11.sp)
        fun text(t: String, at: Offset, style: TextStyle = small) {
            val m = measurer.measure(t, style)
            drawText(m, topLeft = Offset(at.x - m.size.width / 2f, at.y - m.size.height / 2f))
        }
        text("N", Offset(c.x, c.y - r - 14.dp.toPx())); text("S", Offset(c.x, c.y + r + 14.dp.toPx()))
        text("W", Offset(c.x - r - 14.dp.toPx(), c.y)); text("E", Offset(c.x + r + 14.dp.toPx(), c.y))
        // The shortest tree through the satellites in use (Prim's), as faint lines.
        val inUse = sats.filter { it.used }.map { skyAt(it, c, r) }
        for ((i, j) in mstEdges(inUse)) drawLine(Ink.accent.copy(alpha = 0.35f), inUse[i], inUse[j], 1.5.dp.toPx())
        // Labels go beside their mark, and move to another side where they'd run into one already placed.
        val placed = ArrayList<androidx.compose.ui.geometry.Rect>()
        val gap = 9.dp.toPx()
        for (s in sats) skyAt(s, c, r).let { p -> mark(s, p, if (s.used) Ink.accent else Ink.muted, s.used) }
        for (s in sats.sortedByDescending { it.used }) {
            val p = skyAt(s, c, r)
            val m = measurer.measure(s.label, small.copy(color = if (s.used) Ink.text else Ink.textSecondary))
            val w = m.size.width.toFloat(); val h = m.size.height.toFloat()
            val options = listOf(
                Offset(p.x + gap, p.y - h - 2f), Offset(p.x + gap, p.y + 2f), Offset(p.x - gap - w, p.y - h - 2f), Offset(p.x - gap - w, p.y + 2f),
                Offset(p.x - w / 2f, p.y - gap - h), Offset(p.x - w / 2f, p.y + gap),
                Offset(p.x + gap, p.y - h / 2f), Offset(p.x - gap - w, p.y - h / 2f),
            )
            val at = options.firstOrNull { o ->
                val box = androidx.compose.ui.geometry.Rect(o.x, o.y, o.x + w, o.y + h)
                placed.none { it.overlaps(box) }
            } ?: options[0]
            placed.add(androidx.compose.ui.geometry.Rect(at.x, at.y, at.x + w, at.y + h))
            drawText(m, topLeft = at)
            if (s.label == picked) drawCircle(Ink.text, 13.dp.toPx(), p, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun skyAt(s: Sat, c: Offset, r: Float): Offset {
    val dist = r * (90f - s.elevationDeg.coerceIn(0f, 90f)) / 90f
    val a = s.azimuthDeg * PI.toFloat() / 180f
    return Offset(c.x + dist * sin(a), c.y - dist * cos(a))
}

/** GPS circle, Galileo diamond, GLONASS triangle, BeiDou square. Filled when in use, outlined grey when not. */
private fun DrawScope.mark(s: Sat, p: Offset, color: Color, filled: Boolean) {
    val k = 6.dp.toPx()
    val path = Path()
    when (s.letter) {
        'E' -> { path.moveTo(p.x, p.y - k * 1.25f); path.lineTo(p.x + k * 1.25f, p.y); path.lineTo(p.x, p.y + k * 1.25f); path.lineTo(p.x - k * 1.25f, p.y); path.close() }
        'R' -> { path.moveTo(p.x, p.y - k * 1.2f); path.lineTo(p.x + k * 1.1f, p.y + k * 0.9f); path.lineTo(p.x - k * 1.1f, p.y + k * 0.9f); path.close() }
        'C' -> { path.addRect(androidx.compose.ui.geometry.Rect(p.x - k * 0.9f, p.y - k * 0.9f, p.x + k * 0.9f, p.y + k * 0.9f)) }
        else -> path.addOval(androidx.compose.ui.geometry.Rect(p.x - k, p.y - k, p.x + k, p.y + k))
    }
    if (filled) drawPath(path, color) else drawPath(path, color, style = Stroke(1.5.dp.toPx()))
}

@Composable
private fun SatCard(s: Sat, rateKmS: Double?, close: () -> Unit) {
    val orbit = SatFacts.orbit(s.system, s.svid)
    val fleet = SatFacts.fleet(s.system)
    val km = rangeOf(s)
    val ctx = LocalContext.current
    val craft = org.beachsync.proto.GnssTable.get(ctx)[s.label]
    val fleetToday = org.beachsync.proto.GnssTable.fleet(ctx, s.letter)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("● ", style = Tokens.Text.bodyStrong.style(if (s.used) Ink.accent else Ink.muted))
            BasicText(s.label, style = Tokens.Text.bodyStrong.style())
            BasicText("  ${s.systemName} · ${if (s.used) "in use" else "in view, not used"}", Modifier.weight(1f), Tokens.Text.small.style(Ink.textSecondary))
            BasicText("×", Modifier.clickable(onClick = close).padding(8.dp), Tokens.Text.bodyStrong.style())
        }
        // From the IGS table (hand-off 44): `GPS IIR-M · NAVSTAR 59 · launched Nov 2006`.
        craft?.let { c ->
            val launched = runCatching { java.time.LocalDate.parse(c.launched).let { d -> d.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.UK) + " " + d.year } }.getOrNull()
            BasicText(listOfNotNull(c.block.replaceFirst('-', ' ').ifEmpty { null }, c.name.ifEmpty { null }, launched?.let { "launched $it" }).joinToString(" · "), style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Column(Modifier.fillMaxWidth().border(1.dp, Ink.border, RoundedCornerShape(10.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("DISTANCE FROM YOU, NOW", style = Tokens.Text.cardLabel.style())
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText("%,d".format(Locale.ROOT, km.toInt()), style = Tokens.Text.value.style().copy(fontSize = 30.sp))
                BasicText(" km", Modifier.weight(1f), Tokens.Text.body.style(Ink.textSecondary))
                rateKmS?.let { r ->
                    BasicText(if (r < 0) "▼ %.1f km/s closer".format(Locale.ROOT, -r) else "▲ %.1f km/s farther".format(Locale.ROOT, r), style = Tokens.Text.valueSmall.style(if (r < 0) Ink.accent else Ink.textSecondary))
                }
            }
            BasicText("Its signal left %.1f ms ago; the phone allows for that.".format(Locale.ROOT, km / SatFacts.C_KM_S * 1000), style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { BasicText("HEIGHT", style = Tokens.Text.cardLabel.style()); BasicText("%.0f°".format(Locale.ROOT, s.elevationDeg), style = Tokens.Text.valueSmall.style()) }
            Column { BasicText("DIRECTION", style = Tokens.Text.cardLabel.style()); BasicText("%.0f° %s".format(Locale.ROOT, s.azimuthDeg, compass(s.azimuthDeg)), style = Tokens.Text.valueSmall.style()) }
            Column { BasicText("SIGNAL", style = Tokens.Text.cardLabel.style()); BasicText("%.0f dB-Hz".format(Locale.ROOT, s.cn0DbHz), style = Tokens.Text.valueSmall.style()) }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).background(Ink.track, RoundedCornerShape(3.dp))) {
            Box(Modifier.fillMaxWidth((s.cn0DbHz / 50f).coerceIn(0.02f, 1f)).height(6.dp).background(Ink.accent, RoundedCornerShape(3.dp)))
        }
        fleet?.let { f ->
            FactRow("BUILT IN") {
                for ((code, name) in f.countries) Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(org.beachsync.proto.flagOf(code), style = Tokens.Text.body.style().copy(fontSize = 20.sp))
                    BasicText("  $name", style = Tokens.Text.body.style())
                }
                BasicText(f.operator, style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        FactRow("ORBIT") {
            BasicText("${orbit.name} · ${"%,d".format(Locale.ROOT, orbit.heightKm)} km up", style = Tokens.Text.body.style())
            BasicText(orbit.words, style = Tokens.Text.small.style(Ink.textSecondary))
        }
        FactRow("SPEED") {
            BasicText("%.1f km/s".format(Locale.ROOT, orbit.speedKmS), style = Tokens.Text.valueSmall.style())
            BasicText("about ${"%,d".format(Locale.ROOT, (orbit.speedKmS * 3600 / 10).toInt() * 10)} km/h", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        craft?.massKg?.let { m ->
            FactRow("WEIGHT") {
                BasicText("%,d kg".format(Locale.ROOT, m.toInt()), style = Tokens.Text.valueSmall.style())
                BasicText("in orbit today", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        if (fleetToday > 0) FactRow("FLEET") {
            BasicText("one of $fleetToday ${s.systemName} satellites", style = Tokens.Text.body.style())
            BasicText("with a signal number today", style = Tokens.Text.small.style(Ink.textSecondary))
        } else fleet?.let { f -> FactRow("FLEET") { BasicText("one of ${f.size} ${s.systemName} satellites", style = Tokens.Text.body.style()) } }
    }
    BasicText(
        "Distance updates every second from where the satellite is in your sky and the height of its orbit, worked out on this phone. Craft, weight and fleet come from the IGS satellite table in the app (International GNSS Service satellite metadata, DLR).",
        style = Tokens.Text.small.style(Ink.textSecondary),
    )
}

@Composable
private fun FactRow(label: String, body: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        BasicText(label, Modifier.width(84.dp).padding(top = 2.dp), Tokens.Text.cardLabel.style())
        Column(Modifier.weight(1f)) { body() }
    }
}
