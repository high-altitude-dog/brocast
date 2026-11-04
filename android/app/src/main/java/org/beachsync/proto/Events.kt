package org.beachsync.proto

import java.util.Locale
import kotlin.math.abs

/*
 * The plain event log: what changed during a run, in plain words.
 * A low-priority thread compares readings of the app's existing counters once a second, so nothing here
 * ever runs on the audio thread. Plain Kotlin (no Android calls), so simtest can run it on a desktop.
 */

/** A duration in adaptive units at three significant figures, as design/ui-spec.md asks. Always positive. */
fun fmtDuration(ns: Double): String {
    if (ns.isNaN() || ns.isInfinite()) return "—"
    val a = abs(ns)
    if (a == 0.0) return "0 µs"   // zero is under 1 ms too
    if (a < 1e3) return "%.0f ns".format(Locale.ROOT, a)
    val (v, unit) = when {
        a < 1e6 -> a / 1e3 to "µs"
        a < 1e9 -> a / 1e6 to "ms"
        else -> a / 1e9 to "s"
    }
    val digits = when { v < 10 -> 2; v < 100 -> 1; else -> 0 }
    return "%.${digits}f %s".format(Locale.ROOT, v, unit)
}

/** A value given in milliseconds, in microseconds (or nanoseconds) when it is under 1 ms, as the user decided on 2 Nov. [digits] applies to the milliseconds form. */
fun fmtMillis(ms: Double, digits: Int = 1): String {
    if (ms.isNaN() || ms.isInfinite()) return "—"
    val a = abs(ms)
    if (a < 1.0) return (if (ms < 0) "-" else "") + fmtDuration(a * 1e6)
    return "%.${digits}f ms".format(Locale.ROOT, ms)
}

/** The same, with a sign in front. */
fun fmtSigned(ns: Double): String = when {
    ns.isNaN() || ns.isInfinite() -> "—"
    ns < 0 -> "−" + fmtDuration(ns)
    else -> "+" + fmtDuration(ns)
}

enum class Lane { CLOCK, AUDIO, NET, OUTPUT }

class Event(val boot: Long, val utc: Long?, val lane: Lane, val text: String)

/** A time source in a listener's words: no internal names ("internet time", not "NTP"). */
private fun srcWord(s: Source?): String = if (s == Source.NTP) "internet time" else s.toString()

/** One look at the app's state. */
class Reading(
    val boot: Long,
    val utc: Long?,
    val source: Source?,
    val errNs: Double,
    val gpsLastBoot: Long,       // 0 until the first GPS report
    val underruns: Int,
    val jumps: Int,
    val missingFrames: Long,
    val fetchError: String?,     // null while stream fetches are working
    val fetchErrors: Int,
    val ntpError: String?,       // null while NTP is working
    val route: String?,          // null until there is an output
    val audioError: String?,
    val satellites: Int = 0,
    val ntpMinusGpsNs: Double = Double.NaN,   // NaN unless both fits exist
    val ntpErrNs: Double = Double.NaN,
)

/** Turns successive readings into events: one for each change, not one for each second a state lasts. */
class EventDetector {
    private var source: Source? = null
    private var gpsSeen = false
    private var gpsLost = false
    private var gpsLostSince = 0L
    private var underruns = 0
    private var jumps = 0
    private var missingFrames = 0L
    private var silent = false
    private var silentSince = 0L
    private var silentLast = 0L
    private var silentFrames = 0L
    private var silentReadings = 0
    private var fetchFailing = false
    private var fetchSince = 0L
    private var fetchErrorsAtStart = 0
    private var ntpFailing = false
    private var ntpSince = 0L
    private val fetchTexts = HashSet<String>()
    private val ntpTexts = HashSet<String>()
    private var route: String? = null
    private var audioError: String? = null
    private var tightRef = Double.NaN
    private var tightSince = -1L
    private var shownFrom = Double.NaN
    private var shownUntil = 0L
    private var checkBadSince = -1L
    private var checkGoodSince = -1L

    /** True while GPS and internet time disagree by more than internet time's own error (and 50 ms) for 10 s. The design's CLOCK CHECK FAILED. */
    @Volatile var clockCheckFailed = false
        private set

    /** For a short while after a lasting improvement is confirmed, the error it improved on; otherwise NaN. For the screens. */
    @Volatile var tighteningFromNs = Double.NaN
        private set

