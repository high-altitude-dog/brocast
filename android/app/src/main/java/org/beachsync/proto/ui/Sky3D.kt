package org.beachsync.proto.ui

import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.Sat
import org.beachsync.proto.SatFacts
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Satellites in 3D (hand-offs 42 and 45), drawn on a Compose Canvas with a plain perspective camera.
 * Scene frame, in km: origin at the Earth's centre, x east, y north, z up through you, so you stand at (0, 0, R).
 * The camera sits behind you, 7° above your horizon, faces the way [heading] says (0 = north) and pivots on the axis through you.
 * Zoom runs 0 (your sky: satellites on a dome 1.55 × the Earth) to 1 (Orbits: true distances) to 2 (Whole Earth).
 */

private const val R = SatFacts.EARTH_KM
private const val DOME = 1.55 * R
private const val FOV_DEG = 45.0
private const val TILT0 = 7f
private const val TOUR_MS = 6000L
private val LEVELS = listOf("YOUR SKY", "ORBITS", "WHOLE EARTH")

/** The land map's dots as unit vectors in the scene frame, for a phone at [lat], [lon]. */
private fun landInScene(raw: ShortArray, lat: Double, lon: Double): FloatArray {
    val n = raw.size / 2
    val out = FloatArray(n * 3)
    val f0 = Math.toRadians(lat); val l0 = Math.toRadians(lon)
    val sf = sin(f0); val cf = cos(f0); val sl = sin(l0); val cl = cos(l0)
    for (i in 0 until n) {
        val f = Math.toRadians(raw[2 * i] / 100.0); val l = Math.toRadians(raw[2 * i + 1] / 100.0)
        val x = cos(f) * cos(l); val y = cos(f) * sin(l); val z = sin(f)
        out[3 * i] = (-sl * x + cl * y).toFloat()
        out[3 * i + 1] = (-sf * cl * x - sf * sl * y + cf * z).toFloat()
        out[3 * i + 2] = (cf * cl * x + cf * sl * y + sf * z).toFloat()
    }
    return out
}

/** A point on the Earth-fixed unit sphere at [lat], [lon] in the scene frame of a phone at [lat0], [lon0]. */
private fun ecefToScene(lat: Double, lon: Double, lat0: Double, lon0: Double): DoubleArray {
    val f0 = Math.toRadians(lat0); val l0 = Math.toRadians(lon0)
    val f = Math.toRadians(lat); val l = Math.toRadians(lon)
    val x = cos(f) * cos(l); val y = cos(f) * sin(l); val z = sin(f)
    return doubleArrayOf(-sin(l0) * x + cos(l0) * y, -sin(f0) * cos(l0) * x - sin(f0) * sin(l0) * y + cos(f0) * z, cos(f0) * cos(l0) * x + cos(f0) * sin(l0) * y + sin(f0) * z)
}

private object LandDots {
    @Volatile private var raw: ShortArray? = null
    fun get(ctx: Context): ShortArray = raw ?: runCatching {
        val b = ctx.assets.open("land-dots.bin").use { it.readBytes() }
        ShortArray(b.size / 2).also { ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }.getOrDefault(ShortArray(0)).also { raw = it }
}

/** The camera for one frame: position, basis and focal length, and how to put a scene point on screen. Each level frames the whole Earth. */
private class Cam(heading: Double, tilt: Double, zoom: Double, val w: Float, val h: Float) {
    val px: Double; val py: Double; val pz: Double
    private val fx: Double; private val fy: Double; private val fz: Double
    private val rx: Double; private val ry: Double
    private val ux: Double; private val uy: Double; private val uz: Double
    val focal = h / 2.0 / tan(Math.toRadians(FOV_DEG / 2))
    val dist: Double

    init {
        val z1 = zoom.coerceIn(0.0, 1.0); val z2 = (zoom - 1).coerceIn(0.0, 1.0)
        // Your sky: dome and Earth (−R to R + 1.55 R); Orbits: the satellites in view at true distance; Whole Earth: the geostationary belt.
        dist = R * 4.9 * exp(ln(11.0 / 4.9) * z1) * exp(ln(19.0 / 11.0) * z2)
        val tz = R * (0.78 + (2.0 - 0.78) * z1 + (0.2 - 2.0) * z2)        // the point looked at, on the axis through you
        val t = Math.toRadians(tilt); val hd = Math.toRadians(heading)
        val hx = sin(hd); val hy = cos(hd)
        px = -cos(t) * hx * dist; py = -cos(t) * hy * dist; pz = tz + sin(t) * dist
        val dx = -px; val dy = -py; val dz = tz - pz
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        fx = dx / dl; fy = dy / dl; fz = dz / dl
        val rl = hypot(fy, fx)
        rx = fy / rl; ry = -fx / rl
        ux = ry * fz; uy = -rx * fz; uz = rx * fy - ry * fx
    }

