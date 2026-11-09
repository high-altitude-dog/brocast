package org.beachsync.proto

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * "You kept N people listening" (hand-off 53): a daily ledger of the music this phone passed on, per listener. Listening time is the bytes
 * passed on ÷ the library's bitrate; a person counts once they've had 60 s from this phone that day. Kept on this phone only, never named or
 * sent, and only the last 8 days. Updated from each engine status (once a second), off the audio thread.
 */
object KeptLedger {
    class Day(val date: LocalDate, val people: Int, val seconds: Long)

    private const val KEEP_DAYS = 8L
    private const val MIN_S = 60
    private val lastTx = HashMap<String, Long>()
    /** Bytes passed on per listener key, per day ("2026-11-09" → key → bytes). */
    private var days: HashMap<String, HashMap<String, Long>>? = null
    private var dirty = 0L
    @Volatile var bytesPerSecond = 16_000.0   // 128 kb/s until the library says
        private set

    @Synchronized
    fun update(ctx: Context, v: MeshView, lib: LibraryStore?) {
        val all = load(ctx)
        lib?.editions?.lastOrNull()?.items?.takeIf { it.isNotEmpty() }?.let { items ->
            val s = items.sumOf { it.seconds }; if (s > 0) bytesPerSecond = items.sumOf { it.bytes }.toDouble() / s
        }
        val today = all.getOrPut(LocalDate.now().toString()) { HashMap() }
        for (p in v.listenerPeers) {
            val was = lastTx[p.key]
            lastTx[p.key] = p.txBytes
            if (was == null || p.txBytes <= was) continue
            today[p.key] = (today[p.key] ?: 0L) + (p.txBytes - was)
            dirty++
        }
        if (dirty >= 30) save(ctx)   // about every 30 s of sharing
    }

    /** Today's and the past week's totals, oldest first, today last. */
    @Synchronized
    fun week(ctx: Context): List<Day> {
        val all = load(ctx)
        return (6 downTo 0).map { back ->
            val d = LocalDate.now().minusDays(back.toLong())
            val per = all[d.toString()].orEmpty().values.map { (it / bytesPerSecond).toLong() }
            Day(d, per.count { it >= MIN_S }, per.filter { it >= MIN_S }.sum())
        }
    }

    private fun load(ctx: Context): HashMap<String, HashMap<String, Long>> {
        days?.let { return it }
        val out = HashMap<String, HashMap<String, Long>>()
        runCatching {
            val j = JSONObject(ctx.getSharedPreferences("ledger", Context.MODE_PRIVATE).getString("days", "{}") ?: "{}")
            for (d in j.keys()) { val m = j.getJSONObject(d); out[d] = HashMap(m.keys().asSequence().associateWith { m.getLong(it) }) }
        }
        days = out
        return out
    }

    private fun save(ctx: Context) {
        val all = days ?: return
        val oldest = LocalDate.now().minusDays(KEEP_DAYS).toString()
        all.keys.filter { it < oldest }.forEach { all.remove(it) }
        val j = JSONObject()
        for ((d, m) in all) j.put(d, JSONObject(m as Map<*, *>))
        ctx.getSharedPreferences("ledger", Context.MODE_PRIVATE).edit().putString("days", j.toString()).apply()
        dirty = 0
    }
}

/** "2 h 10 m", "45 min". */
fun keptTime(seconds: Long): String = if (seconds >= 3600) "${seconds / 3600} h ${seconds % 3600 / 60} m" else "${maxOf(1, seconds / 60)} min"
