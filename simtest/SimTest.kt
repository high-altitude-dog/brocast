// Desktop tests of the app's pure timing code: NTP parsing, the GPS and NTP fits, and the playout loop.
// Needs the Kotlin compiler (kotlinc) and the Android SDK's android.jar. From the beachsync-prototype folder:
//   P=android/app/src/main/java/org/beachsync/proto; J=$ANDROID_HOME/platforms/android-35/android.jar
//   kotlinc -cp $J -d /tmp/simtest simtest/SimTest.kt $P/Flow.kt $P/Renderer.kt $P/NtpClient.kt $P/CsvLog.kt $P/Events.kt $P/Nudge.kt $P/FetchPlan.kt $P/Calibration.kt $P/OpusBlock.kt $P/LibraryPlan.kt
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

var lastSimJumps = 0

/**
 * A simulated phone: the DAC runs [ppm] fast or slow, time stamps have [jitterUs] of noise. If [gapAtS] is set, flowtime
 * moves by [gapNs] at that moment and the gap closes over [periodS]; the error is measured against that closing target.
 */
fun simPlayout(ppm: Double, jitterUs: Double, seconds: Int, seed: Int, gapAtS: Int = -1, gapNs: Long = 0L, periodS: Double = 0.0): Pair<Double, Double> {
    val rnd = Random(seed)
    val rate = 48000.0 * (1 + ppm * 1e-6)                  // true frames per second
    val utc0 = 1_761_955_200_000_000_000L                   // boot clock = true time here; flowtime is exact
    val fit0 = ClockFit(0, utc0, 0.0, 100.0, 0.0)
    var hold = NudgeState(fit0, 0L, 0L, 0.0)
    var shift = 0L
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
        if (gapAtS >= 0 && shift == 0L && written >= gapAtS * 48000L) {
            shift = gapNs
            hold = NudgeState(ClockFit(0, utc0 + gapNs, 0.0, 100.0, 0.0), gapNs, now, gapNs / (periodS * 1e9))
        }
        if (haveTs) {
            val bootOut = tsBoot + (written - tsFrame) * 1_000_000_000L / 48000
            r.stream(buf, chunk, hold.fit!!.utcAt(bootOut) + lag, start, d, hold.heldAt(bootOut), hold.rateAt(bootOut))
            // the truth: when does each of these frames really play, and which stream sample should that be?
            val p0 = r.pos - chunk * r.ratio
            for (i in 0 until chunk step 60) {
                val tTrue = playStart + ((written + i) / rate * 1e9)
                val want = (tTrue + shift - hold.heldAt(tTrue.toLong()) - (start - utc0 + d)) * 48000 / 1e9
                val errUs = (p0 + i * r.ratio - want) / 48000 * 1e6
                if (written > 45 * 48000) { sum2 += errUs * errUs; n++; worst = maxOf(worst, abs(errUs)) }
            }
        }
        written += chunk
    }
    lastSimJumps = r.jumps
    return Pair(sqrt(sum2 / n), worst)
}

fun testPlayout() {
    println("Playout loop: true heard-time error after the first 45 s (DAC rate off, noisy time stamps)")
    for ((ppm, jit) in listOf(80.0 to 0.0, -80.0 to 0.0, 30.0 to 50.0, -30.0 to 200.0)) {
        val (rms, worst) = simPlayout(ppm, jit, 120, 3)
        check(worst < 10 + 0.5 * jit, "DAC ${"%+.0f".format(ppm)} ppm, stamp jitter ±${jit.toInt()} µs: rms ${"%.1f".format(rms)} µs, worst ${"%.1f".format(worst)} µs")
    }
    println("Closing a gap: the stream follows the closing target, with no jump and no overshoot")
    for ((gap, period) in listOf(5_000_000L to 30.0, -5_000_000L to 30.0, 5_000_000L to 5.6, 15_000_000L to 300.0)) {
        val (rms, worst) = simPlayout(80.0, 0.0, 480, 3, gapAtS = 60, gapNs = gap, periodS = period)
        check(worst < 20 && lastSimJumps == 0, "${gap / 1_000_000} ms over ${period} s: rms ${"%.1f".format(rms)} µs, worst ${"%.1f".format(worst)} µs, ${lastSimJumps} jumps")
    }
}

