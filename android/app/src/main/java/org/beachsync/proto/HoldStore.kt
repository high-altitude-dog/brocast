package org.beachsync.proto

import android.content.Context
import android.provider.Settings

/**
 * Keeps the last GPS fit across app restarts, so a reopened app can hold over from it (user, 7 Nov): when satellite time isn't back yet, the holdover
 * clock is used if its error is smaller than NTP's. The boot clock runs on while the app is closed, so the fit stays valid until the phone restarts;
 * its error bound keeps growing with time since the fix, and [FlowTime.reselect] hands over to NTP once that's better. Never touches the audio thread.
 */
object HoldStore {
    private const val PREFS = "hold"

    private fun bootCount(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)

    /** Saves the current GPS fit, if there is one. Called when the app leaves the screen. */
    fun save(ctx: Context) {
        val f = FlowTime.gps ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("boot", bootCount(ctx)).putLong("boot0", f.boot0).putLong("off0", f.off0)
            .putLong("slope", f.slope.toRawBits()).putLong("err0", f.err0.toRawBits()).putLong("growth", f.growth.toRawBits())
            .apply()
    }

    /** At app start: brings back the saved fit as the holdover clock, if the phone hasn't restarted since and no GPS fit exists yet. */
    fun restore(ctx: Context) {
        if (FlowTime.gps != null) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = bootCount(ctx)
        if (boot < 0 || p.getInt("boot", -2) != boot) return
        val boot0 = p.getLong("boot0", 0L)
        if (boot0 <= 0L || boot0 > Session.nowBoot()) return
        FlowTime.gps = ClockFit(boot0, p.getLong("off0", 0L), Double.fromBits(p.getLong("slope", 0L)), Double.fromBits(p.getLong("err0", 0L)), Double.fromBits(p.getLong("growth", 0L)))
        FlowTime.reselect(Session.nowBoot())
    }
}
