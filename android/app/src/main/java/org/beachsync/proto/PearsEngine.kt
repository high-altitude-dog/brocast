package org.beachsync.proto

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import to.holepunch.bare.kit.Ipc
import to.holepunch.bare.kit.Worklet
import java.io.File
import java.security.SecureRandom

/**
 * The Pears engine (pears/host/worklet.js) in a bare-kit worklet, spoken to over bare-rpc as in docs/engine-api.md. Given a station's key, it
 * fetches the library over the mesh into [LibraryStore]'s folder, where [LocalSupply] plays it unchanged, and shares what it holds. It runs on
 * its own threads and never touches the audio thread.
 *
 * On a metered network it's receive-only (nothing announced, each block cleared once it's in the built file): sharing on mobile data needs the
 * listener's opt-in, which isn't built yet. A change between Wi-Fi and mobile restarts it.
 *
 * Not yet (pears-spike-results.md, "What the main app needs"): the health gate and version slots, the identity wizard's seed in the
 * Keystore (a random seed in the app's private files stands in), and MeshSupply for playing a set before it's whole.
 */
/** One live peer, from the engine's status. [path] is lan, direct, punched or relayed (null if not yet known). */
class MeshPeer(val key: String, val station: Boolean, val path: String?, val toYouKbps: Int, val fromYouKbps: Int)

/** What the screens show of the mesh, parsed once per status (once a second). Null while the engine isn't running. */
class MeshView(val nym: String, val listeners: Int, val peers: List<MeshPeer>, val foundNotConnected: Int, val upBytes: Long, val downBytes: Long, val sharing: Boolean) {
    val stations get() = peers.filter { it.station }
    val listenerPeers get() = peers.filter { !it.station }

    companion object {
        fun of(s: JSONObject, sharing: Boolean): MeshView {
            val sw = s.optJSONObject("swarm")
            val list = sw?.optJSONArray("peers")
            val peers = (0 until (list?.length() ?: 0)).map { i ->
                val p = list!!.getJSONObject(i)
                MeshPeer(p.optString("key"), p.optString("role") == "station-seed", p.optString("path").ifEmpty { null }?.takeIf { it != "null" }, p.optInt("toYouKbps"), p.optInt("fromYouKbps"))
            }
            val today = s.optJSONObject("ledger")?.optJSONObject("today")
            return MeshView(
                s.optJSONObject("me")?.optString("nym").orEmpty(), s.optJSONObject("listeners")?.optInt("estimate") ?: 0, peers,
                sw?.optInt("foundNotConnected") ?: 0, today?.optLong("upBytes") ?: 0L, today?.optLong("downBytes") ?: 0L, sharing,
            )
        }
    }
}

object PearsEngine {
    /** The live mesh for the screens: Compose state, so they redraw when it changes. Null while the engine isn't running. */
    var view by androidx.compose.runtime.mutableStateOf<MeshView?>(null)
        private set
    /** Privacy mode: this phone isn't counted among listeners. Read at each start; a change restarts the engine. */
    @Volatile private var counted = true

    /** The engine's latest status snapshot (API §6), or null before the first. */
    @Volatile var status: JSONObject? = null
        private set
    /** What went wrong, if the engine stopped by itself. */
    @Volatile var error: String? = null
        private set
    @Volatile var sharing = false
        private set

    private var key = ""
    private var app: Context? = null
    private var library: LibraryStore? = null
    private var worklet: Worklet? = null
    private var ipc: Ipc? = null
    private var rpc: Rpc? = null
    private var watching: ConnectivityManager.NetworkCallback? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val running get() = worklet != null

    /** Starts, restarts with a new key, or (with a blank key) stops. Safe to call more than once. */
    @Synchronized
    fun use(ctx: Context, stationKey: String, lib: LibraryStore, privacy: Boolean = false) {
        val k = stationKey.trim().lowercase()
        if (k == key && counted == !privacy && (worklet != null || k.isEmpty())) return
        counted = !privacy
        stop()
        key = k
        app = ctx.applicationContext
        library = lib
        lib.meshKey = k
        if (k.isEmpty()) return
        if (!Regex("^[0-9a-f]{64}$").matches(k)) { error = "A station key is 64 hex digits"; return }
        begin()
        watchNetwork()
    }

