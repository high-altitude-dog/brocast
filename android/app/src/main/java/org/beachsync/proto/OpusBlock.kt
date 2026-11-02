package org.beachsync.proto

/** The server's 1 s Opus blocks: 50 packets, each a little-endian 2-byte length and its bytes. Plain Kotlin, tested in simtest/. */
object OpusBlock {
    const val FORMAT = "opus-20ms-1s"
    const val PACKETS = 50
    const val FRAMES_PER_PACKET = 960

    /** Where each packet is, as (start, size), or null if the block isn't exactly [PACKETS] whole packets. */
    fun spans(b: ByteArray): List<Pair<Int, Int>>? {
        val out = ArrayList<Pair<Int, Int>>(PACKETS)
        var at = 0
        while (at < b.size) {
            if (at + 2 > b.size) return null
            val size = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
            at += 2
            if (at + size > b.size) return null
            out.add(at to size)
            at += size
        }
        return if (out.size == PACKETS) out else null
    }

    /** The header the decoder needs. It skips no samples, so every phone's output lines up with the packets the same way. */
    fun head(): ByteArray {
        val h = ByteArray(19)
        "OpusHead".toByteArray().copyInto(h)
        h[8] = 1
        h[9] = 2
        val rate = 48000
        for (i in 0..3) h[12 + i] = (rate shr (8 * i)).toByte()
        return h
    }
}

/** How many frames [late] is after [original], searching up to [maxLag] either way, and how well they match from 0 to 1. Stereo, compared on the left channel. */
fun bestLag(late: ShortArray, original: ShortArray, maxLag: Int): Pair<Int, Double> {
    val frames = minOf(late.size, original.size) / 2
    val span = 8000
    val start = maxLag
    if (frames < start + span + maxLag) return 0 to 0.0
    var best = 0
    var top = Double.NEGATIVE_INFINITY
    for (lag in -maxLag..maxLag) {
        var sum = 0.0
        for (k in start until start + span) sum += late[2 * (k + lag)].toDouble() * original[2 * k]
        if (sum > top) { top = sum; best = lag }
    }
    var a = 0.0
    var b = 0.0
    for (k in start until start + span) {
        a += late[2 * (k + best)].toDouble() * late[2 * (k + best)]
        b += original[2 * k].toDouble() * original[2 * k]
    }
    return best to if (a > 0 && b > 0) top / Math.sqrt(a * b) else 0.0
}
