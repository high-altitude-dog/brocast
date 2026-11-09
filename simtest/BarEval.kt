// Scores bar grids against EDM structure (9 Nov): where the low end comes back after a break or leaves for one is bar 1 of a phrase.
// Pull <id>.beats and <id>.feat from a phone's files/library into DIR, then compile with SetBeats.kt, BeatGrid.kt, BeatDetector.kt (as SimTest) and run:
//   java -cp ... EvalKt DIR fresh 0.8 q        (clear drops and breaks; args: mode stored|fresh, event threshold, q = summary only, then STRUCT_MODE WEIGHT REACH MAXD THRESH)
import org.beachsync.proto.*
import java.io.File

// Scores a set's bar grid against EDM structure: where the low end (kick + bass, 40-150 Hz) comes back after a break, or leaves for one,
// that's bar 1 of a phrase. Reports which beat of the bar each such event lands on.
fun readFloats(f: File): FloatArray {
    val n = (f.length() / 4).toInt(); val out = FloatArray(n)
    java.io.DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(f))).use { for (i in 0 until n) out[i] = it.readFloat() }
    return out
}

class Ev(val frame: Int, val rise: Boolean, val size: Double)

var THRESH = 0.45
var MAXD = 99.0
fun events(feat: FloatArray): List<Ev> {
    val B = BeatFeatures.BANDS; val n = feat.size / B
    val low = DoubleArray(n) { f -> (0..3).sumOf { feat[f * B + it].toDouble() } / 4 }
    val sorted = low.sorted(); val hi = sorted[(n * 0.75).toInt()]; val lo = sorted[(n * 0.05).toInt()]
    val span = hi - lo
    val W = 32  // 4 s each side
    val out = ArrayList<Ev>()
    var f = W
    while (f < n - W) {
        val before = (f - W until f).sumOf { low[it] } / W
        val after = (f until f + W).sumOf { low[it] } / W
        val d = (after - before) / span
        if (Math.abs(d) > THRESH) {
            // The change's centre (where the before/after difference peaks), then the steepest single step within ±3 frames of it.
            val dd = { c: Int -> ((c until c + W).sumOf { low[it] } - (c - W until c).sumOf { low[it] }) / W / span }
            val c = (f until minOf(f + W, n - W)).maxBy { if (d > 0) dd(it) else -dd(it) }
            val best = (c - 3..c + 3).maxBy { k -> if (d > 0) low[k] - low[k - 1] else low[k - 1] - low[k] }
            if (Math.abs(dd(c)) < MAXD) out.add(Ev(best, d > 0, Math.abs(d)))
            f += 2 * W
        } else f++
    }
    return out
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val mode = args.getOrElse(1) { "stored" }
    THRESH = args.getOrElse(2) { "0.45" }.toDouble()
    val quiet = args.getOrElse(3) { "" } == "q"
    args.getOrNull(4)?.let { SetBeats.STRUCT_MODE = it.toInt() }
    args.getOrNull(5)?.let { SetBeats.STRUCT_WEIGHT = it.toDouble() }
    args.getOrNull(6)?.let { SetBeats.STRUCT_REACH = it.toInt() }
    args.getOrNull(7)?.let { MAXD = it.toDouble() }
    args.getOrNull(8)?.let { SetBeats.STRUCT_THRESH = it.toDouble() }
    val up = IntArray(4); val down = IntArray(4)
    val tally = IntArray(4); var total = 0
    val hist = IntArray(16)
    for (bf in dir.listFiles()!!.filter { it.name.endsWith(".beats") }.sortedBy { it.name }) {
        val id = bf.name.removeSuffix(".beats")
        val feat = readFloats(File(dir, "$id.feat"))
        val grid = if (mode == "stored") SetBeats.decode(bf.readText())!! else SetBeats.resolve(SetBeats.decodeRaw(bf.readText()), feat)
        val t = IntArray(4); var miss = 0
        val detail = StringBuilder()
        for (e in events(feat)) {
            val sample = e.frame * 6000.0 + 1024   // the frame's centre
            val p = grid.at(sample) ?: run { miss++; null } ?: continue
            val nearest = if (p.beatFrac < 0.5) p.beatInBar else (p.beatInBar + 1) % 4
            hist[(((p.beatInBar + p.beatFrac) * 4).toInt()).coerceIn(0, 15)]++
            t[nearest]++; if (e.rise) up[nearest]++ else down[nearest]++
            detail.append(" ${if (e.rise) "↑" else "↓"}${"%d:%02d".format(e.frame / 8 / 60, e.frame / 8 % 60)}=b${nearest + 1}")
        }
        for (k in 0..3) tally[k] += t[k]; total += t.sum()
        if (!quiet) println("${id.take(6)} on 1: ${t[0]}/${t.sum()}  (b2 ${t[1]}, b3 ${t[2]}, b4 ${t[3]}, no grid $miss)$detail")
    }
    println("position in bar (quarter beats from bar 1): " + hist.joinToString(" "))
    println("kick-in on 1: ${up[0]}/${up.sum()}  kick-out on 1: ${down[0]}/${down.sum()}")
    println("ALL on 1: ${tally[0]}/$total = ${"%.0f".format(100.0 * tally[0] / total)}%  b2 ${tally[1]} b3 ${tally[2]} b4 ${tally[3]}")
}