    /** Screen x, y and depth, or depth ≤ 0 when behind the camera. */
    fun project(x: Double, y: Double, z: Double, out: FloatArray, at: Int = 0) {
        val dx = x - px; val dy = y - py; val dz = z - pz
        val d = dx * fx + dy * fy + dz * fz
        out[at + 2] = d.toFloat()
        if (d <= 1.0) return
        out[at] = (w / 2 + focal * (dx * rx + dy * ry) / d).toFloat()
        out[at + 1] = (h / 2 - focal * (dx * ux + dy * uy + dz * uz) / d).toFloat()
    }

    /** Apparent radius on screen of a sphere of [radius] at the Earth's centre. */
    fun sphereRadius(radius: Double): Float {
        val dc = sqrt(px * px + py * py + pz * pz)
        return if (radius >= dc) 1e5f else (focal * tan(asin(radius / dc))).toFloat()
    }
}

/** A satellite's direction from you as a unit vector: x east, y north, z up. */
private fun look(s: Sat): DoubleArray {
    val e = Math.toRadians(s.elevationDeg.toDouble()); val a = Math.toRadians(s.azimuthDeg.toDouble())
    return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
}

internal fun rangeOf(s: Sat) = SatFacts.rangeKm(s.elevationDeg.toDouble(), SatFacts.orbit(s.system, s.svid).radiusKm)

/**
 * A circular orbit through [r] (scene frame, km from the Earth's centre) tilted [tiltDeg] to the equator, whose pole is [axis]: 120 points, 3° apart,
 * point 0 the satellite and rising index the way it's moving. Of the two such orbits, [rising] picks the one where it heads north.
 */
private fun orbitRing(r: DoubleArray, axis: DoubleArray, tiltDeg: Int, rising: Boolean): DoubleArray {
    val rl = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
    val rh = DoubleArray(3) { r[it] / rl }
    val sinLat = rh[0] * axis[0] + rh[1] * axis[1] + rh[2] * axis[2]
    val cosLat = sqrt((1 - sinLat * sinLat).coerceAtLeast(1e-9))
    val e1 = DoubleArray(3) { (axis[it] - sinLat * rh[it]) / cosLat }
    val e2 = doubleArrayOf(rh[1] * e1[2] - rh[2] * e1[1], rh[2] * e1[0] - rh[0] * e1[2], rh[0] * e1[1] - rh[1] * e1[0])
    val a = (cos(Math.toRadians(tiltDeg.toDouble())) / cosLat).coerceIn(-1.0, 1.0)
    val b = sqrt(1 - a * a) * (if (rising) 1 else -1)
    val n = DoubleArray(3) { a * e1[it] + b * e2[it] }
    val v = doubleArrayOf(n[1] * rh[2] - n[2] * rh[1], n[2] * rh[0] - n[0] * rh[2], n[0] * rh[1] - n[1] * rh[0])
    return DoubleArray(360).also { out -> for (i in 0 until 120) { val t = i * 2 * PI / 120; for (k in 0..2) out[3 * i + k] = rl * (rh[k] * cos(t) + v[k] * sin(t)) } }
}

/** True when the Earth stands between the camera and the point. */
private fun behindEarth(cam: Cam, x: Double, y: Double, z: Double): Boolean {
    val dx = x - cam.px; val dy = y - cam.py; val dz = z - cam.pz
    val dd = dx * dx + dy * dy + dz * dz
    val t = -(cam.px * dx + cam.py * dy + cam.pz * dz) / dd
    if (t <= 0 || t >= 1) return false
    val cx = cam.px + dx * t; val cy = cam.py + dy * t; val cz = cam.pz + dz * t
    return cx * cx + cy * cy + cz * cz < R * R
}

/**
 * How fast a satellite crosses your sky, radians of its orbit per hour: its own motion less the Earth's turning under the orbit (rough, for "rose 2 h ago"
 * and "sets in 1 h"). Near zero for geostationary ones, which never set.
 */
private fun skyRatePerHour(o: org.beachsync.proto.Orbit): Double {
    val periodH = 2 * PI * sqrt(o.radiusKm * o.radiusKm * o.radiusKm / SatFacts.GM) / 3600
    return 2 * PI / periodH - 2 * PI / 23.934 * cos(Math.toRadians(o.tiltDeg.toDouble()))
}

private fun hm(hours: Double): String { val m = (hours * 60).roundToInt(); return if (m >= 60) "${m / 60} h ${m % 60} m" else "$m m" }

@Composable
internal fun Sky3D(sats: List<Sat>, position: Pair<Double, Double>?, picked: String?, pick: (String?) -> Unit, flat: () -> Unit) {
    val ctx = LocalContext.current
    val measurer = rememberTextMeasurer()
    val reduceMotion = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    var heading by remember { mutableFloatStateOf(0f) }
    var tilt by remember { mutableFloatStateOf(TILT0) }
    var zoomTarget by remember { mutableFloatStateOf(0f) }
    var pinching by remember { mutableStateOf(false) }
    val zoom by animateFloatAsState(zoomTarget, if (pinching || reduceMotion) snap() else tween(450), label = "zoom")
    val raw = remember { LandDots.get(ctx) }
    val posKey = position?.let { (it.first * 10).roundToInt() to (it.second * 10).roundToInt() }
    val land = remember(posKey) { posKey?.let { landInScene(raw, it.first / 10.0, it.second / 10.0) } }
    val hits = remember { ArrayList<Pair<String, Offset>>() }
    val stars = remember { val r = java.util.Random(7); FloatArray(200) { r.nextFloat() } }
    val dotPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND } }
    // Which way each satellite is heading (north or south), from its latitude over time, for its orbit's direction.
    val firstLat = remember { HashMap<String, Double>() }
    val rising = remember { HashMap<String, Boolean>() }
    position?.let { pos ->
        val axis = ecefToScene(90.0, 0.0, pos.first, pos.second)
        for (s in sats) {
            val u = look(s); val d = rangeOf(s)
            val x = u[0] * d; val y = u[1] * d; val z = R + u[2] * d
            val lat = Math.toDegrees(asin(((x * axis[0] + y * axis[1] + z * axis[2]) / sqrt(x * x + y * y + z * z)).coerceIn(-1.0, 1.0)))
            val first = firstLat.getOrPut(s.label) { lat }
            if (abs(lat - first) > 0.3) rising[s.label] = lat > first
        }
    }
    // The tour (hand-off 45): one satellite's path at a time, 6 s each, until one is tapped. Reduce motion: the highest one, no tour.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { now = System.currentTimeMillis() } }
    var tour by remember { mutableStateOf<String?>(null) }
    var prevTour by remember { mutableStateOf<String?>(null) }
    var tourAt by remember { mutableLongStateOf(0L) }
    var lastPicked by remember { mutableStateOf<String?>(null) }
    if (picked != lastPicked) {
        // × or tapping empty sky: the path stays 3 s, then the tour goes on with another satellite.
        if (picked == null && lastPicked != null) { prevTour = null; tour = lastPicked; tourAt = now - (TOUR_MS - 3000) }
        lastPicked = picked
    }
    val pool = sats.filter { it.used }.let { u -> if (u.size >= 3) u else sats }
    if (reduceMotion) tour = sats.maxByOrNull { it.elevationDeg }?.label
    else if (picked == null && (tour == null || now - tourAt >= TOUR_MS || pool.none { it.label == tour })) {
        val next = pool.filter { it.label != tour }.randomOrNull() ?: pool.firstOrNull()
        prevTour = tour; tour = next?.label; tourAt = now
    }
    val shown = picked ?: tour
    val sinceSwitch = if (picked != null || reduceMotion) Long.MAX_VALUE else now - tourAt
    val used = sats.count { it.used }
    val sel = sats.firstOrNull { it.label == picked }
    val level = zoom.roundToInt().coerceIn(0, 2)
    val summary = "${sats.size} satellites in view, $used in use." + (sel?.let { " ${it.label} selected, ${"%,d".format(rangeOf(it).roundToInt())} km away." } ?: "")
    val slop = LocalViewConfiguration.current.touchSlop
    val side = Tokens.Space.screenPaddingSide.dp
    val boxHeight by animateDpAsState(if (zoom < 0.5f) 470.dp else 420.dp, if (reduceMotion) snap() else tween(300), label = "box")

    // Edge to edge: wider than the screen's column by its side padding (hand-off 45), hairlines top and bottom.
    Box(
        Modifier.layout { m, c ->
            val extra = (side * 2).roundToPx()
            val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
            layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
        }.height(boxHeight).background(Color(0xFF05070A)),
    ) {
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = summary }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var turning = false; var acc = Offset.Zero
                        do {
                            val ev = awaitPointerEvent()
                            val down = ev.changes.count { it.pressed }
                            if (down >= 2) {
                                pinching = true
                                val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                zoomTarget = (zoomTarget - ln(z.toDouble()).toFloat() * 1.8f).coerceIn(0f, 2f)
                                tilt = (tilt + pan.y * 0.25f).coerceIn(2f, 85f)
                                ev.changes.forEach { it.consume() }
                            } else if (down == 1) {
                                val pan = ev.calculatePan()
                                if (!turning) { acc += pan; if (abs(acc.x) > slop && abs(acc.x) > abs(acc.y)) turning = true }
                                if (turning) { heading = ((heading - pan.x * 0.3f) % 360f + 360f) % 360f; ev.changes.forEach { it.consume() } }
                            }
                        } while (ev.changes.any { it.pressed })
                        pinching = false
                    }
                }
                .pointerInput(sats) {
                    detectTapGestures(
                        onDoubleTap = { zoomTarget = (zoomTarget.roundToInt() + 1).coerceAtMost(2).toFloat() },
                        onTap = { tap ->
                            val hit = hits.minByOrNull { (it.second - tap).getDistance() }
                            pick(if (hit != null && (hit.second - tap).getDistance() <= 22.dp.toPx()) hit.first else null)
                        },
                    )
                },
        ) {
            drawLine(Ink.border, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(Ink.border, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
            val cam = Cam(heading.toDouble(), tilt.toDouble(), zoom.toDouble(), size.width, size.height)
            val p = FloatArray(3)
            val q = FloatArray(3)
            val s1 = (zoom.coerceIn(0f, 1f)).let { it * it * (3 - 2 * it) }
            val domeAlpha = 1f - s1
            for (i in 0 until stars.size / 2) drawCircle(Color.White.copy(alpha = 0.15f + 0.35f * stars[(i * 7) % stars.size]), 0.6.dp.toPx() + stars[(i * 3) % stars.size] * 0.6.dp.toPx(), Offset(stars[2 * i] * size.width, stars[2 * i + 1] * size.height))
            // Earth: dark sphere, cyan rim, the land dots and a 15° grid on the side facing the camera.
            cam.project(0.0, 0.0, 0.0, p)
            val ec = Offset(p[0], p[1]); val er = cam.sphereRadius(R)
            val net = Color(Tokens.Colors.laneNet)
            if (p[2] > 0) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF14202A), Color(0xFF070B10)), ec, er), er, ec)
                drawCircle(net.copy(alpha = 0.10f), er + 5.dp.toPx(), ec, style = Stroke(8.dp.toPx()))
                drawCircle(net.copy(alpha = 0.55f), er, ec, style = Stroke(1.2.dp.toPx()))
            }
            fun facing(x: Double, y: Double, z: Double) = x * (cam.px - x) + y * (cam.py - y) + z * (cam.pz - z) > 0
            if (land != null && position != null) {
                val n = land.size / 3
                val pts = FloatArray(n * 2); var k = 0
                for (i in 0 until n) {
                    val x = land[3 * i] * R; val y = land[3 * i + 1] * R; val z = land[3 * i + 2] * R
                    if (!facing(x, y, z)) continue
                    cam.project(x, y, z, p); if (p[2] <= 0) continue
                    pts[k++] = p[0]; pts[k++] = p[1]
                }
                dotPaint.color = Color(0xFF5E6E7A).toArgb(); dotPaint.strokeWidth = (1.6.dp.toPx() * (4.9 * R / cam.dist).toFloat().coerceIn(0.45f, 1f))
                drawIntoCanvas { it.nativeCanvas.drawPoints(pts, 0, k, dotPaint) }
                val (lat0, lon0) = position
                val grid = Ink.grid.copy(alpha = 0.35f)
                fun line(pts: List<DoubleArray>) {
                    for (j in 1 until pts.size) {
                        val a = pts[j - 1]; val b = pts[j]
                        if (!facing(a[0] * R, a[1] * R, a[2] * R) || !facing(b[0] * R, b[1] * R, b[2] * R)) continue
                        cam.project(a[0] * R, a[1] * R, a[2] * R, p); cam.project(b[0] * R, b[1] * R, b[2] * R, q)
                        if (p[2] > 0 && q[2] > 0) drawLine(grid, Offset(p[0], p[1]), Offset(q[0], q[1]), 0.7.dp.toPx())
                    }
                }
                for (lat in -75..75 step 15) line((0..72).map { ecefToScene(lat.toDouble(), it * 5.0 - 180, lat0, lon0) })
                for (lon in -180 until 180 step 15) line((0..36).map { ecefToScene(it * 5.0 - 90, lon.toDouble(), lat0, lon0) })
                val z2 = (zoom - 1f).coerceIn(0f, 1f)
                if (z2 > 0f) {
                    val rg = SatFacts.EARTH_EQ_KM + 35786.0
                    val ring = Path(); var started = false
                    for (i in 0..120) {
                        val v = ecefToScene(0.0, i * 3.0, lat0, lon0)
                        cam.project(v[0] * rg, v[1] * rg, v[2] * rg, p)
                        if (p[2] <= 0) { started = false; continue }
                        if (!started) ring.moveTo(p[0], p[1]) else ring.lineTo(p[0], p[1]); started = true
                    }
                    drawPath(ring, net.copy(alpha = 0.5f * z2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                }
            }
            if (zoom > 0.6f) for (hKm in listOf(20180.0, 23222.0, 35786.0)) drawCircle(Ink.grid.copy(alpha = 0.25f * ((zoom - 0.6f) / 0.4f).coerceAtMost(1f)), cam.sphereRadius(SatFacts.EARTH_EQ_KM + hKm), ec, style = Stroke(0.8.dp.toPx()))
            cam.project(0.0, 0.0, R, p)
            val you = Offset(p[0], p[1])
            // The dome: a bowl over the planet. Tinted horizon disc, 30° and 60° rings dashed, N E S W at the rim.
            if (domeAlpha > 0.02f) {
                fun ring(elDeg: Double): Path {
                    val path = Path(); val e = Math.toRadians(elDeg)
                    for (i in 0..72) {
                        val a = Math.toRadians(i * 5.0)
                        cam.project(DOME * cos(e) * sin(a), DOME * cos(e) * cos(a), R + DOME * sin(e), p)
                        if (i == 0) path.moveTo(p[0], p[1]) else path.lineTo(p[0], p[1])
                    }
                    return path
                }
                val horizon = ring(0.0)
                drawPath(horizon, Ink.accent.copy(alpha = 0.07f * domeAlpha))
                drawPath(horizon, Ink.accent.copy(alpha = 0.45f * domeAlpha), style = Stroke(1.dp.toPx()))
                for (e in listOf(30.0, 60.0)) drawPath(ring(e), Ink.accent.copy(alpha = 0.25f * domeAlpha), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
                for ((t, a) in listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0)) {
                    val r = Math.toRadians(a)
                    cam.project(DOME * 1.06 * sin(r), DOME * 1.06 * cos(r), R, p)
                    val m = measurer.measure(t, TextStyle(color = Ink.textSecondary.copy(alpha = domeAlpha), fontSize = 11.sp))
                    if (p[2] > 0) drawText(m, topLeft = Offset(p[0] - m.size.width / 2f, p[1] - m.size.height / 2f))
                }
            }
            // A point at its true place, pulled onto the dome as far as the zoom says.
            fun scene(x: Double, y: Double, z: Double): DoubleArray {
                val dz = z - R; val len = sqrt(x * x + y * y + dz * dz)
                val k = (DOME + (len - DOME) * s1) / len
                return doubleArrayOf(x * k, y * k, R + dz * k)
            }
            // One path: the shown satellite's (tour or tapped); the one before fades out over 400 ms, the new one draws rise to set over 800 ms.
            fun drawPathOf(label: String, alpha: Float, drawn: Float) {
                val s = sats.firstOrNull { it.label == label } ?: return
                val pos = position ?: return
                val orbit = SatFacts.orbit(s.system, s.svid)
                val u = look(s); val d = rangeOf(s)
                val ring = orbitRing(doubleArrayOf(u[0] * d, u[1] * d, R + u[2] * d), ecefToScene(90.0, 0.0, pos.first, pos.second), orbit.tiltDeg, rising[s.label] ?: true)
                val color = Ink.accent.copy(alpha = alpha)
                val w = (if (label == picked) 2f else 1.5f) * 1.dp.toPx()
                val dotted = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
                fun pt(i: Int): Offset? { val j = Math.floorMod(i, 120); val v = scene(ring[3 * j], ring[3 * j + 1], ring[3 * j + 2]); cam.project(v[0], v[1], v[2], p); return if (p[2] > 0) Offset(p[0], p[1]) else null }
                fun above(i: Int) = ring[3 * Math.floorMod(i, 120) + 2] > R
                if (s1 < 0.5f) {
                    // Your sky: where its orbit crosses your sky. Dotted since it rose, solid with an arrow to where it sets.
                    var back = 0; while (back < 119 && above(-(back + 1))) back++
                    var fwd = 0; while (fwd < 119 && above(fwd + 1)) fwd++
                    val upTo = (drawn * (back + fwd)).roundToInt()
                    val rose = Path(); val sets = Path()
                    var roseStarted = false; var setsStarted = false
                    for (i in -back..fwd) {
                        if (i + back > upTo) break
                        val o = pt(i) ?: continue
                        if (i <= 0) { if (!roseStarted) rose.moveTo(o.x, o.y) else rose.lineTo(o.x, o.y); roseStarted = true }
                        if (i >= 0) { if (!setsStarted) sets.moveTo(o.x, o.y) else sets.lineTo(o.x, o.y); setsStarted = true }
                    }
                    drawPath(rose, color, style = Stroke(w, pathEffect = dotted, cap = StrokeCap.Round))
                    drawPath(sets, color, style = Stroke(w, cap = StrokeCap.Round))
                    val rate = skyRatePerHour(orbit)
                    val small = TextStyle(color = Ink.textSecondary.copy(alpha = alpha), fontSize = 9.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    if (drawn >= 1f && fwd < 119) pt(fwd)?.let { e ->
                        pt(fwd - 1)?.let { b -> val a = atan2(e.y - b.y, e.x - b.x); val k = 7.dp.toPx()
                            drawLine(color, e, Offset(e.x - k * cos(a - 0.5f), e.y - k * sin(a - 0.5f)), w); drawLine(color, e, Offset(e.x - k * cos(a + 0.5f), e.y - k * sin(a + 0.5f)), w) }
                        if (rate > 0.02) { val m = measurer.measure("sets in ${hm(fwd * 3 * PI / 180 / rate)}", small); drawText(m, topLeft = Offset((e.x - m.size.width / 2f).coerceIn(4f, size.width - m.size.width - 4f), (e.y + 6.dp.toPx()).coerceAtMost(size.height - m.size.height - 4f))) }
                    }
                    if (drawn > 0f && back < 119) pt(-back)?.let { b ->
                        drawCircle(color, 3.dp.toPx(), b, style = Stroke(1.dp.toPx()))
                        if (rate > 0.02) { val m = measurer.measure("rose ${hm(back * 3 * PI / 180 / rate)} ago", small); drawText(m, topLeft = Offset((b.x - m.size.width / 2f).coerceIn(4f, size.width - m.size.width - 4f), (b.y + 6.dp.toPx()).coerceAtMost(size.height - m.size.height - 4f))) }
                    }
                    if (rate <= 0.02) pt(0)?.let { val m = measurer.measure("stays in your sky", small); drawText(m, topLeft = Offset(it.x - m.size.width / 2f, it.y + 14.dp.toPx())) }
                } else {
                    // Orbits and Whole Earth: its whole orbit, dotted behind the Earth.
                    var prev: Offset? = null
                    for (i in 0..(120 * drawn).roundToInt()) {
                        val j = Math.floorMod(i, 120)
                        val v = scene(ring[3 * j], ring[3 * j + 1], ring[3 * j + 2])
                        cam.project(v[0], v[1], v[2], p)
                        if (p[2] <= 0) { prev = null; continue }
                        val o = Offset(p[0], p[1]); val hidden = behindEarth(cam, v[0], v[1], v[2])
                        prev?.let { a -> if (!hidden || i % 2 == 0) drawLine(if (hidden) color.copy(alpha = alpha * 0.4f) else color, a, o, w) }
                        prev = o
                    }
                }
            }
            if (position != null) {
                if (sinceSwitch < 400) prevTour?.let { drawPathOf(it, 1f - sinceSwitch / 400f, 1f) }
                shown?.let { drawPathOf(it, 1f, if (sinceSwitch < 400) 0f else ((sinceSwitch - 400) / 800f).coerceIn(0f, 1f)) }
            }
            // Satellites: on the dome at their true direction, sliding out to their true distance as you zoom.
            hits.clear()
            val placed = ArrayList<Rect>()
            val drawnSats = sats.map { s ->
                val u = look(s); val d = rangeOf(s)
                val v = scene(u[0] * d, u[1] * d, R + u[2] * d)
                cam.project(v[0], v[1], v[2], p)
                Triple(s, Offset(p[0], p[1]), p[2])
            }.filter { it.third > 0 }.sortedByDescending { it.third }
            for ((s, at, depth) in drawnSats) {
                val near = (cam.dist / depth).toFloat().coerceIn(0.8f, 1.25f)
                mark3d(s, at, if (s.used) Ink.accent else Ink.muted, s.used, near)
                hits.add(s.label to at)
                if (s.label == shown) drawCircle(Ink.text, 13.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                val labelled = (s.used && zoom < 1.5f) || zoom < 0.5f
                if (!labelled || s.label == shown) continue
                val m = measurer.measure(s.label, TextStyle(color = if (s.used) Ink.text else Ink.textSecondary, fontSize = 10.sp))
                val w = m.size.width.toFloat(); val h = m.size.height.toFloat(); val g = 9.dp.toPx()
                val options = listOf(Offset(at.x + g, at.y - h - 2f), Offset(at.x - w / 2, at.y - g - h), Offset(at.x - g - w, at.y - h - 2f), Offset(at.x + g, at.y + 2f), Offset(at.x - w / 2, at.y + g))
                val o = options.firstOrNull { c -> placed.none { it.overlaps(Rect(c.x, c.y, c.x + w, c.y + h)) } } ?: options[0]
                placed.add(Rect(o.x, o.y, o.x + w, o.y + h)); drawText(m, topLeft = o)
            }
            // The pill on the shown satellite: "C21 · its path across your sky" on the tour, the distance to its left once tapped. Kept inside the box.
            shown?.let { label -> drawnSats.firstOrNull { it.first.label == label }?.let { (s, at, _) ->
                val text = if (label == picked) "%,d km".format(rangeOf(s).roundToInt()) else "$label · its path across your sky"
                val m = measurer.measure(text, TextStyle(color = Ink.text, fontSize = 10.sp))
                val pw = m.size.width + 12.dp.toPx(); val ph = m.size.height + 6.dp.toPx()
                val x0 = (if (label == picked) at.x - 18.dp.toPx() - pw else at.x + 18.dp.toPx()).coerceIn(4f, size.width - pw - 4f)
                val y0 = (at.y - ph / 2).coerceIn(4f, size.height - ph - 4f)
                drawRoundRect(Color(0xE6101418), Offset(x0, y0), androidx.compose.ui.geometry.Size(pw, ph), CornerRadius(ph / 2))
                drawRoundRect(Ink.accent, Offset(x0, y0), androidx.compose.ui.geometry.Size(pw, ph), CornerRadius(ph / 2), style = Stroke(1.dp.toPx()))
                drawText(m, topLeft = Offset(x0 + 6.dp.toPx(), y0 + 3.dp.toPx()))
            } }
            drawCircle(Color.White, 5.dp.toPx(), you)
            drawCircle(Color.Black, 5.dp.toPx(), you, style = Stroke(1.5.dp.toPx()))
            val ym = measurer.measure("You", TextStyle(color = Ink.text, fontSize = 10.sp))
            drawRoundRect(Color(0xCC000000), Offset(you.x - ym.size.width / 2f - 5.dp.toPx(), you.y + 8.dp.toPx()), androidx.compose.ui.geometry.Size(ym.size.width + 10.dp.toPx(), ym.size.height + 2.dp.toPx()), CornerRadius(4.dp.toPx()))
            drawText(ym, topLeft = Offset(you.x - ym.size.width / 2f, you.y + 9.dp.toPx()))
        }
        // Controls stay in the box, all 44 dp.
        Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0xCC0B0F14), RoundedCornerShape(10.dp)).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).padding(3.dp)) {
            Seg("3D", true) {}
            Seg("Flat", false, flat)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton("Face north", { CompassGlyph(heading) }) { heading = 0f }
            OverlayButton("Recentre", { BasicText("⌖", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }) { heading = 0f; tilt = TILT0; zoomTarget = 0f }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color(0xCC0B0F14), RoundedCornerShape(10.dp)).border(1.dp, Ink.border, RoundedCornerShape(10.dp))) {
            OverlayButton("Zoom out", { BasicText("−", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }, plain = true) { zoomTarget = (zoomTarget.roundToInt() + 1).coerceAtMost(2).toFloat() }
            OverlayButton("Zoom in", { BasicText("+", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }, plain = true) { zoomTarget = (zoomTarget.roundToInt() - 1).coerceAtLeast(0).toFloat() }
        }
    }
    // Under the box: the level chip and the scale note, then the tour caption.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(LEVELS[level], style = Tokens.Text.cardLabel.style(Ink.text))
        for (i in 0..2) Box(Modifier.padding(start = if (i == 0) 8.dp else 3.dp).size(if (i == level) 14.dp else 6.dp, 6.dp).background(if (i == level) Ink.accent else Ink.muted, RoundedCornerShape(3.dp)))
        BasicText(if (zoom < 0.5f) "Dome not to scale · distances are real" else "True scale", Modifier.weight(1f), Tokens.Text.small.style(Ink.textSecondary).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End, fontSize = 11.sp))
    }
    if (position == null) BasicText("The map and paths wait for a position fix. Your position stays on this phone.", style = Tokens.Text.small.style(Ink.textSecondary))
    else if (picked == null) Row(verticalAlignment = Alignment.CenterVertically) {
        if (!reduceMotion) Canvas(Modifier.size(16.dp)) {
            drawCircle(Ink.track, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            drawArc(Ink.accent, -90f, 360f * (1f - (sinceSwitch.coerceAtMost(TOUR_MS) / TOUR_MS.toFloat())), false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        val left = ((TOUR_MS - sinceSwitch.coerceAtMost(TOUR_MS)) / 1000 + 1).coerceAtMost(6)
        BasicText(
            if (reduceMotion) "Showing ${tour ?: "—"}, the highest · tap a satellite to show another" else "Showing ${tour ?: "—"} · next in $left s · tap a satellite to keep one",
            Modifier.padding(start = 8.dp), Tokens.Text.small.style(Ink.textSecondary),
        )
    }
}

@Composable
private fun Seg(text: String, on: Boolean, click: () -> Unit) {
    BasicText(
        text,
        Modifier.height(38.dp).background(if (on) Ink.track else Color.Transparent, RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = click).padding(horizontal = 16.dp).wrapContentSize(Alignment.Center),
        Tokens.Text.bodyStrong.style(if (on) Ink.text else Ink.textSecondary),
    )
}

@Composable
private fun OverlayButton(label: String, glyph: @Composable () -> Unit, plain: Boolean = false, click: () -> Unit) {
    val m = Modifier.size(44.dp).semantics { contentDescription = label }
    Box(
        (if (plain) m else m.background(Color(0xCC0B0F14), RoundedCornerShape(10.dp)).border(1.dp, Ink.border, RoundedCornerShape(10.dp))).clickable(role = Role.Button, onClick = click),
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/** A compass needle pointing to north as the scene is turned: red half towards north. */
@Composable
private fun CompassGlyph(heading: Float) {
    Canvas(Modifier.size(22.dp)) {
        val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2
        val a = Math.toRadians(-heading.toDouble())
        fun pt(ang: Double, len: Float) = Offset(c.x + (len * sin(ang)).toFloat(), c.y - (len * cos(ang)).toFloat())
        val n = Path().apply { moveTo(pt(a, r).x, pt(a, r).y); lineTo(pt(a + PI / 2, r * 0.3f).x, pt(a + PI / 2, r * 0.3f).y); lineTo(pt(a - PI / 2, r * 0.3f).x, pt(a - PI / 2, r * 0.3f).y); close() }
        val s = Path().apply { moveTo(pt(a + PI, r).x, pt(a + PI, r).y); lineTo(pt(a + PI / 2, r * 0.3f).x, pt(a + PI / 2, r * 0.3f).y); lineTo(pt(a - PI / 2, r * 0.3f).x, pt(a - PI / 2, r * 0.3f).y); close() }
        drawPath(n, Ink.danger); drawPath(s, Ink.muted)
    }
}

/** GPS circle, Galileo diamond, GLONASS triangle, BeiDou square (QZSS and NavIC circles), scaled by [k0] so nearer marks look a little larger. */
private fun DrawScope.mark3d(s: Sat, p: Offset, color: Color, filled: Boolean, k0: Float) {
    val k = 6.dp.toPx() * k0
    val path = Path()
    when (s.letter) {
        'E' -> { path.moveTo(p.x, p.y - k * 1.25f); path.lineTo(p.x + k * 1.25f, p.y); path.lineTo(p.x, p.y + k * 1.25f); path.lineTo(p.x - k * 1.25f, p.y); path.close() }
        'R' -> { path.moveTo(p.x, p.y - k * 1.2f); path.lineTo(p.x + k * 1.1f, p.y + k * 0.9f); path.lineTo(p.x - k * 1.1f, p.y + k * 0.9f); path.close() }
        'C' -> path.addRect(Rect(p.x - k * 0.9f, p.y - k * 0.9f, p.x + k * 0.9f, p.y + k * 0.9f))
        else -> path.addOval(Rect(p.x - k, p.y - k, p.x + k, p.y + k))
    }
    if (filled) drawPath(path, color) else { drawPath(path, Color(0xFF05070A)); drawPath(path, color, style = Stroke(1.5.dp.toPx())) }
}
