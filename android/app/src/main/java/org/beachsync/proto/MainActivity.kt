package org.beachsync.proto

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import org.beachsync.proto.ui.PocketGuard
import org.beachsync.proto.ui.SyncLineScreen
import org.beachsync.proto.ui.DataSharingScreen
import org.beachsync.proto.ui.DataSharingAdvancedScreen
import org.beachsync.proto.ui.DataLogScreen
import org.beachsync.proto.ui.IdentityWizard
import org.beachsync.proto.ui.KeptScreen
import org.beachsync.proto.ui.TestCardScreen
import org.beachsync.proto.ui.TestCardSetupScreen
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.net.Uri
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
import org.beachsync.proto.ui.ArtistPhotoScreen
import org.beachsync.proto.ui.SwarmScreen
import org.beachsync.proto.ui.BufferScreen
import org.beachsync.proto.ui.TimeSignalsScreen
import org.beachsync.proto.ui.SettingsPage
import org.beachsync.proto.ui.TestBench

/** Opens on Now playing. The test scripts launch straight into the test bench. The run itself lives in [Session]. */
class MainActivity : ComponentActivity(), BenchActions {
    private val prefs by lazy { getSharedPreferences("proto", MODE_PRIVATE) }
    private val state = BenchState()

    private val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val runStopped: () -> Unit = { state.session = null; runOnUiThread { startIdle() } }
    private var idle: IdleClock? = null
    private var visible = false
    private var listenLag: Double? = null
    private var listenFromLibrary = false
    private var earcupOutput: AudioOutput? = null

