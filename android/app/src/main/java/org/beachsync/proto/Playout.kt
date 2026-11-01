package org.beachsync.proto

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs

/**
 * The audio thread. Each 5 ms chunk: work out when its first frame will leave the phone (from Android's
 * output time stamp), convert that to flowtime, add the headphone lag, and let the Renderer fill it.
 * Lean on purpose: no allocation, no locks, no logging in the loop.
 */
class Playout(private val renderer: Renderer, private val stream: StreamClient?, private val lagNs: Long) : Thread("playout") {
    @Volatile var running = true
    @Volatile var state = "starting"
    @Volatile var aheadMs = Double.NaN      // how far ahead of the output the next frame is written
    @Volatile var underruns = 0
    @Volatile var stampDevUs = Double.NaN   // average disagreement between successive output time stamps
    @Volatile var track: AudioTrack? = null
    @Volatile var error: String? = null

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = try { makeTrack() } catch (e: Exception) { error = e.toString(); state = "audio failed"; return }
        track = t
        val buf = FloatArray(CHUNK * 2)
        val ts = AudioTimestamp()
        var written = 0L
        var tsFrame = 0L
        var tsBoot = 0L
        var haveTs = false
        var lastQuery = 0L
        var m2b = monoToBootNs()
        var lastM2b = SystemClock.elapsedRealtimeNanos()
        var dev = Double.NaN
        t.play()
        try {
            while (running) {
                val now = SystemClock.elapsedRealtimeNanos()
                if (now - lastM2b > 1_000_000_000L) { m2b = monoToBootNs(); lastM2b = now }
                if (now - lastQuery >= 20_000_000L) {
                    lastQuery = now
                    if (t.getTimestamp(ts) && ts.framePosition > 0) {
                        val boot = ts.nanoTime + m2b
                        if (haveTs && ts.framePosition != tsFrame) {
                            val predicted = tsBoot + (ts.framePosition - tsFrame) * 1_000_000_000L / Renderer.RATE
                            val d = abs(boot - predicted) / 1e3
                            dev = if (dev.isNaN()) d else 0.95 * dev + 0.05 * d
                            stampDevUs = dev
                        }
                        tsFrame = ts.framePosition; tsBoot = boot; haveTs = true
                    }
                }
                val fit = FlowTime.fit()
                val m = stream?.manifest
                if (fit == null) {
                    renderer.silence(buf, CHUNK); renderer.reset(); state = "waiting for flowtime"
                } else if (!haveTs) {
                    renderer.silence(buf, CHUNK); state = "waiting for the output time stamp"
                } else if (stream != null && m == null) {
                    renderer.silence(buf, CHUNK); state = "waiting for the stream"
                } else {
                    // boot-clock time at which frame `written` leaves the phone, then flowtime at the ear
                    val bootOut = tsBoot + (written - tsFrame) * 1_000_000_000L / Renderer.RATE
                    aheadMs = (bootOut - now) / 1e6
                    val ear = fit.utcAt(bootOut) + lagNs
                    if (m == null) {
                        renderer.ticks(buf, CHUNK, ear); state = "ticking"
                    } else {
                        renderer.stream(buf, CHUNK, ear, m.startNs, m.dMs * 1_000_000L); state = "streaming"
                    }
                }
                t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                written += CHUNK
                underruns = t.underrunCount
            }
        } finally {
            track = null
            t.pause(); t.flush(); t.release()
        }
    }

    private fun makeTrack(): AudioTrack {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(Renderer.RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val min = AudioTrack.getMinBufferSize(Renderer.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(min * 2, CHUNK * 8 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    companion object { const val CHUNK = 240 }   // 5 ms at 48 kHz
}

/**
 * Android stamps audio output on the monotonic clock; flowtime is kept on the boot clock. While the phone
 * is awake the two tick together, so the gap between them is a constant. Read it with the tightest of five tries.
 */
fun monoToBootNs(): Long {
    var best = Long.MAX_VALUE
    var off = 0L
    repeat(5) {
        val a = System.nanoTime()
        val b = SystemClock.elapsedRealtimeNanos()
        val c = System.nanoTime()
        if (c - a < best) { best = c - a; off = b - (a + (c - a) / 2) }
    }
    return off
}
