package org.beachsync.proto

import android.os.SystemClock
import android.util.Log
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
    class Manifest(val startNs: Long, val newest: Long, val oldest: Long, val dMs: Long, val source: String, val opus: Boolean)

    private class Seg(val n: Long, val pcm: ShortArray)

    private val ring = AtomicReferenceArray<Seg?>(RING)

    @Volatile var manifest: Manifest? = null
    @Volatile var running = true
    @Volatile var fetched = 0
    @Volatile var errors = 0
    @Volatile var lastError: String? = null
    @Volatile var buffered = 0
    @Volatile var serverMinusFlowMs = Double.NaN
    @Volatile var fetchMs = Double.NaN
    private var fetchEstNs = 300_000_000L   // how long a segment takes to arrive; used only on this thread

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
        decoder?.close()
    }

    private fun poll() {
        val b1 = SystemClock.elapsedRealtimeNanos()
        val j = JSONObject(String(get("/manifest.json"), Charsets.UTF_8))
        val b4 = SystemClock.elapsedRealtimeNanos()
        if (j.getInt("sample_rate") != Renderer.RATE || j.getInt("channels") != 2) throw IOException("stream must be 48 kHz stereo")
        val m = Manifest(j.getLong("start_utc_ns"), j.getLong("newest"), j.getLong("oldest"), j.getLong("D_ms"), j.optString("source"), j.optString("block_format") == OpusBlock.FORMAT)
        val old = manifest
        if (old != null && old.startNs != m.startNs) {   // server restarted: new stream
            for (i in 0 until RING) ring.set(i, null)
            decodedUpTo = NONE
        }
        manifest = m
        val fit = FlowTime.fit()
        if (fit != null) serverMinusFlowMs = (j.getLong("server_utc_ns") - fit.utcAt(b1 + (b4 - b1) / 2)) / 1e6
        // Work the window out afresh before each fetch, so a slow link never spends its time on segments already past.
        while (running) {
            val now = SystemClock.elapsedRealtimeNanos()
            val f = FlowTime.fit()
            val first: Long
            val last: Long
            val useful: Long
            val heard: Long
            if (f != null) {
                val heardNs = f.utcAt(now) + lagNs - m.dMs * 1_000_000L - m.startNs
                heard = Math.floorDiv(heardNs, NS_PER_S)
                first = maxOf(m.oldest, heard - 1)
                last = minOf(m.newest, heard + BUFFER_S)
                useful = Math.floorDiv(heardNs + fetchEstNs + fetchEstNs / 2, NS_PER_S)
            } else {
                first = maxOf(m.oldest, m.newest - minOf(BUFFER_S.toLong(), m.dMs / 1000))
                last = m.newest
                heard = first
                useful = first
            }
            buffered = contiguous(maxOf(heard, first), last)
            val n = FetchPlan.next(first, last, useful) { segment(it) != null } ?: return
            val t0 = SystemClock.elapsedRealtimeNanos()
            try {
                ring.set(slot(n), Seg(n, if (m.opus) opus(n, m) else pcm(get("/seg/$n.pcm"))))
                fetched++
            } finally {
                noteFetch(SystemClock.elapsedRealtimeNanos() - t0)
            }
            if (SystemClock.elapsedRealtimeNanos() - b4 > MANIFEST_MAX_AGE_NS) return
        }
    }

    /**
     * Decoding happens here, on this thread, so the audio thread still only reads finished samples from the ring. Opus depends
     * on what came before, so after a jump in the stream the block before is decoded too, and thrown away, to settle it.
     */
    private fun opus(n: Long, m: Manifest): ShortArray {
        val d = decoder ?: OpusDecoder().also { decoder = it }
        try {
            if (n != decodedUpTo + 1) {
                d.reset()
                if (n > m.oldest) d.decode(get("/seg/${n - 1}.opus"))
            }
            val block = get("/seg/$n.opus")
            val out = d.decode(block)
            decodedUpTo = n
            blockBytes = block.size
            checkOnce(n, out)
            return out
        } catch (e: Exception) {
            decodedUpTo = NONE
            throw e
        }
    }

    /** Once a run, after the decoder has settled: how the decoded audio lines up with the original. Opus's own delay is 312 samples. */
    private fun checkOnce(n: Long, decoded: ShortArray) {
        if (++blocksDecoded != CHECK_AFTER) return
        val (lag, match) = bestLag(decoded, pcm(get("/seg/$n.pcm")), CHECK_MAX_LAG)
        Log.i("BeachSync", "Opus check: ${d()} decoded audio is $lag samples after the original, match %.3f, block %d bytes".format(java.util.Locale.ROOT, match, blockBytes))
    }

    private fun d() = decoder?.name ?: "no decoder"

    /** How many segments are here in a row from [from]: how long the audio could carry on if fetching stopped now. */
    private fun contiguous(from: Long, last: Long): Int {
        var n = from
        while (n <= last && segment(n) != null) n++
        return (n - from).toInt()
    }

    /** Rises at once when fetches slow down and falls gently, so one lucky fetch doesn't undo the margin. */
    private fun noteFetch(ns: Long) {
        fetchEstNs = maxOf(ns, (fetchEstNs * 7 + ns * 3) / 10).coerceAtMost(FETCH_EST_MAX_NS)
        fetchMs = ns / 1e6
    }

    private var decoder: OpusDecoder? = null
    private var decodedUpTo = NONE
    private var blocksDecoded = 0
    @Volatile var blockBytes = 0

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
        const val NS_PER_S = 1_000_000_000L
        const val NONE = Long.MIN_VALUE / 2
        const val CHECK_AFTER = 3
        const val CHECK_MAX_LAG = 2000
        const val MANIFEST_MAX_AGE_NS = 5_000_000_000L   // while catching up, re-read the manifest this often
        const val FETCH_EST_MAX_NS = 10_000_000_000L
    }
}
