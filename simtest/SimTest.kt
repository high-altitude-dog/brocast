// Desktop tests of the app's pure timing code: NTP parsing, the GPS and NTP fits, and the playout loop.
// Needs the Kotlin compiler (kotlinc) and the Android SDK's android.jar. From the beachsync-prototype folder:
//   P=android/app/src/main/java/org/beachsync/proto; J=$ANDROID_HOME/platforms/android-35/android.jar
//   kotlinc -cp $J -d /tmp/simtest simtest/SimTest.kt $P/Flow.kt $P/Renderer.kt $P/NtpClient.kt $P/CsvLog.kt $P/Events.kt
//   java -cp /tmp/simtest:$J:<kotlinc folder>/lib/kotlin-stdlib.jar SimTestKt
import org.beachsync.proto.*
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

var failures = 0
fun check(ok: Boolean, what: String) { println((if (ok) "  ok   " else "  FAIL ") + what); if (!ok) failures++ }

fun putU32(p: ByteArray, i: Int, v: Long) { for (k in 0..3) p[i + k] = ((v ushr (24 - 8 * k)) and 0xff).toByte() }
fun putNtp(p: ByteArray, i: Int, unixNs: Long) {
    val sec = Math.floorDiv(unixNs, 1_000_000_000L)
    val ns = Math.floorMod(unixNs, 1_000_000_000L)
    putU32(p, i, sec + 2_208_988_800L)
    putU32(p, i + 4, ((ns shl 32) + 500_000_000L) / 1_000_000_000L)
}

fun testNtp() {
    println("NTP reply parsing")
    val nonce = ByteArray(8) { (it * 37 + 5).toByte() }
    val x = 1_761_955_200_000_000_000L - 123_456_789_012L   // true UTC minus boot clock
    val b1 = 98_765_432_100_000L
    val t2 = b1 + x + 10_000_000L                              // 10 ms out
    val t3 = t2 + 1_000_000L                                   // 1 ms in the server
    val b4 = t3 - x + 10_000_000L                              // 10 ms back
    val p = ByteArray(48)
    p[0] = 0x24; p[1] = 1
    putU32(p, 4, 0); putU32(p, 8, 66)                          // root dispersion ~1 ms
    "NPL".toByteArray().copyInto(p, 12)
    nonce.copyInto(p, 24)
    putNtp(p, 32, t2); putNtp(p, 40, t3)
    val r = parseNtp(p, nonce, b1, b4)
    check(abs(r.off - x) <= 2, "offset recovered to ${r.off - x} ns")
    check(abs(r.rtt - 20_000_000L) <= 2, "round trip 20 ms (${r.rtt} ns)")
    check(r.refId == "NPL" && r.stratum == 1, "stratum 1, ref ${r.refId}")
    check(abs(r.bound - (10e6 + 66 * 1e9 / 65536)) < 10, "bound = half round trip + root dispersion (${"%.0f".format(r.bound)} ns)")
    val bad = p.copyOf(); bad[25] = 0
    check(runCatching { parseNtp(bad, nonce, b1, b4) }.isFailure, "reply with the wrong nonce is refused")
    val kod = p.copyOf(); kod[1] = 0
    check(runCatching { parseNtp(kod, nonce, b1, b4) }.isFailure, "kiss-o'-death (stratum 0) is refused")
}

