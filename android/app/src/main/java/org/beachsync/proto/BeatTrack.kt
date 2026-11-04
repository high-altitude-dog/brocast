package org.beachsync.proto

import android.os.Process

/**
 * Works out the beat grid of the music being heard, for the beat sync view. It reads the decoded blocks the stream already holds (a lock-free read, like the audio
 * thread's), on its own low-priority thread, so it never touches the audio path. The grid comes from fixed, time-aligned windows of the stream: window w is
 * 16 s of blocks from 8w, and is used for the 8 s from 8w + 4. Two phones holding the same blocks therefore get the same grid however long ago each started.
 */
class BeatTrack(private val stream: StreamClient) : Thread("beats") {
    /** By window number. A null value means the window was analysed and has no clear beat. */
    @Volatile var grids: Map<Long, BeatGrid.Result?> = emptyMap()
    @Volatile private var running = true
    private val grid = BeatGrid()

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (running) {
            val m = stream.manifest
            val fit = FlowTime.fit()
            if (m != null && fit != null) {
                val pos = ((fit.utcAt(Session.nowBoot()) - m.startNs - m.dMs * 1_000_000L).toDouble() * RATE / 1e9).toLong()
                val w0 = windowAt(pos)
                var map = grids
                for (w in w0..w0 + AHEAD_W) {
                    if (map.containsKey(w) || w < 0) continue
                    val segs = ArrayList<ShortArray>()
                    for (n in WINDOW_S * w until WINDOW_S * w + LENGTH_S) segs.add(stream.segment(n) ?: break)
                    if (segs.size < LENGTH_S) continue
                    val result = grid.analyse(WINDOW_S * w, segs)
                    map = map.filterKeys { it >= w0 - 1 } + (w to result)
                    grids = map
                }
            }
            try { sleep(300) } catch (_: InterruptedException) { return }
        }
    }

    fun stopNow() { running = false; interrupt() }

    companion object {
        const val RATE = 48000L
        const val WINDOW_S = 8L
        const val LENGTH_S = 16
        const val AHEAD_W = 4L        // windows analysed ahead of the one in use: 8(4 + 1) + 16 s is inside the 60 s the stream holds

        /** The window whose middle 8 s hold stream sample [pos]. */
        fun windowAt(pos: Long): Long = Math.floorDiv(Math.floorDiv(pos, RATE) - 4, WINDOW_S)
    }
}
