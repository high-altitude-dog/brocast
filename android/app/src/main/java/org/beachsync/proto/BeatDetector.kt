package org.beachsync.proto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the prominent beats in a stream of 1 s stereo blocks (a snare, say), as sample positions in the stream. Plain Kotlin, so simtest can check it.
 *
 * It works on the 1 to 5 kHz band, where a snare's crack lives and a kick and most hi-hats don't: positive spectral flux on 1024-point frames every 512
 * samples, against the median of the last couple of seconds, keeping only peaks that are also at least half as strong as the strongest recent one and at
 * least 250 ms from the last beat. Each is then refined to the sample where the high-passed signal first reaches 40% of its peak, so the time is good to
 * about a millisecond rather than a hop. Every step is deterministic: the same blocks give the same beats on any phone. It runs off the audio thread.
 */
class BeatDetector(private val rate: Int = 48000) {
    private val n = 1024
    private val hop = 512
    private val lo = (1000.0 * n / rate).toInt()
    private val hi = (5000.0 * n / rate).toInt()
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(2 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { -sin(2 * PI * it / n).toFloat() }
    private val hpA = (1.0 / (1.0 + 2 * PI * 1500.0 / rate)).toFloat()

    private var buf = FloatArray(0)         // mono samples not yet dropped
    private var hp = FloatArray(0)          // the same, high-passed
    private var absBase = 0L                // stream sample index of buf[0]
    private var frameRel = 0                // where the next frame starts in buf
    private var lastSeg = Long.MIN_VALUE
    private var prevMag: FloatArray? = null
    private val flux = ArrayList<Float>()   // recent flux values, one per hop
    private val fluxStart = ArrayList<Int>() // buf-relative start of each frame (kept in step with flux)
    private var lastBeat = Long.MIN_VALUE / 2
    private var hpX = 0f
    private var hpY = 0f

    fun reset() {
        buf = FloatArray(0); hp = FloatArray(0); absBase = 0; frameRel = 0; lastSeg = Long.MIN_VALUE
        prevMag = null; flux.clear(); fluxStart.clear(); lastBeat = Long.MIN_VALUE / 2; hpX = 0f; hpY = 0f
    }

    /** Takes block [seg] (rate frames of interleaved stereo) and appends the beats found, as stream sample indices. Blocks must follow one another, or it starts afresh. */
    fun process(seg: Long, pcm: ShortArray, out: MutableList<Long>) {
        if (seg != lastSeg + 1) { reset(); absBase = seg * rate }
        lastSeg = seg
        val frames = pcm.size / 2
        val oldLen = buf.size
        buf = buf.copyOf(oldLen + frames); hp = hp.copyOf(oldLen + frames)
        for (i in 0 until frames) {
            val x = (pcm[2 * i] + pcm[2 * i + 1]) / 65536f
            buf[oldLen + i] = x
            val y = hpA * (hpY + x - hpX)
            hpX = x; hpY = y
            hp[oldLen + i] = y
        }
        val re = FloatArray(n); val im = FloatArray(n)
        while (frameRel + n <= buf.size) {
            for (i in 0 until n) { re[i] = buf[frameRel + i] * window[i]; im[i] = 0f }
            fft(re, im)
            val mag = FloatArray(hi + 1)
            for (k in lo..hi) mag[k] = ln(1f + 100f * sqrt(re[k] * re[k] + im[k] * im[k]) / n)
            val p = prevMag
            var f = 0f
            if (p != null) for (k in lo..hi) { val d = mag[k] - p[k]; if (d > 0f) f += d }
            prevMag = mag
            flux.add(f); fluxStart.add(frameRel)
            if (flux.size > HISTORY) { flux.removeAt(0); fluxStart.removeAt(0) }
            decide(out)
            frameRel += hop
        }
        // Keep only what a refinement can still need.
        val drop = frameRel - KEEP
        if (drop > 0 && drop < buf.size) {
            buf = buf.copyOfRange(drop, buf.size); hp = hp.copyOfRange(drop, hp.size)
            absBase += drop; frameRel -= drop
            for (i in fluxStart.indices) fluxStart[i] -= drop
        }
    }

    /** Judges the second-newest frame, now that the one after it is known. */
    private fun decide(out: MutableList<Long>) {
        val m = flux.size
        if (m < 40) return
        val c = flux[m - 2]
        if (!(c > flux[m - 3] && c >= flux[m - 1])) return
        val sorted = flux.sorted()
        val median = sorted[m / 2]
        val top = sorted[m - 1]
        if (c < 3f * median + 0.5f || c < 0.5f * top) return
        val centre = fluxStart[m - 2] + n / 2
        val from = centre - 1280
        val to = centre + 256
        if (from < 0 || to > buf.size) return
        // The sample where the 1 ms-smoothed high-passed level first reaches 40% of its peak in the window.
        val env = FloatArray(to - from)
        var acc = 0f
        val w = 48
        for (i in from until to) {
            acc += abs(hp[i]); if (i - w >= from) acc -= abs(hp[i - w])
            env[i - from] = acc
        }
        val peak = env.max()
        if (peak <= 0f) return
        var onset = from
        for (i in env.indices) if (env[i] >= 0.4f * peak) { onset = from + i; break }
        val abs = absBase + onset
        if (abs - lastBeat < rate / 4) return
        lastBeat = abs
        out.add(abs)
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
            for (s in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = s + k; val b = a + len / 2
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
            }
            len = len shl 1
        }
    }

    private companion object {
        const val HISTORY = 192
        const val KEEP = 4096
    }
}
