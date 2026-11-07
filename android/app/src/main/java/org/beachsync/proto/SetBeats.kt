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
 * set is in hand, [resolve] can then look both ways: a drumless window borrows the nearest good grid, and each window's bar start is the vote of every window
 * within about a minute that shares its tempo (the same track), so bars stop flipping from one window to the next. Bars are numbered from the start of the set.
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

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        for (w in windows) append(if (w == null) "-" else "${w.period} ${w.downbeat} ${w.bar0} ${w.confidence}").append('\n')
    }

    companion object {
        const val RATE = 48000.0
        const val WINDOW_S = 8L
        const val LENGTH_S = 16
        private const val HEADER = "beats 1"
        private const val FILL = 4            // a window with no beat borrows a good one up to this many windows away
        private const val SPAN = 8            // windows either side that vote on the bar start: about a minute each way
        private const val SAME_TEMPO = 0.02   // tempi within 2% count as the same track

        /** The window whose middle 8 s hold file sample [sample]. */
        fun windowAt(sample: Long): Long = Math.floorDiv(Math.floorDiv(sample, RATE.toLong()) - 4, WINDOW_S)

        fun decode(text: String): SetBeats? {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.firstOrNull() != HEADER) return null
            return try {
                SetBeats(lines.drop(1).map { l ->
                    if (l == "-") null else l.split(' ').let { Win(it[0].toDouble(), it[1].toDouble(), it[2].toLong(), it[3].toFloat()) }
                })
            } catch (_: RuntimeException) { null }
        }

        /** From each window's own grid (null where it found no clear beat), in window order from the set's start, to one grid for the whole set. */
        fun resolve(raw: List<BeatGrid.Result?>): SetBeats {
            val n = raw.size
            // A drumless stretch: borrow the nearest good grid, ahead first, so the beat carries on through it.
            val filled = List(n) { w -> raw[w] ?: (1..FILL).asSequence().flatMap { sequenceOf(w + it, w - it) }.firstNotNullOfOrNull { raw.getOrNull(it) } }
            // Tracks: a new one starts wherever the tempo jumps by more than 2%.
            val track = IntArray(n)
            var last: BeatGrid.Result? = null
            var t = 0
            for (w in 0 until n) {
                val g = filled[w]
                if (g != null) { if (last != null && abs(g.periodSamples / last.periodSamples - 1) > SAME_TEMPO) t++; last = g }
                track[w] = t
            }
            val wins = arrayOfNulls<Win>(n)
            var prevDown = Double.NaN; var prevBar = 0L
            for (w in 0 until n) {
                val g = filled[w] ?: continue
                // The bar start: every window of the same track nearby votes, mapped onto this window's beats, weighted by confidence and nearness.
                // A vote whose mapping lands far from a beat (tempo drift) is left out.
                val votes = DoubleArray(4)
                for (j in max(0, w - SPAN)..min(n - 1, w + SPAN)) {
                    val h = raw[j] ?: continue
                    if (track[j] != track[w]) continue
                    val x = (h.phaseSample + h.barOffset * h.periodSamples - g.phaseSample) / g.periodSamples
                    val k = Math.round(x)
                    if (abs(x - k) > 0.25) continue
                    votes[Math.floorMod(k, 4L).toInt()] += h.confidence * (1.0 - abs(j - w).toDouble() / (SPAN + 1))
                }
                var best = Math.floorMod(g.barOffset, 4)
                for (i in 0..3) if (votes[i] > votes[best]) best = i
                val bar = 4 * g.periodSamples
                // The downbeat at or just before the start of the 8 s this window is used for.
                val start = (WINDOW_S * w + 4) * RATE
                var down = g.phaseSample + best * g.periodSamples
                down += floor((start - down) / bar) * bar
                val bar0 = if (prevDown.isNaN()) max(0L, (down / bar).roundToLong()) else prevBar + ((down - prevDown) / bar).roundToLong()
                wins[w] = Win(g.periodSamples, down, bar0, g.confidence)
                prevDown = down; prevBar = bar0
            }
            return SetBeats(wins.toList())
        }
    }
}
