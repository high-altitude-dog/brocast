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
    if (a < 1e3) return "%.0f ns".format(Locale.ROOT, a)
    val (v, unit) = when {
        a < 1e6 -> a / 1e3 to "µs"
        a < 1e9 -> a / 1e6 to "ms"
        else -> a / 1e9 to "s"
    }
    val digits = when { v < 10 -> 2; v < 100 -> 1; else -> 0 }
    return "%.${digits}f %s".format(Locale.ROOT, v, unit)
}

/** The same, with a sign in front. */
fun fmtSigned(ns: Double): String = when {
    ns.isNaN() || ns.isInfinite() -> "—"
    ns < 0 -> "−" + fmtDuration(ns)
    else -> "+" + fmtDuration(ns)
}

enum class Lane { CLOCK, AUDIO, NET, OUTPUT }

class Event(val boot: Long, val utc: Long?, val lane: Lane, val text: String)

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
    private var fetchFailing = false
    private var fetchSince = 0L
    private var fetchErrorsAtStart = 0
    private var ntpFailing = false
    private var ntpSince = 0L
    private var route: String? = null
    private var audioError: String? = null
    private var tightRef = Double.NaN
    private var tightSince = -1L

    /** While an improvement is being confirmed, the error it improves on; otherwise NaN. For the status panel. */
    @Volatile var tighteningFromNs = Double.NaN
        private set

    fun update(r: Reading): List<Event> {
        val out = ArrayList<Event>()
        fun add(lane: Lane, text: String) { out.add(Event(r.boot, r.utc, lane, text)) }

        if (r.source != source) {
            val err = if (r.errNs.isNaN() || r.errNs.isInfinite()) "" else " (±${fmtDuration(r.errNs)})"
            add(Lane.CLOCK, when {
                r.source == null -> "No time source"
                source == null -> "Time source: ${r.source}$err"
                else -> "Time source changed from $source to ${r.source}$err"
            })
            source = r.source
        }

        // Tightened: the estimate is at least 20% better and stays so for 10 s. Satellite count alone never triggers it.
        if (r.source == null || r.errNs.isNaN() || r.errNs.isInfinite()) {
            tightRef = Double.NaN; tightSince = -1
        } else if (tightRef.isNaN() || r.errNs > TIGHT_WORSE * tightRef) {
            tightRef = r.errNs; tightSince = -1
        } else if (r.errNs < TIGHT_BETTER * tightRef && tightRef >= TIGHT_FLOOR_NS) {
            if (tightSince < 0) tightSince = r.boot
            if (r.boot - tightSince >= TIGHT_HOLD_NS) {
                val sats = if (r.satellites > 0) ", ${r.satellites} satellites" else ""
                add(Lane.CLOCK, "Sync tightened from ±${fmtDuration(tightRef)} to ±${fmtDuration(r.errNs)}$sats")
                tightRef = r.errNs; tightSince = -1
            }
        } else tightSince = -1
        tighteningFromNs = if (tightSince >= 0) tightRef else Double.NaN

        if (r.gpsLastBoot > 0) {
            if (!gpsSeen) { gpsSeen = true; add(Lane.CLOCK, "First GPS time report") }
            val age = r.boot - r.gpsLastBoot
            if (!gpsLost && age > GPS_LOST_NS) {
                gpsLost = true; gpsLostSince = r.gpsLastBoot
                add(Lane.CLOCK, "GPS lost: no report for ${age / 1_000_000_000L} s, carrying on from the last fit")
            } else if (gpsLost && age <= GPS_LOST_NS) {
                gpsLost = false
                add(Lane.CLOCK, "GPS back after ${(r.gpsLastBoot - gpsLostSince) / 1_000_000_000L} s")
            }
        }

        if (r.underruns > underruns) {
            add(Lane.AUDIO, "Underrun: the audio output ran dry (×${r.underruns - underruns})")
        }
        underruns = r.underruns
        if (r.jumps > jumps) {
            add(Lane.AUDIO, "Jump: more than 20 ms out, moved straight to the right point (×${r.jumps - jumps})")
        }
        jumps = r.jumps
        if (r.missingFrames > missingFrames) {
            add(Lane.AUDIO, "Silence: ${(r.missingFrames - missingFrames) / FRAMES_PER_MS} ms of audio wasn't here in time")
        }
        missingFrames = r.missingFrames
        if (r.audioError != null && r.audioError != audioError) add(Lane.AUDIO, "Audio error: ${r.audioError}")
        audioError = r.audioError

        if (r.fetchError != null && !fetchFailing) {
            fetchFailing = true; fetchSince = r.boot; fetchErrorsAtStart = r.fetchErrors - 1
            add(Lane.NET, "Stream fetch failing: ${r.fetchError}")
        } else if (r.fetchError == null && fetchFailing) {
            fetchFailing = false
            add(Lane.NET, "Stream fetch working again after ${(r.boot - fetchSince) / 1_000_000_000L} s " +
                "(${r.fetchErrors - fetchErrorsAtStart} errors)")
        }

        if (r.ntpError != null && !ntpFailing) {
            ntpFailing = true; ntpSince = r.boot
            add(Lane.NET, "NTP failing: ${r.ntpError}")
        } else if (r.ntpError == null && ntpFailing) {
            ntpFailing = false
            add(Lane.NET, "NTP working again after ${(r.boot - ntpSince) / 1_000_000_000L} s")
        }

        if (r.route != null && r.route != route) {
            add(Lane.OUTPUT, if (route == null) "Audio output: ${r.route}" else "Audio output changed to ${r.route}")
            route = r.route
        }
        return out
    }

    private companion object {
        const val GPS_LOST_NS = 5_000_000_000L
        const val FRAMES_PER_MS = 48L
        const val TIGHT_BETTER = 0.8
        const val TIGHT_WORSE = 1.25
        const val TIGHT_HOLD_NS = 10_000_000_000L
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
