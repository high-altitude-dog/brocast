package org.beachsync.proto

import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Fetches the server's manifest every second and keeps the segments around the one being heard,
 * from 1 s behind to [BUFFER_S] s ahead. Segments live in a small ring the audio thread can read without locks.
 */
class StreamClient(private val baseUrl: String, private val lagNs: Long) : Thread("stream"), SegmentSource {
    class Manifest(val startNs: Long, val newest: Long, val oldest: Long, val dMs: Long, val source: String)

    private class Seg(val n: Long, val pcm: ShortArray)

    private val ring = AtomicReferenceArray<Seg?>(RING)

    @Volatile var manifest: Manifest? = null
    @Volatile var running = true
    @Volatile var fetched = 0
    @Volatile var errors = 0
    @Volatile var lastError: String? = null
    @Volatile var buffered = 0
    @Volatile var serverMinusFlowMs = Double.NaN

    override fun segment(n: Long): ShortArray? {
        val s = ring.get(slot(n))
        return if (s != null && s.n == n) s.pcm else null
    }

    override fun run() {
        while (running) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            try {
                poll(); lastError = null
            } catch (e: Exception) {
                errors++; lastError = e.message ?: e.javaClass.simpleName
            }
            val spentMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
            try { sleep(maxOf(100L, 1000L - spentMs)) } catch (_: InterruptedException) { }
        }
    }

    private fun poll() {
        val b1 = SystemClock.elapsedRealtimeNanos()
        val j = JSONObject(String(get("/manifest.json"), Charsets.UTF_8))
        val b4 = SystemClock.elapsedRealtimeNanos()
        if (j.getInt("sample_rate") != Renderer.RATE || j.getInt("channels") != 2) throw IOException("stream must be 48 kHz stereo")
        val m = Manifest(j.getLong("start_utc_ns"), j.getLong("newest"), j.getLong("oldest"), j.getLong("D_ms"), j.optString("source"))
        val old = manifest
        if (old != null && old.startNs != m.startNs) for (i in 0 until RING) ring.set(i, null)   // server restarted: new stream
        manifest = m
        val fit = FlowTime.fit()
        if (fit != null) serverMinusFlowMs = (j.getLong("server_utc_ns") - fit.utcAt(b1 + (b4 - b1) / 2)) / 1e6
        val first: Long
        val last: Long
        if (fit != null) {
            val heard = Math.floorDiv(fit.utcAt(b4) + lagNs - m.dMs * 1_000_000L - m.startNs, 1_000_000_000L)
            first = maxOf(m.oldest, heard - 1)
            last = minOf(m.newest, heard + BUFFER_S)
        } else {
            first = maxOf(m.oldest, m.newest - minOf(BUFFER_S.toLong(), m.dMs / 1000))
            last = m.newest
        }
        var have = 0
        for (n in first..last) {
            if (!running) return
            if (segment(n) == null) {
                ring.set(slot(n), Seg(n, pcm(get("/seg/$n.pcm"))))
                fetched++
            }
            have++
        }
        buffered = have
    }

    private fun get(path: String): ByteArray {
        val c = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 3000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $path")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun pcm(b: ByteArray): ShortArray {
        val expect = Renderer.RATE * 4
        if (b.size != expect) throw IOException("segment is ${b.size} bytes, expected $expect")
        val s = ShortArray(b.size / 2)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s)
        return s
    }

    private fun slot(n: Long) = Math.floorMod(n, RING.toLong()).toInt()

    private companion object {
        const val BUFFER_S = 60             // seconds of audio to hold ahead of the one being heard
        const val RING = BUFFER_S + 4       // holds the whole window (1 s behind to BUFFER_S ahead) without collisions
    }
}
