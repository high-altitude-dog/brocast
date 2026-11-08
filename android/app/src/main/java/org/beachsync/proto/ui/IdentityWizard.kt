package org.beachsync.proto.ui

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Hand-off 24: the first-run identity wizard, on DEMO DATA (user, 7 Nov). No key is made and nothing is stored: the engine's makeSeed isn't in the app yet.
 * Real here: which sensors this phone has (the list), Privacy mode, the scribble pad and FLAG_SECURE on the words. Made up: byte counts, the nym, the key,
 * the words and the dropped-sensor reasons. Opened from Settings › Your identity, so it can be seen again; it doesn't run on first start yet.
 */

private class Src(val name: String, val id: String, val bytes: Int, val dropped: String? = null, val off: String? = null)

private val SENSORS = listOf(
    Triple(Sensor.TYPE_ACCELEROMETER, "Accelerometer", 31700), Triple(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, "Accelerometer, raw", 47500),
    Triple(Sensor.TYPE_GYROSCOPE, "Gyroscope", 31700), Triple(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, "Gyroscope, raw", 47500),
    Triple(Sensor.TYPE_MAGNETIC_FIELD, "Magnetometer", 7900), Triple(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, "Magnetometer, raw", 11900),
    Triple(Sensor.TYPE_PRESSURE, "Air pressure", 1200), Triple(Sensor.TYPE_LIGHT, "Light", 600), Triple(Sensor.TYPE_GRAVITY, "Gravity", 15800),
    Triple(Sensor.TYPE_LINEAR_ACCELERATION, "Linear acceleration", 15800), Triple(Sensor.TYPE_ROTATION_VECTOR, "Rotation", 21100),
    Triple(Sensor.TYPE_PROXIMITY, "Proximity", 0), Triple(Sensor.TYPE_STEP_DETECTOR, "Step detector", 0),
)

private fun sensorId(name: String) = "sensor-" + name.lowercase().replace(", raw", "-uncalibrated").replace(' ', '-')

private val MACHINE = listOf(
    Src("Engine's own generator", "engine-os", 32), Src("Android's secure generator", "host-csprng", 64),
    Src("Hardware security chip", "keystore-hmac · StrongBox", 64), Src("Processor timing jitter", "cpu-jitter", 4100),
)

private val NYMS = listOf(
    "Amber Fig Heron" to 0xFFE8A640, "Teal Plum Otter" to 0xFF3FB6A8, "Rose Lime Wren" to 0xFFE07A9A,
    "Jade Kiwi Lynx" to 0xFF5FBF6A, "Coral Date Finch" to 0xFFF07F5F,
)

private val WORDS = listOf(
    "harbor", "violet", "tundra", "mosaic", "lantern", "orbit", "cedar", "ripple", "falcon", "quartz", "meadow", "ember",
    "canyon", "pixel", "saffron", "drift", "willow", "comet", "basalt", "tango", "glacier", "nectar", "rumba", "atlas",
)

private val STEP_NAMES = listOf("WELCOME", "GATHER", "ADD YOURS", "MIX", "YOUR NAME", "BACK UP")

private fun bytesText(b: Int) = if (b < 1000) "$b B" else "%.1f KB".format(b / 1000.0)

@Composable
fun IdentityWizard(privacy: Boolean, close: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val sensors = remember {
        val sm = context.getSystemService(SensorManager::class.java)
        SENSORS.filter { sm.getDefaultSensor(it.first) != null }.map { (type, name, bytes) ->
            when (type) {
                Sensor.TYPE_PROXIMITY -> Src(name, sensorId(name), 0, dropped = "stuck at 5.0 cm")
                Sensor.TYPE_STEP_DETECTOR -> Src(name, sensorId(name), 0, dropped = "no events")
                else -> Src(name, sensorId(name), bytes)
            }
        }
    }
    val other = remember(privacy) {
        listOf(
            Src("Battery current and voltage", "battery-noise", 2300),
            if (privacy) Src("Satellite raw signals", "gnss-raw", 0, off = "not used: Privacy mode") else Src("Satellite raw signals", "gnss-raw · Use satellites is on", 18400),
            if (privacy) Src("Wi-Fi signal strengths", "wifi-rssi", 0, off = "not used: Privacy mode") else Src("Wi-Fi signal strengths", "wifi-rssi", 6200),
            if (privacy) Src("Bluetooth signal strengths", "bt-rssi", 0, off = "not used: Privacy mode") else Src("Bluetooth signal strengths", "bt-rssi", 3000),
        )
    }
    var extras by remember { mutableStateOf(listOf<Src>()) }
    var touches by remember { mutableIntStateOf(0) }
    var nym by remember { mutableIntStateOf(0) }
    BackHandler { if (step == 0) close() else step-- }
    val all = MACHINE + sensors + other + extras
    when (step) {
        0 -> Welcome(all.count { it.dropped == null && it.off == null }) { step = 1 }
        1 -> Gather(sensors, other, extras, { extras = it }) { step = 2 }
        2 -> AddYours({ touches = it }, { touches = 0; step = 3 }) { step = 3 }
        3 -> Mix(all, touches) { step = 4 }
        4 -> Nym(nym, { nym = (nym + 1) % NYMS.size }) { step = 5 }
        else -> Backup(close)
    }
}

