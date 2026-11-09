package org.beachsync.proto

import android.content.Context
import org.json.JSONObject

/**
 * Per-satellite facts from the IGS satellite metadata (hand-off 44, design/data/gnss_table.json, shipped as an asset): craft, name, launch date and
 * mass, keyed by signal number (G12, E30, J01...). Credit: International GNSS Service (IGS) satellite metadata, DLR.
 */
object GnssTable {
    class Entry(val block: String, val name: String, val launched: String, val massKg: Double?)

    @Volatile private var table: Map<String, Entry>? = null

    fun get(ctx: Context): Map<String, Entry> = table ?: runCatching {
        val j = JSONObject(ctx.assets.open("gnss_table.json").bufferedReader().use { it.readText() })
        j.keys().asSequence().associateWith { k ->
            val e = j.getJSONObject(k)
            Entry(e.optString("block"), e.optString("name"), e.optString("launched"), if (e.isNull("mass_kg")) null else e.optDouble("mass_kg"))
        }
    }.getOrDefault(emptyMap()).also { table = it }

    /** How many satellites of a system (by label letter) have a signal number today. */
    fun fleet(ctx: Context, letter: Char): Int = get(ctx).keys.count { it.firstOrNull() == letter }
}
