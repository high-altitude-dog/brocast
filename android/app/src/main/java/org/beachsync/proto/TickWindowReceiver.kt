package org.beachsync.proto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts and stops the test sound for measure/quicktest.py and musictest.py, which send this over adb with a duration in ms:
 * more than 0 sounds for that long, and 0 stops it now. The duration means a lost connection can't leave a phone
 * ticking. Only adb can send it, because the manifest requires a permission that ordinary apps can't hold.
 */
class TickWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = MainActivity.session?.renderer ?: return
        val ms = intent.getLongExtra("ms", 0L)
        if (ms <= 0L) {
            r.tickUntilUtc = Long.MIN_VALUE
            return
        }
        val now = FlowTime.fit()?.utcAt(Session.nowBoot()) ?: return
        r.tickUntilUtc = now + ms * 1_000_000L
        r.tickFromUtc = now
    }
}
