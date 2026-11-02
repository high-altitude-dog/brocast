package org.beachsync.proto

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * Flowtime: the app's very best attempt to match UTC, kept as the boot clock plus a correction.
 * Each time source (GPS, NTP) turns its samples into a ClockFit; FlowTime picks one.
 * Everything here is plain Kotlin (no Android calls), so it can be tested on a desktop.
 * All times are nanoseconds: UTC as Unix-epoch ns, the boot clock as elapsedRealtimeNanos.
 */

/** UTC minus boot clock = off0 + slope * (boot - boot0), with a 95% error bound that grows away from boot0. */
class ClockFit(
    val boot0: Long,
    val off0: Long,
    val slope: Double,   // dimensionless: 1e-6 means the boot clock gains or loses 1 ppm
    val err0: Double,    // ns, at boot0
    val growth: Double,  // extra error per ns away from boot0
) {
    fun utcAt(boot: Long): Long = boot + off0 + ((boot - boot0) * slope).toLong()
    fun errAt(boot: Long): Double = err0 + abs((boot - boot0).toDouble()) * growth
}

enum class Source { GPS, NTP }
enum class Policy { AUTO, GPS_ONLY, NTP_ONLY }

object FlowTime {
    @Volatile var gps: ClockFit? = null
    @Volatile var ntp: ClockFit? = null
    @Volatile var policy = Policy.AUTO
    @Volatile var chosen: Source? = null
        private set
    @Volatile var switches = 0
        private set
    private var challengerSince = 0L

    fun reset(p: Policy) {
        gps = null; ntp = null; policy = p; chosen = null; switches = 0; challengerSince = 0
    }

    /** The fit flowtime is using right now, or null before any source is ready. The audio thread calls this. */
    fun fit(): ClockFit? = when (chosen) {
        Source.GPS -> gps
        Source.NTP -> ntp
        null -> null
    }

    /** Use the source with the smaller error estimate; switch only when the other is 20% better for 10 s. */
    @Synchronized
    fun reselect(boot: Long) {
        val inf = Double.POSITIVE_INFINITY
        val eg = if (policy != Policy.NTP_ONLY) gps?.errAt(boot) ?: inf else inf
        val en = if (policy != Policy.GPS_ONLY) ntp?.errAt(boot) ?: inf else inf
        val cur = chosen
        val eCur = when (cur) { Source.GPS -> eg; Source.NTP -> en; null -> inf }
        if (eCur == inf) {
            val pick = when {
                eg == inf && en == inf -> null
                eg <= en -> Source.GPS
                else -> Source.NTP
            }
            if (cur != null && pick != null && pick != cur) switches++
            chosen = pick
            challengerSince = 0
            return
        }
        val other = if (cur == Source.GPS) Source.NTP else Source.GPS
        val eOther = if (other == Source.GPS) eg else en
        if (eOther < 0.8 * eCur) {
            if (challengerSince == 0L) challengerSince = boot
            else if (boot - challengerSince >= 10_000_000_000L) {
                chosen = other; switches++; challengerSince = 0
            }
        } else challengerSince = 0
    }
}

/** Least-squares line y = meanY + slope * (x - meanX), with its residual scatter. */
class Line(
    val meanX: Double, val meanY: Double, val slope: Double,
    private val sxx: Double, val rms: Double, val n: Int, val span: Double,
) {
    fun at(x: Double) = meanY + slope * (x - meanX)
    /** Standard error of the fitted line at x. */
    fun se(x: Double) = rms * sqrt(1.0 / n + (x - meanX) * (x - meanX) / sxx)
    fun slopeSe() = rms / sqrt(sxx)
}

fun fitLine(x: DoubleArray, y: DoubleArray, n: Int, w: DoubleArray? = null): Line? {
    if (n < 3) return null
    var sumW = 0.0
    for (i in 0 until n) sumW += w?.get(i) ?: 1.0
    val scale = n / sumW   // weights average 1, so with no weights this is the ordinary fit
    var mx = 0.0
    var my = 0.0
    for (i in 0 until n) { val wi = scale * (w?.get(i) ?: 1.0); mx += wi * x[i]; my += wi * y[i] }
    mx /= n; my /= n
    var sxx = 0.0
    var sxy = 0.0
    var lo = x[0]
    var hi = x[0]
    for (i in 0 until n) {
        val wi = scale * (w?.get(i) ?: 1.0)
        val dx = x[i] - mx
        sxx += wi * dx * dx; sxy += wi * dx * (y[i] - my)
        if (x[i] < lo) lo = x[i]
        if (x[i] > hi) hi = x[i]
    }
    if (sxx <= 0.0) return null
    val b = sxy / sxx
    var ss = 0.0
    for (i in 0 until n) { val r = y[i] - my - b * (x[i] - mx); ss += scale * (w?.get(i) ?: 1.0) * r * r }
    return Line(mx, my, b, sxx, sqrt(ss / (n - 2)), n, hi - lo)
}

