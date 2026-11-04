package org.beachsync.proto

import kotlin.math.abs
import kotlin.math.max

/*
 * Closing gaps. The Renderer always tracks the audio clock, which can't wait. This decides how a gap closes when
 * flowtime itself moves: straight away in Auto, or when the listener presses Go in Manual. Plain Kotlin, so simtest
 * can run it. The control thread builds each NudgeState; the audio thread only reads the current one.
 */

enum class NudgeMode { AUTO, MANUAL }

/**
 * What the audio thread reads: the fit to use, and how far the stream is held behind it. The gap falls at [rate]
 * (same sign as [held0]) from [boot0] until it reaches zero. Immutable, and its methods don't allocate.
 */
class NudgeState(val fit: ClockFit?, val held0: Long, val boot0: Long, val rate: Double) {
    private val end = if (rate == 0.0) Long.MAX_VALUE else boot0 + (held0 / rate).toLong()
    val closing get() = rate != 0.0

    fun heldAt(boot: Long): Long = when {
        rate == 0.0 -> held0
        boot >= end -> 0L
        else -> held0 - ((boot - boot0) * rate).toLong()
    }

    fun rateAt(boot: Long): Double = if (boot < end) rate else 0.0

    fun done(boot: Long) = rate != 0.0 && boot >= end
}

/** The project's terms. A positive held gap means the audio is heard behind flowtime, and a positive rate speeds it up. */
fun gapWord(heldNs: Long) = when { heldNs > 0 -> "Dragging"; heldNs < 0 -> "Rushing"; else -> "In step" }
fun nudgeWord(rate: Double) = when { rate > 0 -> "Catching-up"; rate < 0 -> "Slowing-down"; else -> "Not nudging" }

/** Formats only the number: the sentence itself holds a percent sign, which a format string would read as an instruction. */
fun tooFastNote(gapNs: Long, fastestS: Double) =
    "Log scale. The hatched start is too fast: the 0.1% rate limit means ${fmtDuration(gapNs.toDouble())} takes at least " + "%.1f s.".format(java.util.Locale.ROOT, fastestS)

class NudgeControl(mode: NudgeMode, periodS: Double?, private val note: (String) -> Unit = {}) {
    @Volatile var state = NudgeState(null, 0L, 0L, 0.0)
        private set
    @Volatile var mode = mode
        private set
    /** The listener's chosen period, or null for the recommended one. */
    @Volatile var periodS = periodS
        private set
    private var closeStart = 0L

    fun gapAt(boot: Long) = state.heldAt(boot)

    /** The period a gap of this size would really take: the chosen or recommended one, but never above the rate limit. */
    fun effectivePeriodS(gapNs: Long): Double = max(periodS ?: recommendedS(gapNs), fastestS(gapNs))

    fun secondsLeft(boot: Long): Double {
        val s = state
        return if (s.closing && !s.done(boot)) abs(s.heldAt(boot) / s.rate) / 1e9 else 0.0
    }

    /** Called about ten times a second with flowtime's current fit. */
    @Synchronized
    fun tick(boot: Long, fit: ClockFit?) {
        val cur = state
        if (fit == null) return
        val curFit = cur.fit
        if (curFit == null) { state = NudgeState(fit, 0L, boot, 0.0); return }
        val finished = cur.done(boot)
        if (finished) closed(boot)
        if (fit === curFit) {
            if (finished) state = NudgeState(fit, 0L, boot, 0.0)
            return
        }
        // Keep the stream exactly where it is: the new fit plus a held gap gives the same position as the old fit did.
        val delta = fit.utcAt(boot) - curFit.utcAt(boot)
        val held = cur.heldAt(boot) + delta
        val wasClosing = cur.closing && !finished
        when {
            abs(held) <= ABSORB_NS -> {
                if (wasClosing) closed(boot)
                state = NudgeState(fit, 0L, boot, 0.0)   // routine: the Renderer takes a step this small in its stride
            }
            mode == NudgeMode.AUTO && abs(held) > JUMP_NS -> {
                note("This device is ${gapWord(held).lowercase()} ${fmt(held)}: too large to nudge, so jumping straight to the right point")
                state = NudgeState(fit, 0L, boot, 0.0)   // the Renderer jumps when it sees the difference
            }
            wasClosing -> {
                val keep = abs(delta) <= ABSORB_NS && (held > 0) == (cur.rate > 0)
                state = NudgeState(fit, held, boot, if (keep) cur.rate else rateFor(held))
            }
            mode == NudgeMode.AUTO -> begin(fit, held, boot)
            else -> {
                if (cur.held0 == 0L || finished) note("This device is ${gapWord(held).lowercase()} ${fmt(held)}: press Go to close it")
                state = NudgeState(fit, held, boot, 0.0)
            }
        }
    }

