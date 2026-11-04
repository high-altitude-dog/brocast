package org.beachsync.proto

import kotlin.math.abs

/**
 * Works out a route's headphone lag from the shared table, the earcup test or the two-phone handshake. Plain Kotlin, tested
 * in simtest/. It only does the arithmetic: nothing here touches the audio thread, and nothing uses these values yet.
 *
 * A larger lag makes the app output earlier, so a phone or route that's later by x ms needs x ms more lag. All times are in
 * ms. Every lag is relative to REFERENCE: the table can't yet say what the absolute delay to the ear is.
 */
enum class CalSource { NONE, TABLE, EARCUP, HANDSHAKE, TYPED }

data class Calibration(val lagMs: Double, val plusMinusMs: Double, val source: CalSource, val measuredUtcMs: Long = 0L)

data class RouteKey(val model: String, val route: String)

/** Middle value, how far readings typically sit from it, and how many there were. */
data class Readings(val medianMs: Double, val scatterMs: Double, val count: Int)

object CalLimits {
    const val MIN_READINGS = 5
    const val MAX_SCATTER_MS = 2.0
    /** Sound travels about 0.34 m in 1 ms, so phones "side by side" shouldn't show more than this. */
    const val MAX_DISTANCE_MS = 5.0
    /** Used when nothing is known. Bluetooth alone can be hundreds of ms out, so this claims nothing. */
    const val UNKNOWN_PLUS_MINUS_MS = 300.0
}

/** Empty readings give null, so a test that heard nothing can never produce a value. */
fun summarise(readingsMs: DoubleArray): Readings? {
    if (readingsMs.isEmpty()) return null
    val med = median(readingsMs)
    return Readings(med, median(DoubleArray(readingsMs.size) { abs(readingsMs[it] - med) }), readingsMs.size)
}