fun testGps() {
    println("GPS fit: 3.2 ppm drift, 30 ns scatter, one 5 ms outlier, then a real 2 ms step")
    val rnd = Random(1)
    val f = GpsFitter()
    val x0 = 1_761_955_200_000_000_000L
    var step = 0L
    fun truth(b: Long) = x0 + (3.2e-6 * b).toLong() + step
    var fit: ClockFit? = null
    var b = 5_000_000_000L
    var worst = 0.0
    for (i in 0 until 200) {
        if (i == 150) step = 2_000_000L
        val noise = (rnd.nextDouble() * 2 - 1) * 52.0               // uniform, sd 30 ns
        val o = truth(b) + noise.toLong() + if (i == 50) 5_000_000L else 0L
        f.add(b, o, 10.0, fit)?.let { fit = it }
        val ff = fit
        if (ff != null && i in 20..149 && i != 50) worst = maxOf(worst, abs((ff.utcAt(b) - b - truth(b)).toDouble()))
        b += 1_000_000_000L + rnd.nextLong(-2_000_000, 2_000_000)  // about 1 Hz
        if (i == 120) {
            val ff2 = fit!!
            println("    at 120 s: drift ${"%.4f".format(ff2.slope * 1e6)} ppm, ±${"%.0f".format(ff2.err0)} ns")
            check(abs(ff2.slope * 1e6 - 3.2) < 0.01, "drift measured to within 0.01 ppm")
            val later = b + 60_000_000_000L
            val e = abs((ff2.utcAt(later) - later - truth(later)).toDouble())
            check(e < ff2.errAt(later), "holdover 60 s later: off by ${"%.0f".format(e)} ns, inside its bound ±${"%.0f".format(ff2.errAt(later))} ns")
        }
    }
    check(worst < 100, "tracking error stays under 100 ns (worst ${"%.0f".format(worst)} ns)")
    check(f.rejected >= 1, "the 5 ms outlier was rejected (${f.rejected} rejections)")
    check(f.resets == 1, "the real 2 ms step caused exactly one reset")
    val e = abs((fit!!.utcAt(b) - b - truth(b)).toDouble())
    check(e < 100, "after the step it tracks again: off by ${"%.0f".format(e)} ns")
}

fun testGpsWeighting() {
    println("GPS fit: 40 s of poor signal, then 20 s of good signal, 200 trials")
    val x0 = 1_761_955_200_000_000_000L
    fun meanError(useChipFigure: Boolean): Double {
        var total = 0.0
        for (seed in 0 until 200) {
            val rnd = Random(1000 + seed)
            val f = GpsFitter()
            var fit: ClockFit? = null
            var b = 5_000_000_000L
            for (i in 0 until 60) {
                val sigma = if (i < 40) 5000.0 else 30.0
                val noise = (rnd.nextDouble() * 2 - 1) * sigma * 1.73   // uniform, sd = sigma
                val unc = if (useChipFigure) sigma else 30.0
                // current = null: this test is about the fit, so the outlier check stays out of it
                f.add(b, x0 + noise.toLong(), unc, null)?.let { fit = it }
                b += 1_000_000_000L
            }
            val last = b - 1_000_000_000L
            total += abs((fit!!.utcAt(last) - last - x0).toDouble())
        }
        return total / 200
    }
    val weighted = meanError(true)
    val equal = meanError(false)
    check(weighted < equal / 3, "using the chip's figures: off by ${"%.0f".format(weighted)} ns, against ${"%.0f".format(equal)} ns with equal weights")
}

fun testGpsEstimate() {
    println("GPS error estimate: Silver's link delay is counted, and holdover's bound grows at 5 ppm")
    val x0 = 1_761_955_200_000_000_000L
    fun fitted(link: Double): ClockFit {
        val f = GpsFitter()
        var fit: ClockFit? = null
        for (i in 0 until 60) { val b = 5_000_000_000L + i * 1_000_000_000L; f.add(b, x0, 10.0, fit, link)?.let { fit = it } }
        return fit!!
    }
    val gold = fitted(0.0)
    val silver = fitted(GpsFitter.SILVER_LINK_NS)
    check(gold.err0 < 1e6, "Gold starts under 1 ms (±${"%.0f".format(gold.err0)} ns)")
    check(silver.err0 >= 5e6 && silver.err0 < 5.1e6, "Silver starts at about 5 ms (±${"%.2f".format(silver.err0 / 1e6)} ms)")
    val hour = gold.boot0 + 3600 * 1_000_000_000L
    check(abs(gold.errAt(hour) - 18e6) < 1e5, "an hour of holdover adds about 18 ms (±${"%.2f".format(gold.errAt(hour) / 1e6)} ms)")

    // NTP at ±15 ms takes over once GPS's bound is 25% worse: about 62.5 minutes, not days
    FlowTime.reset(Policy.AUTO)
    FlowTime.gps = gold
    FlowTime.reselect(gold.boot0)
    FlowTime.ntp = ClockFit(gold.boot0, x0, 0.0, 15e6, 0.0)
    var t = 0L
    while (FlowTime.chosen != Source.NTP && t < 86400) { t += 1; FlowTime.reselect(gold.boot0 + t * 1_000_000_000L) }
    check(t in 3700..3800, "NTP at ±15 ms takes over after ${t / 60} minutes")
    FlowTime.reset(Policy.AUTO)
}

