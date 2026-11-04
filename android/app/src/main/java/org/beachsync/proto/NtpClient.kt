package org.beachsync.proto

import android.os.SystemClock
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import kotlin.math.roundToLong

/**
 * Asks NPL's stratum-1 servers for the time every 16 s (well inside the one-query-per-4-s rule),
 * feeds the samples to an NtpFitter, and compares each one with GPS flowtime.
 */
class NtpClient(private val log: CsvLog?) : Thread("ntp") {
    @Volatile var running = true
    @Volatile var host = HOSTS[0]
    @Volatile var stratum = 0
    @Volatile var refId = ""
    @Volatile var rttMs = Double.NaN
    @Volatile var boundMs = Double.NaN
    @Volatile var minusGpsUs = Double.NaN
    @Volatile var samples = 0
    @Volatile var lastError: String? = null
    private val fitter = NtpFitter()
    private val rnd = SecureRandom()

    override fun run() {
        var i = 0
        var fails = 0
        while (running) {
            val sent = SystemClock.elapsedRealtime()
            try {
                query(HOSTS[i]); lastError = null; fails = 0
            } catch (e: Exception) {
                lastError = "${HOSTS[i]}: ${e.message ?: e.javaClass.simpleName}"
                i = (i + 1) % HOSTS.size
                fails++
            }
            // Until the first answer, try the next server sooner, because nothing can play without a time source:
            // 4, 8, then 16 s between queries, which stays inside NPL's one-query-per-4-s rule.
            val gap = if (samples == 0 && fails > 0) minOf(16_000L, 4_000L shl (fails - 1).coerceAtMost(2)) else 16_000L
            val wait = (gap - (SystemClock.elapsedRealtime() - sent)).coerceAtLeast(1_000L)
            try { sleep(wait) } catch (_: InterruptedException) { }
        }
    }

    private fun query(h: String) {
        val addr = InetAddress.getByName(h)
        val req = ByteArray(48)
        req[0] = 0x23                                  // version 4, client
        val nonce = ByteArray(8).also { rnd.nextBytes(it) }
        nonce.copyInto(req, 40)                        // the server echoes this back: proves the reply is ours
        val resp = ByteArray(48)
        DatagramSocket().use { s ->
            s.soTimeout = 2000
            val b1 = SystemClock.elapsedRealtimeNanos()
            s.send(DatagramPacket(req, 48, addr, 123))
            s.receive(DatagramPacket(resp, 48))
            val b4 = SystemClock.elapsedRealtimeNanos()
            val r = parseNtp(resp, nonce, b1, b4)
            val mid = b1 + (b4 - b1) / 2
            FlowTime.ntp = fitter.add(NtpFitter.Sample(mid, r.off, r.bound))
            val g = FlowTime.gps
            minusGpsUs = if (g != null) (r.off - (g.utcAt(mid) - mid)) / 1e3 else Double.NaN
            host = h; stratum = r.stratum; refId = r.refId
            rttMs = r.rtt / 1e6; boundMs = r.bound / 1e6; samples++
            log?.row(mid, r.off, r.rtt, r.bound.roundToLong(), r.stratum, r.refId, h, minusGpsUs)
        }
    }

    companion object {
        val HOSTS = listOf("ntp1.npl.co.uk", "ntp2.npl.co.uk", "ntp3.npl.co.uk")
    }
}

/** One NTP reply, as UTC minus boot clock at the midpoint of the exchange. bound = round trip / 2 + root distance. */
class NtpReply(val off: Long, val rtt: Long, val bound: Double, val stratum: Int, val refId: String)

fun parseNtp(p: ByteArray, nonce: ByteArray, b1: Long, b4: Long): NtpReply {
    val mode = p[0].toInt() and 7
    val stratum = p[1].toInt() and 0xff
    if (mode != 4) throw IOException("not a server reply (mode $mode)")
    if (stratum == 0 || stratum > 15) throw IOException("server refused: ${String(p, 12, 4, Charsets.US_ASCII)}")
    for (k in 0 until 8) if (p[24 + k] != nonce[k]) throw IOException("reply does not match the request")
    val rootDelay = u32(p, 4) * 1e9 / 65536.0
    val rootDisp = u32(p, 8) * 1e9 / 65536.0
    val t2 = ntpNs(p, 32)
    val t3 = ntpNs(p, 40)
    val off = (t2 - b1) / 2 + (t3 - b4) / 2
    val rtt = (b4 - b1) - (t3 - t2)
    val ref = if (stratum == 1) String(p, 12, 4, Charsets.US_ASCII).trimEnd('\u0000', ' ')
              else (12..15).joinToString(".") { (p[it].toInt() and 0xff).toString() }
    return NtpReply(off, rtt, rtt / 2.0 + rootDelay / 2 + rootDisp, stratum, ref)
}

private fun u32(p: ByteArray, i: Int): Long =
    ((p[i].toLong() and 0xff) shl 24) or ((p[i + 1].toLong() and 0xff) shl 16) or
        ((p[i + 2].toLong() and 0xff) shl 8) or (p[i + 3].toLong() and 0xff)

/** NTP timestamp (seconds since 1900 plus a 32-bit fraction) to Unix-epoch ns. Good until 2036. */
private fun ntpNs(p: ByteArray, i: Int): Long =
    (u32(p, i) - 2_208_988_800L) * 1_000_000_000L + ((u32(p, i + 4) * 1_000_000_000L) ushr 32)
