package org.beachsync.proto.ui

import android.app.Activity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Renderer
import org.beachsync.proto.monoToBootNs
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * Hand-off 31: the camera test card. A third phone films two phones side by side; each flashes its whole screen white on each beat (or each flowtime
 * second) and, in Every second mode, clicks at the same moment through the app's own tick (Left 2 kHz, Right 3 kHz). Between flashes nothing on screen
 * changes. Every flash is logged with the display frame it landed on (testcard-*.csv in the app's files), so the video analysis can remove frame steps.
 * It reads flowtime and the beat grid only; it never touches the audio thread.
 */

class TestCardSetup(val label: String, val channel: Int, val flashOn: Int, val click: Boolean)   // flashOn: 0 every beat, 1 beat 1 only, 2 every second

private val CARD_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.of("Europe/London"))

@Composable
fun TestCardSetupScreen(state: BenchState, actions: BenchActions, back: () -> Unit, start: (TestCardSetup) -> Unit) {
    var label by remember { mutableIntStateOf(0) }
    var channel by remember { mutableIntStateOf(listOf(Renderer.LEFT, Renderer.BOTH, Renderer.RIGHT).indexOf(state.channel).coerceAtLeast(1)) }
    var flashOn by remember { mutableIntStateOf(2) }
    var click by remember { mutableStateOf(true) }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { ScreenPrefs.load(ctx) }
    val labels = listOf("L", "R", "A", "B", "Name")
    Screen {
        ScreenHeader("Camera test card", back)
        BasicText("A full-screen card for a third phone to film, side by side with another phone, to time each phone's screen flash and sound.", style = Tokens.Text.body.style(Ink.textSecondary))
        Card {
            BasicText("LABEL ON THE CARD", style = Tokens.Text.cardLabel.style())
            Choice(labels, label, { label = it })
            BasicText("CHANNEL", style = Tokens.Text.cardLabel.style())
            Choice(listOf("Left", "Stereo", "Right"), channel, { channel = it })
            BasicText("FLASH ON", style = Tokens.Text.cardLabel.style())
            Choice(listOf("Every beat", "Beat 1 only", "Every second"), flashOn, { flashOn = it })
            SwitchRow("Click", if (flashOn == 2) "A 10 ms click at each flash: Left 2 kHz, Right 3 kHz" else "With music, the kick drum is the sound to time; no click", click && flashOn == 2) { click = it }
        }
        Card {
            BasicText("BRIGHT FLASHES", style = Tokens.Text.cardLabel.style(Ink.warn))
            BasicText("The whole screen flashes white up to 3 times a second. Don't use it if flashing light affects you." +
                if (ScreenPrefs.noFlashing) " No flashing is on: starting turns it off for the test, and it comes back on when the card closes." else "", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Card {
            BasicText("FILMING", style = Tokens.Text.cardLabel.style())
            BasicText("Both phones side by side, flat, same distance from the camera. Film at the highest frame rate (slow motion if the camera has it), held still, for 60 s. Music modes need the music playing first; Every second plays clicks, so only when you're ready for sound.", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Row { PillButton("Start test card", true) {
            val c = listOf(Renderer.LEFT, Renderer.BOTH, Renderer.RIGHT)[channel]
            start(TestCardSetup(if (label == 4) DemoMesh.NYM else labels[label], c, flashOn, click && flashOn == 2))
        } }
    }
}

@Composable
fun TestCardScreen(state: BenchState, actions: BenchActions, setup: TestCardSetup, close: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    // Full brightness, no system bars, No flashing off for the test; all put back on close.
    DisposableEffect(Unit) {
        val w = activity?.window
        val oldBrightness = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        w?.insetsController?.hide(WindowInsets.Type.systemBars())
        val wasNoFlashing = ScreenPrefs.noFlashing
        if (wasNoFlashing) ScreenPrefs.set(ctx, noFlashing = false)
        actions.setChannel(setup.channel)
        onDispose {
            w?.attributes = w?.attributes?.apply { screenBrightness = oldBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            w?.insetsController?.show(WindowInsets.Type.systemBars())
            if (wasNoFlashing) ScreenPrefs.set(ctx, noFlashing = true)
        }
    }
    // Every second with a click: a tick run (the app's per-second click), at the channel's pitch. It ends when the card closes.
    DisposableEffect(setup.click) {
        val before = Triple(state.stream, state.pitch, state.session != null)
        if (setup.click) {
            if (state.session != null) actions.startOrStop()
            state.stream = false
            state.pitch = if (setup.channel == Renderer.RIGHT) "3000" else "2000"
            actions.listen()
        }
        onDispose {
            if (setup.click) {
                if (state.session != null) actions.startOrStop()
                state.stream = before.first; state.pitch = before.second
            }
        }
    }
    BackHandler { close() }
    val monoToBoot = remember { monoToBootNs() }
    val log = remember { File(ctx.getExternalFilesDir(null), "testcard-${System.currentTimeMillis()}.csv").apply { writeText("event_utc_ns,frame_utc_ns,frame_boot_ns,label,channel,mode\n") } }
    var lit by remember { mutableIntStateOf(0) }            // frames left to show white
    var shownUtc by remember { mutableLongStateOf(0L) }      // flowtime of the last flash
    var beatLine by remember { mutableStateOf("waiting for the first flash") }
    var lastEvent by remember { mutableLongStateOf(0L) }
    var sync by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(setup) {
        while (true) withFrameNanos { frame ->
            if (lit > 0) lit--
            val fit = FlowTime.fit() ?: return@withFrameNanos
            val boot = frame + monoToBoot
            val utc = fit.utcAt(boot)
            val s = state.session
            sync = s?.syncErrorNs(boot)?.takeIf { !it.isNaN() && it > 5e6 }?.let { "OUT OF SYNC ±${(it / 1e6).toInt()} ms" }
            // The most recent event at or before this frame: a flowtime second, or a beat from the playing set's grid.
            var event = Long.MIN_VALUE; var line = "NO BEAT · SECOND"
            if (setup.flashOn == 2) event = Math.floorDiv(utc, 1_000_000_000L) * 1_000_000_000L
            else {
                val m = s?.stream?.manifest; val lib = state.library
                if (s != null && s.s.library && m != null && lib != null) {
                    val pos = (utc - m.startNs - m.dMs * 1_000_000L).toDouble() * 48000.0 / 1e9
                    libraryBeat(lib, utc, pos)?.let { p ->
                        val every = if (p.bpm > 180) 2 else 1   // never more than 3 flashes a second
                        val beatIndex = p.bar * 4 + p.beatInBar
                        val wanted = if (setup.flashOn == 1) p.beatInBar == 0 else beatIndex % every == 0L
                        if (wanted) { event = utc - (p.sinceBeatSamples / 48000.0 * 1e9).toLong(); line = "BEAT ${p.beatInBar + 1} · BAR ${p.bar + 1}" }
                    }
                }
            }
            if (event != Long.MIN_VALUE && event > lastEvent && utc - event < 100_000_000L) {
                lastEvent = event; shownUtc = event; beatLine = line; lit = 2
                val row = "$event,$utc,$boot,${setup.label},${setup.channel},${setup.flashOn}\n"
                scope.launch(Dispatchers.IO) { log.appendText(row) }
            }
        }
    }
    val flash = lit > 0
    val bg = if (flash) Color.White else Color.Black
    val fg = if (flash) Color.Black else Color.White
    val second = Math.floorMod(Math.floorDiv(shownUtc, 1_000_000_000L), 256L).toInt()
    val bits = (7 downTo 0).map { (second shr it) and 1 }
    Box(
        Modifier.fillMaxSize().background(bg).pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                val up = withTimeoutOrNull(2000) { waitForUpOrCancellation() }
                if (up == null) close()
            }
        },
    ) {
        BasicText("${listOf("STEREO", "LEFT", "RIGHT")[setup.channel]}${if (setup.click) " · CLICK ${if (setup.channel == Renderer.RIGHT) 3 else 2} kHz" else ""}",
            Modifier.align(Alignment.TopStart).padding(16.dp), Tokens.Text.valueSmall.style(fg.copy(alpha = 0.4f)).copy(fontSize = 11.sp))
        BasicText(sync ?: "${DemoMesh.NYM.lowercase()}", Modifier.align(Alignment.TopEnd).padding(16.dp),
            Tokens.Text.valueSmall.style(if (sync != null) Ink.warn else fg.copy(alpha = 0.4f)).copy(fontSize = 11.sp))
        Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(setup.label, style = Tokens.Text.screenTitle.style(fg).copy(fontSize = if (setup.label.length <= 2) 240.sp else 40.sp, fontWeight = FontWeight.Bold))
            BasicText(if (shownUtc == 0L) "--:--:--.---" else CARD_CLOCK.format(Instant.ofEpochSecond(0, shownUtc)), style = Tokens.Text.value.style(fg).copy(fontSize = 40.sp))
            BasicText("FLOWTIME · UK · $beatLine", style = Tokens.Text.cardLabel.style(fg.copy(alpha = 0.7f)))
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Two references (on, off), then the second mod 256, most significant bit first.
                Box(Modifier.size(28.dp).background(fg))
                Box(Modifier.size(28.dp).background(bg).then(Modifier.background(bg)))
                for (b in bits) Box(Modifier.size(28.dp).background(if (b == 1) fg else bg).then(if (b == 0) Modifier.background(bg) else Modifier))
            }
            BasicText("CODE $second · ${bits.joinToString("")} · second mod 256", Modifier.padding(top = 6.dp), Tokens.Text.valueSmall.style(fg.copy(alpha = 0.6f)).copy(fontSize = 11.sp))
            Spacer(Modifier.weight(1f))
            BasicText("Hold 2 s anywhere to leave", Modifier.padding(bottom = 24.dp), Tokens.Text.small.style(fg.copy(alpha = 0.4f)))
        }
    }
}
