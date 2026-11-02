package org.beachsync.proto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * Keeps a run alive when the app is off screen: playback, networking and, when on, GPS. Without it, Android cuts the
 * app's network and GPS within seconds of leaving it, then freezes it. The run itself still lives in [Session], and
 * nothing here touches the audio thread. Its notice is the plain one the test bench needs, not the designed media
 * notification in design/ui-spec.md, which comes with the listener screens.
 */
class SessionService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Test run", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beachsync:session")
            .apply { acquire(WAKE_MAX_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MainActivity.stopRun()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val satellites = intent?.getBooleanExtra(EXTRA_SATELLITES, false) == true &&
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val music = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        try {
            startForeground(ID, notice(), if (satellites) music or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else music)
        } catch (e: RuntimeException) {
            // The music matters more than GPS in the background, so fall back to music alone.
            Log.w(TAG, "foreground with location refused", e)
            try { startForeground(ID, notice(), music) } catch (e2: RuntimeException) { Log.w(TAG, "foreground refused", e2); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    private fun notice(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Beach Sync test run")
            .setContentText("Playing. Tap to open.")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "SessionService"
        private const val CHANNEL = "run"
        private const val ID = 1
        private const val ACTION_STOP = "org.beachsync.proto.STOP"
        private const val EXTRA_SATELLITES = "satellites"
        private const val WAKE_MAX_MS = 24 * 60 * 60 * 1000L   // a safety limit: a forgotten run can't hold the phone awake for ever

        /** Starts the service, or changes its type when "Use satellites" changes. Call only while the app is on screen. */
        fun run(ctx: Context, satellites: Boolean) {
            ctx.startForegroundService(Intent(ctx, SessionService::class.java).putExtra(EXTRA_SATELLITES, satellites))
        }

        fun end(ctx: Context) {
            ctx.stopService(Intent(ctx, SessionService::class.java))
        }
    }
}