    fun update(r: Reading): List<Event> {
        val out = ArrayList<Event>()
        fun add(lane: Lane, text: String) { out.add(Event(r.boot, r.utc, lane, text)) }

        if (r.source != source) {
            val err = if (r.errNs.isNaN() || r.errNs.isInfinite()) "" else " (±${fmtDuration(r.errNs)})"
            add(Lane.CLOCK, when {
                r.source == null -> "No time source: playback has nothing to keep time with"
                source == null -> "Time source is ${srcWord(r.source)}$err: this is what playback now follows"
                else -> "Time source changed from ${srcWord(source)} to ${srcWord(r.source)}$err: playback now follows it"
            })
            source = r.source
        }

        // Tightened: the estimate is at least 20% better and stays so for 10 s. Satellite count alone never triggers it.
        if (r.source == null || r.errNs.isNaN() || r.errNs.isInfinite()) {
            tightRef = Double.NaN; tightSince = -1; shownFrom = Double.NaN
        } else if (tightRef.isNaN() || r.errNs > TIGHT_WORSE * tightRef) {
            tightRef = r.errNs; tightSince = -1; shownFrom = Double.NaN
        } else if (r.errNs < TIGHT_BETTER * tightRef && tightRef >= TIGHT_FLOOR_NS) {
            if (tightSince < 0) tightSince = r.boot
            if (r.boot - tightSince >= TIGHT_HOLD_NS) {
                val sats = if (r.satellites > 0) ", ${r.satellites} satellites" else ""
                add(Lane.CLOCK, "Timing tightened from ±${fmtDuration(tightRef)} to ±${fmtDuration(r.errNs)}$sats: keeping the better time")
                if (shownFrom.isNaN()) shownFrom = tightRef   // a second step straight after keeps the first figure
                shownUntil = r.boot + TIGHT_SHOW_NS
                tightRef = r.errNs; tightSince = -1
            }
        } else tightSince = -1
        if (r.boot >= shownUntil) shownFrom = Double.NaN
        tighteningFromNs = shownFrom

        // The cross-check: both clocks can't be right. Internet time is rough (tens to hundreds of ms), so it only counts when the gap is bigger than its own error.
        if (!r.ntpMinusGpsNs.isNaN() && !r.ntpErrNs.isNaN()) {
            val limit = maxOf(CHECK_MIN_NS, r.ntpErrNs)
            if (abs(r.ntpMinusGpsNs) > limit) {
                checkGoodSince = -1
                if (checkBadSince < 0) checkBadSince = r.boot
                if (!clockCheckFailed && r.boot - checkBadSince >= CHECK_HOLD_NS) {
                    clockCheckFailed = true
                    add(Lane.CLOCK, "GPS and internet time differ by ${fmtDuration(abs(r.ntpMinusGpsNs))} (internet time ±${fmtDuration(r.ntpErrNs)}): clock check failed, so the time can't be trusted yet")
                }
            } else {
                checkBadSince = -1
                if (clockCheckFailed) {
                    if (checkGoodSince < 0) checkGoodSince = r.boot
                    if (r.boot - checkGoodSince >= CHECK_HOLD_NS) { clockCheckFailed = false; add(Lane.CLOCK, "GPS and internet time agree again: clock check passed") }
                }
            }
        }

        if (r.gpsLastBoot > 0) {
            if (!gpsSeen) { gpsSeen = true; add(Lane.CLOCK, "First GPS time report: the satellites are sharing their clock") }
            val age = r.boot - r.gpsLastBoot
            if (!gpsLost && age > GPS_LOST_NS) {
                gpsLost = true; gpsLostSince = r.gpsLastBoot
                add(Lane.CLOCK, "GPS lost, no report for ${age / 1_000_000_000L} s: holding time from the last fix, with the error growing")
            } else if (gpsLost && age <= GPS_LOST_NS) {
                gpsLost = false
                add(Lane.CLOCK, "GPS back after ${(r.gpsLastBoot - gpsLostSince) / 1_000_000_000L} s: timing tightening again")
            }
        }

        if (r.underruns > underruns) {
            add(Lane.AUDIO, "Audio ran dry (×${r.underruns - underruns}): played silence and stayed in step")
        }
        underruns = r.underruns
        if (r.jumps > jumps) {
            add(Lane.AUDIO, "Audio was more than 20 ms out (×${r.jumps - jumps}): moved straight to the right point")
        }
        jumps = r.jumps
        // One line when a silence starts, and one when it ends if it lasted more than a single reading.
        if (r.missingFrames > missingFrames) {
            val lost = r.missingFrames - missingFrames
            if (!silent) {
                silent = true; silentSince = r.boot; silentFrames = 0L; silentReadings = 0
                add(Lane.AUDIO, "Audio ran dry for ${fmtDuration(lost * 1e9 / (FRAMES_PER_MS * 1000))}: played silence and stayed in step")
            }
            silentFrames += lost; silentReadings++; silentLast = r.boot
        } else if (silent && r.boot - silentLast >= SILENCE_OVER_NS) {
            silent = false
            if (silentReadings > 1) {
                add(Lane.AUDIO, "Audio back after ${(silentLast - silentSince) / 1_000_000_000L} s: ${fmtDuration(silentFrames * 1e9 / (FRAMES_PER_MS * 1000))} of sound was missing")
            }
        }
        missingFrames = r.missingFrames
        if (r.audioError != null && r.audioError != audioError) add(Lane.AUDIO, "Audio output error: ${r.audioError}. Trying to carry on")
        audioError = r.audioError

        if (r.fetchError != null && !fetchFailing) {
            fetchFailing = true; fetchSince = r.boot; fetchErrorsAtStart = r.fetchErrors - 1
            fetchTexts.clear(); fetchTexts.add(r.fetchError)
            add(Lane.NET, "Can't reach the station: ${r.fetchError}. Playing from downloads")
        } else if (r.fetchError != null && fetchTexts.size < MAX_FAIL_TEXTS && fetchTexts.add(r.fetchError)) {
            add(Lane.NET, "Still can't reach the station: ${r.fetchError}. Playing from downloads")
        } else if (r.fetchError == null && fetchFailing) {
            fetchFailing = false
            add(Lane.NET, "Station reachable again after ${(r.boot - fetchSince) / 1_000_000_000L} s: ${r.fetchErrors - fetchErrorsAtStart} requests failed meanwhile")
        }

        if (r.ntpError != null && !ntpFailing) {
            ntpFailing = true; ntpSince = r.boot
            ntpTexts.clear(); ntpTexts.add(r.ntpError)
            add(Lane.NET, "Can't reach internet time: ${r.ntpError}. Using satellites if they are on")
        } else if (r.ntpError != null && ntpTexts.size < MAX_FAIL_TEXTS && ntpTexts.add(r.ntpError)) {
            // Each server's message once: NTP cycles through its servers, which would otherwise add a line every poll.
            add(Lane.NET, "Still can't reach internet time: ${r.ntpError}")
        } else if (r.ntpError == null && ntpFailing) {
            ntpFailing = false
            add(Lane.NET, "Internet time reachable again after ${(r.boot - ntpSince) / 1_000_000_000L} s: nothing was missed")
        }

        if (r.route != null && r.route != route) {
            add(Lane.OUTPUT, if (route == null) "Output is ${r.route}: audio is sent early to match its delay" else "Output changed to ${r.route}: audio now sent early to match its delay")
            route = r.route
        }
        return out
    }

