package org.beachsync.proto

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * The beta's library: every set plays in turn on a shared timeline, so any phone holding the files can work out what's due
 * from flowtime alone. Plain Kotlin, tested in simtest/. Audio stays silent where a file hasn't arrived, rather than out of sync.
 */
class LibraryItem(val id: String, val title: String, val artist: String, val seconds: Long, val bytes: Long, val sha256: String)

class Edition(val startNs: Long, val items: List<LibraryItem>) {
    val totalSeconds = items.sumOf { it.seconds }

    /** Which item segment [n] falls in, and which block of it. */
    fun at(n: Long): Pair<LibraryItem, Long>? {
        if (totalSeconds <= 0) return null
        var left = Math.floorMod(n, totalSeconds)
        for (item in items) {
            if (left < item.seconds) return item to left
            left -= item.seconds
        }
        return null
    }

    /** The items in the order they'll next play, from segment [n]: what's due soonest downloads first. */
    fun dueOrder(n: Long): List<LibraryItem> {
        val here = at(n)?.first ?: return emptyList()
        val i = items.indexOf(here)
        return items.drop(i) + items.take(i)
    }
}

/** The newest edition already started, or the earliest if none has: a new edition takes over on every phone at the same moment. */
fun editionAt(editions: List<Edition>, utcNs: Long): Edition? =
    editions.filter { it.startNs <= utcNs }.maxByOrNull { it.startNs } ?: editions.minByOrNull { it.startNs }

/** One Ogg Opus file as 1 s blocks, read from disk as needed, matching the server's OpusBlocks. Only an index sits in memory. */
class OpusFile(file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    private val first = ArrayList<Int>().apply { add(0) }
    private val where = ArrayList<Long>()
    private val size = ArrayList<Int>()
    private val packets: Int

    init {
        val length = raf.length()
        val head = ByteArray(27)
        var at = 0L
        var open = false
        while (at + 27 <= length) {
            raf.seek(at)
            raf.readFully(head)
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "OggS") throw java.io.IOException("not an Ogg file")
            val laces = ByteArray(head[26].toInt() and 255)
            raf.readFully(laces)
            var data = at + 27 + laces.size
            var piece = 0
            for (l in laces) {
                val lace = l.toInt() and 255
                piece += lace
                if (lace < 255) {
                    add(data, piece, true)
                    data += piece
                    piece = 0
                    open = false
                }
            }
            if (piece > 0) {
                add(data, piece, false)
                data += piece
                open = true
            }
            at = data
        }
        if (open || first.size < 3 || String(packet(0), 0, 8, Charsets.ISO_8859_1) != "OpusHead") throw java.io.IOException("not an Opus file")
        packets = first.size - 3
    }

    private fun add(at: Long, bytes: Int, ends: Boolean) {
        where.add(at)
        size.add(bytes)
        if (ends) first.add(where.size)
    }

    private fun packet(i: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (k in first[i] until first[i + 1]) {
            val b = ByteArray(size[k])
            raf.seek(where[k])
            raf.readFully(b)
            out.write(b)
        }
        return out.toByteArray()
    }

    /** Past the end of the file, silence, because an item is a whole number of seconds. */
    fun block(n: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val start = n * OpusBlock.PACKETS
        for (i in start until start + OpusBlock.PACKETS) {
            val p = if (i < packets) packet((i + 2).toInt()) else SILENCE
            out.write(p.size and 255)
            out.write(p.size shr 8)
            out.write(p)
        }
        return out.toByteArray()
    }

    override fun close() = raf.close()

    private companion object {
        val SILENCE = byteArrayOf(0xF8.toByte(), 0xFF.toByte(), 0xFE.toByte())
    }
}

/** What the shared timeline has at the ear at flowtime [utcNs]: the item and how far into it, or null when there's no edition. */
fun playingAt(editions: List<Edition>, dMs: Long, utcNs: Long): Pair<LibraryItem, Long>? {
    val edition = editionAt(editions, utcNs) ?: return null
    return edition.at(Math.floorDiv(utcNs - dMs * 1_000_000L - edition.startNs, 1_000_000_000L))
}

/**
 * Seconds of music already on the phone from the playhead on, counting whole files in play order until one is missing.
 * Null when every file is here: the library loops, so the music never runs out.
 */
fun aheadSeconds(edition: Edition, item: LibraryItem, offsetS: Long, have: (LibraryItem) -> Boolean): Long? {
    if (edition.items.all(have)) return null
    if (!have(item)) return 0L
    var total = item.seconds - offsetS
    val i = edition.items.indexOf(item)
    for (next in edition.items.drop(i + 1) + edition.items.take(i)) {
        if (!have(next)) break
        total += next.seconds
    }
    return total
}

/**
 * Seconds until the library has sound at the ear: 0 when music is on air. A new edition takes over at its start, but
 * its first sound reaches the ear only after the playout delay, so there is a silent gap of that length (and the same
 * before the very first edition).
 */
fun silentForSeconds(editions: List<Edition>, dMs: Long, utcNs: Long): Long {
    val ed = editionAt(editions, utcNs) ?: return 0L
    val n = Math.floorDiv(utcNs - dMs * 1_000_000L - ed.startNs, 1_000_000_000L)
    return if (n < 0) -n else 0L
}

/** What to show: the item at the ear, or while the library is silent, the first item of the edition, which is what plays next. */
fun shownAt(editions: List<Edition>, dMs: Long, utcNs: Long): Pair<LibraryItem, Long>? {
    if (silentForSeconds(editions, dMs, utcNs) > 0) return editionAt(editions, utcNs)?.items?.firstOrNull()?.let { it to 0L }
    return playingAt(editions, dMs, utcNs)
}