fun testNudge() {
    println("Nudge control: Auto closes gaps, Manual holds them until Go")
    val s = 1_000_000_000L
    val x0 = 1_761_955_200_000_000_000L
    fun fit(off: Long) = ClockFit(0, x0 + off, 0.0, 100.0, 0.0)
    val notes = ArrayList<String>()
    val t0 = 1000 * s

    val a = NudgeControl(NudgeMode.AUTO, null) { notes.add(it) }
    a.tick(t0, fit(0))
    check(a.gapAt(t0) == 0L, "the first fit is no gap")
    a.tick(t0 + s, fit(50_000))
    check(a.gapAt(t0 + s) == 0L && !a.state.closing, "a 0.05 ms routine correction is absorbed")
    val f5 = fit(5_050_000)
    a.tick(t0 + 2 * s, f5)
    check(a.state.closing && abs(a.state.heldAt(t0 + 2 * s) - 5_000_000) < 100, "Auto starts closing a 5 ms gap straight away")
    check(abs(a.state.heldAt(t0 + 32 * s) - 2_500_000) < 1000, "over the recommended 60 s: half gone after 30 s")
    a.tick(t0 + 63 * s, f5)
    check(a.gapAt(t0 + 63 * s) == 0L && !a.state.closing && notes.last().startsWith("Back in step"), "it closes and says so")
    a.tick(t0 + 64 * s, fit(35_050_000))
    check(a.gapAt(t0 + 64 * s) == 0L && notes.last().contains("jumping"), "Auto jumps across a 30 ms gap")

    val fast = NudgeControl(NudgeMode.AUTO, 1.0)
    fast.tick(t0, fit(0)); fast.tick(t0 + s, fit(5_000_000))
    check(abs(fast.state.rate - NudgeControl.MAX_RATE) < 1e-9, "a 1 s period on 5 ms is held to the rate limit")
    check(abs(fast.secondsLeft(t0 + s) - 5.0 / 0.9) < 0.01, "so it really takes 5.6 s, and says so")

    notes.clear()
    val m = NudgeControl(NudgeMode.MANUAL, 10.0) { notes.add(it) }
    m.tick(t0, fit(0))
    val g = fit(5_000_000)
    m.tick(t0 + s, g)
    check(!m.state.closing && m.gapAt(t0 + 100 * s) == 5_000_000L && notes.last().contains("press Go"), "Manual holds the gap and doesn't let it grow")
    m.tick(t0 + 101 * s, fit(5_010_000))
    check(m.gapAt(t0 + 101 * s) == 5_010_000L && notes.size == 1, "routine corrections add up quietly while a gap is held")
    m.go(t0 + 102 * s)
    check(m.state.closing && abs(m.state.rate - 5.01e-4) < 1e-9, "Go closes it over the chosen 10 s")
    m.pause(t0 + 107 * s)
    check(!m.state.closing && abs(m.gapAt(t0 + 200 * s) - 2_505_000) < 1000, "Pause holds what's left")
    m.setMode(NudgeMode.AUTO, t0 + 201 * s)
    check(m.state.closing, "switching to Auto finishes the job")
    m.setMode(NudgeMode.MANUAL, t0 + 202 * s)
    m.jump(t0 + 202 * s)
    check(m.gapAt(t0 + 203 * s) == 0L, "Jump clears the gap")
    val big = fit(35_000_000)
    m.tick(t0 + 204 * s, big)
    check(!m.state.closing && m.gapAt(t0 + 204 * s) != 0L, "Manual holds even a large gap for the listener to choose")

    val before = NudgeState(fit(0), 2_000_000L, t0, 1e-3)
    check(before.heldAt(t0 + 3 * s) == 0L && before.rateAt(t0 + 3 * s) == 0.0, "a finished gap holds nothing and adds no rate")
    val neg = NudgeState(fit(0), -2_000_000L, t0, -1e-3)
    check(neg.heldAt(t0 + s) == -1_000_000L && neg.heldAt(t0 + 3 * s) == 0L, "negative gaps close the same way")
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

fun testTickPattern() {
    println("Tick pattern: a click every 200 ms of flowtime, stepping through the pitches, with phone B 60 ms after phone A")
    val t0 = 1_761_955_200_000_000_000L     // a whole second, so slot 0 starts here on any phone
    val slot = Renderer.PATTERN_SLOT_NS
    val stagger = Renderer.PATTERN_STAGGER_NS
    val a = Renderer(2000.0, null, tickPattern = Renderer.PATTERN_A)
    val b = Renderer(2000.0, null, tickPattern = Renderer.PATTERN_B)
    fun frame(r: Renderer, utc: Long): Float { val buf = FloatArray(2); r.ticks(buf, 1, utc); return buf[0] }
    val into = 1_000_000L     // 1 ms into the click
    check(frame(a, t0 + into) == 0f, "silent until the window is set")
    a.tickFromUtc = t0 + slot; a.tickUntilUtc = t0 + 2 * slot
    check(frame(a, t0 + into) == 0f, "silent before the window")
    check(frame(a, t0 + slot + into) != 0f, "sounds inside the window")
    check(frame(a, t0 + 2 * slot + into) == 0f, "silent again after the window")
    for (r in listOf(a, b)) { r.tickFromUtc = Long.MIN_VALUE; r.tickUntilUtc = Long.MAX_VALUE }
    for ((n, hz) in Renderer.patternHz.withIndex()) {
        val at = t0 + n * slot + into
        check(abs(frame(a, at) - Renderer.click(into.toDouble(), hz)) < 1e-4 && frame(b, at) == 0f, "slot $n: phone A clicks at $hz Hz and phone B is silent")
        check(abs(frame(b, at + stagger) - Renderer.click(into.toDouble(), hz)) < 1e-4 && frame(a, at + stagger) == 0f, "slot $n: phone B clicks 60 ms later, at the same pitch")
    }
    check(frame(a, t0 + 50_000_000L) == 0f && frame(b, t0 + 50_000_000L) == 0f, "silent between clicks")
    for (r in listOf(a, b)) {
        val buf = FloatArray(2 * 48000)
        r.ticks(buf, 48000, t0 + 500_000_000L)     // a chunk that crosses the cycle boundary
        val on = (0 until 48000).filter { buf[2 * it] != 0f }
        check(on.map { ((it + 24000) % 48000) / 9600 }.distinct().size == 5, "five clicks a second, across the cycle boundary")
    }
    val off = Renderer(2000.0, null)
    check(abs(frame(off, t0 + into - Math.floorMod(t0, 1_000_000_000L)) - Renderer.click(into.toDouble(), 2000.0)) < 1e-4, "with no pattern, ticks are unchanged")
}

fun testSoloGate() {
    println("Solo gate: phones take turns to be heard, and the stream's position is untouched")
    val seg = ShortArray(96000) { 8192 }
    val start = 1_761_955_200_000_000_000L
    val d = 21_000_000_000L
    // A whole number of 1 s cycles (two 400 ms turns and 200 ms of silence), so turn 0 starts here.
    val t0 = start + d + 2_000_000_000L - Math.floorMod(start + d + 2_000_000_000L, 1_000_000_000L)
    fun level(r: Renderer, utc: Long): Float {
        val buf = FloatArray(2)
        r.stream(buf, 1, utc, start, d)
        return buf[0]
    }
    val a = Renderer(2000.0, SegmentSource { seg }, soloSlot = 0, soloCount = 2)
    val b = Renderer(2000.0, SegmentSource { seg }, soloSlot = 1, soloCount = 2)
    val plain = Renderer(2000.0, SegmentSource { seg })
    val mid = 200_000_000L
    check(level(a, t0 + mid) == 0f, "silent until the window is set")
    for (r in listOf(a, b)) { r.tickFromUtc = Long.MIN_VALUE; r.tickUntilUtc = Long.MAX_VALUE }
    check(level(a, t0 + mid) == 0.25f && level(b, t0 + mid) == 0f, "first turn: phone A is heard and phone B isn't")
    check(level(a, t0 + 400_000_000L + mid) == 0f && level(b, t0 + 400_000_000L + mid) == 0.25f, "second turn: phone B is heard and phone A isn't")
    check(level(a, t0 + 900_000_000L) == 0f && level(b, t0 + 900_000_000L) == 0f, "then both are silent, which marks the start")
    val fade = level(a, t0 + 5_000_000L)
    check(abs(fade - 0.125f) < 0.01f, "each turn fades in, so it doesn't click")
    for (r in listOf(a, b)) { r.tickFromUtc = Long.MIN_VALUE; r.tickUntilUtc = t0 }
    check(level(a, t0 + mid) == 0f, "silent again after the window")
    val g = Renderer(2000.0, SegmentSource { seg }, soloSlot = 1, soloCount = 2)
    val bufG = FloatArray(480)
    val bufP = FloatArray(480)
    g.stream(bufG, 240, t0 + mid, start, d)
    plain.stream(bufP, 240, t0 + mid, start, d)
    check(g.pos == plain.pos && g.ratio == plain.ratio && g.missingFrames == plain.missingFrames, "the gate changes the level only, not the position or the nudge")
    val off = Renderer(2000.0, SegmentSource { seg })
    check(level(off, t0 + mid) == 0.25f && level(off, t0 + 900_000_000L) == 0.25f, "with no gate, playback is unchanged")
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

    println("Event log: a long silence is two lines, not one a second")
    val q = EventDetector()
    check(q.update(reading(10, missing = 48000)).single().text.contains("1000 ms"), "the start of a silence is logged")
    check((11L..59L).all { q.update(reading(it, missing = 48000 * (it - 9))).isEmpty() }, "the rest of it logs nothing")
    val r = EventDetector()
    r.update(reading(10, missing = 48000))
    for (t in 11L..20L) r.update(reading(t, missing = 48000 * (t - 9)))
    check(r.update(reading(21, missing = 48000 * 11)).isEmpty(), "1 s without loss doesn't end a silence")
    check(r.update(reading(22, missing = 48000 * 12)).isEmpty(), "more loss carries the same silence on")
    check(r.update(reading(25, missing = 48000 * 12)).single().text == "Audio back after 12 s (12.0 s missing)", "the end gives its length and the audio lost")
    check(r.update(reading(26, missing = 48000 * 12)).isEmpty(), "the end is logged once")

    println("Durations: adaptive units at three significant figures")
    for ((ns, want) in listOf(70.0 to "70 ns", 3_000.0 to "3.00 µs", 38_400.0 to "38.4 µs", 800_000.0 to "800 µs", 3_200_000.0 to "3.20 ms", 1_400_000_000.0 to "1.40 s", -5_000_000.0 to "5.00 ms", Double.NaN to "—")) {
        check(fmtDuration(ns) == want, "$ns ns reads as ${fmtDuration(ns)}")
    }
    check(fmtSigned(-1_200_000.0) == "−1.20 ms" && fmtSigned(3_000.0) == "+3.00 µs", "signed durations show their sign")
    val gpsErr = EventDetector().update(Reading(s, null, Source.GPS, 70.0, 0L, 0, 0, 0L, null, 0, null, null, null))
    check(gpsErr.single().text == "Time source: GPS (±70 ns)", "a GPS error no longer reads as ±0.0 ms")

    println("GPS: a report with no real date is refused")
    check(!gpsDateValid(315_964_002_822_084_299L + 793_181_674_796L), "the Pixel's first report, which reads as 1980, is refused")
    check(gpsDateValid(1_793_626_846_987_839_256L + 827_012_160_925L), "its first good report is accepted")

    println("Event log: a failure logs each distinct message once")
    val fd = EventDetector()
    fun ntpFail(t: Long, error: String?) =
        fd.update(Reading(t * s, null, null, Double.NaN, 0L, 0, 0, 0L, null, 0, error, null, null)).filter { it.text.startsWith("NTP") }.map { it.text }
    check(ntpFail(1, "ntp1: timed out") == listOf("NTP failing: ntp1: timed out"), "the first error logs as failing")
    check(ntpFail(17, "ntp2: timed out") == listOf("NTP still failing: ntp2: timed out"), "a new message logs as still failing")
    check(ntpFail(33, "ntp1: timed out").isEmpty() && ntpFail(49, "ntp2: timed out").isEmpty(), "cycling back through the servers logs nothing")
    check((0 until 20).sumOf { ntpFail(65L + it, "varying $it").size } == 6, "messages that vary each time stop at the cap")
    check(ntpFail(100, null).single().startsWith("NTP working again"), "recovery logs once")
    check(ntpFail(101, "ntp2: timed out") == listOf("NTP failing: ntp2: timed out"), "a new failure starts afresh")

    println("Event log: tightening needs a real, lasting improvement")
    val td = EventDetector()
    fun tight(t: Long, err: Double, sats: Int = 9) =
        td.update(Reading(t * s, null, Source.GPS, err, t * s, 0, 0, 0L, null, 0, null, null, null, sats)).filter { it.text.startsWith("Sync") }
    check(tight(200, 3.2e6).isEmpty(), "the first estimate is only a baseline")
    check(tight(201, 0.8e6).isEmpty() && td.tighteningFromNs.isNaN(), "an improvement doesn't show while it's being confirmed")
    check(tight(205, 0.8e6).isEmpty(), "and isn't logged before 10 s")
    check(tight(211, 0.8e6).single().text == "Sync tightened from ±3.20 ms to ±800 µs, 9 satellites", "after 10 s it's logged once, with the figures")
    check(td.tighteningFromNs == 3.2e6, "and the screens show it from then, with the figure it improved on")
    check(tight(212, 0.8e6).isEmpty() && td.tighteningFromNs == 3.2e6, "it isn't repeated, and still shows")
    check(tight(213, 0.75e6, sats = 14).isEmpty() && tight(230, 0.75e6, sats = 14).isEmpty(), "more satellites with little improvement log nothing")
    check(td.tighteningFromNs.isNaN(), "and the screens stop showing it after 10 s")
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

/**
 * Plays [seconds] of stream after an outage that left the buffer empty, on a link where each fetch takes [fetchS].
 * Returns (seconds until the first sound, the share of the stream heard). Fetches one at a time, as StreamClient does.
 */
fun recovery(fetchS: Double, seconds: Int, skipAhead: Boolean): Pair<Double, Double> {
    val arrived = HashMap<Long, Double>()
    var t = 0.0
    while (t < seconds) {
        val heard = kotlin.math.floor(t).toLong()
        val useful = if (skipAhead) kotlin.math.floor(t + 1.5 * fetchS).toLong() else heard - 1
        val n = FetchPlan.next(heard - 1, heard + 60, useful) { arrived.containsKey(it) } ?: break
        t += fetchS
        arrived[n] = t
    }
    var heardS = 0.0
    var firstSound = Double.NaN
    for (n in 0 until seconds.toLong()) {
        val at = arrived[n] ?: continue
        val part = (n + 1 - maxOf(n.toDouble(), at)).coerceAtLeast(0.0)
        if (part > 0 && firstSound.isNaN()) firstSound = maxOf(n.toDouble(), at)
        heardS += part
    }
    return firstSound to heardS / seconds
}

fun testFetchPlan() {
    println("Fetching: skip what can't arrive in time, so the audio comes back soon after the link does")
    val have = setOf(10L, 11L, 12L, 14L)
    check(FetchPlan.next(9, 20, 10) { it in have } == 13L, "fills the first hole it can still reach")
    check(FetchPlan.next(9, 20, 15) { it in have } == 15L, "skips holes that will have passed")
    check(FetchPlan.next(9, 15, 15) { it in have + 15 } == 9L, "then goes back for the rest of the window")
    check(FetchPlan.next(10, 12, 10) { it in have } == null, "nothing to do when the window is full")

    val (oldFirst, _) = recovery(0.9, 300, skipAhead = false)
    val (newFirst, newShare) = recovery(0.9, 300, skipAhead = true)
    println("         link at 0.9 s a segment: sound after %.1f s, was %.1f s".format(newFirst, oldFirst))
    check(newFirst <= 3.0, "a link just above real time is heard again within 3 s")
    check(oldFirst > 5.0, "starting from the second being heard took far longer (why this exists)")
    check(newShare > 0.95, "and then plays with almost nothing missing")
    val (slowFirst, slowShare) = recovery(1.1, 300, skipAhead = true)
    val (_, oldSlowShare) = recovery(1.1, 300, skipAhead = false)
    println("         link at 1.1 s a segment: heard %.0f%%, was %.0f%%".format(slowShare * 100, oldSlowShare * 100))
    check(slowFirst <= 3.0 && slowShare > 0.75, "a link below real time still plays most of the stream")
    check(oldSlowShare < 0.05, "where starting from the second being heard played almost nothing")
}

fun testLibrary() {
    println("Library")
    check(tooFastNote(5_000_000L, 5.6).contains("0.1% rate limit") && tooFastNote(-5_000_000L, 5.6).contains("5.6 s"), "the rate limit note, with its percent sign, doesn't crash")
    check(gapWord(5L) == "Dragging" && gapWord(-5L) == "Rushing" && gapWord(0L) == "In step", "a held gap means dragging, its opposite rushing")
    check(nudgeWord(0.001) == "Catching-up" && nudgeWord(-0.001) == "Slowing-down", "a faster nudge is catching-up, a slower one slowing-down")
    val a = LibraryItem("a", "A", "", 3, 0, ""); val b = LibraryItem("b", "B", "", 2, 0, "")
    val e = Edition(1000L, listOf(a, b))
    check(e.at(0)!!.first === a && e.at(0)!!.second == 0L && e.at(2)!!.second == 2L, "segments fall in the first set")
    check(e.at(3)!!.first === b && e.at(3)!!.second == 0L, "the next set starts straight after")
    check(e.at(5)!!.first === a && e.at(5)!!.second == 0L && e.at(-1)!!.first === b, "and the library loops")
    check(e.dueOrder(3).map { it.id } == listOf("b", "a"), "what plays soonest downloads first")
    val next = Edition(5000L, listOf(b))
    check(editionAt(listOf(e, next), 4999L) === e && editionAt(listOf(e, next), 5000L) === next, "a new edition takes over exactly on time")
    check(editionAt(listOf(next), 10L) === next && editionAt(emptyList(), 10L) == null, "before the first edition starts, it's still the one to prepare")
    check(Edition(0L, emptyList()).at(4) == null, "an empty library has nothing to play")
    // Ogg pages with a packet running across two of them.
    fun page(body: ByteArray, lacing: List<Int>) = "OggS".toByteArray() + ByteArray(22) + byteArrayOf(lacing.size.toByte()) + lacing.map { it.toByte() }.toByteArray() + body
    val tmp = java.io.File.createTempFile("opus", ".ogg")
    try {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val tags = "OpusTags".toByteArray()
        val big = ByteArray(300) { 7 }
        tmp.writeBytes(page(head + tags, listOf(head.size, tags.size)) + page(big.copyOf(255), listOf(255)) + page(big.copyOfRange(255, 300) + byteArrayOf(9), listOf(45, 1)))
        OpusFile(tmp).use { f ->
            val spans = OpusBlock.spans(f.block(0))
            check(spans != null && spans[0].second == 300 && spans[1].second == 1 && spans[2].second == 3, "a packet across two pages joins up, and silence fills the block")
            check(OpusBlock.spans(f.block(5)) != null, "past the end of the file there's still a whole block")
        }
    } finally { tmp.delete() }
}

fun testOpusBlock() {
    println("Opus blocks")
    val out = java.io.ByteArrayOutputStream()
    for (i in 0 until OpusBlock.PACKETS) { val size = 3 + i; out.write(size and 255); out.write(size shr 8); repeat(size) { out.write(i) } }
    val block = out.toByteArray()
    val spans = OpusBlock.spans(block)
    check(spans != null && spans.size == OpusBlock.PACKETS && spans[7].second == 10 && block[spans[7].first].toInt() == 7, "a block splits into its packets")
    check(OpusBlock.spans(block.copyOf(block.size - 1)) == null, "a cut-off block is refused")
    check(OpusBlock.spans(block + byteArrayOf(1, 0, 9)) == null, "a block with too many packets is refused")
    val head = OpusBlock.head()
    check(String(head, 0, 8) == "OpusHead" && head.size == 19 && head[9].toInt() == 2 && head[10].toInt() == 0 && head[11].toInt() == 0, "the decoder's header has stereo and no skipped samples")
}

fun testEarcupAnalysis() {
    println("Earcup analysis")
    val rnd = Random(7)
    val click = Earcup.click()
    fun recording(lagMs: Double, gain: Float, noise: Float, wobbleMs: Double = 0.0): Pair<FloatArray, LongArray> {
        val rec = FloatArray(Earcup.RATE * 7) { (rnd.nextFloat() - 0.5f) * 2 * noise }
        val expected = LongArray(Earcup.CLICKS) { (Earcup.RATE + it * Earcup.SPACING_S * Earcup.RATE).toLong() }
        for ((i, e) in expected.withIndex()) {
            val wobble = if (i % 2 == 0) wobbleMs else -wobbleMs
            val at = e.toInt() + ((lagMs + wobble) * Earcup.RATE / 1000).toInt()
            for (j in click.indices) rec[at + j] += click[j] * gain
        }
        return rec to expected
    }
    fun leg(lagMs: Double, gain: Float = 0.5f, noise: Float = 0.002f, wobbleMs: Double = 0.0): EarcupLeg? {
        val (rec, expected) = recording(lagMs, gain, noise, wobbleMs)
        return analyseLeg(rec, expected, click)
    }
    val pass = earcupVerdict(leg(40.0), leg(220.0))
    check(pass is EarcupOutcome.Pass && abs(pass.laterMs - 180.0) < 0.1, "finds how much later the headphones are: ${(pass as? EarcupOutcome.Pass)?.laterMs}")
    val quiet = earcupVerdict(leg(40.0, 0.002f, 0.0005f), leg(220.0, 0.002f, 0.0005f))
    check(quiet is EarcupOutcome.Fail && quiet.fault == EarcupFault.TOO_QUIET, "a quiet recording says too quiet")
    val noisy = earcupVerdict(leg(40.0, 0.05f, 0.6f), leg(220.0, 0.05f, 0.6f))
    check(noisy is EarcupOutcome.Fail && noisy.fault == EarcupFault.TOO_NOISY, "a click lost in noise says too noisy: ${(noisy as? EarcupOutcome.Fail)?.fault ?: "passed"}")
    val wobbly = earcupVerdict(leg(40.0), leg(220.0, wobbleMs = 4.0))
    check(wobbly is EarcupOutcome.Fail && wobbly.fault == EarcupFault.DISAGREE, "clicks that won't hold still say readings disagree")
    check(earcupVerdict(null, leg(220.0)) is EarcupOutcome.Fail, "a leg with nothing to search gives no value")
}

fun testCalibration() {
    println("Calibration arithmetic")
    fun near(a: Double, b: Double) = abs(a - b) < 1e-9
    fun steady(ms: Double) = summarise(DoubleArray(9) { ms + (it - 4) * 0.01 })

    // Output latencies and mic latencies the test knows, and the handshake isn't told.
    val outA = 45.0; val outB = 25.0; val micA = 80.0; val micB = 33.0; val dist = 1.2
    val atA = steady((outA + micA) - (outB + dist + micA))
    val atB = steady((outA + dist + micB) - (outB + micB))
    val h = handshake(atA, atB, 0.2)!!
    check(near(h.laterMs, 20.0), "handshake finds the output difference, whatever the mics and distance: ${h.laterMs}")
    check(near(h.distanceMs, dist), "handshake also recovers the distance: ${h.distanceMs}")
    check(near(lagFromHandshake(1.5, h, 7L).lagMs, 21.5), "a phone that's later gets more lag, on top of its current lag")
    check(handshake(null, atB, 0.2) == null, "a phone that heard nothing gives no handshake")
    check(handshake(summarise(doubleArrayOf(20.0, 20.0)), atB, 0.2) == null, "too few readings give no handshake")
    check(handshake(summarise(DoubleArray(9) { 20.0 + it * 3 }), atB, 0.2) == null, "scattered readings give no handshake")
    check(handshake(steady(0.0), steady(40.0), 0.2) == null, "phones too far apart give no handshake")

    val speaker = Calibration(20.0, 3.0, CalSource.TABLE)
    val cup = lagFromEarcup(speaker, steady(100.0), steady(250.0), 9L)!!
    check(near(cup.lagMs, 170.0) && cup.source == CalSource.EARCUP, "earcup adds the headphones' extra delay to the speaker's value: ${cup.lagMs}")
    check(cup.plusMinusMs > speaker.plusMinusMs, "earcup's ± includes the speaker value's error")
    check(lagFromEarcup(speaker, null, steady(250.0), 9L) == null, "an earcup test that heard nothing gives no value")

    check(LatencyTable.lookup(RouteKey("SM-A166B", "speaker")).source == CalSource.TABLE, "a listed route comes from the table")
    val unknown = LatencyTable.lookup(RouteKey("SM-A166B", "Bluetooth"))
    check(unknown.source == CalSource.NONE && unknown.lagMs == 0.0 && unknown.plusMinusMs >= 100.0, "an unlisted route claims nothing")
    check(chooseCalibration(cup, speaker) == cup && chooseCalibration(null, speaker) == speaker, "a saved value beats the table")

    check(handshakeCode(-20.73) == "-0207" && parseHandshakeCode("-0207") == -20.7, "the short code carries a negative value to 0.1 ms")
    check(handshakeCode(1000.0) == null, "a value too large for the code gives no code")
    check(parseHandshakeCode("12a4") == null && parseHandshakeCode("123") == null, "a mistyped code is refused")
}

/** GPS against internet time: only a gap beyond internet time's own error counts, and only after 10 s. */
fun testClockCheck() {
    println("Clock check (GPS against internet time)")
    val s = 1_000_000_000L
    fun reading(t: Long, diffMs: Double, ntpErrMs: Double) =
        Reading(t * s, null, Source.GPS, 2e5, t * s, 0, 0, 0L, null, 0, null, null, null, 0, diffMs * 1e6, ntpErrMs * 1e6)
    val a = EventDetector()
    val noisy = (1..40L).flatMap { a.update(reading(it, if (it % 2 == 0L) 70.0 else -60.0, 150.0)) }.filter { it.text.startsWith("Clock check") }
    check(!a.clockCheckFailed && noisy.isEmpty(), "normal internet-time noise (±70 ms against ±150 ms) never fails the check")
    val b = EventDetector()
    val early = (1..6L).flatMap { b.update(reading(it, 1000.0, 150.0)) }.filter { it.text.startsWith("Clock check") }
    check(!b.clockCheckFailed && early.isEmpty(), "a 1 s gap isn't flagged until it has lasted 10 s")
    val later = (7..20L).flatMap { b.update(reading(it, 1000.0, 150.0)) }.filter { it.text.startsWith("Clock check") }
    check(b.clockCheckFailed && later.size == 1 && later[0].text.contains("1.00 s"), "a 1 s gap that lasts fails the check, with one event naming the size")
    val back = (21..40L).flatMap { b.update(reading(it, -44.0, 260.0)) }.filter { it.text.startsWith("Clock check passed") }
    check(!b.clockCheckFailed && back.size == 1, "and passes again, once, when the clocks agree for 10 s")
    val c = EventDetector()
    (1..30L).forEach { c.update(reading(it, 120.0, 400.0)) }
    check(!c.clockCheckFailed, "a gap inside internet time's own error (120 ms against ±400 ms) is not a failure")
    val d = EventDetector()
    (1..30L).forEach { d.update(Reading(it * s, null, Source.GPS, 2e5, it * s, 0, 0, 0L, null, 0, null, null, null)) }
    check(!d.clockCheckFailed, "with no internet-time reading there is nothing to check against")
}

fun main() {
    testLibrary()
    testOpusBlock()
    testEarcupAnalysis()
    testCalibration()
    testFetchPlan()
    testTickPattern()
    testSoloGate()
    testNtp(); testGps(); testGpsWeighting(); testGpsEstimate(); testNtpFit(); testPlayout(); testTickTiming(); testChannel(); testEvents(); testClockCheck(); testNudge()
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
}