@Composable
private fun WizardPage(step: Int, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().padding(horizontal = Tokens.Space.screenPaddingSide.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0..5) Box(Modifier.weight(1f).height(3.dp).background(if (i < step) Ink.accent else if (i == step) Ink.text else Ink.track, RoundedCornerShape(2.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 8.dp)) {
            BasicText("YOUR IDENTITY · ${step + 1} OF 6", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            BasicText(STEP_NAMES[step], style = Tokens.Text.cardLabel.style())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DemoBanner("No key is made yet. Names, words and byte counts are made up until the engine is in the app.")
            content()
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { buttons() }
    }
}

@Composable
private fun Title(text: String) = BasicText(text, style = Tokens.Text.screenTitle.style().copy(fontSize = 28.sp))

@Composable
private fun Lead(text: String) = BasicText(text, style = Tokens.Text.body.style(Ink.textSecondary).copy(fontSize = 16.sp))

@Composable
private fun RowScope.Primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Box(
        Modifier.weight(1f).heightIn(min = 56.dp).background(if (enabled) Ink.accent else Ink.surface, shape).clickable(enabled, role = Role.Button, onClick = onClick),
        Alignment.Center,
    ) { BasicText(text, style = Tokens.Text.bodyStrong.style(if (enabled) Ink.onAccent else Ink.muted).copy(fontSize = 17.sp)) }
}

@Composable
private fun Outline(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        Modifier.heightIn(min = 56.dp).border(Tokens.Stroke.emphasis.dp, Ink.grid, RoundedCornerShape(Tokens.Radius.pill.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp).wrapContentHeight(),
        Tokens.Text.body.style().copy(fontSize = 17.sp),
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    BasicText(text, Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp), Tokens.Text.cardLabel.style(Ink.bg))
}

@Composable
private fun Tick(text: String) = Row {
    BasicText("✓", Modifier.width(28.dp), Tokens.Text.body.style(Ink.accent))
    BasicText(text, Modifier.weight(1f), Tokens.Text.body.style())
}

// ---- 1 Welcome ----

@Composable
private fun Welcome(sources: Int, next: () -> Unit) {
    var shown by remember { mutableIntStateOf(3) }
    LaunchedEffect(Unit) { while (shown < sources) { delay(400); shown++ } }
    WizardPage(0, { Primary("Start", onClick = next) }) {
        Canvas(Modifier.padding(vertical = 8.dp).size(56.dp)) {
            val w = 3.dp.toPx()
            drawCircle(Ink.accent, size.width * 0.22f, Offset(size.width * 0.3f, size.height * 0.5f), style = Stroke(w))
            drawLine(Ink.accent, Offset(size.width * 0.52f, size.height * 0.5f), Offset(size.width * 0.95f, size.height * 0.5f), w, StrokeCap.Round)
            drawLine(Ink.accent, Offset(size.width * 0.8f, size.height * 0.5f), Offset(size.width * 0.8f, size.height * 0.68f), w, StrokeCap.Round)
            drawLine(Ink.accent, Offset(size.width * 0.92f, size.height * 0.5f), Offset(size.width * 0.92f, size.height * 0.64f), w, StrokeCap.Round)
        }
        Title("Your phone makes its own identity.")
        Lead("No account, no email, no server. Your phone makes a key from its own randomness, and the key never leaves it.")
        Tick("Your name for other listeners comes from your key.")
        Tick("Everything you share is signed with it, so others know it's you.")
        Tick("It takes about 10 seconds.")
        BasicText("● Already gathering: $shown sources while you read", style = Tokens.Text.small.style(Ink.accent))
    }
}

// ---- 2 Gathering ----

@Composable
private fun Gather(sensors: List<Src>, other: List<Src>, extras: List<Src>, setExtras: (List<Src>) -> Unit, next: () -> Unit) {
    val rows = MACHINE + sensors + other + extras
    var done by remember { mutableIntStateOf(0) }
    LaunchedEffect(rows.size) { while (done < rows.size) { delay(220); done++ } }
    var askCamera by remember { mutableStateOf(false) }
    val usable = rows.filter { it.dropped == null && it.off == null }
    val gathered = rows.take(done)
    val bytes = gathered.filter { it.dropped == null && it.off == null }.sumOf { it.bytes }
    val dropped = gathered.count { it.dropped != null }
    Box(Modifier.fillMaxSize()) {
        WizardPage(1, { Primary("Next", done >= rows.size, next) }) {
            Title("Gathering randomness")
            Lead("Every source this phone has. You see each one's name and how much it gave, never what it gave.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    BasicText("${usable.size} sources", style = Tokens.Text.value.style().copy(fontSize = 30.sp))
                    BasicText("$dropped dropped · ${(rows.size - done).coerceAtLeast(0)} still gathering", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                Column(horizontalAlignment = Alignment.End) {
                    BasicText("${bytes / 1000} KB", style = Tokens.Text.value.style().copy(fontSize = 30.sp))
                    BasicText("raw noise, not key size", style = Tokens.Text.small.style(Ink.textSecondary))
                }
            }
            var index = 0
            for ((head, list) in listOf("MACHINE · ALWAYS USED" to MACHINE, "SENSORS · 3 s EACH, FASTEST RATE" to sensors, "OTHER" to other + extras)) {
                if (list.isEmpty()) continue
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    BasicText(head, Modifier.weight(1f), Tokens.Text.cardLabel.style())
                    BasicText("${list.count { it.dropped == null && it.off == null }} of ${list.size}", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                for (src in list) { SourceRow(src, index < done); index++ }
            }
            BasicText("Dropped means no noise: a sensor that reads the same value every time adds nothing, so it's left out and listed here. Your phone's list will differ.", style = Tokens.Text.small.style(Ink.textSecondary))
            if (extras.isEmpty()) Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Also use the camera and microphone?", style = Tokens.Text.bodyStrong.style())
                        BasicText("A dark photo with your finger over the lens, and 1 s of room noise. Asks permission; nothing is kept.", style = Tokens.Text.small.style(Ink.textSecondary))
                    }
                    Spacer(Modifier.width(8.dp))
                    Outline("Add") { askCamera = true }
                }
            }
        }
        if (askCamera) Sheet({ askCamera = false }) {
            BasicText("Camera and microphone", style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp))
            BasicText("Cover the lens with a finger: one dark photo's sensor noise is used. Then 1 s of room noise from the microphone, only its lowest bits. Nothing is kept, and your music isn't paused.", style = Tokens.Text.body.style(Ink.textSecondary))
            BasicText("Demo: no permission is asked and nothing is recorded.", style = Tokens.Text.small.style(Ink.warn))
            PillButton("Add both", true) {
                setExtras(listOf(Src("Camera, lens covered", "camera-dark", 24600), Src("Microphone, 1 s of room noise", "mic-noise", 9600)))
                askCamera = false
            }
            PillButton("Not now", false) { askCamera = false }
        }
    }
}