private fun median(x: DoubleArray): Double {
    val s = x.sortedArray()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun usable(r: Readings?) = r != null && r.count >= CalLimits.MIN_READINGS && r.scatterMs <= CalLimits.MAX_SCATTER_MS

/** Measured values, for the routes tested so far. Relative to the Pixel's speaker. */
object LatencyTable {
    const val REFERENCE = "Pixel 10 Pro"
    private val rows = mapOf(
        RouteKey(REFERENCE, "speaker") to Calibration(0.0, 3.0, CalSource.TABLE),
        // 4 Nov 2026: five runs of measure/quicktest.py, both phones on GPS at about ±0.2 ms, gave 14.3 to 14.9 ms (mean 14.7), and
        // setting 14.7 ms brought the gap to -0.04 and -0.09 ms. Across pitches it ranges about ±2 ms, hence the ±2.0. The 2 Nov tick tests
        // had given 18 to 22 ms; the difference is unexplained. One phone of this model, so model variation is unknown.
        RouteKey("SM-A166B", "speaker") to Calibration(14.7, 2.0, CalSource.TABLE),
    )

    fun lookup(key: RouteKey): Calibration = rows[key] ?: Calibration(0.0, CalLimits.UNKNOWN_PLUS_MINUS_MS, CalSource.NONE)
}

/** What the listener saved for this route beats the table. Saving a new value replaces the old one, whichever method made it. */
fun chooseCalibration(saved: Calibration?, table: Calibration): Calibration = saved ?: table

data class HandshakeResult(val laterMs: Double, val distanceMs: Double, val plusMinusMs: Double)

/**
 * Each phone records both phones' clicks, and subtracts the time it heard B's from the time it heard A's:
 *     at A's mic: (A's output + A's mic) - (B's output + distance + A's mic) = A's output - B's output - distance
 *     at B's mic: (A's output + distance + B's mic) - (B's output + B's mic) = A's output - B's output + distance
 * Each mic's own latency cancels within its subtraction, and averaging cancels the distance. Each click's scheduled time must
 * already be taken off, so that only output latency remains. Positive laterMs means phone A's sound comes out later.
 *
 * Each phone's speaker sits a little way from its own mic, which adds an error this can't see: the reason for the ± floor.
 */
fun handshake(atA: Readings?, atB: Readings?, clockPlusMinusMs: Double): HandshakeResult? {
    if (!usable(atA) || !usable(atB)) return null
    val distance = (atB!!.medianMs - atA!!.medianMs) / 2
    if (abs(distance) > CalLimits.MAX_DISTANCE_MS) return null
    val spread = atA.scatterMs + atB.scatterMs
    return HandshakeResult((atA.medianMs + atB.medianMs) / 2, distance, 0.5 + spread / 2 + clockPlusMinusMs)
}

/** The new lag for phone A, when both phones ran the handshake at their current lags. */
fun lagFromHandshake(lagAMs: Double, r: HandshakeResult, utcMs: Long) =
    Calibration(lagAMs + r.laterMs, r.plusMinusMs, CalSource.HANDSHAKE, utcMs)

/**
 * The earcup test: one mic hears the speaker and then the headphones, so its latency cancels, and the difference is how much
 * later the headphones are. Both legs must run at the same lag, or that lag must be added back by the caller. The speaker's
 * own value then gives the headphones': this inherits that value's error, which is why it's in the ±.
 */
fun lagFromEarcup(speaker: Calibration, viaSpeaker: Readings?, viaHeadphones: Readings?, utcMs: Long): Calibration? {
    if (!usable(viaSpeaker) || !usable(viaHeadphones)) return null
    val later = viaHeadphones!!.medianMs - viaSpeaker!!.medianMs
    return Calibration(speaker.lagMs + later, speaker.plusMinusMs + 1.0 + viaSpeaker.scatterMs + viaHeadphones.scatterMs, CalSource.EARCUP, utcMs)
}

/** The one number the second phone needs for the handshake: at 0.1 ms, as a short code. Null if it won't fit in 5 characters. */
fun handshakeCode(ms: Double): String? {
    val tenths = Math.round(ms * 10)
    return if (abs(tenths) > 9999) null else (if (tenths < 0) "-" else "") + abs(tenths).toString().padStart(4, '0')
}

fun parseHandshakeCode(code: String): Double? {
    val t = code.trim()
    if (!Regex("-?\\d{4}").matches(t)) return null
    return t.toInt() / 10.0
}

// ---- The earcup test's analysis ----

object Earcup {
    const val RATE = 48000
    const val CLICKS = 10
    const val SPACING_S = 0.5
    const val PREROLL_S = 1.5       // Bluetooth headsets can miss the first moments of sound
    const val TAIL_S = 0.6
    /** Where a click can arrive after its output time stamp: mic latency, and whatever lag the stamp doesn't cover. */
    const val BEFORE_MS = 40.0
    const val AFTER_MS = 450.0
    const val QUIET = 0.01f         // the loudest sample in a leg, full scale being 1
    const val MIN_STRENGTH = 5.0    // the leg's peak against its typical level

    /** A 20 ms sweep from 1 to 4 kHz, the band that matters most for timing. Far easier to find exactly than a single pitch. */
    fun click(): FloatArray {
        val n = RATE / 50
        var phase = 0.0
        return FloatArray(n) { i ->
            val t = i.toDouble() / n
            phase += 2 * Math.PI * (1000.0 + 3000.0 * t) / RATE
            (Math.sin(phase) * Math.sin(Math.PI * t) * Math.sin(Math.PI * t)).toFloat()
        }
    }
}

/** One leg's clicks. Readings are ms from each click's output time stamp to when the mic heard it. */
class EarcupLeg(val readingsMs: DoubleArray, val peakMs: Double, val strength: Double, val level: Float, val profile: FloatArray)

/** [expected] holds where each click would sit in [rec] if it took no time at all. Null if the leg has no audio to search. */
fun analyseLeg(rec: FloatArray, expected: LongArray, click: FloatArray = Earcup.click()): EarcupLeg? {
    val perMs = Earcup.RATE / 1000.0
    val before = (Earcup.BEFORE_MS * perMs).toInt()
    val span = before + (Earcup.AFTER_MS * perMs).toInt()
    val energy = click.sumOf { (it * it).toDouble() }.toFloat()
    val total = FloatArray(span)
    val each = ArrayList<FloatArray>()
    var level = 0f
    for (e in expected) {
        val from = e.toInt() - before
        if (from < 0 || from + span + click.size > rec.size) continue
        val c = FloatArray(span) { k ->
            var sum = 0f
            for (j in click.indices) sum += click[j] * rec[from + k + j]
            kotlin.math.abs(sum) / energy
        }
        for (k in 0 until span) total[k] += c[k]
        for (k in 0 until span + click.size) level = maxOf(level, kotlin.math.abs(rec[from + k]))
        each.add(c)
    }
    if (each.isEmpty()) return null
    val peak = total.indices.maxByOrNull { total[it] }!!
    val typical = median(DoubleArray(span) { total[it].toDouble() })
    // Each click finds its own peak anywhere in the window, so clicks that disagree show up as scatter rather than being hidden.
    val tops = each.map { c ->
        val at = c.indices.maxByOrNull { c[it] }!!
        at to c[at]
    }
    val strongest = median(DoubleArray(tops.size) { tops[it].second.toDouble() })
    val readings = tops.filter { it.second >= 0.3 * strongest }.map { (it.first - before) / perMs }.toDoubleArray()
    return EarcupLeg(readings, (peak - before) / perMs, if (typical > 0) total[peak] / typical else 0.0, level, total)
}

enum class EarcupFault { TOO_QUIET, TOO_NOISY, DISAGREE }

sealed class EarcupOutcome {
    class Pass(val laterMs: Double, val speaker: EarcupLeg, val headphones: EarcupLeg, val scatterMs: Double) : EarcupOutcome()
    class Fail(val fault: EarcupFault, val lowMs: Double = 0.0, val highMs: Double = 0.0) : EarcupOutcome()
    class Problem(val text: String) : EarcupOutcome()
}

/** Faults first in the order that decides the fix: no signal, then a signal lost in noise, then a signal that won't hold still. */
fun earcupVerdict(speaker: EarcupLeg?, headphones: EarcupLeg?): EarcupOutcome {
    if (speaker == null || headphones == null) return EarcupOutcome.Fail(EarcupFault.TOO_QUIET)
    for (leg in listOf(speaker, headphones)) if (leg.level < Earcup.QUIET) return EarcupOutcome.Fail(EarcupFault.TOO_QUIET)
    for (leg in listOf(speaker, headphones)) if (leg.strength < Earcup.MIN_STRENGTH) return EarcupOutcome.Fail(EarcupFault.TOO_NOISY)
    val a = summarise(speaker.readingsMs)
    val b = summarise(headphones.readingsMs)
    if (!usable(a) || !usable(b)) {
        val all = (if (usable(a)) headphones else speaker).readingsMs
        return EarcupOutcome.Fail(EarcupFault.DISAGREE, all.minOrNull() ?: 0.0, all.maxOrNull() ?: 0.0)
    }
    return EarcupOutcome.Pass(b!!.medianMs - a!!.medianMs, speaker, headphones, a.scatterMs + b.scatterMs)
}
