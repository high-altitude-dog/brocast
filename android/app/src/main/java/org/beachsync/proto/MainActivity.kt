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
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One screen: settings, Start/Stop, flowtime as UK time, and the live status panel. */
class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var mode: RadioGroup
    private lateinit var source: RadioGroup
    private lateinit var channel: RadioGroup
    private lateinit var pitch: EditText
    private lateinit var lag: EditText
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
        if (s != null) FlowTime.reselect(boot)
        val fit = FlowTime.fit()
        clock.text = if (s == null || fit == null) "--:--:--.---" else uk.format(Instant.ofEpochSecond(0, fit.utcAt(boot)))
        status.text = s?.describe(boot) ?: "Stopped. Set up both phones the same way, then press Start on each."
        if (s != null && ++ticks % 4 == 0) s.logRow(boot)
        refreshEvents()
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        } else begin(true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        begin(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    private fun begin(gpsAllowed: Boolean) {
        val settings = Settings(
            url = url.text.toString().trim(),
            stream = mode.checkedRadioButtonId == idStream,
            policy = when (source.checkedRadioButtonId) { idGps -> Policy.GPS_ONLY; idNtp -> Policy.NTP_ONLY; else -> Policy.AUTO },
            pitchHz = pitch.text.toString().toDoubleOrNull() ?: 2000.0,
            lagMs = lag.text.toString().toDoubleOrNull() ?: 0.0,
            channel = when (channel.checkedRadioButtonId) { idLeft -> Renderer.LEFT; idRight -> Renderer.RIGHT; else -> Renderer.BOTH },
        )
        session = try {
            Session(this, settings, gpsAllowed).also { it.start(); eventLog = it.events }
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
    }
}
