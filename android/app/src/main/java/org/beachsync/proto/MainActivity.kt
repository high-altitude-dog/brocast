package org.beachsync.proto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.beachsync.proto.ui.BenchActions
import org.beachsync.proto.ui.BenchState
import org.beachsync.proto.ui.PlainEventLog
import org.beachsync.proto.ui.PlainNudge
import org.beachsync.proto.ui.TestBench

/** The test bench: settings, Start/Stop and the live status. The run itself lives in [Session]. */
class MainActivity : ComponentActivity(), BenchActions {
    private val prefs by lazy { getSharedPreferences("proto", MODE_PRIVATE) }
    private val state = BenchState()

    private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val runStopped: () -> Unit = { state.session = null }

    private val askForStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (state.satellites && !hasLocation()) satellitesOff()
        begin(state.satellites)
    }

    private val askForSwitch = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) satellitesInRun(true) else satellitesOff()
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        onRunStopped = runStopped
        // The app is always dark, so the bars' icons stay light whatever the phone's own theme is.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        load()
        state.session = session
        state.eventLog = eventLog
        setContent {
            var page by remember { mutableStateOf(Page.BENCH) }
            var logLane by remember { mutableStateOf<Lane?>(null) }
            BackHandler(page != Page.BENCH) { page = Page.BENCH }
            when (page) {
                Page.BENCH -> TestBench(state, this, { logLane = it; page = Page.LOG }, { page = Page.NUDGE })
                Page.LOG -> PlainEventLog(state, logLane) { page = Page.BENCH }
                Page.NUDGE -> PlainNudge(state, this) { page = Page.BENCH }
            }
        }
    }

    override fun onDestroy() {
        if (onRunStopped === runStopped) onRunStopped = null
        super.onDestroy()
    }

    override fun startOrStop() {
        if (session != null) {
            stopRun()
            SessionService.end(this)
            return
        }
        save()
        val ask = ArrayList<String>()
        if (state.satellites && !hasLocation()) ask.addAll(locationPermissions)
        // Saying no only hides the notice: the run still stays alive off screen.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ask.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ask.isEmpty()) begin(state.satellites) else askForStart.launch(ask.toTypedArray())
    }

    private fun begin(satellitesOn: Boolean) {
        val settings = Settings(
            url = state.url.trim(),
            stream = state.stream,
            policy = state.policy,
            pitchHz = state.pitch.toDoubleOrNull() ?: 2000.0,
            lagMs = state.lag.toDoubleOrNull() ?: 0.0,
            channel = state.channel,
            nudgeMode = state.nudgeMode,
            nudgePeriodS = state.nudgePeriodS,
            // Only measure/quicktest.py sets this, for the launch it starts. It isn't saved, so a run from the screen never gets it.
            tickPattern = when (intent?.getStringExtra("tickPattern")) { "A" -> Renderer.PATTERN_A; "B" -> Renderer.PATTERN_B; else -> Renderer.PATTERN_OFF },
            // The same goes for these, from measure/musictest.py.
            soloSlot = intent?.getIntExtra("soloSlot", 0) ?: 0,
            soloCount = intent?.getIntExtra("soloCount", 0) ?: 0,
        )
        SessionService.run(this, satellitesOn)
        val started = Session(applicationContext, settings, satellitesOn)
        started.start()
        session = started
        eventLog = started.events
        state.session = started
        state.eventLog = started.events
    }

    /** Location is asked for only when the listener wants satellites. Saying no leaves the switch off and the music playing. */
    override fun setSatellites(on: Boolean) {
        state.satellites = on
        prefs.edit().putBoolean(KEY_SATELLITES, on).apply()
        if (on && !hasLocation()) askForSwitch.launch(locationPermissions) else satellitesInRun(on)
    }

    private fun satellitesInRun(on: Boolean) {
        val running = session ?: return
        SessionService.run(this, on)
        running.setSatellites(on)
    }

    private fun satellitesOff() {
        state.satellites = false
        prefs.edit().putBoolean(KEY_SATELLITES, false).apply()
    }

    override fun setNudgeMode(mode: NudgeMode) {
        state.nudgeMode = mode
        prefs.edit().putBoolean(KEY_NUDGE_MANUAL, mode == NudgeMode.MANUAL).apply()
        session?.nudge?.setMode(mode, Session.nowBoot())
    }

    override fun setNudgePeriod(seconds: Double?) {
        state.nudgePeriodS = seconds
        prefs.edit().putFloat(KEY_NUDGE_PERIOD, seconds?.toFloat() ?: -1f).apply()
        session?.nudge?.setPeriod(seconds, Session.nowBoot())
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun load() {
        state.url = prefs.getString(KEY_URL, "http://192.168.1.10:8080") ?: ""
        state.stream = prefs.getBoolean(KEY_STREAM, false)
        state.policy = when (prefs.getString(KEY_POLICY, "AUTO")) { "GPS" -> Policy.GPS_ONLY; "NTP" -> Policy.NTP_ONLY; else -> Policy.AUTO }
        state.channel = when (prefs.getString(KEY_CHANNEL, "BOTH")) { "LEFT" -> Renderer.LEFT; "RIGHT" -> Renderer.RIGHT; else -> Renderer.BOTH }
        state.pitch = prefs.getString(KEY_PITCH, "2000") ?: "2000"
        state.lag = prefs.getString(KEY_LAG, "0") ?: "0"
        state.satellites = prefs.getBoolean(KEY_SATELLITES, true)
        state.nudgeMode = if (prefs.getBoolean(KEY_NUDGE_MANUAL, false)) NudgeMode.MANUAL else NudgeMode.AUTO
        state.nudgePeriodS = prefs.getFloat(KEY_NUDGE_PERIOD, -1f).let { if (it > 0f) it.toDouble() else null }
    }

    private fun save() {
        prefs.edit()
            .putString(KEY_URL, state.url.trim())
            .putBoolean(KEY_STREAM, state.stream)
            .putString(KEY_POLICY, when (state.policy) { Policy.GPS_ONLY -> "GPS"; Policy.NTP_ONLY -> "NTP"; Policy.AUTO -> "AUTO" })
            .putString(KEY_PITCH, state.pitch)
            .putString(KEY_LAG, state.lag)
            .putString(KEY_CHANNEL, when (state.channel) { Renderer.LEFT -> "LEFT"; Renderer.RIGHT -> "RIGHT"; else -> "BOTH" })
            .apply()
    }

    private enum class Page { BENCH, LOG, NUDGE }

    companion object {
        /** Kept outside the activity, so a test run survives the screen being rebuilt. */
        var session: Session? = null

        /** The latest run's events, kept so they can still be read after Stop. */
        var eventLog: EventLog? = null

        /** Tells a live screen that the run stopped, including from the notification's Stop button. */
        private var onRunStopped: (() -> Unit)? = null

        fun stopRun() {
            session?.stop()
            session = null
            onRunStopped?.invoke()
        }

        private const val KEY_URL = "url"
        private const val KEY_STREAM = "stream"
        private const val KEY_POLICY = "policy"
        private const val KEY_PITCH = "pitch"
        private const val KEY_LAG = "lag"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_SATELLITES = "satellites"
        private const val KEY_NUDGE_MANUAL = "nudgeManual"
        private const val KEY_NUDGE_PERIOD = "nudgePeriod"
    }
}
