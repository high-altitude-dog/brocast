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
                if (!f.exists() || out.exists()) continue
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
        val raw = ArrayList<BeatGrid.Result?>()
        val started = System.nanoTime()
        try {
            OpusFile(f).use { file ->
                for (b in 0 until item.seconds) {
                    blocks.addLast(decoder.decode(file.block(b)))
                    if (blocks.size > SetBeats.LENGTH_S) blocks.removeFirst()
                    // Window w is the 16 s from block 8w: the same windows the live tracker uses.
                    val first = b - SetBeats.LENGTH_S + 1
                    if (first >= 0 && first % SetBeats.WINDOW_S == 0L) raw.add(grid.analyse(first, blocks.toList()))
                    if (b % 10 == 0L) { working = item.title to (100 * b / item.seconds.coerceAtLeast(1)).toInt(); sleep(2) }
                }
            }
        } finally { decoder.close() }
        val tmp = File(out.path + ".tmp")
        tmp.writeText(SetBeats.resolve(raw).encode())
        if (!tmp.renameTo(out)) throw IllegalStateException("couldn't save ${out.name}")
        Log.i(TAG, "${item.id}: ${raw.size} windows, ${raw.count { it != null }} with a beat, in ${(System.nanoTime() - started) / 1_000_000_000L} s")
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
