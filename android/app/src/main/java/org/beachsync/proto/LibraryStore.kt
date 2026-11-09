package org.beachsync.proto

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Holds the library on the phone and downloads each file once, at low priority and never on the audio thread. On mobile data
 * it fetches only the two files due next. The list is kept too, so the music carries on with no connection at all.
 */
class LibraryStore private constructor(ctx: Context) : Thread("library") {
    private val app = ctx.applicationContext
    private val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "library").apply { mkdirs() }
    private val listFile = File(dir, "library.json")

    @Volatile var editions: List<Edition> = emptyList()
    /** Starts of queued editions the station called off before they began (hand-off 41), so a phone that saw one coming can say so. */
    @Volatile var calledOff: Set<Long> = emptySet()
    @Volatile var dMs = 60000L
    /** The station's news bulletin (hand-off 49) from its announcement: null when it hasn't said, name "None" when it chose none. */
    @Volatile var news: StationNews? = null
    /** The station colour (hand-off 43) from its announcement, or null for Lime. */
    @Volatile var colour: StationColourSpec? = null
    @Volatile var url = ""
    @Volatile var status = "Library not downloaded yet"
    @Volatile var haveSets = 0
    @Volatile var totalSets = 0
    /** How much of the file being downloaded has arrived, 0 to 100; -1 when nothing is downloading. */
    @Volatile var partPct = -1
    @Volatile private var going = true

    init { if (listFile.exists()) try { read(listFile.readText()); summarise(null) } catch (_: Exception) { } }   // what is already on the phone shows at once, with no network

    fun file(id: String) = File(dir, "$id.opus")

    /** Where the sets live. The Pears engine builds into it too, through `.building/`, so a set appears whole or not at all. */
    val folder: File get() = dir

    /** A station's key when the library comes over the Pears mesh ([PearsEngine]): then nothing is fetched from the server. Blank means the server. */
    @Volatile var meshKey = ""

    /** The station's library list from its catalog: the same format as the server's library.json. */
    fun fromMesh(text: String) {
        read(text)
        val temp = File(dir, "library.json.tmp")
        temp.writeText(text)
        temp.renameTo(listFile)
        summarise(null)
    }

    /** A set finished building over the mesh. */
    fun meshBuilt() = summarise(null)

    /** The engine's status: how much of the set being built has arrived, for the DOWNLOADING badge. */
    fun meshProgress(status: JSONObject) {
        val sets = status.optJSONObject("library")?.optJSONArray("sets") ?: return
        val building = (0 until sets.length()).map { sets.getJSONObject(it) }.firstOrNull { it.optString("state") == "building" }
        partPct = building?.optInt("pct", 0) ?: -1
        if (building == null) summarise(null)
        else {
            val ed = editionAt(editions, System.currentTimeMillis() * 1_000_000L)
            val have = ed?.items?.count { file(it.id).exists() } ?: 0
            status.optString("phase").let { phase -> this.status = if (phase == "fetching") "Downloading ${have + 1} of ${ed?.items?.size ?: "?"} over the mesh · $partPct%" else "Mesh: $phase" }
        }
    }

    /** The artist photo for a track, copied here by hand for now (the photos stay out of the repo). It may not exist. */
    /** Artists from the server's registry: name and photo version, and which artists each set is by. */
    @Volatile var artists: Map<String, Pair<String, Long>> = emptyMap()
    @Volatile var setArtists: Map<String, List<String>> = emptyMap()
    private val artistDir = File(dir, "artists").apply { mkdirs() }
    private fun artistFile(aid: String, crop: String) = artists[aid]?.let { File(artistDir, "$aid-$crop-${it.second}.jpg") }

    /**
     * The set's photo: its first artist's crop from the registry (1x1 for thumbnails, 9x16 full screen, 16x9 wide; hand-off 28), or a photo copied
     * onto the phone by hand before the registry existed.
     */
    fun photo(id: String, crop: String = "1x1"): File =
        setArtists[id]?.firstNotNullOfOrNull { artistFile(it, crop)?.takeIf { f -> f.exists() } } ?: File(dir, "$id.jpg")

    /** The registry's names for the set's artists, joined, or null if it has none. */
    fun artistNames(id: String): String? = setArtists[id]?.mapNotNull { artists[it]?.first }?.takeIf { it.isNotEmpty() }?.joinToString(" & ")

    /** The set's beat and bar grid, saved by [SetBeatAnalyser]. */
    fun beatsFile(id: String) = File(dir, "$id.beats")
    fun featuresFile(id: String) = File(dir, "$id.feat")

    private val beatCache = java.util.concurrent.ConcurrentHashMap<String, SetBeats>()
    private val beatLooked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The set's grid, or null until it's worked out. Cheap enough for every frame: a missing file is looked for again at most every 5 s. */
    fun beats(id: String, fresh: Boolean = false): SetBeats? {
        beatCache[id]?.let { return it }
        val now = android.os.SystemClock.uptimeMillis()
        if (!fresh && now - (beatLooked[id] ?: Long.MIN_VALUE / 2) < 5000) return null
        beatLooked[id] = now
        val f = beatsFile(id)
        return if (f.exists()) SetBeats.decode(f.readText())?.also { beatCache[id] = it } else null
    }

    private fun read(text: String) {
        val j = JSONObject(text)
        dMs = j.optLong("D_ms", 60000L)
        val list = j.getJSONArray("editions")
        editions = (0 until list.length()).map { e ->
            val ed = list.getJSONObject(e)
            val items = ed.getJSONArray("items")
            Edition(ed.getLong("start_ns"), (0 until items.length()).map { i ->
                val it = items.getJSONObject(i)
                LibraryItem(it.getString("id"), it.optString("title"), it.optString("artist"), it.getLong("seconds"), it.getLong("bytes"), it.getString("sha256"), it.optString("color"))
            }, ed.optInt("n"), ed.optString("message"), ed.optLong("published_ns"), ed.optLong("from_s"))
        }
        colour = j.optJSONObject("color")?.let { StationColourSpec(it.optString("hex"), it.optString("prev"), it.optLong("at_ns"), it.optBoolean("tell")) }
        news = j.optJSONObject("news")?.let { StationNews(it.optString("choice"), it.optString("name"), it.optString("url")) }
        calledOff = j.optJSONArray("called_off")?.let { c -> (0 until c.length()).map { c.getJSONObject(it).optLong("start_ns") }.toSet() }.orEmpty()
        artists = j.optJSONObject("artists")?.let { a -> a.keys().asSequence().associateWith { k -> a.getJSONObject(k).let { it.optString("name") to it.optLong("v") } } }.orEmpty()
        setArtists = j.optJSONObject("set_artists")?.let { s -> s.keys().asSequence().associateWith { k -> s.getJSONArray(k).let { l -> (0 until l.length()).map { l.getString(it) } } } }.orEmpty()
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (going) {
            if (meshKey.isNotEmpty()) { try { sleep(RETRY_MS) } catch (_: InterruptedException) { }; continue }   // PearsEngine fetches instead
            val done = try { refresh(); sync() } catch (e: Exception) { Log.w("BeachSync", "Library: $e"); summarise(e.message); false }
            try { sleep(if (done) REFRESH_MS else RETRY_MS) } catch (_: InterruptedException) { }
        }
    }

    private fun refresh() {
        val c = open("/library.json", 0L)
        try {
            if (c.responseCode != 200) return    // an older server, or none: keep what's here
            val text = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            read(text)
            val temp = File(dir, "library.json.tmp")
            temp.writeText(text)
            temp.renameTo(listFile)
        } finally { c.disconnect() }
    }

    /** True once every file due is here. */
    private fun sync(): Boolean {
        val now = System.currentTimeMillis() * 1_000_000L
        val edition = editionAt(editions, now - dMs * 1_000_000L) ?: run { status = "The library is empty"; return true }
        val keep = editions.flatMap { it.items }.map { it.id }.toSet()
        dir.listFiles()?.forEach { f -> if (f.name.endsWith(".opus") && f.name.removeSuffix(".opus") !in keep) f.delete() }
        val n = Math.floorDiv(now - dMs * 1_000_000L - edition.startNs, 1_000_000_000L)
        // Then anything in an edition still to come, so the files are here before it takes over.
        val order = (edition.dueOrder(n) + editions.flatMap { it.items }).distinctBy { it.id }
        val allowed = if (metered()) order.take(MOBILE_FILES) else order
        for (item in allowed) {
            if (!going) return false
            if (!file(item.id).exists()) get(item, edition)
        }
        if (allowed.size == order.size) syncArtists()
        summarise(if (allowed.size < order.size) "waiting for Wi-Fi" else null, edition)
        return allowed.size == order.size
    }

    /** Each artist's three crops, once per photo version (small JPEGs, after the music), and old versions removed. */
    private fun syncArtists() {
        val wanted = HashSet<String>()
        for (aid in artists.keys) for (crop in listOf("1x1", "9x16", "16x9")) {
            val f = artistFile(aid, crop) ?: continue
            wanted.add(f.name)
            if (f.exists() || !going) continue
            try {
                val c = open("/artist/$aid/$crop.jpg", 0L)
                try {
                    if (c.responseCode != 200) continue
                    val tmp = File(artistDir, f.name + ".tmp")
                    c.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
                    tmp.renameTo(f)
                } finally { c.disconnect() }
            } catch (e: IOException) { return }
        }
        artistDir.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
    }

    private fun get(item: LibraryItem, edition: Edition) {
        if (dir.usableSpace < item.bytes + SPACE_MARGIN) throw IOException("not enough space on this phone")
        val part = File(dir, "${item.id}.part")
        val c = open("/file/${item.id}.opus", part.length())
        try {
            val resumed = c.responseCode == 206
            if (c.responseCode != 200 && !resumed) throw IOException("HTTP ${c.responseCode} for ${item.title}")
            FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(64 * 1024)
                var last = 0L
                c.inputStream.use { input ->
                    while (going) {
                        val got = input.read(buf)
                        if (got < 0) break
                        out.write(buf, 0, got)
                        val t = SystemClock.elapsedRealtime()
                        if (t - last > 500) {
                            last = t
                            val have = edition.items.count { file(it.id).exists() }
                            partPct = (part.length() * 100 / maxOf(1, item.bytes)).toInt().coerceAtMost(100)
                            status = "Downloading ${have + 1} of ${edition.items.size} · $partPct%"
                        }
                    }
                }
            }
        } finally { c.disconnect(); partPct = -1 }
        if (!going) return
        if (sha256(part) != item.sha256) {
            part.delete()
            throw IOException("${item.title} didn't download correctly")
        }
        part.renameTo(file(item.id))
    }

    private fun summarise(note: String?, edition: Edition? = editionAt(editions, System.currentTimeMillis() * 1_000_000L)) {
        val total = edition?.items?.size ?: 0
        val have = edition?.items?.count { file(it.id).exists() } ?: 0
        haveSets = have; totalSets = total
        status = when {
            total == 0 -> note ?: "Library not downloaded yet"
            have == total -> "All $total on this phone"
            else -> "$have of $total on this phone" + (note?.let { " · $it" } ?: "")
        }
    }

    private fun open(path: String, from: Long): HttpURLConnection {
        val c = URL(url.trimEnd('/') + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 15000
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    private fun metered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val got = input.read(buf)
                if (got < 0) break
                d.update(buf, 0, got)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val REFRESH_MS = 300_000L
        private const val RETRY_MS = 30_000L
        private const val MOBILE_FILES = 2
        private const val SPACE_MARGIN = 500L * 1024 * 1024

        @Volatile private var only: LibraryStore? = null

        @Synchronized
        fun get(ctx: Context): LibraryStore = only ?: LibraryStore(ctx).also { only = it }

        /** Starts or redirects the downloads. Safe to call more than once. */
        @Synchronized
        fun begin(ctx: Context, url: String, meshKey: String = ""): LibraryStore {
            val s = get(ctx)
            s.url = url
            s.meshKey = meshKey.trim().lowercase()
            if (!s.isAlive) s.start() else s.interrupt()
            SetBeatAnalyser.begin(s)
            return s
        }
    }
}

