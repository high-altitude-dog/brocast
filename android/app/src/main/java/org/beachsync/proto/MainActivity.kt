package org.beachsync.proto

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
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

    private val askForStart = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!granted) satellitesOff()
        begin(granted)
    }

    private val askForSwitch = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) session?.setSatellites(true) else satellitesOff()
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        load()
        state.session = session
        state.eventLog = eventLog
        setContent {
            var page by remember { mutableStateOf(Page.BENCH) }
            BackHandler(page != Page.BENCH) { page = Page.BENCH }
            when (page) {
                Page.BENCH -> TestBench(state, this, { page = Page.LOG }, { page = Page.NUDGE })
                Page.LOG -> PlainEventLog(state) { page = Page.BENCH }
                Page.NUDGE -> PlainNudge(state, this) { page = Page.BENCH }
            }
        }
    }

    override fun startOrStop() {
        val running = session
        if (running != null) {
            running.stop()
            session = null
            state.session = null
            return
        }
        save()
        if (state.satellites && !hasLocation()) askForStart.launch(locationPermissions) else begin(state.satellites)
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
        )
        val started = Session(this, settings, satellitesOn)
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
        if (on && !hasLocation()) askForSwitch.launch(locationPermissions) else session?.setSatellites(on)
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
