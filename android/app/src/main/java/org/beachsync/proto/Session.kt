package org.beachsync.proto

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class Settings(val url: String, val stream: Boolean, val policy: Policy, val pitchHz: Double, val lagMs: Double)

/** One test run: the time sources, the stream, the audio thread and three CSV logs. */
class Session(ctx: Context, val s: Settings, gpsAllowed: Boolean) {
    private val dir: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val name = "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
    private val lagNs = (s.lagMs * 1e6).roundToLong()
    private val statusLog = CsvLog(File(dir, "$name-status.csv"), STATUS_HEADER)
    private val gpsLog = CsvLog(File(dir, "$name-gps.csv"),
        "boot_ns,utc_minus_boot_ns,gold,bias_unc_ns,er_unc_ns,leap_s,leap_from_chip,resid_vs_fit_ns,hw_discontinuities,arrival_minus_stamp_ns")
    private val ntpLog = CsvLog(File(dir, "$name-ntp.csv"),
        "boot_mid_ns,utc_minus_boot_ns,rtt_ns,bound_ns,stratum,ref_id,host,ntp_minus_gps_us")
    val gps = if (gpsAllowed) GpsTime(ctx, gpsLog) else null
    val ntp = NtpClient(ntpLog)
    val stream = if (s.stream) StreamClient(s.url, lagNs) else null
    private val renderer = Renderer(s.pitchHz, stream)
    val play = Playout(renderer, stream, lagNs)
    val folder: String = dir.absolutePath

    fun start() {
        FlowTime.reset(s.policy)
        File(dir, "$name-info.txt").writeText(info())
        gps?.start()
        ntp.start()
        stream?.start()
        play.start()
    }

    fun stop() {
        play.running = false
        stream?.running = false; stream?.interrupt()
        ntp.running = false; ntp.interrupt()
        gps?.stop()
        play.join(500)
        statusLog.close(); gpsLog.close(); ntpLog.close()
    }

    private fun info() = buildString {
        appendLine("phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.FINGERPRINT}")
        appendLine("gnss chip: ${gps?.chip ?: "not used"}; raw clock reports: ${gps?.reportsRaw ?: "not used"}")
        appendLine("mode: ${if (s.stream) "stream from ${s.url}" else "tick at ${s.pitchHz} Hz"}")
        appendLine("time source: ${s.policy}; headphone lag: ${s.lagMs} ms")
    }

    /** Called once a second by the screen loop. */
    fun logRow(boot: Long) {
        val fit = FlowTime.fit()
        val g = gps
        val st = stream
        statusLog.row(
            boot, fit?.utcAt(boot), FlowTime.chosen, fit?.errAt(boot),
            FlowTime.gps?.errAt(boot), FlowTime.ntp?.errAt(boot),
            g?.gold, g?.used, g?.seen, g?.rmsNs, FlowTime.gps?.slope?.times(1e6),
            if (g != null && g.lastBoot > 0) (boot - g.lastBoot) / 1e6 else null,
            ntp.minusGpsUs, ntp.rttMs, ntp.boundMs,
            play.state, renderer.posErrUs, (renderer.ratio - 1) * 1e6, renderer.jumps,
            play.underruns, renderer.missingFrames, play.aheadMs, play.stampDevUs,
            st?.serverMinusFlowMs,
        )
    }