    @Synchronized
    fun setMode(m: NudgeMode, boot: Long) {
        if (m == mode) return
        mode = m
        note("Nudge mode set to $m by you: ${if (m == NudgeMode.AUTO) "gaps close by themselves" else "gaps wait for Go"}")
        val s = state
        val h = s.heldAt(boot)
        if (m == NudgeMode.AUTO && h != 0L && !s.closing) s.fit?.let { begin(it, h, boot) }
    }

    /** A new period also applies to a gap already closing. */
    @Synchronized
    fun setPeriod(p: Double?, boot: Long) {
        periodS = p
        val s = state
        val h = s.heldAt(boot)
        if (s.closing && h != 0L) state = NudgeState(s.fit, h, boot, rateFor(h))
    }

    @Synchronized
    fun go(boot: Long) {
        val s = state
        val h = s.heldAt(boot)
        if (h != 0L && !s.closing) s.fit?.let { begin(it, h, boot) }
    }

    @Synchronized
    fun pause(boot: Long) {
        val s = state
        if (!s.closing || s.done(boot)) return
        val h = s.heldAt(boot)
        state = NudgeState(s.fit, h, boot, 0.0)
        note("You paused: this device is still ${gapWord(h).lowercase()} ${fmt(h)}")
    }

    @Synchronized
    fun jump(boot: Long) {
        val s = state
        val h = s.heldAt(boot)
        if (h == 0L) return
        state = NudgeState(s.fit, 0L, boot, 0.0)
        note("You chose Jump now: this device was ${gapWord(h).lowercase()} ${fmt(h)}, now in step")
    }

    private fun begin(fit: ClockFit, held: Long, boot: Long) {
        closeStart = boot
        state = NudgeState(fit, held, boot, rateFor(held))
        note("This device is ${gapWord(held).lowercase()} ${fmt(held)}: ${nudgeWord(state.rate).lowercase()} playback over %.0f s".format(effectivePeriodS(abs(held))))
    }

    private fun closed(boot: Long) = note("Caught up after %.0f s: in step".format((boot - closeStart) / 1e9))

    private fun rateFor(held: Long): Double {
        val r = abs(held) / (effectivePeriodS(abs(held)) * 1e9)
        return if (held > 0) r else -r
    }

    companion object {
        const val ABSORB_NS = 100_000L            // 0.1 ms: routine corrections never need Go
        const val JUMP_NS = 20_000_000L           // above 20 ms, Auto jumps; Manual lets the listener choose
        const val MAX_RATE = 0.0009               // inside the Renderer's 0.1% limit, leaving room to track the audio clock
        const val MIN_PERIOD_S = 1.0
        const val MAX_PERIOD_S = 300.0
        const val RECOMMENDED_S = 60.0            // a starting value for listening alone; events would use the fastest

        fun fastestS(gapNs: Long) = abs(gapNs) / MAX_RATE / 1e9
        fun recommendedS(gapNs: Long) = max(RECOMMENDED_S, fastestS(gapNs))
        fun fmt(ns: Long) = fmtDuration(ns.toDouble())
    }
}