    @Synchronized
    private fun begin() {
        val ctx = app ?: return
        val lib = library ?: return
        error = null
        sharing = !metered(ctx)
        val w = Worklet(ctx)
        w.start("engine.bundle")   // from the app's assets
        val io = Ipc(w)
        val rp = Rpc({ bytes -> scope.launch { io.write(bytes) } }, ::onEvent)
        worklet = w; ipc = io; rpc = rp
        scope.launch { io.data.collect { rp.feed(it) } }
        val store = File(ctx.filesDir, "mesh-store").apply { mkdirs() }
        call(CMD_HELLO, JSONObject().put("api", JSONArray().put(1)).put("host", JSONObject().put("app", "beachsync-proto"))) {
            call(CMD_START, JSONObject()
                .put("seed", seed(ctx)).put("stationKey", key).put("profile", "balanced").put("share", sharing).put("count", counted)
                .put("dirs", JSONObject().put("store", store.path).put("music", lib.folder.path))) {
                call(CMD_SUBSCRIBE, JSONObject().put("hz", 1)) {}
            }
        }
    }

    @Synchronized
    fun stop() {
        watching?.let { cb -> app?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        watching = null
        halt()
        key = ""
        library?.meshKey = ""
    }

    /** The worklet only: the engine gets the API's 2 s to close its connections, then the worklet ends. */
    @Synchronized
    private fun halt() {
        val w = worklet ?: return
        val r = rpc
        worklet = null; ipc = null; rpc = null; status = null; view = null
        r?.request(CMD_STOP, ByteArray(0)) { }
        scope.launch { kotlinx.coroutines.delay(2000); w.terminate() }
    }

    private fun onEvent(ev: Int, body: ByteArray) {
        val text = String(body)
        when (ev) {
            EV_STATUS -> JSONObject(text).let { s -> status = s; view = MeshView.of(s, sharing); library?.meshProgress(s) }
            EV_CATALOG -> JSONObject(text).optJSONObject("library")?.let { library?.fromMesh(it.toString()) }
            EV_BUILT -> library?.meshBuilt()
            EV_WARNING -> Log.w("BeachSync", "Mesh: $text")
            EV_FATAL -> { error = JSONObject(text).optString("text").lineSequence().firstOrNull() ?: "engine stopped"; Log.e("BeachSync", "Mesh: $text") }
        }
    }

    /**
     * One 1 s block of a set that isn't built yet, from the mesh (or null): MeshSupply's part, for [LocalSupply]. Called on the stream
     * thread, never the audio thread; it waits up to [timeoutMs] for the engine, and StreamClient tries again next second if it fails.
     */
    fun block(setId: String, second: Long, timeoutMs: Long = 10_000L): ByteArray? {
        val r = rpc ?: return null
        val done = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        r.request(CMD_BLOCK, JSONObject().put("setId", setId).put("second", second).toString().toByteArray()) { res -> out = res.getOrNull(); done.countDown() }
        return if (done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) out?.takeIf { it.isNotEmpty() } else null
    }

    private fun call(cmd: Int, body: JSONObject, ok: (JSONObject) -> Unit) {
        rpc?.request(cmd, body.toString().toByteArray()) { res ->
            res.fold({ ok(if (it.isEmpty()) JSONObject() else JSONObject(String(it))) }, { error = "command $cmd: ${it.message}" })
        }
    }

    /** Wi-Fi to mobile or back: the engine restarts, so it never shares on a metered network. */
    private fun watchNetwork() {
        val ctx = app ?: return
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val share = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                synchronized(this@PearsEngine) { if (worklet != null && share != sharing) { halt(); begin() } }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        watching = cb
    }

    private fun metered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** The phone's mesh identity: 32 random bytes kept in private files, until the identity wizard puts the seed in the Keystore. */
    private fun seed(ctx: Context): String {
        val f = File(ctx.filesDir, "mesh-identity.seed")
        if (!f.exists()) f.writeBytes(ByteArray(32).also { SecureRandom().nextBytes(it) })
        return f.readBytes().joinToString("") { "%02x".format(it) }
    }

    // Command and event numbers on the wire, as in pears/host/worklet.js.
    private const val CMD_HELLO = 1
    private const val CMD_START = 2
    private const val CMD_SUBSCRIBE = 3
    private const val CMD_STOP = 5
    private const val CMD_BLOCK = 6
    private const val EV_STATUS = 100
    private const val EV_BUILT = 102
    private const val EV_WARNING = 103
    private const val EV_FATAL = 104
    private const val EV_CATALOG = 105
}