    /** The status panel. */
    fun describe(boot: Long): String = buildString {
        val fit = FlowTime.fit()
        val src = FlowTime.chosen
        val holdover = src == Source.GPS && gps != null && boot - gps.lastBoot > 3_000_000_000L
        append("SOURCE ")
        append(if (src == null) "none yet" else src.name + if (holdover) " (holdover)" else "")
        if (fit != null) append("   ±").append(fmtNs(fit.errAt(boot)))
        append("   switches ").append(FlowTime.switches).append('\n')

        val g = gps
        if (g == null) append("GPS    off (no location permission)\n") else {
            append("GPS    ").append(g.status)
            when (g.gold) {
                true -> append(" · Gold (chip stamps the boot clock)")
                false -> append(" · Silver (stamped on arrival)")
                null -> {}
            }
            append('\n')
            append("       satellites ${g.used} used of ${g.seen} · ")
            val gf = FlowTime.gps
            if (gf != null) {
                append("fit ${g.fitN} s, scatter ${fmtNs(g.rmsNs)} · boot clock ${"%+.3f".format(gf.slope * 1e6)} ppm")
                append(" · last ${"%.1f".format((boot - g.lastBoot) / 1e9)} s ago")
            } else append("no fit yet")
            append('\n')
            append("       chip ${g.chip} · delivery ${"%.0f".format(g.deliveryMs)} ms · rejected ${g.rejected} · resets ${g.resets}\n")
        }

        append("NTP    ${ntp.host} · ")
        if (ntp.samples == 0) append(ntp.lastError ?: "asking") else {
            append("stratum ${ntp.stratum} (${ntp.refId}) · round trip ${"%.1f".format(ntp.rttMs)} ms · ±${"%.1f".format(ntp.boundMs)} ms")
            if (!ntp.minusGpsUs.isNaN()) append(" · NTP−GPS ${fmtNs(ntp.minusGpsUs * 1e3)}")
            ntp.lastError?.let { append(" · last error: $it") }
        }
        append('\n')

        append("OUT    ${play.state}")
        append(if (s.stream) "" else " · ${s.pitchHz.toInt()} Hz")
        append(" · ${routeName(play)}\n")
        append("       ahead ${"%.1f".format(play.aheadMs)} ms · stamp jitter ${fmtNs(play.stampDevUs * 1e3)} · underruns ${play.underruns}")
        append(" · headphone lag ${s.lagMs} ms\n")
        play.error?.let { append("       error: $it\n") }

        val st = stream
        if (st != null) {
            append("       position ${fmtNs(renderer.posErrUs * 1e3)} · nudge ${"%+.0f".format((renderer.ratio - 1) * 1e6)} ppm")
            append(" · jumps ${renderer.jumps} · missing ${renderer.missingFrames} frames\n")
            val m = st.manifest
            append("STREAM ")
            if (m == null) append(st.lastError ?: "connecting to ${s.url}") else {
                append("newest #${m.newest} · buffered ${st.buffered} s · fetched ${st.fetched} · errors ${st.errors}")
                append(" · server−flowtime ${"%.1f".format(st.serverMinusFlowMs)} ms")
                st.lastError?.let { append(" · last error: $it") }
            }
            append('\n')
        }
        append("LOG    $name-*.csv\n       in $folder")
    }

    private fun routeName(p: Playout): String {
        val d = try { p.track?.routedDevice } catch (_: Exception) { null } ?: return "no output yet"
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            else -> "output type ${d.type}"
        }
        return "$type \"${d.productName}\""
    }

    companion object {
        const val STATUS_HEADER = "boot_ns,flow_utc_ns,source,err_ns,gps_err_ns,ntp_err_ns,gold,sats_used,sats_seen," +
            "gps_scatter_ns,boot_clock_ppm,gps_age_ms,ntp_minus_gps_us,ntp_rtt_ms,ntp_bound_ms," +
            "state,pos_err_us,nudge_ppm,jumps,underruns,missing_frames,ahead_ms,stamp_jitter_us,server_minus_flow_ms"

        fun fmtNs(ns: Double): String = when {
            ns.isNaN() || ns.isInfinite() -> "—"
            abs(ns) < 1e3 -> "%.0f ns".format(ns)
            abs(ns) < 1e6 -> "%.2f µs".format(ns / 1e3)
            abs(ns) < 1e9 -> "%.2f ms".format(ns / 1e6)
            else -> "%.2f s".format(ns / 1e9)
        }

        fun nowBoot() = SystemClock.elapsedRealtimeNanos()
    }
}