fun testNtpFit() {
    println("NTP fit: 3 ppm drift, round trips 15-60 ms, asymmetric paths")
    val rnd = Random(2)
    val f = NtpFitter()
    val x0 = 1_761_955_200_000_000_000L
    fun truth(b: Long) = x0 + (3e-6 * b).toLong()
    var b = 1_000_000_000L
    var inside = 0; var total = 0; var lastErr = 0.0; var lastBound = 0.0
    for (i in 0 until 60) {   // 16 minutes
        val rtt = 15e6 + rnd.nextDouble() * 45e6
        val asym = (rnd.nextDouble() * 2 - 1) * rtt / 2 * 0.6   // sample error, always inside rtt/2
        val fit = f.add(NtpFitter.Sample(b, truth(b) + asym.toLong(), rtt / 2 + 1e5))
        for (k in 0 until 16) {   // check the bound every second until the next sample
            val bb = b + k * 1_000_000_000L
            val err = abs((fit.utcAt(bb) - bb - truth(bb)).toDouble())
            total++; if (err <= fit.errAt(bb)) inside++
            lastErr = err; lastBound = fit.errAt(bb)
        }
        b += 16_000_000_000L
    }
    println("    after 16 min: off by ${"%.2f".format(lastErr / 1e6)} ms, bound ±${"%.2f".format(lastBound / 1e6)} ms")
    check(inside.toDouble() / total > 0.95, "true error inside the bound ${inside} of ${total} seconds")
}

/** A simulated phone: the DAC runs [ppm] fast or slow, time stamps have [jitterUs] of noise. */
fun simPlayout(ppm: Double, jitterUs: Double, seconds: Int, seed: Int): Pair<Double, Double> {
    val rnd = Random(seed)
    val rate = 48000.0 * (1 + ppm * 1e-6)                  // true frames per second
    val utc0 = 1_761_955_200_000_000_000L                   // boot clock = true time here; flowtime is exact
    val fit = ClockFit(0, utc0, 0.0, 100.0, 0.0)
    val start = utc0 + 5_000_000_000L - 1_000_000_000L * 3  // stream start, whole second
    val d = 21_000_000_000L
    val lag = 0L
    val seg = ShortArray(96000)
    val r = Renderer(2000.0, SegmentSource { seg })
    val chunk = 240
    val buf = FloatArray(chunk * 2)
    val playStart = 5_000_000_000L + 40_000_000L            // first frame plays 40 ms after start-up
    val bufferFrames = 960
    var written = 0L
    var tsFrame = 0L; var tsBoot = 0L; var haveTs = false; var lastQuery = 0L
    var sum2 = 0.0; var n = 0; var worst = 0.0
    while (written < seconds * 48000L) {
        // the loop runs when the buffer has room: about when frame (written - buffer) is playing
        val now = playStart + ((written - bufferFrames).coerceAtLeast(0) / rate * 1e9).toLong()
        if (now - lastQuery >= 20_000_000L) {
            lastQuery = now
            val played = ((now - playStart) / 1e9 * rate).toLong()
            if (played > 0) {
                tsFrame = played
                tsBoot = playStart + (played / rate * 1e9).toLong() + ((rnd.nextDouble() * 2 - 1) * jitterUs * 1e3).toLong()
                haveTs = true
            }
        }
        if (haveTs) {
            val bootOut = tsBoot + (written - tsFrame) * 1_000_000_000L / 48000
            r.stream(buf, chunk, fit.utcAt(bootOut) + lag, start, d)
            // the truth: when does each of these frames really play, and which stream sample should that be?
            val p0 = r.pos - chunk * r.ratio
            for (i in 0 until chunk step 60) {
                val tTrue = playStart + ((written + i) / rate * 1e9)
                val want = (tTrue - (start - utc0 + d)) * 48000 / 1e9
                val errUs = (p0 + i * r.ratio - want) / 48000 * 1e6
                if (written > 15 * 48000) { sum2 += errUs * errUs; n++; worst = maxOf(worst, abs(errUs)) }
            }
        }
        written += chunk
    }
    return Pair(sqrt(sum2 / n), worst)
}

