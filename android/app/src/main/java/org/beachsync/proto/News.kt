package org.beachsync.proto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * News on the headphone button (hand-off 47). A press of play/pause while the music plays fetches the newest hourly
 * bulletin, dips the music and plays the bulletin over it, then brings the music back up. Only the music's volume
 * changes ([Session.setGain] → AudioTrack's own volume), so the set keeps its synced place. Everything here runs on its
 * own threads; nothing touches the audio thread or the timing path.
 */
object News {
    enum class Phase { IDLE, GETTING, PLAYING, ENDING }
    enum class Source { STATION, NPR, OWN }

    const val NPR_URL = "https://feeds.npr.org/500005/podcast.xml"
    val DOWN_S = floatArrayOf(0.5f, 1.5f, 3f)
    val UP_S = floatArrayOf(0.5f, 2f, 4f)
    val SPEEDS = arrayOf("Quick", "Gentle", "Slow")
    private const val TIMEOUT_MS = 10_000
    private const val TOO_OLD_MS = 150 * 60_000L   // 2 h 30 m: older is treated as not answering
    private const val PREFETCH_EVERY_MS = 10 * 60_000L
    private const val TAG = "News"

    // The listener's settings, kept on the phone.
    @Volatile var on = true
    @Volatile var source = Source.STATION
    @Volatile var ownUrl = ""
    @Volatile var underPct = 25
    @Volatile var fadeDown = 1
    @Volatile var fadeUp = 1
    @Volatile var prefetch = true

    // What the Now playing card shows.
    @Volatile var phase = Phase.IDLE
    @Volatile var sourceName = ""
    @Volatile var gotBytes = 0L
    @Volatile var totalBytes = -1L
    @Volatile var positionMs = 0
    @Volatile var durationMs = 0
    @Volatile var publishedMs = 0L
    @Volatile var endingLeftMs = 0L
    @Volatile var preview = false
    /** A short message for a toast, and when it was set (uptime ms). */
    @Volatile var notice: String? = null
    @Volatile var noticeAt = 0L

    @Volatile private var gen = 0
    @Volatile private var stopAsked = false
    private var app: Context? = null
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        val p = ctx.getSharedPreferences("news", Context.MODE_PRIVATE)
        on = p.getBoolean("on", true)
        source = Source.entries.getOrElse(p.getInt("source", 0)) { Source.STATION }
        ownUrl = p.getString("ownUrl", "") ?: ""
        underPct = p.getInt("underPct", 25); fadeDown = p.getInt("fadeDown", 1); fadeUp = p.getInt("fadeUp", 1)
        prefetch = p.getBoolean("prefetch", true)
        loaded = true
        Thread(::prefetchLoop, "news-prefetch").apply { isDaemon = true }.start()
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("news", Context.MODE_PRIVATE).edit()
            .putBoolean("on", on).putInt("source", source.ordinal).putString("ownUrl", ownUrl)
            .putInt("underPct", underPct).putInt("fadeDown", fadeDown).putInt("fadeUp", fadeUp).putBoolean("prefetch", prefetch).apply()
    }

    /** The feed this phone would use: name and URL, or null (station chose None, or no feed set up). */
    fun feed(lib: LibraryStore?): Pair<String, String>? = when (source) {
        Source.NPR -> "NPR News Now" to NPR_URL
        Source.OWN -> ownUrl.trim().takeIf { it.startsWith("http") }?.let { "Your feed" to it }
        Source.STATION -> lib?.news?.takeIf { it.choice != "none" && it.url.isNotEmpty() }?.let { it.name to it.url }
    }

    /**
     * The headphone button while a run is live. Returns false when it should act as before (stop the music):
     * the setting is off, or there's no feed to use.
     */
    fun press(ctx: Context): Boolean {
        load(ctx)
        if (!on) return false
        when (phase) {
            Phase.GETTING, Phase.PLAYING -> { stop(); return true }
            Phase.ENDING -> return true
            Phase.IDLE -> {}
        }
        val f = feed(LibraryStore.get(ctx)) ?: return false
        tone(ToneGenerator.TONE_PROP_ACK)
        start(ctx, f, 0)
        return true
    }

    /** Settings' "Play 10 s preview": the same dip if the music is playing, else the bulletin alone. */
    fun playPreview(ctx: Context) {
        load(ctx)
        if (phase != Phase.IDLE) { stop(); return }
        val f = feed(LibraryStore.get(ctx)) ?: run { say("No news feed to try"); return }
        start(ctx, f, 10_000)
    }

    /** Stop on the card or a second press: a bulletin still downloading is dropped with the music untouched. */
    fun stop() {
        if (phase == Phase.GETTING) { gen++; phase = Phase.IDLE; return }
        stopAsked = true
    }

    private fun start(ctx: Context, f: Pair<String, String>, limitMs: Int) {
        val my = ++gen
        stopAsked = false; preview = limitMs > 0
        sourceName = f.first; gotBytes = 0; totalBytes = -1; positionMs = 0; durationMs = 0; publishedMs = 0
        if (!online(ctx)) { fail("No news without internet"); return }
        phase = Phase.GETTING
        Thread({ run(ctx.applicationContext, f.second, limitMs, my) }, "news").apply { isDaemon = true }.start()
    }

    private fun run(ctx: Context, feedUrl: String, limitMs: Int, my: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val file = try {
            val item = newest(feedUrl) ?: throw java.io.IOException("no bulletin in the feed")
            if (item.second > 0 && System.currentTimeMillis() - item.second > TOO_OLD_MS) throw java.io.IOException("newest is too old")
            publishedMs = item.second
            fetch(ctx, item.first) { my == gen }
        } catch (e: Exception) {
            Log.w(TAG, "fetch: $e")
            if (my == gen) fail("News isn't answering right now")
            return
        } ?: return   // cancelled
        if (my != gen) return
        val player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(file.path); prepare()
            }
        } catch (e: Exception) { Log.w(TAG, "player: $e"); fail("News isn't answering right now"); return }
        durationMs = if (limitMs > 0) minOf(limitMs, player.duration) else player.duration
        phase = Phase.PLAYING
        val low = underPct / 100f
        val withMusic = MainActivity.session != null
        if (withMusic) ramp(1f, low, DOWN_S[fadeDown], down = true)
        var done = false
        player.setOnCompletionListener { done = true }
        player.start()
        while (!done && !stopAsked && my == gen) {
            positionMs = try { player.currentPosition } catch (_: Exception) { break }
            if (limitMs > 0 && positionMs >= limitMs) break
            if (withMusic && MainActivity.session == null) break   // the music was stopped under the news
            SystemClock.sleep(100)
        }
        try { player.stop() } catch (_: Exception) { }
        player.release()
        if (withMusic && MainActivity.session != null) {
            phase = Phase.ENDING
            ramp(low, 1f, UP_S[fadeUp], down = false)
        }
        MainActivity.session?.setGain(1f)
        if (my == gen) phase = Phase.IDLE
    }

    /** Equal-power-shaped ramp of the music's level, about 50 steps a second. */
    private fun ramp(from: Float, to: Float, seconds: Float, down: Boolean) {
        val totalMs = (seconds * 1000).toLong()
        val t0 = SystemClock.uptimeMillis()
        while (true) {
            val el = SystemClock.uptimeMillis() - t0
            val x = (el.toDouble() / totalMs).coerceIn(0.0, 1.0)
            val g = if (down) to + (from - to) * cos(x * PI / 2) else from + (to - from) * sin(x * PI / 2)
            MainActivity.session?.setGain(g.toFloat()) ?: return
            if (!down) endingLeftMs = totalMs - el
            if (x >= 1.0) return
            SystemClock.sleep(20)
        }
    }

    private fun fail(text: String) {
        phase = Phase.IDLE
        tone(ToneGenerator.TONE_PROP_NACK)
        say(text)
    }

    private fun say(text: String) { notice = text; noticeAt = SystemClock.uptimeMillis() }

    private fun tone(kind: Int) {
        try {
            val t = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            t.startTone(kind, 200)
            Thread { SystemClock.sleep(600); t.release() }.start()
        } catch (e: RuntimeException) { Log.w(TAG, "tone: $e") }
    }

    private fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun unmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Brocast")
    }

    /** The newest item's audio URL and publish time (ms, 0 if unknown). Podcast feeds list newest first. */
    private fun newest(feedUrl: String): Pair<String, Long>? {
        val c = open(feedUrl)
        try {
            val p = Xml.newPullParser()
            p.setInput(c.inputStream, null)
            var inItem = false
            var audio: String? = null
            var date = 0L
            var tag = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        tag = p.name
                        if (tag == "item") inItem = true
                        if (inItem && tag == "enclosure" && audio == null) audio = p.getAttributeValue(null, "url")
                    }
                    XmlPullParser.TEXT -> if (inItem && tag == "pubDate" && date == 0L) date = parseDate(p.text.trim())
                    XmlPullParser.END_TAG -> { if (p.name == "item" && inItem) return audio?.let { it to date }; tag = "" }
                }
            }
            return null
        } finally { c.disconnect() }
    }

    private fun parseDate(s: String): Long = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm:ss Z").firstNotNullOfOrNull {
        try { SimpleDateFormat(it, Locale.US).parse(s)?.time } catch (_: Exception) { null }
    } ?: 0L

    /** The bulletin in the app's cache, downloaded once. Null when cancelled. */
    private fun fetch(ctx: Context, audioUrl: String, keep: () -> Boolean): File? {
        val dir = File(ctx.cacheDir, "news").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-256").digest(audioUrl.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
        val file = File(dir, "$name.mp3")
        if (file.exists()) { gotBytes = file.length(); totalBytes = file.length(); return file }
        val part = File.createTempFile(name, ".part", dir)   // its own name, so a prefetch and a press never share one
        val c = open(audioUrl)
        try {
            totalBytes = c.contentLengthLong
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!keep()) { part.delete(); return null }
                        out.write(buf, 0, n); gotBytes += n
                    }
                }
            }
        } finally { c.disconnect() }
        if (!part.renameTo(file) && !file.exists()) throw java.io.IOException("couldn't keep the bulletin")
        dir.listFiles()?.filter { it.name.endsWith(".mp3") }?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }   // keep the last few
        return file
    }

    /** Get the next one ready on Wi-Fi: while the music plays, checks the feed every 10 minutes and caches a new bulletin. */
    private fun prefetchLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (true) {
            val ctx = app
            if (ctx != null && on && prefetch && phase == Phase.IDLE && MainActivity.session != null && unmetered(ctx)) {
                try {
                    feed(LibraryStore.get(ctx))?.let { f -> newest(f.second)?.let { item -> fetch(ctx, item.first) { phase == Phase.IDLE } } }
                } catch (e: Exception) { Log.w(TAG, "prefetch: $e") }
                if (phase == Phase.IDLE) { gotBytes = 0; totalBytes = -1 }
            }
            SystemClock.sleep(PREFETCH_EVERY_MS)
        }
    }
}
