package org.beachsync.pearstest

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import to.holepunch.bare.kit.Ipc
import to.holepunch.bare.kit.Worklet

/**
 * Step 1 test app: hosts the engine bundle in a bare-kit worklet and shows its status. Given the station key and the
 * laptop's DHT bootstrap address (typed in, or from adb:
 * `am start -n org.beachsync.pearstest/.MainActivity --es station <hex> --es bootstrap <ip>:<port> --ez go true`),
 * it fetches and builds every set, then keeps sharing until Stop (`--ez stop true`) or 10 minutes.
 * Each run starts from an empty store with a new random seed, so every run is a first fetch.
 * Results in files/: run.jsonl (engine events), status.json (last snapshot), host.json (CPU and battery).
 */
class MainActivity : Activity() {
    // One host thread for everything that talks to the engine (docs/engine-api.md §3, mesh-rpc).
    private val rpcThread = Executors.newSingleThreadExecutor { Thread(it, "mesh-rpc") }
    private val scope = CoroutineScope(SupervisorJob() + rpcThread.asCoroutineDispatcher())

    private lateinit var station: EditText
    private lateinit var bootstrap: EditText
    private lateinit var statusView: TextView
    private var worklet: Worklet? = null
    private var ipc: Ipc? = null
    private var rpc: Rpc? = null
    private var run: HostRun? = null
    @Volatile private var lines = "idle"