fun testPlayout() {
    println("Playout loop: true heard-time error after the first 15 s (DAC rate off, noisy time stamps)")
    for ((ppm, jit) in listOf(80.0 to 0.0, -80.0 to 0.0, 30.0 to 50.0, -30.0 to 200.0)) {
        val (rms, worst) = simPlayout(ppm, jit, 120, 3)
        check(worst < 10 + 0.5 * jit, "DAC ${"%+.0f".format(ppm)} ppm, stamp jitter ±${jit.toInt()} µs: rms ${"%.1f".format(rms)} µs, worst ${"%.1f".format(worst)} µs")
    }
}

fun testTickTiming() {
    println("Tick click: rises from zero exactly at the whole second")
    val buf = FloatArray(480)
    val r = Renderer(2000.0, null)
    val sec = 1_761_955_200_000_000_000L
    // first frame 0.5 frame-periods before the second: frame 1 is 0.5 periods after it
    r.ticks(buf, 240, sec - 10_417L)
    check(buf[0] == 0f && buf[2] != 0f && abs(buf[2] - Renderer.click(10_416.67, 2000.0)) < 1e-4, "click starts between frames 0 and 1, at the right phase")
}

fun testChannel() {
    println("Stream channel: Left or Right only plays that channel on both outputs")
    val seg = ShortArray(96000) { if (it % 2 == 0) 8192 else -16384 }   // left 0.25, right -0.5
    val start = 1_761_955_200_000_000_000L
    val ear = start + 21_000_000_000L + 2_000_000_000L
    for ((ch, l, r) in listOf(Triple(Renderer.BOTH, 0.25f, -0.5f), Triple(Renderer.LEFT, 0.25f, 0.25f), Triple(Renderer.RIGHT, -0.5f, -0.5f))) {
        val buf = FloatArray(480)
        Renderer(2000.0, SegmentSource { seg }, ch).stream(buf, 240, ear, start, 21_000_000_000L)
        check((0 until 240).all { buf[2 * it] == l && buf[2 * it + 1] == r }, "channel $ch gives $l, $r")
    }
}