/** Fit, drop points further from the line than 5 robust sigmas plus [floor], fit again. */
fun robustFit(x: DoubleArray, y: DoubleArray, n: Int, floor: Double, w: DoubleArray? = null): Line? {
    val first = fitLine(x, y, n, w) ?: return null
    val res = DoubleArray(n) { abs(y[it] - first.at(x[it])) }
    val sorted = res.copyOf()
    sorted.sort()
    val limit = 5 * 1.4826 * sorted[n / 2] + floor
    val xs = DoubleArray(n)
    val ys = DoubleArray(n)
    val ws = if (w != null) DoubleArray(n) else null
    var k = 0
    for (i in 0 until n) if (res[i] <= limit) { xs[k] = x[i]; ys[k] = y[i]; if (ws != null) ws[k] = w!![i]; k++ }
    return if (k == n) first else fitLine(xs, ys, k, ws)
}

/**
 * GPS time samples -> ClockFit. A robust straight line through the last minute of samples measures the
 * boot clock's rate error (drift) as well as its offset, which is what lets the holdover clock carry on
 * when GPS drops out: the last fit keeps running, with an error bound that grows.
 */
class GpsFitter(private val window: Int = 60) {
    private val boot = LongArray(window)
    private val off = LongArray(window)
    private val unc = DoubleArray(window)
    private var n = 0
    private var head = 0
    private var strikes = 0
    var resets = 0
        private set
    var rejected = 0
        private set
    var last: Line? = null
        private set

    /**
     * Adds a sample; returns the new fit, or null while there is not enough data. [uncNs] is the chip's own
     * 1-sigma figure. [linkNs] is what the chip cannot see: the bound on how late a Silver phone stamps a report.
     */
    fun add(b: Long, o: Long, uncNs: Double, current: ClockFit?, linkNs: Double = 0.0): ClockFit? {
        if (current != null && n >= MIN) {
            val dev = abs((o - (current.utcAt(b) - b)).toDouble())
            val limit = maxOf(1e6, 6 * (last?.rms ?: 0.0))
            if (dev > limit) {
                rejected++
                if (++strikes < 3) return null   // a one-off outlier: ignore it
                n = 0; resets++                  // three in a row: the clock really moved, so start again
            }
        }
        strikes = 0
        boot[head] = b; off[head] = o; unc[head] = uncNs
        head = (head + 1) % window
        if (n < window) n++
        if (n < MIN) return null
        // x: seconds before the newest sample; y: ns relative to the newest offset (keeps the doubles exact)
        // w: the chip's own confidence in each sample, so a better signal counts straight away
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val w = DoubleArray(n)
        for (i in 0 until n) {
            val j = (head - 1 - i + window) % window
            x[i] = (boot[j] - b) / 1e9
            y[i] = (off[j] - o).toDouble()
            val u = maxOf(unc[j], WEIGHT_FLOOR_NS)
            w[i] = 1.0 / (u * u)
        }
        val line = robustFit(x, y, n, 200.0, w) ?: return null
        if (line.span < 3.0) return null
        last = line
        val se = line.se(0.0)
        val err0 = 2 * sqrt(se * se + uncNs * uncNs) + 50.0 + linkNs   // 50 ns for what the chip cannot see
        val growth = (2 * line.slopeSe() + HOLDOVER_DRIFT_NS_PER_S) / 1e9
        return ClockFit(b, o + line.at(0.0).roundToLong(), line.slope / 1e9, err0, growth)
    }

    companion object {
        const val MIN = 5
        const val WEIGHT_FLOOR_NS = 10.0              // chips reporting no figure, or a tiny one, don't dominate the fit
        const val SILVER_LINK_NS = 5_000_000.0        // the design's 1-5 ms Silver link, at its upper end
        const val HOLDOVER_DRIFT_NS_PER_S = 5000.0    // 5 ppm of rate change: a starting value until phones measure it
    }
}

/**
 * NTP samples -> ClockFit. Uses only the better samples (those with small round trips). Until there are
 * enough of them spread over two minutes it cannot measure drift, so it assumes up to 50 ppm.
 */
class NtpFitter(private val keepNs: Long = 900_000_000_000L) {
    class Sample(val boot: Long, val off: Long, val bound: Double)

    private val list = ArrayList<Sample>()
    var lastLine: Line? = null
        private set

    fun add(s: Sample): ClockFit {
        list.add(s)
        while (s.boot - list[0].boot > keepNs) list.removeAt(0)
        val best = list.minOf { it.bound }
        val good = list.filter { it.bound <= 1.5 * best + 1e6 }
        val ref = good.maxBy { it.boot }
        if (good.size >= 4) {
            val x = DoubleArray(good.size) { (good[it].boot - ref.boot) / 1e9 }
            val y = DoubleArray(good.size) { (good[it].off - ref.off).toDouble() }
            val line = robustFit(x, y, good.size, 200_000.0)
            if (line != null && line.span >= 120.0) {
                lastLine = line
                return ClockFit(
                    ref.boot, ref.off + line.at(0.0).roundToLong(), line.slope / 1e9,
                    best + 2 * line.se(0.0), (2 * line.slopeSe() + 20.0) / 1e9,
                )
            }
        }
        val b = good.minBy { it.bound }
        return ClockFit(b.boot, b.off, 0.0, b.bound, UNKNOWN_DRIFT)
    }

    companion object { const val UNKNOWN_DRIFT = 50e-6 }
}
