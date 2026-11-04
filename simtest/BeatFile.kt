// Runs the beat grid over a raw 48 kHz stereo 16-bit file, window by window as the app does, and prints how steady and how consistent the grids are.
//   kotlinc -cp $J -d /tmp/beatfile simtest/BeatFile.kt $P/BeatGrid.kt
//   java -cp /tmp/beatfile:$K/lib/kotlin-stdlib.jar BeatFileKt track.raw
import org.beachsync.proto.BeatGrid
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

fun main(args: Array<String>) {
    val bytes = File(args[0]).readBytes()
    val sh = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val all = ShortArray(sh.remaining()); sh.get(all)
    val secs = all.size / 2 / 48000
    val blocks = (0 until secs).map { s -> all.copyOfRange(s * 96000, (s + 1) * 96000) }
    println("$secs s of audio")
    val grid = BeatGrid()
    val map = HashMap<Long, BeatGrid.Result?>()
    var w = 0
    while (8 * w + 16 <= secs) { map[w.toLong()] = grid.analyse(8L * w, blocks.subList(8 * w, 8 * w + 16)); w++ }
    println("${map.size} windows, ${map.values.count { it == null }} with no grid of their own")
    var prev: BeatGrid.Result? = null
    var flips = 0; var compared = 0; var worstOff = 0.0
    for (i in 0 until map.size) {
        val r = BeatGrid.resolve(i.toLong(), map)
        if (r == null) { println("window %2d: nothing to use".format(i)); prev = null; continue }
        val p = prev
        if (p != null) {
            val t = p.phaseSample + 20 * p.periodSamples
            val d = (t - r.phaseSample) / r.periodSamples
            val offMs = (d - Math.round(d)) * r.periodSamples * 1000 / 48000
            val bar0 = ((p.phaseSample + p.barOffset * p.periodSamples) - (r.phaseSample + r.barOffset * r.periodSamples)) / r.periodSamples
            val same = Math.floorMod(Math.round(bar0), 4L) == 0L
            compared++; if (!same) flips++
            worstOff = maxOf(worstOff, abs(offMs))
            println("window %2d: %6.2f per minute, beat offset %+6.1f ms, same bar start: %s".format(i, 60 * 48000 / r.periodSamples, offMs, same))
        } else println("window %2d: %6.2f per minute".format(i, 60 * 48000 / r.periodSamples))
        prev = r
    }
    println("bar start changed between $flips of $compared consecutive windows; worst beat offset ${"%.1f".format(worstOff)} ms")
}