    private class HostRun(val t0: Long, val cpu0: Long, val charge0: Long, val pct0: Int, val plugged: Int) {
        var doneAt: Long? = null; var cpuDone: Long? = null; var chargeDone: Long? = null
        val currentUa = ArrayList<Long>()
        var lastStatus: JSONObject? = null
        var fatal: String? = null
        val built = ArrayList<String>()
        var warnings = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("step1", MODE_PRIVATE)
        station = EditText(this).apply { hint = "station key (hex)"; setText(prefs.getString("station", "")); textSize = 11f }
        bootstrap = EditText(this).apply { hint = "bootstrap ip:port"; setText(prefs.getString("bootstrap", "")); textSize = 11f }
        statusView = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 11f; text = lines }
        val buttons = LinearLayout(this).apply {
            addView(Button(context).apply { text = "Start"; setOnClickListener { startRun() } })
            addView(Button(context).apply { text = "Stop"; setOnClickListener { stopRun("button") } })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(context).apply { text = "Mesh step 1 test"; textSize = 18f })
            addView(station); addView(bootstrap); addView(buttons); addView(statusView)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        i.getStringExtra("station")?.let { station.setText(it) }
        i.getStringExtra("bootstrap")?.let { bootstrap.setText(it) }
        if (i.getBooleanExtra("stop", false)) stopRun("adb")
        else if (i.getBooleanExtra("go", false)) startRun()
    }

    private fun show(s: String) { lines = s; runOnUiThread { statusView.text = lines } }

    private fun startRun() {
        if (run != null) return
        val key = station.text.toString().trim()
        val boot = bootstrap.text.toString().trim()
        getSharedPreferences("step1", MODE_PRIVATE).edit().putString("station", key).putString("bootstrap", boot).apply()
        show("starting…")
        scope.launch {
            try { begin(key, boot) } catch (e: Throwable) { show("start failed: $e") }
        }
    }

    private fun begin(key: String, boot: String) {
        val store = File(filesDir, "store-v1"); val music = File(filesDir, "music")
        store.deleteRecursively(); music.deleteRecursively(); store.mkdirs(); music.mkdirs()
        val bm = getSystemService(BatteryManager::class.java)
        val plugged = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val r = HostRun(SystemClock.elapsedRealtime(), cpuMs(), bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), plugged)
        run = r
        val w = Worklet(this)
        w.start("engine.bundle")
        val io = Ipc(w)
        worklet = w; ipc = io
        val rp = Rpc({ bytes -> scope.launch { io.write(bytes) } }, { ev, body -> onEvent(ev, body) })
        rpc = rp
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { io.data.collect { rp.feed(it) } }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val (host, port) = boot.split(":")
        call(CMD_HELLO, JSONObject().put("api", JSONArray().put(1)).put("host", JSONObject().put("app", "pearstest/0.1"))) { hello ->
            call(CMD_START, JSONObject()
                .put("seed", seed).put("stationKey", key).put("profile", "generous")
                .put("dirs", JSONObject().put("store", store.path).put("music", music.path).put("log", File(filesDir, "run.jsonl").path))
                .put("bootstrap", JSONArray().put(JSONObject().put("host", host).put("port", port.toInt())))
                .put("phone", JSONObject().put("metered", false).put("charging", plugged > 0))) { started ->
                show("engine ${hello.optJSONObject("engine")}\nme ${started.optString("publicKey").take(16)}…")
                call(CMD_SUBSCRIBE, JSONObject().put("hz", 1).put("arrivals", true)) {}
            }
        }
        // Battery current once a second, and the 10-minute auto-stop (as for every seed server, user 7 Nov).
        scope.launch {
            while (run === r) {
                r.currentUa += bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                if (SystemClock.elapsedRealtime() - r.t0 > 10 * 60_000) { stopRun("autostop 10 min"); break }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun call(cmd: Int, body: JSONObject, ok: (JSONObject) -> Unit) {
        rpc?.request(cmd, body.toString().toByteArray()) { res ->
            scope.launch {
                res.fold({ ok(if (it.isEmpty()) JSONObject() else JSONObject(String(it))) }, { show("command $cmd failed: $it") })
            }
        }
    }

    private fun onEvent(ev: Int, body: ByteArray) {
        val r = run ?: return
        val text = String(body)
        scope.launch {
            when (ev) {
                EV_STATUS -> {
                    val s = JSONObject(text)
                    r.lastStatus = s
                    val st = s.getJSONObject("step1")
                    if (r.doneAt == null && !st.isNull("allBlocksMs") && r.built.size == s.getJSONObject("library").getJSONArray("sets").length()) {
                        r.doneAt = SystemClock.elapsedRealtime(); r.cpuDone = cpuMs()
                        r.chargeDone = getSystemService(BatteryManager::class.java).getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    }
                    show(render(r, s))
                }
                EV_BUILT -> r.built += JSONObject(text).getString("setId")
                EV_WARNING -> r.warnings++
                EV_FATAL -> { r.fatal = text; show("FATAL $text") }
            }
        }
    }

    private fun render(r: HostRun, s: JSONObject): String {
        val st = s.getJSONObject("step1"); val sw = s.getJSONObject("swarm"); val led = s.getJSONObject("ledger")
        val wire = st.getJSONObject("wire")
        val sb = StringBuilder()
        sb.append("${st.getString("phase")}   up ${s.getJSONObject("at").getInt("uptimeSec")} s\n")
        sb.append("peers ${sw.getInt("connected")} connected, ${sw.getInt("foundNotConnected")} found\n")
        sb.append("blocks ${st.getInt("held")} / ${st.opt("total")}   in flight ${st.getInt("inflight")}\n")
        sb.append("audio ${mb(st.getLong("audioBytes"))} MB   wire rx ${mb(wire.getLong("rx"))} tx ${mb(wire.getLong("tx"))} MB\n")
        sb.append("overhead ${led.opt("overheadPct")} %   data msgs over blocks ${led.opt("duplicatePct")} %\n")
        sb.append("packets rx ${wire.getLong("packetsRx")}  my retransmits ${wire.getLong("retransmits")}\n")
        sb.append("first block ${st.opt("firstBlockMs")} ms   all ${st.opt("allBlocksMs")} ms\n")
        sb.append("built ${r.built.size}  warnings ${r.warnings}  cpu ${cpuMs() - r.cpu0} ms\n")
        val sets = s.getJSONObject("library").getJSONArray("sets")
        for (i in 0 until sets.length()) sets.getJSONObject(i).let { sb.append("  ${it.getString("id")} ${it.getString("state")} ${it.getInt("pct")}%\n") }
        val peers = sw.getJSONArray("peers")
        for (i in 0 until peers.length()) peers.getJSONObject(i).let {
            sb.append("  ${it.getString("key").take(8)} ${it.getString("role")} in ${mb(it.getLong("rxBytes"))} out ${mb(it.getLong("txBytes"))} MB\n")
        }
        r.fatal?.let { sb.append("FATAL $it\n") }
        return sb.toString()
    }

    private fun stopRun(why: String) {
        val r = run ?: return
        scope.launch {
            val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
            rpc?.request(CMD_STOP, "{}".toByteArray()) { stopped.complete(Unit) }
            kotlinx.coroutines.withTimeoutOrNull(3000) { stopped.await() }
            run = null
            val bm = getSystemService(BatteryManager::class.java)
            val now = SystemClock.elapsedRealtime()
            val host = JSONObject()
                .put("why", why).put("wallMs", now - r.t0).put("cpuMsTotal", cpuMs() - r.cpu0)
                .put("doneMs", r.doneAt?.let { it - r.t0 }).put("cpuMsToDone", r.cpuDone?.let { it - r.cpu0 })
                .put("chargeUAhUsedToDone", r.chargeDone?.let { r.charge0 - it })
                .put("chargeUAhUsedTotal", r.charge0 - bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER))
                .put("batteryPctStart", r.pct0).put("batteryPctEnd", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                .put("plugged", r.plugged).put("meanCurrentUa", if (r.currentUa.isEmpty()) null else r.currentUa.average())
                .put("built", JSONArray(r.built)).put("warnings", r.warnings).put("fatal", r.fatal)
                .put("model", android.os.Build.MODEL)
            File(filesDir, "host.json").writeText(host.toString(2))
            r.lastStatus?.let { File(filesDir, "status.json").writeText(it.toString(2)) }
            runCatching { ipc?.close() }
            runCatching { worklet?.close() }
            ipc = null; worklet = null; rpc = null
            show("stopped ($why)\n$lines\n\n$host")
        }
    }

    override fun onDestroy() {
        stopRun("destroy")
        super.onDestroy()
    }

    private fun cpuMs(): Long {
        val f = File("/proc/self/stat").readText().substringAfterLast(") ").split(" ")
        val ticks = f[11].toLong() + f[12].toLong() // utime, stime (fields 14 and 15)
        return ticks * 1000 / Os.sysconf(OsConstants._SC_CLK_TCK)
    }

    private fun mb(b: Long) = "%.2f".format(b / 1048576.0)

    companion object {
        // Command and event numbers, as in pears/android-test/js/worklet.js
        const val CMD_HELLO = 1; const val CMD_START = 2; const val CMD_SUBSCRIBE = 3; const val CMD_STOP = 5
        const val EV_STATUS = 100; const val EV_BUILT = 102; const val EV_WARNING = 103; const val EV_FATAL = 104
    }
}