    private companion object {
        const val GPS_LOST_NS = 5_000_000_000L
        const val CHECK_MIN_NS = 50_000_000.0
        const val CHECK_HOLD_NS = 10_000_000_000L
        const val FRAMES_PER_MS = 48L
        const val SILENCE_OVER_NS = 3_000_000_000L   // this long with nothing missing ends a silence
        const val TIGHT_BETTER = 0.8
        const val TIGHT_WORSE = 1.25
        const val TIGHT_HOLD_NS = 10_000_000_000L
        const val TIGHT_SHOW_NS = 10_000_000_000L
        const val MAX_FAIL_TEXTS = 8              // a message that varies each time can't fill the log
        const val TIGHT_FLOOR_NS = 100_000.0      // below 0.1 ms, changes are routine and not worth an entry
    }
}

/** The newest events for the screen. Every event also goes to [sink], which writes the full record. */
class EventLog(private val keep: Int = 1000, private val sink: (Event) -> Unit = {}) {
    private val list = ArrayDeque<Event>()

    /** How many events have ever been added, so the screen can tell when to redraw. */
    @Volatile var total = 0
        private set

    @Synchronized
    fun add(e: Event) {
        list.addLast(e)
        if (list.size > keep) list.removeFirst()
        total++
        sink(e)
    }

    @Synchronized fun latest(): Event? = list.lastOrNull()

    /** Newest first. */
    @Synchronized fun recent(max: Int): List<Event> = list.takeLast(max).reversed()
}
