package org.beachsync.proto

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class Settings(
    val url: String, val stream: Boolean, val policy: Policy, val pitchHz: Double, val lagMs: Double, val channel: Int,
    val nudgeMode: NudgeMode, val nudgePeriodS: Double?, val tickPattern: Int = Renderer.PATTERN_OFF,
    val soloSlot: Int = 0, val soloCount: Int = 0, val library: Boolean = false,
)

/** A run of text in the status grid. Live numbers are [mono], so their digits don't jitter as they update. */
class StatusPart(val text: String, val mono: Boolean = false, val good: Boolean = false)

/** A row of the status grid. An [error] takes its own line, and tapping it opens the event log for [errorLane]. */
class StatusRow(val label: String, val parts: List<StatusPart>, val error: String? = null, val errorLane: Lane? = null)

/** One test run: the time sources, the stream, the audio thread, the event log and four CSV logs. */
class Session(ctx: Context, val s: Settings, private val satellitesOn: Boolean) {
    private val dir: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val name = "run-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.UK).format(Date())
    val startBoot = nowBoot()
    private val lagNs = (s.lagMs * 1e6).roundToLong()
    private val statusLog = CsvLog(File(dir, "$name-status.csv"), STATUS_HEADER)
    private val gpsLog = CsvLog(File(dir, "$name-gps.csv"),
        "boot_ns,utc_minus_boot_ns,gold,bias_unc_ns,er_unc_ns,leap_s,leap_from_chip,resid_vs_fit_ns,hw_discontinuities,arrival_minus_stamp_ns")
    private val ntpLog = CsvLog(File(dir, "$name-ntp.csv"),
        "boot_mid_ns,utc_minus_boot_ns,rtt_ns,bound_ns,stratum,ref_id,host,ntp_minus_gps_us")
    val gps = GpsTime(ctx, gpsLog)
    val ntp = NtpClient(ntpLog)
    val stream = if (s.stream) StreamClient(if (s.library) LocalSupply(LibraryStore.get(ctx)) else HttpSupply(s.url), lagNs) else null
    val renderer = Renderer(s.pitchHz, stream, s.channel, s.tickPattern, s.soloSlot, s.soloCount)
    val nudge = NudgeControl(s.nudgeMode, s.nudgePeriodS) { note(Lane.AUDIO, it) }
    val play = Playout(renderer, stream, lagNs, nudge)
    val folder: String = dir.absolutePath
    private val eventCsv = CsvLog(File(dir, "$name-events.csv"), "boot_ns,flow_utc_ns,lane,text")
    val events = EventLog { e -> eventCsv.row(e.boot, e.utc, e.lane, "\"" + e.text.replace("\"", "\"\"") + "\"") }
    private val detector = EventDetector()
    @Volatile private var watching = true