    /** Asked only from Now playing's card or Calibration's row, once Bluetooth headphones are connected, because their name is the only reason the app wants this. */
    private val askForHeadsetName = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) A2dpCodec.connect(this) else prefs.edit().putInt(KEY_BT_NAME_REFUSED, prefs.getInt(KEY_BT_NAME_REFUSED, 0) + 1).apply()
    }

    /** The clock runs with no run going, so the screen always has a source and Play starts with a clock in hand. Only while on screen. */
    private fun startIdle() {
        if (!visible || idle != null || session != null) return
        val c = IdleClock(applicationContext, state.policy)
        c.start(state.satellites && hasLocation())
        idle = c
        state.idleGps = c.gps
    }

    private fun stopIdle() {
        idle?.stop(); idle = null; state.idleGps = null
    }

    override fun onStart() { super.onStart(); visible = true; HoldStore.restore(this); startIdle() }

    override fun onStop() { visible = false; HoldStore.save(this); if (session == null) stopIdle(); super.onStop() }

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { PocketGuard.covered = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f) }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java).unregisterListener(proximity)
        PocketGuard.covered = false
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        PocketGuard.resumedAt = SystemClock.uptimeMillis()
        getSystemService(SensorManager::class.java).let { sm -> sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) } }
        A2dpCodec.connect(this)   // also after the permission is given in app settings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onRunStopped = runStopped
        org.beachsync.proto.ui.ScreenPrefs.load(this)
        // The app is always dark, so the bars' icons stay light whatever the phone's own theme is.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val firstLaunch = !prefs.getBoolean(KEY_LAUNCHED, false) && prefs.all.isEmpty()   // an app updated from before this key isn't new
        prefs.edit().putBoolean(KEY_LAUNCHED, true).apply()
        state.headphonesNameOffer = !firstLaunch && !prefs.getBoolean(KEY_BT_NAME_ASKED, false)
        load()
        state.session = session
        val testScript = intent?.hasExtra("tickPattern") == true || (intent?.hasExtra("soloSlot") == true && intent?.hasExtra("library") != true)
        if (!testScript) applyStation()
        window.decorView.post(newsTicker)
        state.eventLog = eventLog
        setContent {
            val fromTestScript = intent?.hasExtra("tickPattern") == true || intent?.hasExtra("soloSlot") == true
            val home = if (fromTestScript) Page.BENCH else Page.NOW
            var page by remember { mutableStateOf(home) }
            var from by remember { mutableStateOf(home) }   // where the event log and nudge screens go back to
            var logLane by remember { mutableStateOf<Lane?>(null) }
            var artistFrom by remember { mutableStateOf(home) }
            var artistItem by remember { mutableStateOf<String?>(null) }
            var cardSetup by remember { mutableStateOf<org.beachsync.proto.ui.TestCardSetup?>(null) }
            BackHandler(page != home) {
                if (page == Page.EARCUP) cancelEarcup()
                page = when (page) { Page.LOG, Page.NUDGE -> from; Page.EARCUP -> Page.CALIBRATION; Page.ADVANCED, Page.DATALOG -> Page.DATA; Page.SYNC_LINE -> Page.SYNC; Page.DATA, Page.IDENTITY, Page.CARD_SETUP -> Page.SETTINGS; Page.CARD -> Page.CARD_SETUP; Page.ARTIST -> if (artistFrom == Page.KEPT) Page.KEPT else home; else -> home }
            }
            when (page) {
                Page.NOW -> NowPlaying(state, this, { logLane = null; from = Page.NOW; page = Page.LOG }, { from = Page.NOW; page = Page.NUDGE }, { page = Page.BENCH }, { page = Page.SETTINGS }, { page = Page.SIGNALS }, { page = Page.CALIBRATION }, { page = Page.SATELLITES }, { page = Page.STEREO }, { page = Page.ARTIST }, { page = Page.SWARM }, { page = Page.BUFFER }) { page = Page.KEPT }
                Page.KEPT -> KeptScreen(state, { page = home }) { id -> artistFrom = Page.KEPT; artistItem = id; page = Page.ARTIST }
                Page.CALIBRATION -> CalibrationPage(state, this, { page = home }, { resetEarcup(); page = Page.EARCUP })
                Page.EARCUP -> EarcupPage(state, this) { page = Page.CALIBRATION }
                Page.SIGNALS -> TimeSignalsScreen(state, this, { page = Page.SATELLITES }) { page = home }
                Page.SYNC -> SyncScope(state, this, { page = Page.SETTINGS }, { page = Page.SYNC_LINE }) { page = Page.CARD_SETUP }
                Page.SYNC_LINE -> SyncLineScreen(state) { page = Page.SYNC }
                Page.BEAT -> BeatSyncScope(state) { page = Page.SETTINGS }
                Page.STEREO -> StereoScreen(state, this) { page = home }
                Page.ARTIST -> ArtistPhotoScreen(state, if (artistFrom == Page.KEPT) artistItem else null) { page = if (artistFrom == Page.KEPT) Page.KEPT else home; artistFrom = home }
                Page.SWARM -> SwarmScreen { page = home }
                Page.BUFFER -> BufferScreen(state, { page = home }) { page = Page.SWARM }
                Page.SATELLITES -> SatellitesScreen(state, this, { page = home }, { page = Page.SIGNALS })
                Page.SETTINGS -> SettingsPage(state, this, { page = home }, { page = Page.CALIBRATION }, { page = Page.SATELLITES }, { page = Page.SYNC }, { page = Page.BEAT }, { page = Page.STEREO }, { page = Page.DATA }, { page = Page.IDENTITY }) { page = Page.CARD_SETUP }
                Page.CARD_SETUP -> TestCardSetupScreen(state, this, { page = Page.SETTINGS }) { cardSetup = it; page = Page.CARD }
                Page.CARD -> cardSetup?.let { TestCardScreen(state, this, it) { page = Page.CARD_SETUP } }
                Page.IDENTITY -> IdentityWizard(!state.satellites) { page = Page.SETTINGS }
                Page.DATA -> DataSharingScreen({ page = Page.SETTINGS }, { page = Page.DATALOG }, { page = Page.ADVANCED })
                Page.ADVANCED -> DataSharingAdvancedScreen { page = Page.DATA }
                Page.DATALOG -> DataLogScreen { page = Page.DATA }
                Page.BENCH -> TestBench(state, this, { logLane = it; from = Page.BENCH; page = Page.LOG }, { from = Page.BENCH; page = Page.NUDGE })
                Page.LOG -> EventLogScreen(state, logLane) { page = from }
                Page.NUDGE -> StreamGapScreen(state, this) { page = from }
            }
            org.beachsync.proto.ui.SyncNudgeOverlays(state, this)   // the Sync view's Keep-it toast and sheet, over whichever page it left for
        }
    }

    /** Running-order news (hand-off 41), once a second while the app is alive, on the main thread, from the library only. */
    private val newsTicker = object : Runnable {
        override fun run() {
            org.beachsync.proto.ui.OrderNews.tick(this@MainActivity, state.library, state.eventLog)
            window.decorView.postDelayed(this, 1000L)
        }
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(newsTicker)
        if (onRunStopped === runStopped) onRunStopped = null
        // Closing the app (Back out of it, or finished) stops the music. Home, the screen going off or a rotation don't.
        if (isFinishing && !isChangingConfigurations && session != null) { stopRun(); SessionService.end(this) }
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
        stopIdle()   // the run brings its own time sources; the fits they inherit carry on
        SessionService.run(this, satellitesOn)
        val started = Session(applicationContext, settings, satellitesOn)
        PearsEngine.flushTo(started.events)   // first listen's lines come before the run's own
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

    /** Be counted as a listener (hand-off 36, the user's split from Use satellites): off leaves this phone out of the listener count. */
    override fun setCounted(on: Boolean) {
        state.counted = on
        prefs.edit().putBoolean(KEY_COUNTED, on).apply()
        if (state.library != null) applyStation()
    }

    /** Show my country (hand-off 38): live, without restarting the engine. */
    override fun setShowCountry(on: Boolean) {
        state.showCountry = on
        prefs.edit().putBoolean(KEY_SHOW_COUNTRY, on).apply()
        PearsEngine.setShowCountry(on)
    }

    override fun answerCountry(show: Boolean) {
        state.countryOffer = false
        prefs.edit().putBoolean(KEY_COUNTRY_ASKED, true).apply()
        if (show) setShowCountry(true)
    }

    /** Left, Right or Both, also while playing. Headphones always get Both. */
    override fun setChannel(channel: Int) {
        state.channel = channel
        save()
        session?.setChannel(if (currentOutput(this).kind == OutputKind.HEADPHONES) Renderer.BOTH else channel)
    }

    override fun setLocalTime(on: Boolean) {
        state.localTime = on
        prefs.edit().putBoolean(KEY_LOCAL_TIME, on).apply()
    }

    private fun satellitesInRun(on: Boolean) {
        val running = session ?: run { idle?.setSatellites(on); return }
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

    override fun listen(clicks: Boolean) {
        listenFromLibrary = !clicks
        val out = output()
        val cal = savedCalibration(out) ?: tableCalibration(out)
        if (cal.source != CalSource.NONE) { listenLag = cal.lagMs; state.lagSource = if (cal.source == CalSource.BY_EAR) "adjusted by ear" else cal.source.name.lowercase() } else state.lagSource = "not measured"
        startOrStop()
    }

    override fun output() = currentOutput(this)

    override fun askHeadphonesName() {
        notNowHeadphonesName()
        if (prefs.getInt(KEY_BT_NAME_REFUSED, 0) >= 2) startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        else askForHeadsetName.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    override fun notNowHeadphonesName() {
        state.headphonesNameOffer = false
        prefs.edit().putBoolean(KEY_BT_NAME_ASKED, true).apply()
    }

    override fun tableCalibration(o: AudioOutput) = LatencyTable.lookup(RouteKey(Build.MODEL, tableRoute(o)))

    private fun tableRoute(o: AudioOutput) = if (o.external) o.key else "speaker"

    override fun savedCalibration(o: AudioOutput): Calibration? {
        val p = prefs.getString(KEY_CAL + o.key, null)?.split("|") ?: return null
        val lag = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val source = p.getOrNull(3)?.let { n -> CalSource.entries.firstOrNull { it.name == n } } ?: CalSource.EARCUP
        return Calibration(lag, p.getOrNull(1)?.toDoubleOrNull() ?: 0.0, source, p.getOrNull(2)?.toLongOrNull() ?: 0L)
    }

    override fun saveCalibration(o: AudioOutput, c: Calibration) {
        prefs.edit().putString(KEY_CAL + o.key, "${c.lagMs}|${c.plusMinusMs}|${c.measuredUtcMs}|${c.source.name}").apply()
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
        // A station's key fetches the library over the Pears mesh instead of from the server. adb: am start ... --es station <64 hex digits>, or "-" for the server.
        state.stationKey = intent?.getStringExtra("station")?.let { if (it == "-") "" else it } ?: prefs.getString(KEY_STATION, "") ?: ""
        prefs.edit().putString(KEY_STATION, state.stationKey).apply()
        state.stream = prefs.getBoolean(KEY_STREAM, false)
        state.policy = when (prefs.getString(KEY_POLICY, "AUTO")) { "GPS" -> Policy.GPS_ONLY; "NTP" -> Policy.NTP_ONLY; else -> Policy.AUTO }
        state.channel = Renderer.BOTH   // Stereo at every app start (hand-off 18); a change lasts until the app restarts
        state.pitch = prefs.getString(KEY_PITCH, "2000") ?: "2000"
        state.lag = prefs.getString(KEY_LAG, "0") ?: "0"
        state.satellites = prefs.getBoolean(KEY_SATELLITES, true)
        state.localTime = prefs.getBoolean(KEY_LOCAL_TIME, true)
        state.counted = prefs.getBoolean(KEY_COUNTED, true)
        state.showCountry = prefs.getBoolean(KEY_SHOW_COUNTRY, false)
        state.countryOffer = !prefs.getBoolean(KEY_COUNTRY_ASKED, false)
        PearsEngine.setShowCountry(state.showCountry)
        state.nudgeMode = if (prefs.getBoolean(KEY_NUDGE_MANUAL, false)) NudgeMode.MANUAL else NudgeMode.AUTO
        state.nudgePeriodS = prefs.getFloat(KEY_NUDGE_PERIOD, -1f).let { if (it > 0f) it.toDouble() else null }
    }

    /** The library's source: the station's key over the mesh, or the server. Only acts on a change. */
    private fun applyStation() {
        val key = state.stationKey.trim().lowercase()
        val privacy = !state.counted   // Be counted as a listener, a setting of its own since hand-off 36
        if (state.library != null && "$key/$privacy" == appliedStation) return
        appliedStation = "$key/$privacy"
        val lib = LibraryStore.begin(this, state.url.trim(), key)
        state.library = lib
        PearsEngine.use(this, key, lib, privacy)
    }
    private var appliedStation: String? = null

    private fun save() {
        prefs.edit()
            .putString(KEY_URL, state.url.trim())
            .putString(KEY_STATION, state.stationKey.trim())
            .putBoolean(KEY_STREAM, state.stream)
            .putString(KEY_POLICY, when (state.policy) { Policy.GPS_ONLY -> "GPS"; Policy.NTP_ONLY -> "NTP"; Policy.AUTO -> "AUTO" })
            .putString(KEY_PITCH, state.pitch)
            .putString(KEY_LAG, state.lag)
            .putString(KEY_CHANNEL, when (state.channel) { Renderer.LEFT -> "LEFT"; Renderer.RIGHT -> "RIGHT"; else -> "BOTH" })
            .apply()
        if (state.library != null) applyStation()
    }

    private enum class Page { NOW, BENCH, LOG, NUDGE, SETTINGS, SIGNALS, CALIBRATION, EARCUP, SATELLITES, SYNC, SYNC_LINE, BEAT, STEREO, ARTIST, SWARM, BUFFER, DATA, ADVANCED, DATALOG, IDENTITY, KEPT, CARD_SETUP, CARD }

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
        private const val KEY_STATION = "station"
        private const val KEY_STREAM = "stream"
        private const val KEY_POLICY = "policy"
        private const val KEY_PITCH = "pitch"
        private const val KEY_LAG = "lag"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_SATELLITES = "satellites"
        private const val KEY_LOCAL_TIME = "localTime"
        private const val KEY_COUNTED = "counted"
        private const val KEY_SHOW_COUNTRY = "showCountry"
        private const val KEY_COUNTRY_ASKED = "countryAsked"
        private const val KEY_CAL = "cal."
        private const val KEY_LAUNCHED = "launched"
        private const val KEY_BT_NAME_ASKED = "btNameAsked"
        private const val KEY_BT_NAME_REFUSED = "btNameRefused"
        private const val KEY_NUDGE_MANUAL = "nudgeManual"
        private const val KEY_NUDGE_PERIOD = "nudgePeriod"
    }
}
