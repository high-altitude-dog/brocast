package org.beachsync.proto.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.delay
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.STREAM_EPOCH_NS
import org.beachsync.proto.Session
import org.beachsync.proto.editionAt
import org.beachsync.proto.playingAt

/**
 * The station colour (hand-offs 43 and 50). The accent is a pure function of flowtime and the announcement, so every
 * phone in a crowd shows the same colour at the same instant: a set with its own colour takes over on beat 1 of its
 * first bar, as the sound reaches the ears, fading over 2 s; a station-wide change fades from its time. UI thread only.
 */
object StationColour {
    val PALETTE = linkedMapOf(
        "Lime" to 0xFFC8FF4D, "Chartreuse" to 0xFFDEFF5C, "Lemon" to 0xFFF4F56A, "Pistachio" to 0xFFB8F2A0, "Spring" to 0xFF7DFF7A,
        "Mint" to 0xFF6BFFB8, "Seafoam" to 0xFF5CF2D0, "Ice" to 0xFFA6E4FF, "Sky" to 0xFF7CC8FF, "Bluebell" to 0xFF86A8FF,
        "Hyacinth" to 0xFFA2A6FF, "Lavender" to 0xFFBCA8FF, "Purple" to 0xFFA98BFF, "Violet" to 0xFFC483FF, "Orchid" to 0xFFDE8CFF,
        "Lilac" to 0xFFE6B3FF, "Magenta" to 0xFFFF7AE0, "Pink" to 0xFFFF8FC8, "Bubblegum" to 0xFFFFB0D9, "Blush" to 0xFFFFC4CF, "Iris" to 0xFF9E95FF,
    )
    private val LIME = Color(0xFFC8FF4D)
    private const val FADE_NS = 2_000_000_000L

    var accent by mutableStateOf(LIME)
        private set
    /** `Beach Sync Radio is Violet now`, shown for 4 s when the station tells listeners. */
    var toast by mutableStateOf<Pair<String, Long>?>(null)
        private set
    private var toldAt = 0L

    /** One of the 21, or null: anything else reads as Lime. */
    fun parse(hex: String?): Color? {
        val v = hex?.removePrefix("#")?.uppercase() ?: return null
        return PALETTE.values.firstOrNull { "%06X".format(it and 0xFFFFFF) == v }?.let { Color(it) }
    }

    fun name(c: Color): String = PALETTE.entries.firstOrNull { Color(it.value) == c }?.key ?: "Lime"

    /** The station colour at flowtime [utc], with its own 2 s fade. */
    private fun stationAt(lib: LibraryStore, utc: Long): Color {
        val spec = lib.colour ?: return LIME
        val now = parse(spec.hex) ?: LIME
        val before = parse(spec.prev) ?: LIME
        return fade(before, now, utc - spec.atNs)
    }

    private fun fade(from: Color, to: Color, sinceNs: Long): Color = when {
        sinceNs <= 0 -> from
        sinceNs >= FADE_NS || ScreenPrefs.noFlashing -> to
        else -> lerp(from, to, sinceNs.toFloat() / FADE_NS)
    }

    /** The accent at flowtime [utc]: the set's own colour from its first downbeat, else the station's. */
    fun at(lib: LibraryStore, utc: Long): Color {
        val station = stationAt(lib, utc)
        val (item, offS) = playingAt(lib.editions, lib.dMs, utc) ?: return station
        val ed = editionAt(lib.editions, utc - lib.dMs * 1_000_000L) ?: return station
        val i = ed.items.indexOfFirst { it.id == item.id }
        val prev = if (i >= 0 && ed.items.size > 1) ed.items[(i - 1 + ed.items.size) % ed.items.size] else null
        val mine = parse(item.color)
        val theirs = parse(prev?.color)
        if (mine == null && theirs == null) return station
        // The set's start at the ear, to the nanosecond, then its first bar from the beat grid (the set's start if not worked out yet).
        val block = Math.floorDiv(utc - lib.dMs * 1_000_000L - STREAM_EPOCH_NS, 1_000_000_000L)
        val startNs = STREAM_EPOCH_NS + lib.dMs * 1_000_000L + (block - offS) * 1_000_000_000L
        val firstBarNs = startNs + (firstBarSeconds(lib, item.id) * 1e9).toLong()
        return fade(theirs ?: station, mine ?: station, utc - firstBarNs)
    }

    private fun firstBarSeconds(lib: LibraryStore, id: String): Double {
        val w = lib.beats(id)?.windows?.firstOrNull { it != null } ?: return 0.0
        val bar = 4 * w.period
        return ((w.downbeat % bar) + bar) % bar / 48000.0
    }

    private fun update(lib: LibraryStore?, utc: Long): Boolean {
        val c = lib?.let { at(it, utc) } ?: LIME
        if (c != accent) accent = c
        val spec = lib?.colour
        if (spec != null && spec.tell && spec.atNs != toldAt && utc >= spec.atNs && utc - spec.atNs < 10 * 60_000_000_000L) {
            toldAt = spec.atNs
            if (spec.prev != spec.hex) toast = "Beach Sync Radio is ${name(parse(spec.hex) ?: LIME)} now" to SystemClock.uptimeMillis()
        }
        toast?.let { if (SystemClock.uptimeMillis() - it.second > 4000) toast = null }
        return c != (lib?.let { at(it, utc + 100_000_000L) } ?: LIME)   // still changing: follow it frame by frame
    }

    /** Runs for the life of the screen: every 100 ms, or every frame while a fade runs. */
    @Composable
    fun Driver(lib: LibraryStore?) {
        LaunchedEffect(lib) {
            while (true) {
                val fading = update(lib, FlowTime.fit()?.utcAt(Session.nowBoot()) ?: (System.currentTimeMillis() * 1_000_000L))
                if (fading) withFrameNanos { } else delay(100)
            }
        }
    }
}
