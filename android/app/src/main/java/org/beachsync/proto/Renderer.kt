package org.beachsync.proto

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** One 1-second segment of the stream (48,000 stereo frames, s16 interleaved), or null if it is not here yet. */
fun interface SegmentSource {
    fun segment(n: Long): ShortArray?
}

/**
 * The timing core of playout. For each chunk, the caller says when its first frame will reach the
 * listener's ear, in flowtime (UTC ns), and this fills the chunk so the right sound is heard at that moment.
 * No Android calls and no allocation: it runs on the audio thread, and it can be tested on a desktop.
 */
/**
 * [channel] picks what stream mode plays: [BOTH] as sent, or only [LEFT] or [RIGHT] on both outputs. The
 * acoustic test uses one each, so a single microphone can tell two phones apart by the pitch of each channel.
 */
class Renderer(
    private val clickHz: Double, private val source: SegmentSource?, private val channel: Int = BOTH,
    private val tickPattern: Int = PATTERN_OFF, private val soloSlot: Int = 0, private val soloCount: Int = 0,
) {
    var pos = Double.NaN          // stream position (in samples) of the next frame to write
        private set
    var ratio = 1.0               // stream samples per output frame: 1 plus or minus the nudge
        private set
    var posErrUs = Double.NaN     // how far ahead (+) or behind (-) the stream was at the start of the last chunk
        private set
    var jumps = 0
        private set
    var missingFrames = 0L
        private set
    @Volatile var tickFromUtc = Long.MAX_VALUE    // the tick pattern's sounding window; silent until something sets it
    @Volatile var tickUntilUtc = Long.MIN_VALUE
    private var integral = 0.0
    private var segN = Long.MIN_VALUE
    private var seg: ShortArray? = null

    fun reset() { pos = Double.NaN; ratio = 1.0; posErrUs = Double.NaN; integral = 0.0 }

    /** Tick mode: a 5 ms tone burst starting exactly on every whole second of flowtime, as heard. */
    fun ticks(buf: FloatArray, frames: Int, earUtc0: Long) {
        if (tickPattern != PATTERN_OFF) return patternTicks(buf, frames, earUtc0)
        val phase0 = Math.floorMod(earUtc0, NS_PER_S).toDouble()
        for (i in 0 until frames) {
            var t = phase0 + i * NS_PER_FRAME
            if (t >= 1e9) t -= 1e9
            val v = click(t, clickHz)
            buf[2 * i] = v
            buf[2 * i + 1] = v
        }
    }

    /**
     * The quick acoustic test (measure/quicktest.py): a click every [PATTERN_SLOT_NS] of flowtime, stepping through
     * [PATTERN_HZ]. Both phones play the same pitch, with phone B [PATTERN_STAGGER_NS] after phone A, so the clicks never
     * overlap, the order says which phone is which, and the recording shows the gap at each pitch. It stays silent
     * outside the window [tickFromUtc, tickUntilUtc) in flowtime, so a phone can hold its GPS lock quietly until the test starts.
     */
    private fun patternTicks(buf: FloatArray, frames: Int, earUtc0: Long) {
        if (earUtc0 < tickFromUtc || earUtc0 >= tickUntilUtc) return silence(buf, frames)
        val cycle = PATTERN_SLOT_NS * PATTERN_HZ.size
        val stagger = if (tickPattern == PATTERN_B) PATTERN_STAGGER_NS else 0L
        val phase0 = Math.floorMod(earUtc0 - stagger, cycle).toDouble()
        for (i in 0 until frames) {
            var t = phase0 + i * NS_PER_FRAME
            if (t >= cycle) t -= cycle
            val slot = (t / PATTERN_SLOT_NS).toInt()
            val v = click(t - slot * PATTERN_SLOT_NS, PATTERN_HZ[slot])
            buf[2 * i] = v
            buf[2 * i + 1] = v
        }
    }

    /**
     * Stream mode: stream sample n is heard when flowtime at the ear, less any gap being held ([heldNs]), is
     * start + n / 48 kHz + D. [gapRate] is how fast that gap is closing; it goes straight into the playback rate,
     * so the control loop below only has to track the audio clock and doesn't overshoot when a gap finishes closing.
     */
    fun stream(buf: FloatArray, frames: Int, earUtc0: Long, startNs: Long, dNs: Long, heldNs: Long = 0L, gapRate: Double = 0.0) {
        val src = source ?: return silence(buf, frames)
        val want = (earUtc0 - startNs - dNs - heldNs).toDouble() * RATE / 1e9
        if (pos.isNaN() || abs(pos - want) > JUMP) {
            if (!pos.isNaN()) jumps++ else integral = 0.0
            pos = want
        }
        // Proportional-integral nudge. The integral part learns how fast or slow this phone's audio clock
        // runs against flowtime, so the error settles at zero rather than parking at an offset.
        val e = pos - want
        integral = (integral + KI * e).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        ratio = (1.0 - (KP * e + integral).coerceIn(-MAX_NUDGE, MAX_NUDGE) + gapRate).coerceIn(1.0 - MAX_NUDGE, 1.0 + MAX_NUDGE)
        posErrUs = e * 1e6 / RATE
        segN = Long.MIN_VALUE   // look segments up afresh each chunk: one may have just arrived
        var p = pos
        for (i in 0 until frames) {
            val n0 = floor(p).toLong()
            val f = (p - n0).toFloat()
            val l0 = sample(src, n0, 0)
            val r0 = sample(src, n0, 1)
            val l1 = sample(src, n0 + 1, 0)
            val r1 = sample(src, n0 + 1, 1)
            if (l0.isNaN() || l1.isNaN()) {
                buf[2 * i] = 0f; buf[2 * i + 1] = 0f; missingFrames++
            } else {
                val l = l0 + (l1 - l0) * f
                val r = r0 + (r1 - r0) * f
                buf[2 * i] = if (channel == RIGHT) r else l
                buf[2 * i + 1] = if (channel == LEFT) l else r
            }
            p += ratio
        }
        pos = p
        if (soloCount > 0) soloGate(buf, frames, earUtc0)
    }

    /**
     * The music test (measure/musictest.py): phones take turns to be heard, each for [SOLO_WINDOW_NS], then all are silent
     * for [SOLO_SILENCE_NS], which marks where the turns start. It only changes the level after rendering, so the stream's
     * position and the rate nudge carry on exactly as in normal playback. Off unless [soloCount] is set, and silent outside
     * the window [tickFromUtc, tickUntilUtc), so a phone can buffer and hold its GPS lock quietly until the test starts.
     */
    private fun soloGate(buf: FloatArray, frames: Int, earUtc0: Long) {
        if (earUtc0 < tickFromUtc || earUtc0 >= tickUntilUtc) return silence(buf, frames)
        val cycle = SOLO_WINDOW_NS * soloCount + SOLO_SILENCE_NS
        val from = (soloSlot * SOLO_WINDOW_NS).toDouble()
        val phase0 = Math.floorMod(earUtc0, cycle).toDouble()
        for (i in 0 until frames) {
            var t = phase0 + i * NS_PER_FRAME
            if (t >= cycle) t -= cycle
            val d = t - from
            val gain = if (d < 0.0 || d >= SOLO_WINDOW_NS) 0f else {
                val edge = min(d, SOLO_WINDOW_NS - d)
                if (edge >= SOLO_FADE_NS) 1f else (0.5 - 0.5 * cos(PI * edge / SOLO_FADE_NS)).toFloat()
            }
            buf[2 * i] *= gain
            buf[2 * i + 1] *= gain
        }
    }

    fun silence(buf: FloatArray, frames: Int) { java.util.Arrays.fill(buf, 0, 2 * frames, 0f) }

    private fun sample(src: SegmentSource, n: Long, ch: Int): Float {
        val s = Math.floorDiv(n, RATE_L)
        if (s != segN) { segN = s; seg = src.segment(s) }
        val a = seg ?: return Float.NaN
        return a[((n - s * RATE_L) * 2 + ch).toInt()] / 32768f
    }

    companion object {
        const val BOTH = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val PATTERN_OFF = 0
        const val PATTERN_A = 1
        const val PATTERN_B = 2
        const val PATTERN_SLOT_NS = 200_000_000L
        const val SOLO_WINDOW_NS = 400_000_000L
        const val SOLO_SILENCE_NS = 200_000_000L
        private const val SOLO_FADE_NS = 10_000_000.0
        const val PATTERN_STAGGER_NS = 60_000_000L
        private val PATTERN_HZ = doubleArrayOf(500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        val patternHz: List<Double> get() = PATTERN_HZ.toList()     // for tests; never called from the audio thread
        const val RATE = 48000
        const val RATE_L = 48000L
        const val NS_PER_S = 1_000_000_000L
        const val NS_PER_FRAME = 1e9 / RATE
        const val JUMP = 0.020 * RATE      // more than 20 ms out: jump straight there
        const val MAX_NUDGE = 0.001        // at most 0.1% faster or slower
        const val TRACK_S = 3              // the audio clock tracking period: 3 s halves the time stamp jitter that 1 s lets through
        const val KP = 1.0 / (RATE * TRACK_S)     // 3 ms out gives the full 0.1% nudge
        const val KI = KP / (800 * TRACK_S)       // the integral catches up over about 12 s (2400 chunks): critically damped
        private const val BURST_NS = 5_000_000.0
        private const val ATTACK_NS = 200_000.0

        /** The click: a 0.2 ms rise then a 5 ms cosine fade of a tone at [hz]; t is ns since the whole second. */
        fun click(tNs: Double, hz: Double): Float {
            if (tNs >= BURST_NS) return 0f
            val env = min(1.0, tNs / ATTACK_NS) * (0.5 + 0.5 * cos(PI * tNs / BURST_NS))
            return (0.8 * env * sin(2 * PI * hz * tNs / 1e9)).toFloat()
        }
    }
}
