package org.beachsync.proto

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android's software Opus decoder, asked for by name so that phones use the same one where it exists. It runs only on the
 * stream thread, turning a 1 s block into 48,000 stereo frames, and never touches the audio thread.
 */
class OpusDecoder {
    private var codec: MediaCodec? = null
    private var pts = 0L
    private val info = MediaCodec.BufferInfo()
    var name = "not started"
        private set

    private fun make(): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, Renderer.RATE, 2)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(OpusBlock.head()))
        format.setByteBuffer("csd-1", nanos(0L))
        format.setByteBuffer("csd-2", nanos(SEEK_PREROLL_NS))
        val known = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { !it.isEncoder && it.name == SOFTWARE }
        val c = if (known) MediaCodec.createByCodecName(SOFTWARE) else MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        c.configure(format, null, null, 0)
        c.start()
        name = c.name
        return c
    }

    private fun nanos(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0, v)

    /** A new decoder, for a jump in the stream: more certain than flushing, and it only happens after a gap. */
    fun reset() {
        close()
    }

    fun decode(block: ByteArray): ShortArray {
        val spans = OpusBlock.spans(block) ?: throw IOException("bad Opus block of ${block.size} bytes")
        val c = codec ?: make().also { codec = it }
        val out = ShortArray(Renderer.RATE * 2)
        var filled = 0
        for ((start, size) in spans) {
            var slot = c.dequeueInputBuffer(WAIT_US)
            while (slot < 0) {
                filled = drain(c, out, filled, WAIT_US)
                slot = c.dequeueInputBuffer(WAIT_US)
            }
            val input = c.getInputBuffer(slot)!!
            input.clear()
            input.put(block, start, size)
            c.queueInputBuffer(slot, 0, size, pts, 0)
            pts += PACKET_US
            filled = drain(c, out, filled, 0L)
        }
        var waited = 0L
        while (filled < out.size) {
            val before = filled
            filled = drain(c, out, filled, WAIT_US)
            waited = if (filled == before) waited + WAIT_US else 0L
            if (waited > GIVE_UP_US) throw IOException("the Opus decoder gave $filled of ${out.size} samples")
        }
        return out
    }

    private fun drain(c: MediaCodec, out: ShortArray, start: Int, waitUs: Long): Int {
        var filled = start
        while (true) {
            val slot = c.dequeueOutputBuffer(info, waitUs)
            if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || slot == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (slot < 0) return filled
            val shorts = c.getOutputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val count = minOf(info.size / 2, out.size - filled)
            shorts.position(info.offset / 2)
            shorts.get(out, filled, count)
            filled += count
            c.releaseOutputBuffer(slot, false)
            if (filled == out.size) return filled
        }
    }

    fun close() {
        try { codec?.stop() } catch (_: Exception) { }
        codec?.release()
        codec = null
    }

    private companion object {
        const val SOFTWARE = "c2.android.opus.decoder"
        const val SEEK_PREROLL_NS = 80_000_000L
        const val PACKET_US = 20_000L
        const val WAIT_US = 5_000L
        const val GIVE_UP_US = 2_000_000L
    }
}
