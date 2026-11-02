package org.beachsync.proto

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow

/** One screen: settings, Start/Stop, flowtime as UK time, and the live status panel. */
class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var mode: RadioGroup
    private lateinit var source: RadioGroup
    private lateinit var channel: RadioGroup
    private lateinit var pitch: EditText
    private lateinit var lag: EditText
    private lateinit var satellites: Switch
    private lateinit var nudgeMode: RadioGroup
    private lateinit var nudgeSlider: SeekBar
    private lateinit var nudgeInfo: TextView
    private lateinit var goButton: Button
    private lateinit var jumpButton: Button
    private var idNudgeAuto = 0
    private var idManual = 0
    private lateinit var button: Button
    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var latestEvent: TextView
    private lateinit var logButton: Button
    private lateinit var eventList: TextView
    private var logOpen = false
    private var shownLog: EventLog? = null
    private var shownEvents = -1
    private var idTick = 0
    private var idStream = 0
    private var idAuto = 0
    private var idGps = 0
    private var idNtp = 0
    private var idBoth = 0
    private var idLeft = 0
    private var idRight = 0
    private var ticks = 0
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("proto", MODE_PRIVATE) }
    private val uk = DateTimeFormatter.ofPattern("HH:mm:ss.SSS z").withZone(ZoneId.of("Europe/London"))
    private val ukEvent = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/London"))

    private val loop = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildUi()
        refreshButton()
    }

    override fun onResume() { super.onResume(); ui.post(loop) }
    override fun onPause() { ui.removeCallbacks(loop); super.onPause() }

    private fun refresh() {
        val boot = Session.nowBoot()
        val s = session
        val fit = FlowTime.fit()
        clock.text = if (s == null || fit == null) "--:--:--.---" else uk.format(Instant.ofEpochSecond(0, fit.utcAt(boot)))
        status.text = s?.describe(boot) ?: "Stopped. Set up both phones the same way, then press Start on each."
        if (s != null && ++ticks % 4 == 0) s.logRow(boot)
        refreshEvents()
        refreshNudge(boot)
    }

    private fun chosenPeriod(): Double? = prefs.getFloat("nudgePeriod", -1f).let { if (it > 0f) it.toDouble() else null }

    private fun chosenMode() = if (nudgeMode.checkedRadioButtonId == idManual) NudgeMode.MANUAL else NudgeMode.AUTO

    private fun refreshNudge(boot: Long) {
        val n = session?.nudge
        val period = chosenPeriod()
        val label = if (period == null) "recommended" else "%.0f s".format(period)
        val gap = n?.gapAt(boot) ?: 0L
        val closing = n != null && n.state.closing && gap != 0L
        nudgeInfo.text = buildString {
            if (gap == 0L) append("No gap · period: $label") else {
                val secs = if (closing) n!!.secondsLeft(boot) else n!!.effectivePeriodS(gap)
                val rate = if (closing) abs(n.state.rate) else abs(gap) / (secs * 1e9)
                append("Gap ${NudgeControl.fmt(gap)} · ")
                append(if (closing) "closing, %.0f s left".format(secs) else "held, would take %.0f s".format(secs))
                append(" · up to %.2f cents".format(1200 * log2(1 + rate)))
            }
        }
        val manual = chosenMode() == NudgeMode.MANUAL
        goButton.text = if (closing) "Pause" else "Go"
        goButton.isEnabled = manual && gap != 0L
        jumpButton.isEnabled = manual && gap != 0L
    }

    private fun periodToProgress(s: Double) = (1000 * ln(s / NudgeControl.MIN_PERIOD_S) / ln(NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S)).toInt()

    private fun progressToPeriod(p: Int) = NudgeControl.MIN_PERIOD_S * (NudgeControl.MAX_PERIOD_S / NudgeControl.MIN_PERIOD_S).pow(p / 1000.0)

    private fun setPeriod(p: Double?) {
        prefs.edit().putFloat("nudgePeriod", p?.toFloat() ?: -1f).apply()
        session?.nudge?.setPeriod(p, Session.nowBoot())
    }

    /** Redraws only when there is something new, and builds the full list only while it is open. */
    private fun refreshEvents() {
        val log = eventLog
        val n = log?.total ?: 0
        if (log === shownLog && n == shownEvents) return
        shownLog = log; shownEvents = n
        val last = log?.latest()
        latestEvent.text = if (last == null) "No events yet" else "Latest: " + eventLine(last)
        if (logOpen) {
            eventList.text = log?.recent(SHOWN_EVENTS)?.joinToString("\n") { eventLine(it) } ?: ""
        }
    }

    private fun eventLine(e: Event): String {
        val t = e.utc?.let { ukEvent.format(Instant.ofEpochSecond(0, it)) } ?: "--:--:--"
        return "$t ${e.lane.name.padEnd(6)} ${e.text}"
    }

    private fun toggleLog() {
        logOpen = !logOpen
        logButton.text = if (logOpen) "Hide event log" else "Show event log"
        eventList.visibility = if (logOpen) View.VISIBLE else View.GONE
        shownEvents = -1
        refreshEvents()
    }

    private fun onButton() {
        val running = session
        if (running != null) {
            running.stop(); session = null; refreshButton(); return
        }
        savePrefs()
        if (satellites.isChecked && !hasLocation()) askLocation(REQUEST_FOR_START) else begin(satellites.isChecked)
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocation(request: Int) {
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), request)
    }

    /** Location is asked for only when the listener wants satellites. Saying no leaves the switch off and the music playing. */
    private fun onSatellitesChanged(on: Boolean) {
        prefs.edit().putBoolean("satellites", on).apply()
        if (on && !hasLocation()) askLocation(REQUEST_FOR_SWITCH) else session?.setSatellites(on)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) satellites.isChecked = false
        if (requestCode == REQUEST_FOR_START) begin(granted) else if (granted) session?.setSatellites(true)
    }

    private fun begin(satellitesOn: Boolean) {
        val settings = Settings(
            url = url.text.toString().trim(),
            stream = mode.checkedRadioButtonId == idStream,
            policy = when (source.checkedRadioButtonId) { idGps -> Policy.GPS_ONLY; idNtp -> Policy.NTP_ONLY; else -> Policy.AUTO },
            pitchHz = pitch.text.toString().toDoubleOrNull() ?: 2000.0,
            lagMs = lag.text.toString().toDoubleOrNull() ?: 0.0,
            channel = when (channel.checkedRadioButtonId) { idLeft -> Renderer.LEFT; idRight -> Renderer.RIGHT; else -> Renderer.BOTH },
            nudgeMode = chosenMode(),
            nudgePeriodS = chosenPeriod(),
        )
        session = try {
            Session(this, settings, satellitesOn).also { it.start(); eventLog = it.events }
        } catch (e: Exception) {
            status.text = "Could not start: $e"; null
        }
        refreshButton()
    }

    private fun refreshButton() {
        val on = session != null
        button.text = if (on) "Stop" else "Start"
        for (v in listOf(url, pitch, lag)) v.isEnabled = !on
        for (g in listOf(mode, source, channel)) for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = !on
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("url", url.text.toString().trim())
            .putBoolean("stream", mode.checkedRadioButtonId == idStream)
            .putString("policy", when (source.checkedRadioButtonId) { idGps -> "GPS"; idNtp -> "NTP"; else -> "AUTO" })
            .putString("pitch", pitch.text.toString())
            .putString("lag", lag.text.toString())
            .putString("channel", when (channel.checkedRadioButtonId) { idLeft -> "LEFT"; idRight -> "RIGHT"; else -> "BOTH" })
            .apply()
    }

    private fun buildUi() {
        val pad = dp(16)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        clock = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 30f; text = "--:--:--.---" }
        col.addView(clock)
        col.addView(caption("Flowtime, UK time"))

        col.addView(caption("Stream server"))
        url = EditText(this).apply {
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("url", "http://192.168.1.10:8080"))
        }
        col.addView(url)

        col.addView(caption("Mode"))
        mode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        idTick = radio(mode, "Tick")
        idStream = radio(mode, "Stream")
        mode.check(if (prefs.getBoolean("stream", false)) idStream else idTick)
        col.addView(mode)

        col.addView(caption("Time source"))
        source = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        idAuto = radio(source, "Auto")
        idGps = radio(source, "GPS only")
        idNtp = radio(source, "NTP only")
        source.check(when (prefs.getString("policy", "AUTO")) { "GPS" -> idGps; "NTP" -> idNtp; else -> idAuto })
        col.addView(source)

        col.addView(caption("Stream channel"))
        channel = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        idBoth = radio(channel, "Both")
        idLeft = radio(channel, "Left only")
        idRight = radio(channel, "Right only")
        channel.check(when (prefs.getString("channel", "BOTH")) { "LEFT" -> idLeft; "RIGHT" -> idRight; else -> idBoth })
        col.addView(channel)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pitch = number(prefs.getString("pitch", "2000") ?: "2000")
        lag = number(prefs.getString("lag", "0") ?: "0")
        row.addView(labelled("Tick pitch, Hz", pitch), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(labelled("Headphone lag, ms", lag), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row)

        satellites = Switch(this).apply {
            text = "Use satellites"
            isChecked = prefs.getBoolean("satellites", true)
            setPadding(0, dp(10), 0, dp(10))
            setOnCheckedChangeListener { _, on -> onSatellitesChanged(on) }
        }
        col.addView(satellites)

        button = Button(this).apply { setOnClickListener { onButton() } }
        col.addView(button)

        latestEvent = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; text = "No events yet"
            setPadding(0, dp(8), 0, dp(4))
            setOnClickListener { toggleLog() }
        }
        col.addView(latestEvent)
        logButton = Button(this).apply { text = "Show event log"; setOnClickListener { toggleLog() } }
        col.addView(logButton)
        eventList = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true); visibility = View.GONE
        }
        col.addView(eventList)

        col.addView(caption("Rate nudge: how a gap closes when flowtime moves"))
        nudgeMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        idNudgeAuto = radio(nudgeMode, "Auto")
        idManual = radio(nudgeMode, "Manual")
        nudgeMode.check(if (prefs.getBoolean("nudgeManual", false)) idManual else idNudgeAuto)
        nudgeMode.setOnCheckedChangeListener { _, _ ->
            prefs.edit().putBoolean("nudgeManual", chosenMode() == NudgeMode.MANUAL).apply()
            session?.nudge?.setMode(chosenMode(), Session.nowBoot())
        }
        col.addView(nudgeMode)
        col.addView(caption("Period, 1 s to 5 min"))
        nudgeSlider = SeekBar(this).apply {
            max = 1000
            progress = periodToProgress(chosenPeriod() ?: NudgeControl.RECOMMENDED_S)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) setPeriod(progressToPeriod(value))
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        col.addView(nudgeSlider)
        nudgeInfo = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f }
        col.addView(nudgeInfo)
        val nudgeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val recommended = Button(this).apply {
            text = "Recommended"
            setOnClickListener {
                setPeriod(null)
                nudgeSlider.progress = periodToProgress(NudgeControl.RECOMMENDED_S)
            }
        }
        goButton = Button(this).apply {
            text = "Go"
            setOnClickListener {
                val n = session?.nudge ?: return@setOnClickListener
                val now = Session.nowBoot()
                if (n.state.closing) n.pause(now) else n.go(now)
            }
        }
        jumpButton = Button(this).apply {
            text = "Jump"
            setOnClickListener { session?.nudge?.jump(Session.nowBoot()) }
        }
        for (b in listOf(recommended, goButton, jumpButton)) nudgeRow.addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(nudgeRow)

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(status)

        val scroll = ScrollView(this).apply { addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
    }

    private fun caption(t: String) = TextView(this).apply { text = t; alpha = 0.7f; setPadding(0, dp(10), 0, 0) }

    private fun radio(g: RadioGroup, t: String): Int {
        val b = RadioButton(this).apply { text = t; id = View.generateViewId() }
        g.addView(b)
        return b.id
    }

    private fun number(v: String) = EditText(this).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        setText(v)
    }

    private fun labelled(t: String, field: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(caption(t))
        addView(field)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Kept outside the activity, so a test run survives the screen being rebuilt. */
        var session: Session? = null

        /** The latest run's events, kept so they can still be read after Stop. */
        var eventLog: EventLog? = null
        private const val SHOWN_EVENTS = 200
        private const val REQUEST_FOR_START = 1
        private const val REQUEST_FOR_SWITCH = 2
    }
}
