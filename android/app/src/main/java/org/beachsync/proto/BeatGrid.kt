package org.beachsync.proto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The beat grid of a stretch of music: its tempo, where the beats fall, and which of them start a bar. Plain Kotlin, tested in simtest. It is a pure function of
 * the blocks it is given, so two phones that hold the same blocks get the same grid whenever they started listening.
 *
 * The method: positive spectral flux (how fast the spectrum is rising) every 512 samples gives an onset-strength signal; its autocorrelation, helped by a mild
 * preference for ordinary dance and pop tempi, gives the beat period; the phase that collects the most onset strength gives the first beat; each beat is then
 * snapped to the real hit near it (the sample where the high-passed signal first reaches 40% of its local peak) and a robust line fit through those hits gives
 * the final period and phase. The bar's first beat is the one of every four whose low-frequency flux (the kick) is strongest.
 */
class BeatGrid(private val rate: Int = 48000) {
    /** [phaseSample] is a stream sample position on a beat; beat k is at phaseSample + k * periodSamples; beats with (k - barOffset) divisible by 4 start a bar. */
    class Result(val periodSamples: Double, val phaseSample: Long, val barOffset: Int, val confidence: Float)

    /** Why the last call returned nothing, for tests and logs. */
    var lastNote = ""
        private set

    private val n = 1024
    private val hop = 512
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { -sin(2 * PI * it / n).toFloat() }
    private val hpA = (1.0 / (1.0 + 2 * PI * 1500.0 / rate)).toFloat()
    private val hopsPerS = rate.toDouble() / hop

    companion object {
        /**
         * The grid to use for window [w], given every analysed window so far (null where a window had no clear beat). A window with none borrows the first good
         * grid up to four windows ahead (the beat lattice carries on through a drumless stretch), and the bar start is the vote of windows w, w + 1 and w + 2,
         * each mapped onto the chosen grid, because one window alone often flips between beats. It looks only ahead, so two phones that hold the same upcoming
         * audio get the same answer however long ago each started. Null if nothing in reach has a beat, or the windows it needs aren't analysed yet.
         */
        fun resolve(w: Long, grids: Map<Long, Result?>): Result? {
            for (i in 0L..2L) if (!grids.containsKey(w + i)) return null
            var base: Result? = null
            for (i in 0L..4L) { val g = grids[w + i]; if (g != null) { base = g; break } }
            if (base == null) return null
            val votes = IntArray(4)
            for (i in 0L..2L) {
                val g = grids[w + i] ?: continue
                val downbeat = g.phaseSample + g.barOffset * g.periodSamples
                val j = Math.floorMod(Math.round((downbeat - base.phaseSample) / base.periodSamples), 4L).toInt()
                votes[j]++
            }
            var best = Math.floorMod(base.barOffset, 4)
            for (j in 0..3) if (votes[j] > votes[best]) best = j
            return Result(base.periodSamples, base.phaseSample, best, base.confidence)
        }
    }

