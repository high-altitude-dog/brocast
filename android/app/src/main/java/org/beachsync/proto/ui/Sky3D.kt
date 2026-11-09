package org.beachsync.proto.ui

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Satellites in 3D (hand-off 42), drawn on a Compose Canvas with a plain perspective camera.
 * Scene frame, in km: origin at the Earth's centre, x east, y north, z up through you, so you stand at (0, 0, R).
 * The camera sits behind you and above, faces the way [heading] says (0 = north) and pivots on you.
 * Zoom runs 0 (your sky: satellites on a dome) to 1 (Orbits: true distances) to 2 (Whole Earth).
 */

private const val R = SatFacts.EARTH_KM
private const val DOME = 0.55 * R
private const val FOV_DEG = 45.0
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

/** The camera for one frame: position, basis and focal length, and how to put a scene point on screen. */
private class Cam(heading: Double, tilt: Double, zoom: Double, val w: Float, val h: Float) {
    val px: Double; val py: Double; val pz: Double
    private val fx: Double; private val fy: Double; private val fz: Double
    private val rx: Double; private val ry: Double
    private val ux: Double; private val uy: Double; private val uz: Double
    val focal = h / 2.0 / tan(Math.toRadians(FOV_DEG / 2))
    val dist: Double

    init {
        val z1 = zoom.coerceIn(0.0, 1.0); val z2 = (zoom - 1).coerceIn(0.0, 1.0)
        dist = R * 3.2 * exp(ln(14.0 / 3.2) * z1) * exp(ln(26.0 / 14.0) * z2)
        val tz = R + R * (0.25 + (-0.65) * z1 + (-0.15) * z2)          // the point looked at, on the axis through you
        val t = Math.toRadians(tilt); val hd = Math.toRadians(heading)
        val hx = sin(hd); val hy = cos(hd)
        px = -cos(t) * hx * dist; py = -cos(t) * hy * dist; pz = tz + sin(t) * dist
        val dx = -px; val dy = -py; val dz = tz - pz
        val dl = sqrt(dx * dx + dy * dy + dz * dz)
        fx = dx / dl; fy = dy / dl; fz = dz / dl
        val rl = hypot(fy, fx)
        rx = fy / rl; ry = -fx / rl                                      // forward × up, flat
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

/**
 * A circular orbit through [r] (scene frame, km from the Earth's centre) tilted [tiltDeg] to the equator, whose pole is [axis]: 120 points.
 * Of the two such orbits, [rising] picks the one where the satellite is heading north. Null if the tilt can't reach its latitude.
 */
private fun orbitRing(r: DoubleArray, axis: DoubleArray, tiltDeg: Int, rising: Boolean): DoubleArray? {
    val rl = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
    val rh = DoubleArray(3) { r[it] / rl }
    val sinLat = rh[0] * axis[0] + rh[1] * axis[1] + rh[2] * axis[2]
    val cosLat = sqrt((1 - sinLat * sinLat).coerceAtLeast(1e-9))
    // e1: towards the pole, square to r; e2 = r × e1, towards the east.
    val e1 = DoubleArray(3) { (axis[it] - sinLat * rh[it]) / cosLat }
    val e2 = doubleArrayOf(rh[1] * e1[2] - rh[2] * e1[1], rh[2] * e1[0] - rh[0] * e1[2], rh[0] * e1[1] - rh[1] * e1[0])
    val a = (cos(Math.toRadians(tiltDeg.toDouble())) / cosLat).coerceIn(-1.0, 1.0)
    val b = sqrt(1 - a * a) * (if (rising) 1 else -1)
    val n = DoubleArray(3) { a * e1[it] + b * e2[it] }                         // the orbit's pole
    val v = doubleArrayOf(n[1] * rh[2] - n[2] * rh[1], n[2] * rh[0] - n[0] * rh[2], n[0] * rh[1] - n[1] * rh[0])
    val out = DoubleArray(360)
    for (i in 0 until 120) {
        val t = i * 2 * Math.PI / 120
        for (k in 0..2) out[3 * i + k] = rl * (rh[k] * cos(t) + v[k] * sin(t))
    }
    return out
}

/** True when the Earth stands between the camera and the point. */
private fun behindEarth(cam: Cam, x: Double, y: Double, z: Double): Boolean {
    val dx = x - cam.px; val dy = y - cam.py; val dz = z - cam.pz
    val dd = dx * dx + dy * dy + dz * dz
    val t = -(cam.px * dx + cam.py * dy + cam.pz * dz) / dd                  // nearest approach to the centre along the ray
    if (t <= 0 || t >= 1) return false
    val cx = cam.px + dx * t; val cy = cam.py + dy * t; val cz = cam.pz + dz * t
    return cx * cx + cy * cy + cz * cz < R * R
}

/** A satellite's direction from you as a unit vector: x east, y north, z up. */
private fun look(s: Sat): DoubleArray {
    val e = Math.toRadians(s.elevationDeg.toDouble()); val a = Math.toRadians(s.azimuthDeg.toDouble())
    return doubleArrayOf(cos(e) * sin(a), cos(e) * cos(a), sin(e))
}

internal fun rangeOf(s: Sat) = SatFacts.rangeKm(s.elevationDeg.toDouble(), SatFacts.orbit(s.system, s.svid).radiusKm)

@Composable
internal fun Sky3D(sats: List<Sat>, position: Pair<Double, Double>?, picked: String?, pick: (String?) -> Unit, flat: () -> Unit) {
    val ctx = LocalContext.current
    val measurer = rememberTextMeasurer()
    val reduceMotion = remember { runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    var heading by remember { mutableFloatStateOf(0f) }
    var tilt by remember { mutableFloatStateOf(22f) }
    var zoomTarget by remember { mutableFloatStateOf(0f) }
    var pinching by remember { mutableStateOf(false) }
    val zoom by animateFloatAsState(zoomTarget, if (pinching || reduceMotion) snap() else tween(450), label = "zoom")
    val raw = remember { LandDots.get(ctx) }
    // Rounded so a moving phone doesn't redo the map every second; the map is far coarser than 0.1°.
    val posKey = position?.let { (it.first * 10).roundToInt() to (it.second * 10).roundToInt() }
    val land = remember(posKey) { posKey?.let { landInScene(raw, it.first / 10.0, it.second / 10.0) } }
    val hits = remember { ArrayList<Pair<String, Offset>>() }
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
    val stars = remember { val r = java.util.Random(7); FloatArray(160) { r.nextFloat() } }
    val dotPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; strokeCap = android.graphics.Paint.Cap.ROUND } }
    val used = sats.count { it.used }
    val sel = sats.firstOrNull { it.label == picked }
    val level = zoom.roundToInt().coerceIn(0, 2)
    val summary = "${sats.size} satellites in view, $used in use." + (sel?.let { " ${it.label} selected, ${"%,d".format(rangeOf(it).roundToInt())} km away." } ?: "")
    val slop = LocalViewConfiguration.current.touchSlop

    Box(Modifier.fillMaxWidth().height(410.dp).background(Color(0xFF05070A), RoundedCornerShape(12.dp)).border(1.dp, Ink.border, RoundedCornerShape(12.dp))) {
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
                                // Two fingers down the screen tilt towards the flat plot, up towards the horizon.
                                tilt = (tilt + pan.y * 0.25f).coerceIn(5f, 85f)
                                ev.changes.forEach { it.consume() }
                            } else if (down == 1) {
                                val pan = ev.calculatePan()
                                if (!turning) { acc += pan; if (abs(acc.x) > slop && abs(acc.x) > abs(acc.y)) turning = true }
                                // One finger across turns the scene round you; up and down is left to the page's scroll.
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
            val cam = Cam(heading.toDouble(), tilt.toDouble(), zoom.toDouble(), size.width, size.height)
            val p = FloatArray(3)
            val q = FloatArray(3)
            val s1 = (zoom.coerceIn(0f, 1f)).let { it * it * (3 - 2 * it) }   // dome → true distances
            val domeAlpha = 1f - s1
            // Stars.
            for (i in 0 until stars.size / 2) drawCircle(Color.White.copy(alpha = 0.15f + 0.35f * stars[(i * 7) % stars.size]), 0.6.dp.toPx() + stars[(i * 3) % stars.size] * 0.6.dp.toPx(), Offset(stars[2 * i] * size.width, stars[2 * i + 1] * size.height))
            // Earth: dark sphere, cyan rim, then the land dots and a 15° grid on the side facing the camera.
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
                dotPaint.color = Color(0xFF5E6E7A).toArgb(); dotPaint.strokeWidth = (1.6.dp.toPx() * (3.2 * R / cam.dist).toFloat().coerceIn(0.45f, 1f))
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
                // Whole Earth: the geostationary belt as a dashed cyan ring round the equator.
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
            // Orbits and Whole Earth: faint rings at the three orbit heights.
            if (zoom > 0.6f) for (hKm in listOf(20180.0, 23222.0, 35786.0)) drawCircle(Ink.grid.copy(alpha = 0.25f * ((zoom - 0.6f) / 0.4f).coerceAtMost(1f)), cam.sphereRadius(SatFacts.EARTH_EQ_KM + hKm), ec, style = Stroke(0.8.dp.toPx()))
            // You.
            cam.project(0.0, 0.0, R, p)
            val you = Offset(p[0], p[1])
            // The dome: tinted horizon disc, 30° and 60° rings dashed, N E S W at the rim.
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
                    cam.project(DOME * 1.12 * sin(r), DOME * 1.12 * cos(r), R, p)
                    val m = measurer.measure(t, TextStyle(color = Ink.textSecondary.copy(alpha = domeAlpha), fontSize = 11.sp))
                    if (p[2] > 0) drawText(m, topLeft = Offset(p[0] - m.size.width / 2f, p[1] - m.size.height / 2f))
                }
            }
            // Satellites: on the dome at their true direction, sliding out to their true distance as you zoom.
            hits.clear()
            val placed = ArrayList<Rect>()
            val drawn = sats.map { s ->
                val u = look(s)
                val d = DOME + (rangeOf(s) - DOME) * s1
                cam.project(u[0] * d, u[1] * d, R + u[2] * d, p)
                Triple(s, Offset(p[0], p[1]), p[2])
            }.filter { it.third > 0 }.sortedByDescending { it.third }
            // Each satellite's orbit: true scale once zoomed out; on the dome, its path across your sky.
            if (position != null) {
                val axis = ecefToScene(90.0, 0.0, position.first, position.second)
                for ((s, _, _) in drawn) {
                    val orbit = SatFacts.orbit(s.system, s.svid)
                    val u = look(s); val d = rangeOf(s)
                    val ring = orbitRing(doubleArrayOf(u[0] * d, u[1] * d, R + u[2] * d), axis, orbit.tiltDeg, rising[s.label] ?: true) ?: continue
                    val color = when { s.label == picked -> Ink.accent; s.used -> Ink.accent.copy(alpha = 0.35f); else -> Ink.muted.copy(alpha = 0.3f) }
                    val width = (if (s.label == picked) 2f else 1f) * 1.dp.toPx()
                    var prev: Offset? = null; var prevHidden = false
                    for (j in 0..ring.size / 3) {
                        val i = j % (ring.size / 3)
                        val x = ring[3 * i]; val y = ring[3 * i + 1]; val z = ring[3 * i + 2] - R      // from you
                        val len = sqrt(x * x + y * y + z * z)
                        val above = z > 0
                        if (!above && s1 < 0.5f) { prev = null; continue }
                        val k = (DOME + (len - DOME) * s1) / len
                        val wx = x * k; val wy = y * k; val wz = R + z * k
                        cam.project(wx, wy, wz, p)
                        if (p[2] <= 0) { prev = null; continue }
                        val hidden = behindEarth(cam, wx, wy, wz)
                        val pt = Offset(p[0], p[1])
                        prev?.let { a -> drawLine(if (hidden || prevHidden) color.copy(alpha = color.alpha * 0.35f) else color, a, pt, width) }
                        prev = pt; prevHidden = hidden
                    }
                }
            }
            for ((s, at, depth) in drawn) {
                val near = (cam.dist / depth).toFloat().coerceIn(0.8f, 1.25f)
                mark3d(s, at, if (s.used) Ink.accent else Ink.muted, s.used, near)
                hits.add(s.label to at)
                if (s.label == picked) drawCircle(Ink.text, 13.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                val labelled = s.label == picked || (s.used && zoom < 1.5f) || zoom < 0.5f
                if (!labelled) continue
                val m = measurer.measure(s.label, TextStyle(color = if (s.used) Ink.text else Ink.textSecondary, fontSize = 10.sp))
                val w = m.size.width.toFloat(); val h = m.size.height.toFloat(); val g = 9.dp.toPx()
                val options = listOf(Offset(at.x + g, at.y - h - 2f), Offset(at.x - w / 2, at.y - g - h), Offset(at.x - g - w, at.y - h - 2f), Offset(at.x + g, at.y + 2f), Offset(at.x - w / 2, at.y + g))
                val o = options.firstOrNull { c -> placed.none { it.overlaps(Rect(c.x, c.y, c.x + w, c.y + h)) } } ?: options[0]
                placed.add(Rect(o.x, o.y, o.x + w, o.y + h)); drawText(m, topLeft = o)
            }
            // The selected satellite's distance, halfway along its line.
            sel?.let { s -> drawn.firstOrNull { it.first === s }?.let { (_, at, _) ->
                val m = measurer.measure("%,d km".format(rangeOf(s).roundToInt()), TextStyle(color = Ink.text, fontSize = 10.sp))
                val mid = Offset(at.x, at.y + 26.dp.toPx())
                val box = Rect(mid.x - m.size.width / 2f - 6.dp.toPx(), mid.y - m.size.height / 2f - 3.dp.toPx(), mid.x + m.size.width / 2f + 6.dp.toPx(), mid.y + m.size.height / 2f + 3.dp.toPx())
                drawRoundRect(Color(0xE6101418), box.topLeft, box.size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                drawRoundRect(Ink.accent, box.topLeft, box.size, androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawText(m, topLeft = Offset(mid.x - m.size.width / 2f, mid.y - m.size.height / 2f))
            } }
            drawCircle(Color.White, 5.dp.toPx(), you)
            drawCircle(Color.Black, 5.dp.toPx(), you, style = Stroke(1.5.dp.toPx()))
            val ym = measurer.measure("You", TextStyle(color = Ink.text, fontSize = 10.sp))
            drawRoundRect(Color(0xCC000000), Offset(you.x - ym.size.width / 2f - 5.dp.toPx(), you.y + 8.dp.toPx()), androidx.compose.ui.geometry.Size(ym.size.width + 10.dp.toPx(), ym.size.height + 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawText(ym, topLeft = Offset(you.x - ym.size.width / 2f, you.y + 9.dp.toPx()))
        }
        // Overlays, all 44 dp.
        Row(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0xCC0B0F14), RoundedCornerShape(10.dp)).border(1.dp, Ink.border, RoundedCornerShape(10.dp)).padding(3.dp)) {
            Seg("3D", true) {}
            Seg("Flat", false, flat)
        }
        Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlayButton("Face north", { CompassGlyph(heading) }) { heading = 0f }
            OverlayButton("Recentre", { BasicText("⌖", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }) { heading = 0f; tilt = 22f; zoomTarget = 0f }
        }
        androidx.compose.foundation.layout.Column(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.background(Color(0xCC0B0F14), RoundedCornerShape(8.dp)).border(1.dp, Ink.border, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(LEVELS[level], style = Tokens.Text.cardLabel.style(Ink.text))
                for (i in 0..2) Box(Modifier.padding(start = if (i == 0) 8.dp else 3.dp).size(if (i == level) 14.dp else 6.dp, 6.dp).background(if (i == level) Ink.accent else Ink.muted, RoundedCornerShape(3.dp)))
            }
            if (zoom < 0.5f) BasicText("dome not to scale", Modifier.background(Color(0xCC0B0F14), RoundedCornerShape(6.dp)).border(1.dp, Ink.border, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), Tokens.Text.small.style(Ink.textSecondary).copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp))
            if (position == null) BasicText("map waits for a position fix", Modifier.background(Color(0xCC0B0F14), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp), Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 11.sp))
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color(0xCC0B0F14), RoundedCornerShape(10.dp)).border(1.dp, Ink.border, RoundedCornerShape(10.dp))) {
            OverlayButton("Zoom out", { BasicText("−", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }, plain = true) { zoomTarget = (zoomTarget.roundToInt() + 1).coerceAtMost(2).toFloat() }
            OverlayButton("Zoom in", { BasicText("+", style = Tokens.Text.bodyStrong.style().copy(fontSize = 20.sp)) }, plain = true) { zoomTarget = (zoomTarget.roundToInt() - 1).coerceAtLeast(0).toFloat() }
        }
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
        val n = Path().apply { moveTo(pt(a, r).x, pt(a, r).y); lineTo(pt(a + Math.PI / 2, r * 0.3f).x, pt(a + Math.PI / 2, r * 0.3f).y); lineTo(pt(a - Math.PI / 2, r * 0.3f).x, pt(a - Math.PI / 2, r * 0.3f).y); close() }
        val s = Path().apply { moveTo(pt(a + Math.PI, r).x, pt(a + Math.PI, r).y); lineTo(pt(a + Math.PI / 2, r * 0.3f).x, pt(a + Math.PI / 2, r * 0.3f).y); lineTo(pt(a - Math.PI / 2, r * 0.3f).x, pt(a - Math.PI / 2, r * 0.3f).y); close() }
        drawPath(n, Ink.danger); drawPath(s, Ink.muted)
    }
}

/** GPS circle, Galileo diamond, GLONASS triangle, BeiDou square, scaled by [k] so nearer marks look a little larger. */
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

