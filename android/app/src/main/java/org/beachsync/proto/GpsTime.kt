package org.beachsync.proto

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.Executor
import kotlin.math.sqrt

/**
 * GPS time from the chip's raw clock reports (GnssClock), once per second:
 *   GPS time = TimeNanos - (FullBiasNanos + BiasNanos);  UTC = GPS time + 315,964,800 s - leap seconds.
 * "Gold" phones also say which boot-clock instant that reading belongs to (elapsedRealtimeNanos).
 * "Silver" phones don't, so the sample is stamped when it arrives, which adds the delivery delay.
 */
class GpsTime(ctx: Context, private val log: CsvLog) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("gnss").apply { start() }
    private val handler = Handler(thread.looper)
    private val exec = Executor { handler.post(it) }
    private var fitter = GpsFitter()   // used only on the gnss thread

    /** True while the GPS engine is asked to run. "Use satellites" can turn it on and off at any point. */
    @Volatile var active = false
        private set

    val chip: String = lm.gnssHardwareModelName ?: "unknown"
    val reportsRaw: Boolean = lm.gnssCapabilities.hasMeasurements()

    @Volatile var status = "starting"
    @Volatile var gold: Boolean? = null
    @Volatile var epochs = 0
    @Volatile var used = 0
    @Volatile var seen = 0
    @Volatile var lastBoot = 0L
    @Volatile var rmsNs = Double.NaN
    @Volatile var fitN = 0
    @Volatile var rejected = 0
    @Volatile var rejectedNoFix = 0   // clock reports ignored because the chip had no satellites in use and gave no uncertainty
    @Volatile var resets = 0
    @Volatile var deliveryMs = Double.NaN
    /** What the chip says about itself, for the Satellites screen. Written on the gnss thread, read by the UI. */
    @Volatile var erUncNs = Double.NaN        // the chip's declared uncertainty of its boot-clock stamp, 1 sigma
    @Volatile var biasUncNs = Double.NaN      // how well the satellites agree on the clock bias
    @Volatile var leapFromChip: Boolean? = null
    @Volatile var sats: List<Sat> = emptyList()

    private val measurements = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            onClock(event.clock, SystemClock.elapsedRealtimeNanos())
        }
    }

    private val satellites = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            var u = 0
            for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) u++
            used = u; seen = s.satelliteCount
            // Chips list a satellite once per signal (GPS L1 and L5, for example): one mark per satellite, the strongest signal, in use if any is.
            sats = (0 until s.satelliteCount).groupBy { s.getConstellationType(it) * 1000 + s.getSvid(it) }.values.map { idx ->
                val best = idx.maxByOrNull { s.getCn0DbHz(it) }!!
                Sat(s.getSvid(best), s.getConstellationType(best), s.getAzimuthDegrees(best), s.getElevationDegrees(best), s.getCn0DbHz(best), idx.any { s.usedInFix(it) })
            }
        }
    }

    // The GNSS engine only runs while someone asks for position fixes, so ask for them and ignore them.
    private val fixes = LocationListener { }

    /** The caller must already hold location permission. A restart begins a fresh fit; the last one carries on as holdover. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (active) return
        handler.post { fitter = GpsFitter() }
        lastBoot = 0L
        active = true
        val req = GnssMeasurementRequest.Builder().setFullTracking(true).build()
        lm.registerGnssMeasurementsCallback(req, exec, measurements)
        lm.registerGnssStatusCallback(exec, satellites)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, fixes, thread.looper)
        status = when {
            !reportsRaw -> "this phone does not report raw GNSS clocks"
            !lm.isLocationEnabled -> "Location is off in the phone's settings"
            else -> "waiting for satellites"
        }
    }

    /** Stops the GPS engine straight away. FlowTime keeps the last fit, which carries on as the holdover clock. */
    fun stop() {
        if (!active) return
        active = false
        lm.unregisterGnssMeasurementsCallback(measurements)
        lm.unregisterGnssStatusCallback(satellites)
        lm.removeUpdates(fixes)
        used = 0; seen = 0
        status = "off"
    }

    fun close() {
        stop()
        thread.quitSafely()
    }

    private fun onClock(c: GnssClock, arrival: Long) {
        if (!active) return   // a report already queued when the switch went off
        epochs++
        if (!c.hasFullBiasNanos()) { status = "waiting for GPS time"; return }
        val biasUnc = if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else 0.0
        // A chip with no satellites in use and no uncertainty to report has no time solution, and its clock can be a whole second out
        // (seen on a Pixel 10 Pro on 2 Nov 2026: 1.03 s behind for three minutes). Ignore those reports and carry on from the last fit.
        if (used == 0 && biasUnc <= 0.0) { rejectedNoFix++; status = "no satellites in use: ignoring the chip's clock"; return }
        if (biasUnc > 1e6) { status = "GPS time still rough (±%.1f ms)".format(biasUnc / 1e6); return }
        val leap = if (c.hasLeapSecond()) c.leapSecond else 18
        val bias = if (c.hasBiasNanos()) c.biasNanos else 0.0
        val utc = c.timeNanos - c.fullBiasNanos - Math.round(bias) + GPS_EPOCH_UNIX_NS - leap * 1_000_000_000L
        if (!gpsDateValid(utc)) { status = "waiting for GPS date"; return }
        val isGold = c.hasElapsedRealtimeNanos()
        gold = isGold
        leapFromChip = c.hasLeapSecond()
        val boot = if (isGold) c.elapsedRealtimeNanos else arrival
        val off = utc - boot
        val erUnc = if (isGold && c.hasElapsedRealtimeUncertaintyNanos()) c.elapsedRealtimeUncertaintyNanos else 0.0
        val before = FlowTime.gps
        val resid = if (before != null) (off - (before.utcAt(boot) - boot)).toDouble() else Double.NaN
        val link = if (isGold) 0.0 else GpsFitter.SILVER_LINK_NS
        erUncNs = erUnc; biasUncNs = biasUnc
        val fit = fitter.add(boot, off, sqrt(biasUnc * biasUnc + erUnc * erUnc), before, link)
        if (fit != null) FlowTime.gps = fit
        val line = fitter.last
        rmsNs = line?.rms ?: Double.NaN
        fitN = line?.n ?: 0
        rejected = fitter.rejected; resets = fitter.resets
        lastBoot = boot
        deliveryMs = (arrival - boot) / 1e6
        status = if (FlowTime.gps == null) "collecting samples" else "tracking"
        log.row(boot, off, isGold, biasUnc, erUnc, leap, c.hasLeapSecond(), resid,
            c.hardwareClockDiscontinuityCount, arrival - boot)
    }

    companion object {
        const val GPS_EPOCH_UNIX_NS = 315_964_800_000_000_000L   // 6 Jan 1980, in Unix-epoch ns
    }
}

/** One satellite in view, as the chip reports it. [system] is a GnssStatus.CONSTELLATION_* value. */
class Sat(val svid: Int, val system: Int, val azimuthDeg: Float, val elevationDeg: Float, val cn0DbHz: Float, val used: Boolean) {
    val letter get() = when (system) { 1 -> 'G'; 2 -> 'S'; 3 -> 'R'; 4 -> 'J'; 5 -> 'C'; 6 -> 'E'; 7 -> 'I'; else -> '?' }
    val label get() = "$letter%02d".format(svid)
    val systemName get() = when (system) { 1 -> "GPS"; 2 -> "SBAS"; 3 -> "GLONASS"; 4 -> "QZSS"; 5 -> "BeiDou"; 6 -> "Galileo"; 7 -> "NavIC"; else -> "Other" }
}