/**
 * Feeds StreamClient from the phone's own files, through the same ring as a server would: the audio thread can't tell the difference.
 * A set still arriving over the Pears mesh plays from the engine's blocks (MeshSupply), the same bytes the built file will hold.
 */
class LocalSupply(private val store: LibraryStore) : BlockSupply {
    private val open = HashMap<String, OpusFile>()

    private fun now() = FlowTime.fit()?.utcAt(SystemClock.elapsedRealtimeNanos()) ?: (System.currentTimeMillis() * 1_000_000L)

    // One stream from a fixed start, whatever the edition: a new running order takes over between two segments, not with a restart.
    override fun manifest(): ByteArray {
        if (store.editions.isEmpty()) throw IOException("Library not downloaded yet")
        val now = now()
        val newest = Math.floorDiv(now - STREAM_EPOCH_NS, 1_000_000_000L) - 1
        val heard = Math.floorDiv(now - store.dMs * 1_000_000L - STREAM_EPOCH_NS, 1_000_000_000L)
        val item = segmentAt(store.editions, heard)?.first
        return JSONObject().put("sample_rate", Renderer.RATE).put("channels", 2).put("start_utc_ns", STREAM_EPOCH_NS).put("newest", newest)
            .put("oldest", maxOf(0L, newest - RETAIN)).put("D_ms", store.dMs).put("server_utc_ns", now)
            .put("source", listOfNotNull(item?.title, item?.artist?.ifEmpty { null }).joinToString(" · "))
            .put("block_format", OpusBlock.FORMAT).toString().toByteArray()
    }

    override fun opus(n: Long): ByteArray {
        if (store.editions.isEmpty()) throw IOException("Library not downloaded yet")
        val (item, block) = segmentAt(store.editions, n) ?: return OpusFile.silentBlock()   // before the first edition
        val f = store.file(item.id)
        if (!f.exists()) return PearsEngine.block(item.id, block) ?: throw IOException("Downloading ${item.title}")
        if (open.size > 2 && item.id !in open) { open.values.forEach { it.close() }; open.clear() }
        return open.getOrPut(item.id) { OpusFile(f) }.block(block)
    }

    override fun startsFile(n: Long) = segmentAt(store.editions, n)?.second == 0L

    private companion object { const val RETAIN = 120L }
}

/** A station's news choice: "bbc", "npr" or "none", its name, and the feed URL ('' if the station has none set up). */
data class StationNews(val choice: String, val name: String, val url: String)

/** The station colour: hex from the 21, the one before, and the flowtime at the ear when it takes over (hand-off 43). */
data class StationColourSpec(val hex: String, val prev: String, val atNs: Long, val tell: Boolean)