    /** Once a second, whether or not the screen is on: the event log and status.csv. */
    private val watcher = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (watching) {
            checkEvents()
            logRow(nowBoot())
            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
    }, "events")

    /** Picks the time source and hands flowtime to the audio thread, whether or not the screen is on. */
    private val control = Thread({
        while (watching) {
            val boot = nowBoot()
            FlowTime.reselect(boot)
            nudge.tick(boot, FlowTime.fit())
            try { Thread.sleep(100) } catch (_: InterruptedException) { }
        }
    }, "nudge")

    fun start() {
        FlowTime.beginRun(s.policy)   // fits already running stay, so Play never leaves flowtime without a source
        File(dir, "$name-info.txt").writeText(info())
        note(Lane.CLOCK, "Run started: time source setting ${s.policy}, satellites ${if (satellitesOn) "on" else "off"}")
        if (satellitesOn) gps.start()
        ntp.start()
        stream?.start()
        control.start()
        play.start()
        watcher.start()
    }

    /** A call or other short interruption. Called from the main thread, never the audio thread. */
    fun mute(on: Boolean) { play.muted = on }

    fun stop() {
        play.running = false
        stream?.running = false; stream?.interrupt()
        ntp.running = false; ntp.interrupt()
        gps.close()
        play.join(500)
        watching = false
        control.interrupt(); control.join(500)
        watcher.interrupt(); watcher.join(500)
        note(Lane.CLOCK, "Run stopped")
        statusLog.close(); gpsLog.close(); ntpLog.close(); eventCsv.close()
    }

    /** The stereo side, changed while playing; the audio thread picks it up on its next chunk. */
    fun setChannel(channel: Int) { renderer.channel = channel }

    /** "Use satellites" during a run. The audio keeps playing: flowtime carries on from the last fit, then picks the best source. */
    fun setSatellites(on: Boolean) {
        if (on == gps.active) return
        if (on) gps.start() else gps.stop()
        note(Lane.CLOCK, "Satellites turned ${if (on) "on" else "off"} by you")
    }

    /**
     * The Sync view's temporary diagnostic offset (ms, larger plays earlier). Logged, because it changes what is heard; it ends with the session.
     * It rides on the lag kept by ear ([keptNs]), so both reach the audio thread as the one value it already reads.
     */
    fun setDiagnosticOffsetMs(ms: Double) {
        play.extraLagNs = keptNs + Math.round(ms * 1e6)
        note(Lane.AUDIO, if (ms == 0.0) "Diagnostic audio offset cleared" else "Diagnostic audio offset set to %+.0f ms (%s)".format(java.util.Locale.ROOT, ms, if (ms > 0) "plays earlier" else "plays later"))
    }
    val diagnosticOffsetMs get() = (play.extraLagNs - keptNs) / 1e6

    /** Lag kept as calibration by ear during this run. The saved value applies from the next start; this keeps the sound where it is until then. */
    @Volatile private var keptNs = 0L

    /** The diagnostic offset becomes kept lag. The audio thread's value doesn't change, so the sound doesn't move. */
    fun keepDiagnostic() {
        keptNs = play.extraLagNs
        note(Lane.AUDIO, "Lag adjusted by ear: the audio offset is kept as calibration")
    }

    /** Kept lag added after the diagnostic offset was already removed (the Sync view's Keep-it toast), so the sound goes back to where it was nudged. */
    fun keepOffsetMs(ms: Double) {
        val diag = play.extraLagNs - keptNs
        keptNs += Math.round(ms * 1e6)
        play.extraLagNs = keptNs + diag
        note(Lane.AUDIO, "Lag adjusted by ear: %+.0f ms kept as calibration".format(java.util.Locale.ROOT, ms))
    }
    val lagInUseMs get() = s.lagMs + keptNs / 1e6

    /** From the UI thread: the channel chip's change, for the event log. */
    fun logChannel(text: String) = note(Lane.AUDIO, text)

    private fun note(lane: Lane, text: String) {
        val boot = nowBoot()
        events.add(Event(boot, FlowTime.fit()?.utcAt(boot), lane, text))
    }

    /** Runs on the watcher thread only. It reads the counters the status grid already shows. */
    private fun checkEvents() {
        val boot = nowBoot()
        val fit = FlowTime.fit()
        val found = detector.update(Reading(
            boot, fit?.utcAt(boot), FlowTime.chosen, fit?.errAt(boot) ?: Double.NaN,
            if (gps.active) gps.lastBoot else 0L, play.underruns, renderer.jumps, renderer.missingFrames,
            stream?.lastError, stream?.errors ?: 0, ntp.lastError,
            if (play.track != null) routeName(play) else null, play.error, gps.used,
            ntpMinusGpsNs = run {
                val g = FlowTime.gps; val n = FlowTime.ntp
                if (g != null && n != null) (n.utcAt(boot) - g.utcAt(boot)).toDouble() else Double.NaN
            },
            ntpErrNs = FlowTime.ntp?.errAt(boot) ?: Double.NaN,
        ))
        for (e in found) events.add(e)
    }

    private fun info() = buildString {
        appendLine("phone: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.FINGERPRINT}")
        appendLine("gnss chip: ${gps.chip}; raw clock reports: ${gps.reportsRaw}; satellites at start: ${if (satellitesOn) "on" else "off"}")
        appendLine("mode: ${when {
            s.library -> "library on this phone, ${channelName()} channel" + if (s.soloCount > 0) ", solo ${s.soloSlot} of ${s.soloCount}" else ""
            s.stream && s.soloCount > 0 -> "stream from ${s.url}, ${channelName()} channel, solo ${s.soloSlot} of ${s.soloCount}"
            s.stream -> "stream from ${s.url}, ${channelName()} channel"
            s.tickPattern == Renderer.PATTERN_A -> "tick pattern A"
            s.tickPattern == Renderer.PATTERN_B -> "tick pattern B"
            else -> "tick at ${s.pitchHz} Hz"
        }}")
        appendLine("time source: ${s.policy}; headphone lag: ${s.lagMs} ms")
    }

    private fun channelName() = when (s.channel) { Renderer.LEFT -> "left"; Renderer.RIGHT -> "right"; else -> "both" }

    private fun logRow(boot: Long) {
        val fit = FlowTime.fit()
        val g = gps
        val st = stream
        statusLog.row(
            boot, fit?.utcAt(boot), FlowTime.chosen, fit?.errAt(boot),
            FlowTime.gps?.errAt(boot), FlowTime.ntp?.errAt(boot),
            g.gold, g.used, g.seen, g.rmsNs, FlowTime.gps?.slope?.times(1e6),
            if (g.lastBoot > 0) (boot - g.lastBoot) / 1e6 else null,
            ntp.minusGpsUs, ntp.rttMs, ntp.boundMs,
            play.state, renderer.posErrUs, (renderer.ratio - 1) * 1e6, renderer.jumps,
            play.underruns, renderer.missingFrames, play.aheadMs, play.stampDevUs,
            st?.serverMinusFlowMs, nudge.gapAt(boot), nudge.state.rateAt(boot) * 1e6,
            st?.buffered, st?.fetchMs,
        )
    }

    /** The source line above the status grid, such as "GPS (holdover)". */
    fun sourceName(boot: Long): String {
        val src = FlowTime.chosen ?: return "No source"
        return if (src == Source.GPS && boot - gps.lastBoot > 3_000_000_000L) "GPS (holdover)" else src.name
    }

    /** The sync error shown to the listener: flowtime and the output lag's uncertainty combined, plus any gap being held, which is real sync error. */
    fun syncErrorNs(boot: Long, outputLagUncNs: Double = 0.0): Double {
        val fit = FlowTime.fit() ?: return Double.NaN
        val flow = fit.errAt(boot)
        return Math.sqrt(flow * flow + outputLagUncNs * outputLagUncNs) + abs(nudge.gapAt(boot)).toDouble()
    }

    /** The line beside the clock, such as "Gold · 14 of 16 sats · switches 1". */
    fun sourceDetail(): String {
        val switches = "switches ${FlowTime.switches}"
        if (!gps.active) return "Satellites off · $switches"
        val grade = when (gps.gold) { true -> "Gold · "; false -> "Silver · "; null -> "" }
        return "$grade${gps.used} of ${gps.seen} sats · $switches"
    }

    /** True while a better error estimate is being confirmed, with the figure it improves on. */
    fun tighteningFromNs() = detector.tighteningFromNs

    /** True while GPS and internet time disagree beyond internet time's own error: one of them is wrong. */
    val clockCheckFailed get() = detector.clockCheckFailed

    /** The test bench's status grid, from the fields design/ui-spec.md names. */
    fun statusRows(boot: Long): List<StatusRow> {
        fun t(text: String) = StatusPart(text)
        fun n(text: String, good: Boolean = false) = StatusPart(text, mono = true, good = good)
        val rows = ArrayList<StatusRow>()

        val output = mutableListOf(
            t("${routeName(play)} · ahead "), n(fmtMs(play.aheadMs)), t(" · jitter "), n(fmtDuration(play.stampDevUs * 1e3)),
            t(" · underruns "), n(play.underruns.toString()),
        )
        if (play.state != "streaming" && play.state != "ticking") output.add(t(" · ${play.state}"))
        rows.add(StatusRow("OUTPUT", output, play.error, Lane.AUDIO))

        val posNs = renderer.posErrUs * 1e3
        rows.add(StatusRow("PLAYOUT", listOf(
            t("position "), n(fmtSigned(posNs), good = abs(posNs) < 5e6), t(" · nudge "), n("%+.0f ppm".format(Locale.ROOT, (renderer.ratio - 1) * 1e6)),
            t(" · jumps "), n(renderer.jumps.toString()), t(" · missing "), n(renderer.missingFrames.toString()),
        )))

        val st = stream
        if (st != null) {
            val m = st.manifest
            val parts = if (m == null) listOf(t("connecting to ${s.url}")) else listOf(
                t("#"), n(m.newest.toString()), t(" · "), n("${st.buffered} s"), t(" buffered · "), n(st.errors.toString()),
                t(" errors · server−flow "), n(fmtSigned(st.serverMinusFlowMs * 1e6)),
            )
            rows.add(StatusRow("STREAM", parts, st.lastError, Lane.NET))
        }

        val gap = nudge.gapAt(boot)
        val mode = if (nudge.mode == NudgeMode.AUTO) "Auto" else "Manual"
        rows.add(StatusRow("GAP", when {
            gap == 0L -> listOf(n("0"), t(" · $mode"))
            nudge.state.closing -> listOf(n(fmtDuration(gap.toDouble())), t(" · $mode · closes in "), n(fmtDuration(nudge.secondsLeft(boot) * 1e9)))
            else -> listOf(n(fmtDuration(gap.toDouble())), t(" · $mode · paused"))
        }))

        val ntpParts = if (ntp.samples == 0) listOf(t("asking ${ntp.host}")) else listOf(
            t("${ntp.host} S${ntp.stratum} · rtt "), n(fmtMs(ntp.rttMs)), t(" · NTP−GPS "), n(fmtSigned(ntp.minusGpsUs * 1e3)),
        )
        rows.add(StatusRow("NTP", ntpParts, ntp.lastError, Lane.NET))

        val fitted = FlowTime.gps
        rows.add(StatusRow("GPS", when {
            !gps.active -> listOf(t("off · position stays on phone"))
            fitted == null || gps.lastBoot == 0L -> listOf(t(gps.status))
            else -> listOf(
                t("scatter "), n(fmtDuration(gps.rmsNs)), t(" · clock "), n("%+.2f ppm".format(Locale.ROOT, fitted.slope * 1e6)),
                t(" · delivery "), n(fmtMs(gps.deliveryMs)),
            )
        }))

        rows.add(StatusRow("LOG", listOf(n("$name-*.csv"))))
        return rows
    }

    /** The output in use, such as "speaker" or "Bluetooth". */
    fun outputName() = routeName(play)

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
            "state,pos_err_us,nudge_ppm,jumps,underruns,missing_frames,ahead_ms,stamp_jitter_us,server_minus_flow_ms," +
            "held_gap_ns,gap_rate_ppm,buffered_s,fetch_ms"

        private fun fmtMs(ms: Double) = fmtDuration(ms * 1e6)

        fun nowBoot() = SystemClock.elapsedRealtimeNanos()
    }
}
