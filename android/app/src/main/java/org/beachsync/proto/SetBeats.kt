package org.beachsync.proto

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A whole set's beat and bar grid, worked out once on the phone after the set downloads ([SetBeatAnalyser]) and kept beside the file as `<id>.beats`
 * (user, 7 Nov: establish the beats and bars well before play-out, on the phone). Plain Kotlin, tested in simtest.
 *
 * The set is cut into the same windows as the live tracker (16 s from 8w, used for the 8 s from 8w + 4) and each is analysed by [BeatGrid]. Because the whole
 * set is in hand, [resolve] can then look both ways: a window locked onto a wrong multiple of the tempo is dropped, a drumless window borrows the nearest good
 * grid, one beat count runs through each track (a stretch of steady tempo), and the bar start is the vote of the whole track, so bars don't jump between
 * windows. Bars are numbered from the start of the set.
 * It is a pure function of the file, so every phone holding the same file gets the same grid.
 */
class SetBeats(val windows: List<Win?>) {
    /** Beats fall at [downbeat] + k × [period] (file samples at 48 kHz); k divisible by 4 starts a bar, and that bar is number [bar0] + k / 4. */
    class Win(val period: Double, val downbeat: Double, val bar0: Long, val confidence: Float)

    /** Where [at] puts a file sample: tempo, beat 0 to 3 within the bar, how far through that beat, and the bar number from the set's start. */
    class Pos(val bpm: Double, val beatInBar: Int, val beatFrac: Double, val bar: Long, val sinceBeatSamples: Double)

    fun at(fileSample: Double): Pos? {
        if (windows.isEmpty()) return null
        val w = windowAt(floor(fileSample).toLong()).coerceIn(0L, windows.lastIndex.toLong()).toInt()
        val win = windows[w] ?: return null
        val x = (fileSample - win.downbeat) / win.period
        val k = floor(x)
        val kl = k.toLong()
        return Pos(60.0 * RATE / win.period, Math.floorMod(kl, 4L).toInt(), x - k, win.bar0 + Math.floorDiv(kl, 4L), (x - k) * win.period)
    }

    /** The grid, then the raw window results it came from (so a better [resolve] later needs no new analysis). */
    fun encode(raw: List<BeatGrid.Result?> = emptyList()): String = buildString {
        append(HEADER).append('\n')
        for (w in windows) append(if (w == null) "-" else "${w.period} ${w.downbeat} ${w.bar0} ${w.confidence}").append('\n')
        append("raw\n")
        for (r in raw) append(if (r == null) "-" else "${r.periodSamples} ${r.phaseSample} ${r.barOffset} ${r.confidence}").append('\n')
    }

