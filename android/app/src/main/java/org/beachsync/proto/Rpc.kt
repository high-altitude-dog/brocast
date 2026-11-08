package org.beachsync.proto

import java.io.ByteArrayOutputStream

/**
 * The host side of bare-rpc (pears/node_modules/bare-rpc/lib/messages.js), just what the engine host needs: requests with a
 * body and a reply, and events (requests with id 0) from the engine. No streams. Each frame is a u32 LE length, then
 * compact-encoding fields: type, id, then for a request command, stream flags (0) and the body as length + bytes.
 */
class Rpc(private val send: (ByteArray) -> Unit, private val onEvent: (Int, ByteArray) -> Unit) {
    class RpcException(val code: String, message: String) : Exception("$code: $message")

    private var nextId = 1
    private val pending = HashMap<Int, (Result<ByteArray>) -> Unit>()
    private var buf = ByteArray(0)

    @Synchronized
    fun request(command: Int, body: ByteArray, reply: (Result<ByteArray>) -> Unit) {
        val id = nextId++
        pending[id] = reply
        send(frame(REQUEST, id, command, body))
    }

    /** Bytes from the IPC, in any split. Calls back for every whole frame. */
    fun feed(chunk: ByteArray) {
        val frames = ArrayList<ByteArray>()
        synchronized(this) {
            buf += chunk
            var at = 0
            while (buf.size - at >= 4) {
                val n = u32(buf, at)
                if (buf.size - at - 4 < n) break
                frames += buf.copyOfRange(at + 4, at + 4 + n)
                at += 4 + n
            }
            buf = buf.copyOfRange(at, buf.size)
        }
        for (f in frames) onFrame(f)
    }

    private fun onFrame(f: ByteArray) {
        val r = Reader(f)
        when (r.uint().toInt()) {
            REQUEST -> {
                val id = r.uint().toInt()
                val command = r.uint().toInt()
                val stream = r.uint()
                if (id == 0 && stream == 0L) onEvent(command, r.buffer())
            }
            RESPONSE -> {
                val id = r.uint().toInt()
                val error = r.byte() != 0
                r.uint() // stream flags: always 0 here
                val cb = synchronized(this) { pending.remove(id) } ?: return
                if (error) {
                    val message = r.utf8()
                    val code = r.utf8()
                    cb(Result.failure(RpcException(code, message)))
                } else cb(Result.success(r.buffer()))
            }
        }
    }

    private class Reader(val b: ByteArray) {
        var at = 0
        fun byte(): Int = b[at++].toInt() and 0xff
        fun uint(): Long = when (val x = byte()) {
            0xfd -> le(2)
            0xfe -> le(4)
            0xff -> le(8)
            else -> x.toLong()
        }
        fun le(n: Int): Long { var v = 0L; for (i in 0 until n) v = v or (byte().toLong() shl (8 * i)); return v }
        fun buffer(): ByteArray { val n = uint().toInt(); val out = b.copyOfRange(at, at + n); at += n; return out }
        fun utf8(): String = String(buffer(), Charsets.UTF_8)
    }

    companion object {
        const val REQUEST = 1
        const val RESPONSE = 2

        fun u32(b: ByteArray, at: Int): Int =
            (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
                ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

        private fun uint(out: ByteArrayOutputStream, v: Long) {
            fun le(n: Int) { for (i in 0 until n) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
            when {
                v <= 0xfc -> out.write(v.toInt())
                v <= 0xffff -> { out.write(0xfd); le(2) }
                v <= 0xffffffffL -> { out.write(0xfe); le(4) }
                else -> { out.write(0xff); le(8) }
            }
        }

        fun frame(type: Int, id: Int, command: Int, body: ByteArray): ByteArray {
            val m = ByteArrayOutputStream()
            uint(m, type.toLong())
            uint(m, id.toLong())
            uint(m, command.toLong())
            uint(m, 0) // no stream
            uint(m, body.size.toLong())
            m.write(body)
            val payload = m.toByteArray()
            val n = payload.size
            return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + payload
        }
    }
}
