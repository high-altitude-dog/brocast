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
        // Tick tests on 2 Nov 2026 ranged from 18 to 22 ms. One phone of this model, so model variation is unknown.
        RouteKey("SM-A166B", "speaker") to Calibration(20.0, 3.0, CalSource.TABLE),
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
