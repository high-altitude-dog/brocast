package org.beachsync.proto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
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
import org.beachsync.proto.ui.CalibrationPage
import org.beachsync.proto.ui.EarcupPage
import org.beachsync.proto.ui.SatellitesScreen
import org.beachsync.proto.ui.SyncScope
import org.beachsync.proto.ui.BeatSyncScope
import org.beachsync.proto.ui.NowPlaying
import org.beachsync.proto.ui.EventLogScreen
import org.beachsync.proto.ui.StreamGapScreen
import org.beachsync.proto.ui.StereoScreen
import org.beachsync.proto.ui.TimeSignalsScreen
import org.beachsync.proto.ui.SettingsPage
import org.beachsync.proto.ui.TestBench

/** Opens on Now playing. The test scripts launch straight into the test bench. The run itself lives in [Session]. */
class MainActivity : ComponentActivity(), BenchActions {
    private val prefs by lazy { getSharedPreferences("proto", MODE_PRIVATE) }
    private val state = BenchState()

    private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val runStopped: () -> Unit = { state.session = null }
    private var listenLag: Double? = null
    private var listenFromLibrary = false
    private var earcupOutput: AudioOutput? = null

    private val askForHeadsetName = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var askedForHeadsetName = false

    /** Asked only once a Bluetooth headset is connected, because its name is the only reason the app wants this. */
    override fun onResume() {
        super.onResume()
        val bluetooth = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        if (bluetooth && !askedForHeadsetName && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            askedForHeadsetName = true
            askForHeadsetName.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) earcupOutput?.let { beginEarcup(it) }
    }

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
        val testScript = intent?.hasExtra("tickPattern") == true || (intent?.hasExtra("soloSlot") == true && intent?.hasExtra("library") != true)
        if (!testScript) state.library = LibraryStore.begin(this, state.url.trim())
        state.eventLog = eventLog
        setContent {
            val fromTestScript = intent?.hasExtra("tickPattern") == true || intent?.hasExtra("soloSlot") == true
            val home = if (fromTestScript) Page.BENCH else Page.NOW
            var page by remember { mutableStateOf(home) }
            var from by remember { mutableStateOf(home) }   // where the event log and nudge screens go back to
            var logLane by remember { mutableStateOf<Lane?>(null) }
            BackHandler(page != home) {
                if (page == Page.EARCUP) cancelEarcup()
                page = when (page) { Page.LOG, Page.NUDGE -> from; Page.EARCUP -> Page.CALIBRATION; else -> home }
            }
            when (page) {
                Page.NOW -> NowPlaying(state, this, { logLane = null; from = Page.NOW; page = Page.LOG }, { from = Page.NOW; page = Page.NUDGE }, { page = Page.BENCH }, { page = Page.SETTINGS }, { page = Page.SIGNALS }, { page = Page.CALIBRATION }, { page = Page.SATELLITES }, { page = Page.STEREO })
                Page.CALIBRATION -> CalibrationPage(state, this, { page = home }, { resetEarcup(); page = Page.EARCUP })
                Page.EARCUP -> EarcupPage(state, this) { page = Page.CALIBRATION }
                Page.SIGNALS -> TimeSignalsScreen(state, this, { page = Page.SATELLITES }) { page = home }
                Page.SYNC -> SyncScope(state) { page = Page.SETTINGS }
                Page.BEAT -> BeatSyncScope(state) { page = Page.SETTINGS }
                Page.STEREO -> StereoScreen(state, this) { page = home }
                Page.SATELLITES -> SatellitesScreen(state, this, { page = home }, { page = Page.SIGNALS })
                Page.SETTINGS -> SettingsPage(state, this, { page = home }, { page = Page.CALIBRATION }, { page = Page.SATELLITES }, { page = Page.SYNC }, { page = Page.BEAT }, { page = Page.STEREO })
                Page.BENCH -> TestBench(state, this, { logLane = it; from = Page.BENCH; page = Page.LOG }, { from = Page.BENCH; page = Page.NUDGE })
                Page.LOG -> EventLogScreen(state, logLane) { page = from }
                Page.NUDGE -> StreamGapScreen(state, this) { page = from }
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
        val fromLibrary = listenFromLibrary || intent?.getBooleanExtra("library", false) == true
        listenFromLibrary = false
        val settings = Settings(
            url = state.url.trim(),
            stream = state.stream || fromLibrary,
            policy = state.policy,
            pitchHz = state.pitch.toDoubleOrNull() ?: 2000.0,
            lagMs = listenLag ?: state.lag.toDoubleOrNull() ?: 0.0,
            channel = if (currentOutput(this).kind == OutputKind.HEADPHONES) Renderer.BOTH else state.channel,   // headphones give each ear its own side
            nudgeMode = state.nudgeMode,
            nudgePeriodS = state.nudgePeriodS,
            // Only measure/quicktest.py sets this, for the launch it starts. It isn't saved, so a run from the screen never gets it.
            tickPattern = when (intent?.getStringExtra("tickPattern")) { "A" -> Renderer.PATTERN_A; "B" -> Renderer.PATTERN_B; else -> Renderer.PATTERN_OFF },
            // The same goes for these, from measure/musictest.py.
            soloSlot = intent?.getIntExtra("soloSlot", 0) ?: 0,
            soloCount = intent?.getIntExtra("soloCount", 0) ?: 0,
            library = fromLibrary,
        )
        listenLag = null
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

    override fun setLocalTime(on: Boolean) {
        state.localTime = on
        prefs.edit().putBoolean(KEY_LOCAL_TIME, on).apply()
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

    override fun listen() {
        listenFromLibrary = true
        val out = output()
        val cal = savedCalibration(out) ?: tableCalibration(out)
        if (cal.source != CalSource.NONE) { listenLag = cal.lagMs; state.lagSource = cal.source.name.lowercase() } else state.lagSource = "not measured"
        startOrStop()
    }

    override fun output() = currentOutput(this)

    override fun tableCalibration(o: AudioOutput) = LatencyTable.lookup(RouteKey(Build.MODEL, tableRoute(o)))

    private fun tableRoute(o: AudioOutput) = if (o.external) o.key else "speaker"

    override fun savedCalibration(o: AudioOutput): Calibration? {
        val p = prefs.getString(KEY_CAL + o.key, null)?.split("|") ?: return null
        val lag = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        return Calibration(lag, p.getOrNull(1)?.toDoubleOrNull() ?: 0.0, CalSource.EARCUP, p.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    override fun saveCalibration(o: AudioOutput, c: Calibration) {
        prefs.edit().putString(KEY_CAL + o.key, "${c.lagMs}|${c.plusMinusMs}|${c.measuredUtcMs}").apply()
    }

    override fun earcupValue(pass: EarcupOutcome.Pass): Calibration {
        val speaker = LatencyTable.lookup(RouteKey(Build.MODEL, "speaker"))
        return Calibration(speaker.lagMs + pass.laterMs, speaker.plusMinusMs + 1.0 + pass.scatterMs, CalSource.EARCUP, System.currentTimeMillis())
    }

    override fun startEarcup(o: AudioOutput) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginEarcup(o)
        else { earcupOutput = o; askForMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun beginEarcup(o: AudioOutput) {
        if (session != null || state.earcup != null) return
        state.earcup = EarcupRun(applicationContext, o).also { it.start() }
    }

    override fun cancelEarcup() {
        state.earcup?.cancel()
        state.earcup = null
    }

    override fun resetEarcup() { state.earcup = null }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun load() {
        state.url = prefs.getString(KEY_URL, "http://192.168.1.10:8080") ?: ""
        state.stream = prefs.getBoolean(KEY_STREAM, false)
        state.policy = when (prefs.getString(KEY_POLICY, "AUTO")) { "GPS" -> Policy.GPS_ONLY; "NTP" -> Policy.NTP_ONLY; else -> Policy.AUTO }
        state.channel = when (prefs.getString(KEY_CHANNEL, "BOTH")) { "LEFT" -> Renderer.LEFT; "RIGHT" -> Renderer.RIGHT; else -> Renderer.BOTH }
        state.pitch = prefs.getString(KEY_PITCH, "2000") ?: "2000"
        state.lag = prefs.getString(KEY_LAG, "0") ?: "0"
        state.satellites = prefs.getBoolean(KEY_SATELLITES, true)
        state.localTime = prefs.getBoolean(KEY_LOCAL_TIME, true)
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

    private enum class Page { NOW, BENCH, LOG, NUDGE, SETTINGS, SIGNALS, CALIBRATION, EARCUP, SATELLITES, SYNC, BEAT, STEREO }

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
        private const val KEY_LOCAL_TIME = "localTime"
        private const val KEY_CAL = "cal."
        private const val KEY_NUDGE_MANUAL = "nudgeManual"
        private const val KEY_NUDGE_PERIOD = "nudgePeriod"
    }
}
