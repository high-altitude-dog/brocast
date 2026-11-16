package org.beachsync.proto

import org.json.JSONObject
import java.io.File

/**
 * Where each block of a set came from (hand-off 58): the engine's `<id>.src` (pears/engine/sources.js), read for the
 * Where it came from screen and the Receiving box. Read on a background thread; never touches the audio path.
 */
class BlockSources(
    val blocks: Int,
    val blockSeconds: Int,
    val sources: List<Source>,
    /** Per block: 0 = not from a peer (already on the phone, or not here yet), n = sources[n - 1], 255 = a source past the 254th. */
    val from: ByteArray,
    /** Per block: fetch time in 4 ms steps, 0 = unknown, 255 = 1 s or more. */
    val ms4: ByteArray,
) {
    class Source(val key: String, val nym: String?, val country: String, val route: String?, val station: Boolean)

    /** The source of block [i], or null when it didn't come from a peer. */
    fun sourceOf(i: Int): Source? = (from[i].toInt() and 255).let { if (it in 1..sources.size) sources[it - 1] else null }
    /** Fetch time of block [i] in ms, or null if unknown; 1020 means 1 s or more. */
    fun msOf(i: Int): Int? = (ms4[i].toInt() and 255).let { if (it == 0) null else it * 4 }
    /** Blocks per source index (0 = not from a peer, 255 = overflow). */
    fun counts(): IntArray = IntArray(256).also { c -> for (b in from) c[b.toInt() and 255]++ }

    companion object {
        fun read(file: File): BlockSources? = try { parse(file.readBytes()) } catch (e: Exception) { null }

        fun parse(buf: ByteArray): BlockSources? {
            if (buf.size < 9 || String(buf, 0, 4, Charsets.US_ASCII) != "BSRC" || buf[4].toInt() != 1) return null
            val n = (buf[5].toInt() and 255) or ((buf[6].toInt() and 255) shl 8) or ((buf[7].toInt() and 255) shl 16) or ((buf[8].toInt() and 255) shl 24)
            if (n < 0 || 9 + n > buf.size) return null
            val head = JSONObject(String(buf, 9, n, Charsets.UTF_8))
            val blocks = head.getInt("blocks")
            val at = 9 + n
            if (buf.size < at + 2 * blocks) return null
            val arr = head.getJSONArray("sources")
            val sources = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Source(it.getString("key"), it.optString("nym").takeIf { s -> s.isNotEmpty() && s != "null" }, it.optString("country", "unknown"),
                    it.optString("route").takeIf { s -> s.isNotEmpty() && s != "null" }, it.optBoolean("station"))
            }
            return BlockSources(blocks, head.optInt("blockSeconds", 1), sources, buf.copyOfRange(at, at + blocks), buf.copyOfRange(at + blocks, at + 2 * blocks))
        }
    }
}
