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
    private val fitter = GpsFitter()

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
    @Volatile var resets = 0
    @Volatile var deliveryMs = Double.NaN

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
        }
    }

    // The GNSS engine only runs while someone asks for position fixes, so ask for them and ignore them.
    private val fixes = LocationListener { }

    @SuppressLint("MissingPermission")
    fun start() {
        val req = GnssMeasurementRequest.Builder().setFullTracking(true).build()
        lm.registerGnssMeasurementsCallback(req, exec, measurements)
        lm.registerGnssStatusCallback(exec, satellites)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, fixes, thread.looper)
        status = if (reportsRaw) "waiting for satellites" else "this phone does not report raw GNSS clocks"
    }

    fun stop() {
        lm.unregisterGnssMeasurementsCallback(measurements)
        lm.unregisterGnssStatusCallback(satellites)
        lm.removeUpdates(fixes)
        thread.quitSafely()
    }

    private fun onClock(c: GnssClock, arrival: Long) {
        epochs++
        if (!c.hasFullBiasNanos()) { status = "waiting for GPS time"; return }
        val biasUnc = if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else 0.0
        if (biasUnc > 1e6) { status = "GPS time still rough (±%.1f ms)".format(biasUnc / 1e6); return }
        val isGold = c.hasElapsedRealtimeNanos()
        gold = isGold
        val boot = if (isGold) c.elapsedRealtimeNanos else arrival
        val leap = if (c.hasLeapSecond()) c.leapSecond else 18
        val bias = if (c.hasBiasNanos()) c.biasNanos else 0.0
        val utc = c.timeNanos - c.fullBiasNanos - Math.round(bias) + GPS_EPOCH_UNIX_NS - leap * 1_000_000_000L
        val off = utc - boot
        val erUnc = if (isGold && c.hasElapsedRealtimeUncertaintyNanos()) c.elapsedRealtimeUncertaintyNanos else 0.0
        val before = FlowTime.gps
        val resid = if (before != null) (off - (before.utcAt(boot) - boot)).toDouble() else Double.NaN
        val fit = fitter.add(boot, off, sqrt(biasUnc * biasUnc + erUnc * erUnc), before)
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