    companion object {
        const val RATE = 48000.0
        const val WINDOW_S = 8L
        const val LENGTH_S = 16
        private const val HEADER = "beats 2"   // 2: one lattice per track (7 Nov); version 1 files are worked out again
        private const val FILL = 4            // a window with no beat borrows a good one up to this many windows away
        private const val SPAN = 8            // windows either side that vote on the bar start: about a minute each way
        private const val SAME_TEMPO = 0.02   // tempi within 2% count as the same track
        private val WRONG_PULSE = listOf(1.5, 2.0, 2.0 / 3.0, 0.5)

        /** The window whose middle 8 s hold file sample [sample]. */
        fun windowAt(sample: Long): Long = Math.floorDiv(Math.floorDiv(sample, RATE.toLong()) - 4, WINDOW_S)

        /** The raw window results saved after "raw" in a grid file or a progress file. */
        fun decodeRaw(text: String): List<BeatGrid.Result?> = try {
            text.lines().dropWhile { it != "raw" }.drop(1).filter { it.isNotBlank() }.map { l ->
                if (l == "-") null else l.split(' ').let { BeatGrid.Result(it[0].toDouble(), it[1].toLong(), it[2].toInt(), it[3].toFloat()) }
            }
        } catch (_: RuntimeException) { emptyList() }

        fun decode(text: String): SetBeats? {
            val lines = text.lines().filter { it.isNotBlank() }.takeWhile { it != "raw" }
            if (lines.firstOrNull() != HEADER) return null
            return try {
                SetBeats(lines.drop(1).map { l ->
                    if (l == "-") null else l.split(' ').let { Win(it[0].toDouble(), it[1].toDouble(), it[2].toLong(), it[3].toFloat()) }
                })
            } catch (_: RuntimeException) { null }
        }

        /** From each window's own grid (null where it found no clear beat), in window order from the set's start, to one grid for the whole set. */
        fun resolve(raw: List<BeatGrid.Result?>, feat: FloatArray = FloatArray(0)): SetBeats {
            val n = raw.size
            // A window locked onto 2/3, 1/2, 3/2 or 2 times its neighbours' tempo has found the wrong pulse: drop it, so it borrows a neighbour.
            val clean = raw.mapIndexed { w, g ->
                if (g == null) return@mapIndexed null
                val near = (max(0, w - SPAN)..min(n - 1, w + SPAN)).mapNotNull { raw[it]?.periodSamples }.sorted()
                val r = g.periodSamples / near[near.size / 2]
                if (WRONG_PULSE.any { abs(r / it - 1) < 0.04 }) null else g
            }
            // A drumless stretch: borrow the nearest good grid, ahead first, so the beat carries on through it.
            val filled = List(n) { w -> clean[w] ?: (1..FILL).asSequence().flatMap { sequenceOf(w + it, w - it) }.firstNotNullOfOrNull { clean.getOrNull(it) } }
            // Tracks: a new one starts wherever the tempo jumps by more than 2%.
            val track = IntArray(n)
            var last: BeatGrid.Result? = null
            var t = 0
            for (w in 0 until n) {
                val g = filled[w]
                if (g == null) { last = null; t++; track[w] = t; continue }
                if (last == null || abs(g.periodSamples / last.periodSamples - 1) > SAME_TEMPO) t++
                last = g; track[w] = t
            }
            // A steadier tempo: the median period of the same track within four windows. Each window keeps its own beat positions.
            val period = DoubleArray(n) { w ->
                val g = filled[w] ?: return@DoubleArray Double.NaN
                val near = (max(0, w - 4)..min(n - 1, w + 4)).filter { track[it] == track[w] }.mapNotNull { filled[it]?.periodSamples }.sorted()
                if (near.isEmpty()) g.periodSamples else near[near.size / 2]
            }
            // One beat count through each track: each window's beat nearest its start is numbered on from the window before.
            val anchor = DoubleArray(n); val index = LongArray(n)
            for (w in 0 until n) {
                val g = filled[w] ?: continue
                val p = period[w]
                val start = (WINDOW_S * w + 4) * RATE
                val own = g.phaseSample + Math.round((start - g.phaseSample) / g.periodSamples) * g.periodSamples
                anchor[w] = own
                index[w] = if (w > 0 && filled[w - 1] != null && track[w - 1] == track[w]) index[w - 1] + Math.round((own - anchor[w - 1]) / ((p + period[w - 1]) / 2))
                else Math.round(own / p)
            }
            // The bar start, once per track: every window's own downbeat votes on which beat number (mod 4) starts the bars.
            val votes = HashMap<Int, DoubleArray>()
            for (w in 0 until n) {
                val h = clean[w] ?: continue
                val x = (h.phaseSample + h.barOffset * h.periodSamples - anchor[w]) / period[w]
                val k = Math.round(x)
                if (abs(x - k) > 0.25) continue
                votes.getOrPut(track[w]) { DoubleArray(4) }[Math.floorMod(index[w] + k, 4L).toInt()] += h.confidence.toDouble()
            }
            // Better, where the sound is known: bar 1 is where the sound changes most. For each beat, the change in band energies between the two beats
            // after it and the two before it; summed by beat number mod 4 over the whole track, the largest sum marks the bar start.
            val change = HashMap<Int, DoubleArray>()
            val frames = feat.size / BeatFeatures.BANDS
            if (frames > 0) {
                val perFrame = RATE / BeatFeatures.PER_S
                val means = HashMap<Long, FloatArray>()   // per beat number, within the current track
                fun mean(from: Double, to: Double): FloatArray? {
                    val a = (from / perFrame).toInt(); val b = (to / perFrame).toInt()
                    if (a < 0 || b > frames || b <= a) return null
                    val m = FloatArray(BeatFeatures.BANDS)
                    for (f in a until b) for (k in 0 until BeatFeatures.BANDS) m[k] += feat[f * BeatFeatures.BANDS + k]
                    for (k in m.indices) m[k] /= (b - a)
                    return m
                }
                var w = 0
                while (w < n) {
                    val tr = track[w]
                    means.clear()
                    var e = w
                    while (e < n && track[e] == tr) {
                        if (filled[e] != null) {
                            val p = period[e]
                            val start = (WINDOW_S * e + 4) * RATE
                            var j = Math.round((start - anchor[e]) / p)
                            while (anchor[e] + j * p < start + WINDOW_S * RATE) {
                                val at = anchor[e] + j * p
                                mean(at, at + p)?.let { means[index[e] + j] = it }
                                j++
                            }
                        }
                        e++
                    }
                    val sums = DoubleArray(4)
                    for ((i, _) in means) {
                        val after = listOf(means[i], means[i + 1]); val before = listOf(means[i - 2], means[i - 1])
                        if (after.any { it == null } || before.any { it == null }) continue
                        var d = 0.0
                        for (k in 0 until BeatFeatures.BANDS) d += abs((after[0]!![k] + after[1]!![k]) - (before[0]!![k] + before[1]!![k])) / 2
                        sums[Math.floorMod(i, 4L).toInt()] += d
                    }
                    if (sums.sum() > 0) change[tr] = sums
                    w = e
                }
            }
            val wins = arrayOfNulls<Win>(n)
            for (w in 0 until n) {
                val g = filled[w] ?: continue
                val p = period[w]
                val v = change[track[w]] ?: votes[track[w]]
                val phase = if (v == null) Math.floorMod(index[w] + g.barOffset, 4L).toInt() else (0..3).maxBy { v[it] }
                // The downbeat at or just before the start of the 8 s this window is used for, and its bar number from the set's start.
                var k = Math.floorMod(phase - index[w], 4L)
                val start = (WINDOW_S * w + 4) * RATE
                if (anchor[w] + k * p > start) k -= 4
                val down = anchor[w] + k * p
                wins[w] = Win(p, down, Math.floorDiv(index[w] + k - phase, 4L), g.confidence)
            }
            return SetBeats(wins.toList())
        }
    }
}

