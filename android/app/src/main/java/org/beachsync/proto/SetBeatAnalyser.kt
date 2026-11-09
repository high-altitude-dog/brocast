package org.beachsync.proto

import android.os.Process
import android.util.Log
import java.io.File

/**
 * Finds the beats and bars of every downloaded set, once, well before it plays (user, 7 Nov), and saves them as `<id>.beats` beside the file.
 * It decodes the file on its own thread at the lowest priority, with its own decoder, so it never touches the audio thread or the stream's ring.
 * Sets already on the phone are done the first time the app opens after this build. The decoded audio stays on the phone.
 */
class SetBeatAnalyser(private val store: LibraryStore) : Thread("set-beats") {
    /** What it's doing now, for the Beat sync view: the set's title and how far through, or null when idle. */
    @Volatile var working: Pair<String, Int>? = null
        private set

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        while (true) {
            for (item in store.editions.flatMap { it.items }.distinctBy { it.id }) {
                val f = store.file(item.id)
                val out = store.beatsFile(item.id)
                if (!f.exists() || (out.exists() && store.beats(item.id, fresh = true) != null)) continue
                // An older version's file: resolved again from its saved raw results and sound features, in seconds, with no new analysis.
                if (out.exists() && store.featuresFile(item.id).exists()) {
                    val raw = SetBeats.decodeRaw(out.readText())
                    if (raw.isNotEmpty()) {
                        val tmp = File(out.path + ".tmp")
                        try { tmp.writeText(SetBeats.resolve(raw, readFloats(store.featuresFile(item.id))).encode(raw)); if (tmp.renameTo(out)) continue }
                        catch (e: Exception) { Log.w(TAG, "re-resolve ${item.id}", e) }
                    }
                }
                try { analyse(item, f, out) } catch (e: InterruptedException) { return } catch (e: Exception) { Log.w(TAG, "beats for ${item.id}", e) }
                finally { working = null }
            }
            try { sleep(RESCAN_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun analyse(item: LibraryItem, f: File, out: File) {
        val grid = BeatGrid()
        val decoder = OpusDecoder()
        val blocks = ArrayDeque<ShortArray>()
        // Progress is saved every 10 windows, so a restart or an app update carries on where it stopped instead of starting the set again.
        val part = File(out.path + ".part")
        val raw = ArrayList<BeatGrid.Result?>(SetBeats.decodeRaw(if (part.exists()) part.readText() else ""))
        val resumedAt = raw.size
        // The sound's band energies, for the bar starts: kept beside the set (about 1.3 MB an hour) so a better resolve later needs no new analysis.
        val featFile = store.featuresFile(item.id)
        val perBlock = BeatFeatures.PER_S * BeatFeatures.BANDS
        val firstBlock = SetBeats.WINDOW_S * raw.size
        java.io.RandomAccessFile(featFile, "rw").use { it.setLength(minOf(it.length(), firstBlock * perBlock * 4L)) }
        val features = BeatFeatures()
        val featOut = java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(featFile, true)))
        val started = System.nanoTime()
        var decodeNs = 0L; var analyseNs = 0L
        try {
            OpusFile(f).use { file ->
                for (b in firstBlock until item.seconds) {
                    val t0 = System.nanoTime()
                    val pcm = decoder.decode(file.block(b))
                    val t1 = System.nanoTime(); decodeNs += t1 - t0
                    blocks.addLast(pcm)
                    for (v in features.block(pcm)) featOut.writeFloat(v)
                    if (blocks.size > SetBeats.LENGTH_S) blocks.removeFirst()
                    // Window w is the 16 s from block 8w: the same windows the live tracker uses.
                    val first = b - SetBeats.LENGTH_S + 1
                    if (first >= 0 && first % SetBeats.WINDOW_S == 0L && blocks.size == SetBeats.LENGTH_S) {
                        raw.add(grid.analyse(first, blocks.toList()))
                        if (raw.size % 10 == 0) { featOut.flush(); part.writeText(SetBeats(emptyList()).encode(raw)) }
                    }
                    if (b % 10 == 0L) working = item.title to (100 * b / item.seconds.coerceAtLeast(1)).toInt()
                    analyseNs += System.nanoTime() - t1
                }
            }
        } finally { decoder.close(); featOut.close() }
        val feat = readFloats(featFile)
        val tmp = File(out.path + ".tmp")
        tmp.writeText(SetBeats.resolve(raw, feat).encode(raw))
        if (!tmp.renameTo(out)) throw IllegalStateException("couldn't save ${out.name}")
        part.delete()
        Log.i(TAG, "${item.id}: ${raw.size} windows (from $resumedAt), ${raw.count { it != null }} with a beat, in ${(System.nanoTime() - started) / 1_000_000_000L} s: " +
            "decode ${decodeNs / 1_000_000_000L} s, analysis ${analyseNs / 1_000_000_000L} s")
    }

    private fun readFloats(f: File): FloatArray {
        val n = (f.length() / 4).toInt()
        val out = FloatArray(n)
        java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { for (i in 0 until n) out[i] = it.readFloat() }
        return out
    }

    companion object {
        private const val TAG = "SetBeats"
        private const val RESCAN_MS = 30_000L

        @Volatile private var only: SetBeatAnalyser? = null

        /** Starts the one analyser, if it isn't running. Safe to call more than once. */
        @Synchronized
        fun begin(store: LibraryStore): SetBeatAnalyser = only?.takeIf { it.isAlive } ?: SetBeatAnalyser(store).also { only = it; it.isDaemon = true; it.start() }

        fun current(): SetBeatAnalyser? = only
    }
}
