package org.beachsync.proto

import android.content.Context

/**
 * The clock with no run going: NTP always, GPS when the listener has satellites on. It feeds [FlowTime] so the screen
 * always has a source to show, and Play starts with a clock already in hand. It never touches the audio thread and
 * writes no files. Runs only while the app is on screen.
 */
class IdleClock(ctx: Context, policy: Policy) {
    val gps = GpsTime(ctx, null)
    private val ntp = NtpClient(null)
    @Volatile private var watching = true
    private val control = Thread({
        while (watching) {
            FlowTime.reselect(Session.nowBoot())
            try { Thread.sleep(100) } catch (_: InterruptedException) { }
        }
    }, "idle-clock")

    init { FlowTime.beginRun(policy) }

    fun start(satellites: Boolean) {
        if (satellites) gps.start()
        ntp.start()
        control.start()
    }

    fun setSatellites(on: Boolean) { if (on) gps.start() else gps.stop() }

    fun stop() {
        watching = false
        ntp.running = false; ntp.interrupt()
        gps.close()
        control.interrupt()
    }
}