/**
 * How the sound changes over the set, for finding bar starts: 8 frames a second, each the log energy in 16 bands from 40 Hz to 8 kHz. Plain Kotlin.
 * In dance music the kick lands on every beat, so it can't tell beat 1 from beat 3; what marks a bar start is the sound changing there (a chord, a bass
 * note, a new part coming in), which these frames show.
 */
class BeatFeatures {
    companion object {
        const val PER_S = 8
        const val BANDS = 16
        private const val N = 2048
    }

    private val window = FloatArray(N) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / N)).toFloat() }
    private val cosT = FloatArray(N / 2) { kotlin.math.cos(2 * Math.PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { (-kotlin.math.sin(2 * Math.PI * it / N)).toFloat() }
    // Band edges in FFT bins, log-spaced from 40 Hz to 8 kHz at 48 kHz.
    private val edges = IntArray(BANDS + 1) { (40.0 * Math.pow(200.0, it.toDouble() / BANDS) * N / 48000.0).toInt().coerceAtLeast(1) }
    private val re = FloatArray(N); private val im = FloatArray(N)

    /** One 1 s stereo block (48000 frames) to PER_S × BANDS values. */
    fun block(stereo: ShortArray): FloatArray {
        val out = FloatArray(PER_S * BANDS)
        val hop = stereo.size / 2 / PER_S
        for (f in 0 until PER_S) {
            for (i in 0 until N) {
                val j = f * hop + i
                re[i] = if (j < stereo.size / 2) (stereo[2 * j] + stereo[2 * j + 1]) / 65536f * window[i] else 0f
                im[i] = 0f
            }
            fft()
            for (b in 0 until BANDS) {
                var e = 0f
                for (k in edges[b] until maxOf(edges[b] + 1, edges[b + 1])) e += re[k] * re[k] + im[k] * im[k]
                out[f * BANDS + b] = kotlin.math.ln(1f + 1e4f * e / N)
            }
        }
        return out
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b }
        }
        var len = 2
        while (len <= N) {
            val step = N / len
            for (s in 0 until N step len) for (k in 0 until len / 2) {
                val wr = cosT[k * step]; val wi = sinT[k * step]
                val a = s + k; val b = a + len / 2
                val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }
}