fun testEvents() {
    println("Event log: one event per change, not one per second a state lasts")
    val s = 1_000_000_000L
    fun reading(
        t: Long, source: Source? = null, gps: Long = 0L, underruns: Int = 0, jumps: Int = 0, missing: Long = 0L,
        fetch: String? = null, fetchErrors: Int = 0, ntp: String? = null, route: String? = null,
    ) = Reading(t * s, null, source, 2e6, gps * s, underruns, jumps, missing, fetch, fetchErrors, ntp, route, null)
    val d = EventDetector()

    check(d.update(reading(100)).isEmpty(), "a quiet start logs nothing")
    val first = d.update(reading(101, Source.NTP))
    check(first.size == 1 && first[0].lane == Lane.CLOCK && first[0].text.startsWith("Time source: NTP"), "first time source is logged")
    check(d.update(reading(102, Source.NTP)).isEmpty(), "no change logs nothing")
    check(d.update(reading(103, Source.NTP, gps = 103)).single().text == "First GPS time report", "first GPS report is logged")
    check(d.update(reading(108, Source.NTP, gps = 103)).isEmpty(), "5 s without GPS isn't yet a loss")
    check(d.update(reading(109, Source.NTP, gps = 103)).single().text.startsWith("GPS lost"), "6 s without GPS is a loss")
    check(d.update(reading(110, Source.NTP, gps = 103)).isEmpty(), "a loss is logged once")
    check(d.update(reading(130, Source.NTP, gps = 130)).single().text == "GPS back after 27 s", "GPS return gives the gap")
    check(d.update(reading(131, Source.GPS, gps = 131)).single().text.startsWith("Time source changed from NTP to GPS"), "a source change is logged")

    check(d.update(reading(132, Source.GPS, gps = 132, underruns = 3)).single().text.contains("×3"), "underruns are logged with their count")
    check(d.update(reading(133, Source.GPS, gps = 133, underruns = 3)).isEmpty(), "unchanged underruns log nothing")
    check(d.update(reading(134, Source.GPS, gps = 134, underruns = 3, jumps = 1)).single().text.startsWith("Jump"), "a jump is logged")
    check(d.update(reading(135, Source.GPS, gps = 135, underruns = 3, jumps = 1, missing = 4800)).single().text.contains("100 ms"), "missing audio is logged in ms")

    fun later(t: Long, fetch: String? = null, fetchErrors: Int = 0, ntp: String? = null, route: String? = null) =
        reading(t, Source.GPS, gps = t, underruns = 3, jumps = 1, missing = 4800, fetch = fetch, fetchErrors = fetchErrors, ntp = ntp, route = route)
    check(d.update(later(140, fetch = "timeout", fetchErrors = 1)).single().text == "Stream fetch failing: timeout", "fetch failure is logged")
    check(d.update(later(141, fetch = "timeout", fetchErrors = 2)).isEmpty(), "ongoing fetch failure is logged once")
    check(d.update(later(150, fetchErrors = 5)).single().text == "Stream fetch working again after 10 s (5 errors)", "fetch recovery gives time and count")
    check(d.update(later(151, ntp = "no reply")).single().lane == Lane.NET, "NTP failure is logged")
    check(d.update(later(155)).single().text == "NTP working again after 4 s", "NTP recovery is logged")
    check(d.update(later(156, route = "USB headset")).single().text == "Audio output: USB headset", "first output route is logged")
    check(d.update(later(157)).isEmpty(), "losing the route reading logs nothing")
    check(d.update(later(158, route = "speaker")).single().text == "Audio output changed to speaker", "route change is logged")

    println("Event log: tightening needs a real, lasting improvement")
    val td = EventDetector()
    fun tight(t: Long, err: Double, sats: Int = 9) =
        td.update(Reading(t * s, null, Source.GPS, err, t * s, 0, 0, 0L, null, 0, null, null, null, sats)).filter { it.text.startsWith("Sync") }
    check(tight(200, 3.2e6).isEmpty(), "the first estimate is only a baseline")
    check(tight(201, 0.8e6).isEmpty() && !td.tighteningFromNs.isNaN(), "an improvement shows as under way while it's confirmed")
    check(tight(205, 0.8e6).isEmpty(), "and isn't logged before 10 s")
    check(tight(211, 0.8e6).single().text == "Sync tightened from ±3.20 ms to ±800 µs, 9 satellites", "after 10 s it's logged once, with the figures")
    check(td.tighteningFromNs.isNaN(), "and the status panel stops showing it")
    check(tight(212, 0.8e6).isEmpty(), "it isn't repeated")
    check(tight(213, 0.75e6, sats = 14).isEmpty() && tight(230, 0.75e6, sats = 14).isEmpty(), "more satellites with little improvement log nothing")
    check(tight(231, 5e6).isEmpty(), "getting worse logs nothing here")
    check(tight(232, 2e6).isEmpty() && tight(240, 5e6).isEmpty() && tight(251, 2e6).isEmpty(), "a brief improvement that doesn't last logs nothing")
    val quiet = EventDetector()
    fun routine(t: Long, err: Double) = quiet.update(Reading(t * s, null, Source.GPS, err, t * s, 0, 0, 0L, null, 0, null, null, null, 9)).filter { it.text.startsWith("Sync") }
    routine(300, 90_000.0)
    check(routine(301, 40_000.0).isEmpty() && routine(320, 40_000.0).isEmpty(), "changes below 0.1 ms are routine and log nothing")

    var sunk = 0
    val log = EventLog(keep = 2) { sunk++ }
    for (i in 1..3) log.add(Event(i.toLong(), null, Lane.AUDIO, "e$i"))
    check(log.total == 3 && sunk == 3, "every event reaches the file, even past the screen limit")
    check(log.latest()?.text == "e3" && log.recent(10).map { it.text } == listOf("e3", "e2"), "the screen gets the newest first, within its limit")
}

fun main() {
    testNtp(); testGps(); testGpsWeighting(); testGpsEstimate(); testNtpFit(); testPlayout(); testTickTiming(); testChannel(); testEvents()
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
