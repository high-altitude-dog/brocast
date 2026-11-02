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
class Renderer(private val clickHz: Double, private val source: SegmentSource?, private val channel: Int = BOTH) {
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
    private var integral = 0.0
    private var segN = Long.MIN_VALUE
    private var seg: ShortArray? = null

    fun reset() { pos = Double.NaN; ratio = 1.0; posErrUs = Double.NaN; integral = 0.0 }

    /** Tick mode: a 5 ms tone burst starting exactly on every whole second of flowtime, as heard. */
    fun ticks(buf: FloatArray, frames: Int, earUtc0: Long) {
        val phase0 = Math.floorMod(earUtc0, NS_PER_S).toDouble()
        for (i in 0 until frames) {
            var t = phase0 + i * NS_PER_FRAME
            if (t >= 1e9) t -= 1e9
            val v = click(t, clickHz)
            buf[2 * i] = v
            buf[2 * i + 1] = v
        }
    }

    /** Stream mode: stream sample n is heard when flowtime at the ear is start + n / 48 kHz + D. */
    fun stream(buf: FloatArray, frames: Int, earUtc0: Long, startNs: Long, dNs: Long) {
        val src = source ?: return silence(buf, frames)
        val want = (earUtc0 - startNs - dNs).toDouble() * RATE / 1e9
        if (pos.isNaN() || abs(pos - want) > JUMP) {
            if (!pos.isNaN()) jumps++ else integral = 0.0
            pos = want
        }
        // Proportional-integral nudge. The integral part learns how fast or slow this phone's audio clock
        // runs against flowtime, so the error settles at zero rather than parking at an offset.
        val e = pos - want
        integral = (integral + KI * e).coerceIn(-MAX_NUDGE, MAX_NUDGE)
        ratio = 1.0 - (KP * e + integral).coerceIn(-MAX_NUDGE, MAX_NUDGE)
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
        const val RATE = 48000
        const val RATE_L = 48000L
        const val NS_PER_S = 1_000_000_000L
        const val NS_PER_FRAME = 1e9 / RATE
        const val JUMP = 0.020 * RATE      // more than 20 ms out: jump straight there
        const val MAX_NUDGE = 0.001        // at most 0.1% faster or slower
        const val KP = 1.0 / RATE          // 1 ms out gives the full 0.1% nudge; settles in about a second
        const val KI = KP / 800            // the integral catches up over about 4 s (800 chunks): critically damped
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