    /** [segs] are consecutive 1 s stereo blocks starting at block [firstSeg]. Returns null when there is no clear beat. */
    fun analyse(firstSeg: Long, segs: List<ShortArray>): Result? {
        val total = segs.sumOf { it.size / 2 }
        lastNote = ""
        if (total < rate * 6) { lastNote = "too short"; return null }
        val mono = FloatArray(total)
        val hp = FloatArray(total)
        var at = 0; var hx = 0f; var hy = 0f
        for (s in segs) for (i in 0 until s.size / 2) {
            val x = (s[2 * i] + s[2 * i + 1]) / 65536f
            mono[at] = x
            val y = hpA * (hy + x - hx); hx = x; hy = y; hp[at] = y
            at++
        }
        val frames = (total - n) / hop + 1
        val oss = FloatArray(frames)
        val low = FloatArray(frames)        // positive flux in the kick's band, on the linear magnitudes
        var prevRaw = FloatArray(5)
        val re = FloatArray(n); val im = FloatArray(n)
        var prev: FloatArray? = null
        for (f in 0 until frames) {
            for (i in 0 until n) { re[i] = mono[f * hop + i] * window[i]; im[i] = 0f }
            fft(re, im)
            val mag = FloatArray(141)
            val raw = FloatArray(5)
            for (k in 1..140) mag[k] = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]) / n)
            for (k in 1..4) raw[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / n
            val p = prev
            if (p != null) {
                var a = 0f; var l = 0f
                for (k in 2..140) { val d = mag[k] - p[k]; if (d > 0f) a += d }
                for (k in 1..4) { val d = raw[k] - prevRaw[k]; if (d > 0f) l += d }
                oss[f] = a; low[f] = l
            }
            prev = mag; prevRaw = raw
        }
        // Take away the slow trend, so only the sudden rises are left.
        val d = FloatArray(frames)
        val half = 18
        var run = 0f
        for (i in 0 until min(frames, 2 * half + 1)) run += oss[i]
        for (f in 0 until frames) {
            val lo = max(0, f - half); val hi = min(frames - 1, f + half)
            var m = 0f
            for (i in lo..hi) m += oss[i]
            d[f] = max(0f, oss[f] - m / (hi - lo + 1))
        }
        // Strong hits count for far more than weak ones, so square the (normalised) onset strength.
        var dMax = 0f
        for (v in d) dMax = max(dMax, v)
        if (dMax <= 0f) { lastNote = "no onsets"; return null }
        // The kick lives in the low band: weighting it in decides between a beat and the off-beat hats between beats.
        var lMax = 0f
        for (v in low) lMax = max(lMax, v)
        val e = FloatArray(frames) { val v = d[it] / dMax; val k = if (lMax > 0f) low[it] / lMax else 0f; v * v + 1.5f * k * k }
        // The pulse train: for each period from 0.3 s to 0.75 s (200 to 80 per minute), the phase at which evenly spaced pulses collect the most strength on average
        // (taking the best of three neighbouring frames, to allow for jitter). The period at which every beat carries a hit scores highest; a mild preference for
        // ordinary dance and pop tempi settles the rest.
        val minLag = 0.30 * hopsPerS
        val maxLag = 0.75 * hopsPerS
        var bestScore = 0f; var periodHops = 0.0; var bestPhase = 0
        var sum = 0f; var cnt = 0
        var p = minLag
        while (p <= maxLag) {
            val bpm = 60.0 * hopsPerS / p
            val prior = exp(-0.5 * (ln(bpm / 120.0) / ln(2.0) / 0.6).let { it * it }).toFloat()
            var top = 0f; var topPhase = 0
            for (ph in 0 until Math.ceil(p).toInt()) {
                var acc = 0f; var kk = 0; var k = 0
                while (true) {
                    val idx = (ph + k * p).roundToInt()
                    if (idx + 1 >= frames) break
                    acc += max(e[max(0, idx - 1)], max(e[idx], e[idx + 1])); kk++; k++
                }
                if (kk >= 8) { val mean = acc / kk; if (mean > top) { top = mean; topPhase = ph } }
            }
            val score = top * (0.4f + 0.6f * prior)
            sum += score; cnt++
            if (score > bestScore) { bestScore = score; periodHops = p; bestPhase = topPhase }
            p += 0.25
        }
        if (periodHops <= 0.0 || bestScore <= 0f) { lastNote = "no tempo"; return null }
        val confidence = ((bestScore - sum / cnt) / (bestScore + 1e-9f)).coerceIn(0f, 1f)
        if (confidence < 0.25f) { lastNote = "low confidence %.2f at %.1f per minute".format(confidence, 60.0 * hopsPerS / periodHops); return null }
        // Snap each beat to the real hit near it, then fit a line through the hits.
        val periodS = periodHops * hop
        val first = bestPhase * hop + 256.0            // a flux peak sits about a quarter of a frame into the onset
        val ks = ArrayList<Int>(); val rs = ArrayList<Double>()
        var k = 0
        while (true) {
            val expect = first + k * periodS
            if (expect + 600 >= total) break
            if (expect - 2300 >= 0) {
                val onset = refine(hp, expect.toInt() - 2048, expect.toInt() + 512)
                if (onset >= 0) { ks.add(k); rs.add(onset.toDouble()) }
            }
            k++
        }
        if (ks.size < 6) { lastNote = "only ${ks.size} beats snapped to a hit"; return null }
        // Robust line: start from the grid, drop hits more than 15 ms off, fit by least squares, once more.
        var slope = periodS; var icpt = first
        repeat(2) {
            val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in ks.indices) if (abs(rs[i] - (icpt + ks[i] * slope)) < 0.015 * rate) { xs.add(ks[i].toDouble()); ys.add(rs[i]) }
            if (xs.size >= 6) {
                val mx = xs.average(); val my = ys.average()
                var sxx = 0.0; var sxy = 0.0
                for (i in xs.indices) { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
                if (sxx > 0.0) { slope = sxy / sxx; icpt = my - slope * mx }
            }
        }
        // Which of the four beats in a bar carries the kick: the one whose low-frequency flux is strongest.
        val sums = DoubleArray(4)
        for (i in ks.indices) {
            val centre = ((icpt + ks[i] * slope - 256.0) / hop).roundToInt()
            var m = 0f
            for (j in max(0, centre - 2)..min(frames - 1, centre + 2)) m = max(m, low[j])
            sums[ks[i] % 4] += m
        }
        var barOffset = 0
        for (r in 1..3) if (sums[r] > sums[barOffset]) barOffset = r
        return Result(slope, firstSeg * rate + Math.round(icpt), barOffset, confidence)
    }

    /** The sample where the 1 ms-smoothed high-passed level first reaches 40% of its peak within [from, to), or -1 if nothing stands out. */
    private fun refine(hp: FloatArray, from: Int, to: Int): Int {
        if (from < 0 || to > hp.size) return -1
        val w = 48
        val env = FloatArray(to - from)
        var acc = 0f
        for (i in from until to) {
            acc += abs(hp[i]); if (i - w >= from) acc -= abs(hp[i - w])
            env[i - from] = acc
        }
        val peak = env.max()
        var mean = 0f
        for (v in env) mean += v
        mean /= env.size
        if (peak <= 0f || peak < 3f * mean) return -1
        for (i in env.indices) if (env[i] >= 0.4f * peak) return from + i
        return -1
    }

    private fun fft(re: FloatArray, im: FloatArray) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            for (s in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cosT[k * step]; val wi = sinT[k * step]
                val a = s + k; val b = a + len / 2
                val tr = re[b] * wr - im[b] * wi
                val ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr; im[b] = im[a] - ti
                re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }
}