@Composable
private fun SourceRow(src: Src, done: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            when { !done -> "◌"; src.dropped != null -> "✕"; src.off != null -> "–"; else -> "✓" },
            Modifier.width(30.dp), Tokens.Text.body.style(if (done && src.dropped == null && src.off == null) Ink.accent else Ink.muted),
        )
        Column(Modifier.weight(1f)) {
            BasicText(src.name, style = Tokens.Text.body.style(if (src.dropped != null) Ink.muted else Ink.text).copy(textDecoration = if (src.dropped != null && done) TextDecoration.LineThrough else null))
            BasicText(src.id, style = Tokens.Text.valueSmall.style(Ink.muted).copy(fontSize = 10.sp))
        }
        BasicText(
            when { !done -> "gathering…"; src.dropped != null -> "dropped · ${src.dropped}"; src.off != null -> src.off; else -> bytesText(src.bytes) },
            style = Tokens.Text.valueSmall.style(if (done && src.dropped == null && src.off == null) Ink.text else Ink.textSecondary).copy(fontSize = 12.sp),
        )
    }
}

// ---- 3 Add yours ----

@Composable
private fun AddYours(setTouches: (Int) -> Unit, skip: () -> Unit, next: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    val trail = remember { mutableStateListOf<Offset>() }
    var count by remember { mutableIntStateOf(0) }
    WizardPage(2, {
        Outline("Skip", skip)
        Primary("Add it and mix") { setTouches(count); next() }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag("OPTIONAL", Ink.textSecondary)
            BasicText("  Your key is already safe without this", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Title("Add your own randomness")
        Lead("Some people like to put a bit of themselves in. Every wobble of your finger goes into the mix as an extra source.")
        Choice(listOf("Scribble", "Move the phone"), mode, { mode = it })
        val shape = RoundedCornerShape(Tokens.Radius.card.dp)
        if (mode == 0) Canvas(
            Modifier.fillMaxWidth().height(300.dp).drawBehindDashed(shape).pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    trail.add(change.position); count++
                    if (trail.size > 300) trail.removeAt(0)
                }
            },
        ) {
            for (i in 1 until trail.size) {
                val a = i.toFloat() / trail.size
                drawLine(Ink.accent.copy(alpha = 0.15f + 0.6f * a), trail[i - 1], trail[i], 2.dp.toPx(), StrokeCap.Round)
            }
            trail.lastOrNull()?.let { drawCircle(Ink.accent.copy(alpha = 0.25f), 16.dp.toPx(), it); drawCircle(Ink.accent, 16.dp.toPx(), it, style = Stroke(1.5.dp.toPx())) }
            if (trail.isEmpty()) monoText("Scribble anywhere in here", size.width / 2, size.height / 2, 12f, Ink.textSecondary)
        } else Box(Modifier.fillMaxWidth().height(300.dp).drawBehindDashed(shape), Alignment.Center) {
            BasicText("Wave the phone about.\nIn the demo this only shows the idea.", style = Tokens.Text.body.style(Ink.textSecondary).copy(textAlign = TextAlign.Center))
        }
        Row(Modifier.fillMaxWidth()) {
            BasicText("Added", Modifier.weight(1f), Tokens.Text.body.style())
            BasicText("$count touch points · ${bytesText(count * 12)}", style = Tokens.Text.valueSmall.style(Ink.accent))
        }
        BasicText("We don't count this in bits: how unpredictable a scribble is can't be measured, so the key never depends on it. Move the phone instead uses the motion sensors while you wave it about.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

private fun Modifier.drawBehindDashed(shape: RoundedCornerShape): Modifier = this.then(
    Modifier.background(Ink.bg, shape).border(1.dp, Ink.grid, shape),
)

// ---- 4 Mixing ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Mix(all: List<Src>, touches: Int, next: () -> Unit) {
    var how by remember { mutableStateOf(false) }
    val used = all.filter { it.off == null }
    val count = used.count { it.dropped == null } + if (touches > 0) 1 else 0
    WizardPage(3, { Primary("Meet your name", onClick = next) }) {
        Title("Mixing")
        Lead("All $count sources go through one hash into a 32-byte seed. Every key you'll use comes from that seed.")
        Card {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in used + if (touches > 0) listOf(Src("touch", "touch-jitter", touches * 12)) else emptyList()) {
                    val chip = s.id.substringBefore(' ').removePrefix("sensor-")
                    BasicText(
                        if (s.dropped != null) "$chip ×" else chip,
                        Modifier.alpha(if (s.dropped != null) 0.45f else 1f).border(1.dp, Ink.grid, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                        Tokens.Text.valueSmall.style().copy(fontSize = 11.sp),
                    )
                }
            }
            BasicText("↓", Modifier.fillMaxWidth(), Tokens.Text.body.style(Ink.accent).copy(textAlign = TextAlign.Center))
            Column(
                Modifier.fillMaxWidth().background(Ink.accent.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).border(1.5.dp, Ink.accent, RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText("Mixed with keyed BLAKE2b", style = Tokens.Text.bodyStrong.style())
                BasicText("each source tagged with its name and length", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            BasicText("↓", Modifier.fillMaxWidth(), Tokens.Text.body.style(Ink.accent).copy(textAlign = TextAlign.Center))
            Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                val cell = 12.dp.toPx(); val gap = 2.dp.toPx()
                val x0 = (size.width - 16 * (cell + gap)) / 2
                for (i in 0 until 32) drawRoundRect(Ink.accent, Offset(x0 + (i % 16) * (cell + gap), (i / 16) * (cell + gap)), androidx.compose.ui.geometry.Size(cell, cell * 0.8f), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            }
            BasicText("Your 32-byte seed", Modifier.fillMaxWidth(), Tokens.Text.bodyStrong.style().copy(textAlign = TextAlign.Center))
            BasicText("shown as cells, never as values", Modifier.fillMaxWidth(), Tokens.Text.small.style(Ink.textSecondary).copy(textAlign = TextAlign.Center))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((t, d) in listOf("Ed25519" to "identity, signing", "X25519" to "same key, for sealed messages", "Storage key" to "your music store", "Your name" to "the nym")) {
                    Column(Modifier.weight(1f).fillMaxHeight().border(1.dp, Ink.grid, RoundedCornerShape(10.dp)).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(t, style = Tokens.Text.bodyStrong.style().copy(fontSize = 13.sp, textAlign = TextAlign.Center))
                        BasicText(d, style = Tokens.Text.small.style(Ink.textSecondary).copy(fontSize = 11.sp, textAlign = TextAlign.Center))
                    }
                }
            }
        }
        Tick("Seed made and locked in the security chip (demo: nothing is stored)")
        Card {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { how = !how }, verticalAlignment = Alignment.CenterVertically) {
                BasicText("How this works", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
                BasicText(if (how) "︿" else "﹀", style = Tokens.Text.body.style(Ink.textSecondary))
            }
            if (how) for ((t, d) in listOf(
                "Strongest source wins" to "Mixing with a keyed hash means the seed is at least as unpredictable as the best single source. A weak or broken source can't make it worse; it can only be dropped.",
                "Never from you alone" to "Brocast refuses to make a key from your scribble alone (the \"brain wallet\" mistake), and stops if fewer than two machine sources work.",
                "Everything comes from the seed by hashing" to "Each key is a hash of the seed with its own label, so none of them can be worked back to the seed or to each other.",
                "Clamping, and why your seed isn't touched" to "Ed25519 and X25519 clear and set a few bits of a hash of the seed, as their standards require, to keep keys away from weak points on the curve. That's where their ~128-bit strength comes from, and it costs nothing. Brocast never clamps the seed itself, and never uses the library calls that would clamp a key twice.",
                "Stored" to "The seed is encrypted with a key held in the phone's security chip (StrongBox) and kept in Brocast's private files. Sensor readings were wiped after mixing; location-bearing ones in the same instant.",
            )) {
                BasicText(t, style = Tokens.Text.bodyStrong.style())
                BasicText(d, style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
    }
}

// ---- 5 Your name ----

@Composable
private fun Nym(index: Int, shuffle: () -> Unit, next: () -> Unit) {
    val (name, colour) = NYMS[index]
    WizardPage(4, { Primary("Keep this name", onClick = next) }) {
        Title("Meet your name")
        Column(
            Modifier.fillMaxWidth().border(1.dp, Ink.grid, RoundedCornerShape(20.dp)).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Canvas(Modifier.size(76.dp)) {
                drawCircle(Color(colour), size.width / 2)
                val p = Path().apply {
                    moveTo(size.width * 0.33f, size.height * 0.68f); quadraticTo(size.width * 0.45f, size.height * 0.4f, size.width * 0.5f, size.height * 0.32f)
                    quadraticTo(size.width * 0.58f, size.height * 0.45f, size.width * 0.67f, size.height * 0.68f)
                    moveTo(size.width * 0.5f, size.height * 0.32f); lineTo(size.width * 0.56f, size.height * 0.27f)
                }
                drawPath(p, Ink.bg, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            BasicText(name, style = Tokens.Text.screenTitle.style().copy(fontSize = 30.sp))
            BasicText("key e4a107c9…", style = Tokens.Text.valueSmall.style(Ink.textSecondary))
        }
        Lead("Other listeners see this name. It's made by signing a word list with your key, so every phone can check it's really yours and nobody else can show it.")
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("Not keen on it?", style = Tokens.Text.bodyStrong.style())
                    BasicText("Shuffle picks another from about a million. Your key stays the same.", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                Spacer(Modifier.width(8.dp))
                Outline("Shuffle", shuffle)
            }
        }
        BasicText("If two listeners ever show the same name, the first 4 digits of their keys are shown next to it.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

// ---- 6 Back up ----

@Composable
private fun Backup(close: () -> Unit) {
    var role by remember { mutableIntStateOf(0) }       // demo only: 0 listener, 1 seeder, 2 station publisher
    var words by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var skipArmed by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    // No screenshots while the words are on screen.
    DisposableEffect(words) {
        if (words) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val checks = remember { listOf(3, 10, 18) }
    val typed = remember { mutableStateListOf("", "", "") }
    val allRight = checks.indices.all { typed[it].trim().equals(WORDS[checks[it]], ignoreCase = true) }
    WizardPage(5, {
        when {
            checking -> Primary("Finish", allRight, close)
            words -> Primary("I wrote them down") { if (role == 2) checking = true else close() }
            role == 2 -> Primary("Show my words") { words = true }
            else -> {
                Outline(if (role == 1 && skipArmed) "Tap again to skip" else if (role == 1) "Skip" else "Skip for now") { if (role == 1 && !skipArmed) skipArmed = true else close() }
                Primary("Show my words") { words = true }
            }
        }
    }) {
        BasicText("DEMO: SHOW AS", style = Tokens.Text.cardLabel.style(Ink.warn))
        Choice(listOf("Listener", "Seeder", "Publisher"), role, { role = it; words = false; checking = false; skipArmed = false })
        when {
            checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Tag("REQUIRED", Ink.danger); BasicText("  station publisher", style = Tokens.Text.small.style(Ink.textSecondary)) }
                Title("Check your words · 2 of 2")
                Lead("Every listener's app trusts this key. Without a backup, losing it means a new station. There is no Skip.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    checks.forEachIndexed { i, w ->
                        val right = typed[i].trim().equals(WORDS[w], ignoreCase = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            BasicText("Word ${w + 1}", style = Tokens.Text.small.style(Ink.textSecondary))
                            BasicTextField(
                                typed[i], { typed[i] = it }, Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .border(1.5.dp, if (right) Ink.accent else Ink.grid, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 15.dp),
                                singleLine = true, textStyle = Tokens.Text.valueSmall.style().copy(fontSize = 15.sp), cursorBrush = SolidColor(Ink.accent),
                            )
                            if (right) BasicText("✓", style = Tokens.Text.small.style(Ink.accent))
                        }
                    }
                }
                BasicText("Three words picked at random. Finish when all three match.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            words -> {
                Title("Your recovery words")
                BasicText("BROCAST RECOVERY WORDS · NOT A CRYPTO WALLET", style = Tokens.Text.cardLabel.style(Ink.warn))
                for (r in 0 until 8) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        Row(Modifier.weight(1f).border(1.dp, Ink.grid, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText("${i + 1}", Modifier.width(22.dp), Tokens.Text.valueSmall.style(Ink.muted).copy(fontSize = 10.sp))
                            BasicText(WORDS[i], style = Tokens.Text.valueSmall.style().copy(fontSize = 15.sp))
                        }
                    }
                }
                BasicText("Write them on paper, in order. Anyone with these words can be you. Never type them into a website or a wallet app; Brocast only ever asks for them here, when you set up a new phone. Screenshots are blocked on this screen.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            role == 0 -> {
                Tag("OPTIONAL", Ink.textSecondary)
                Title("Back up your identity?")
                Lead("If you lose this phone without a backup, you only lose your name: a new phone makes a new one, and your music is unaffected. 24 words on paper let you keep it.")
            }
            role == 1 -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Tag("RECOMMENDED", Ink.warn); BasicText("  seeder", style = Tokens.Text.small.style(Ink.textSecondary)) }
                Title("Back up this seeder's key")
                Lead("Other phones know this computer by its key. Lose it and they treat a new one as a stranger until it earns trust again. Skip is still allowed, behind a second tap.")
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Tag("REQUIRED", Ink.danger); BasicText("  station publisher", style = Tokens.Text.small.style(Ink.textSecondary)) }
                Title("Back up the station key")
                Lead("Every listener's app trusts this key. Without a backup, losing it means a new station. You'll check three of the words next.")
            }
        }
    }
}
