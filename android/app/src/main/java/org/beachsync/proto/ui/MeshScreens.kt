package org.beachsync.proto.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Hand-offs 21 to 23: Data and sharing, its Advanced tuner, the Data log and Block provenance.
 * Everything on these screens is DEMO DATA until the Pears engine (docs/engine-api.md) runs in the app: the switches and
 * sliders keep their values for this app session only and change nothing. Every screen says so in its banner.
 */

// ---- Shared pieces: sheet, toast, choice, rows, step slider ----

private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }

internal fun DrawScope.monoText(text: String, x: Float, y: Float, sizeSp: Float, color: Color, align: Paint.Align = Paint.Align.CENTER) {
    drawIntoCanvas { c ->
        monoPaint.textSize = sizeSp.sp.toPx(); monoPaint.color = color.toArgb(); monoPaint.textAlign = align
        c.nativeCanvas.drawText(text, x, y, monoPaint)
    }
}

/** A bottom sheet over a dimmed screen. Tap outside, swipe the handle down or press Back to close. */
@Composable
internal fun Sheet(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val close by rememberUpdatedState(onClose)
    BackHandler { close() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { close() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.9f).background(Ink.surface, shape).border(Tokens.Stroke.card.dp, Ink.border, shape)
                .pointerInput(Unit) { detectTapGestures { } }.navigationBarsPadding(),
        ) {
            var dragged by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { dragged = 0f }, onDragEnd = { if (dragged > 60.dp.toPx()) close() }) { _, d -> dragged += d }
                },
                Alignment.Center,
            ) { Box(Modifier.size(40.dp, 4.dp).background(Ink.faint, RoundedCornerShape(2.dp))) }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
internal fun Toast(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).background(Ink.text, RoundedCornerShape(14.dp)).heightIn(min = 52.dp).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, Modifier.weight(1f), Tokens.Text.body.style(Ink.bg))
        if (action != null) BasicText(action, Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp).wrapContentHeight(), Tokens.Text.bodyStrong.style(Ink.bg))
    }
}

/** A full-width segmented control. [underline] marks the option Auto picked. */
@Composable
internal fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit, underline: Int? = null, enabled: Boolean = true) {
    val r = Tokens.Radius.control.dp
    val small = options.size > 3
    Row(Modifier.fillMaxWidth().border(Tokens.Stroke.control.dp, Ink.grid, RoundedCornerShape(r)).selectableGroup()) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            val last = i == options.lastIndex
            val shape = RoundedCornerShape(if (i == 0) r else 0.dp, if (last) r else 0.dp, if (last) r else 0.dp, if (i == 0) r else 0.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).background(if (on) Ink.text else Color.Transparent, shape)
                    .drawBehind {
                        if (i > 0) drawLine(Ink.grid, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                        if (underline == i && !on) drawLine(Ink.accent, Offset(4.dp.toPx(), size.height - 2.dp.toPx()), Offset(size.width - 4.dp.toPx(), size.height - 2.dp.toPx()), 3.dp.toPx())
                    }
                    .selectable(on, enabled, Role.RadioButton) { onSelect(i) },
                Alignment.Center,
            ) {
                BasicText(o, style = Tokens.Text.body.style(if (on) Ink.bg else Ink.text).copy(fontWeight = if (on) FontWeight.Bold else null, fontSize = if (small) 13.sp else 15.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun AmberDot() = Box(Modifier.padding(end = 6.dp).size(7.dp).background(Ink.warn, CircleShape))

@Composable
internal fun SwitchRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(on, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Spacer(Modifier.width(10.dp))
        SatellitesSwitch(on)
    }
}

@Composable
internal fun LinkRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Ink.surface, RoundedCornerShape(Tokens.Radius.card.dp))
            .border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.card.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, Modifier.weight(1f), Tokens.Text.body.style())
        BasicText("›", style = Tokens.Text.screenTitle.style(Ink.textSecondary))
    }
}

@Composable
internal fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    val base = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    Box(
        (if (filled) base.background(Ink.accent, shape) else base.border(Tokens.Stroke.emphasis.dp, Ink.text, shape)).clickable(role = Role.Button, onClick = onClick),
        Alignment.Center,
    ) { BasicText(text, style = Tokens.Text.bodyStrong.style(if (filled) Ink.onAccent else Ink.text)) }
}

/**
 * A slider that snaps to [count] steps, evenly spaced, so a log scale is just a list of log-spaced values.
 * With [low] it has two thumbs: the low one can't pass the high one.
 */
@Composable
internal fun StepSlider(
    name: String, count: Int, index: Int, onChange: (Int) -> Unit, ticks: List<Pair<Int, String>>, valueText: (Int) -> String,
    low: Int? = null, onLow: (Int) -> Unit = {},
) {
    val hi by rememberUpdatedState(index)
    val lo by rememberUpdatedState(low)
    val change by rememberUpdatedState(onChange)
    val changeLow by rememberUpdatedState(onLow)
    Canvas(
        Modifier.fillMaxWidth().height(48.dp)
            .semantics { contentDescription = name; stateDescription = if (low != null) "${valueText(low)} to ${valueText(index)}" else valueText(index) }
            .pointerInput(count, low != null) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pad = 14.dp.toPx(); val w = size.width - 2 * pad
                    fun at(x: Float) = (((x - pad) / w) * (count - 1)).roundToInt().coerceIn(0, count - 1)
                    val start = at(down.position.x)
                    val l0 = lo
                    val useLow = l0 != null && (start < l0 || abs(start - l0) < abs(start - hi))
                    fun apply(x: Float) {
                        val i = at(x)
                        val l = lo
                        if (useLow && l != null) { val v = i.coerceAtMost(hi - 1); if (v != l) changeLow(v) }
                        else { val v = if (l != null) i.coerceAtLeast(l + 1) else i; if (v != hi) change(v) }
                    }
                    apply(down.position.x); down.consume()
                    drag(down.id) { c -> apply(c.position.x); c.consume() }
                }
            },
    ) {
        val pad = 14.dp.toPx(); val w = size.width - 2 * pad; val y = 16.dp.toPx()
        fun x(i: Int) = pad + w * i / (count - 1).coerceAtLeast(1)
        drawLine(Ink.track, Offset(pad, y), Offset(pad + w, y), 6.dp.toPx(), StrokeCap.Round)
        drawLine(Ink.accent, Offset(x(low ?: 0), y), Offset(x(index), y), 6.dp.toPx(), StrokeCap.Round)
        if (low != null) drawCircle(Ink.accent, 11.dp.toPx(), Offset(x(low), y))
        drawCircle(Ink.accent, 11.dp.toPx(), Offset(x(index), y))
        for ((i, t) in ticks) monoText(t, x(i), 42.dp.toPx(), 9f, Ink.muted, when (i) { 0 -> Paint.Align.LEFT; count - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER })
    }
}

/** One setting: name and value, the control, what it does, and what it was before when the profile is Custom. */
@Composable
private fun Setting(title: String, value: String, changedFrom: String?, desc: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (changedFrom != null) AmberDot()
            BasicText(title, Modifier.weight(1f), Tokens.Text.bodyStrong.style())
            BasicText(value, style = Tokens.Text.valueSmall.style())
        }
        control()
        BasicText(desc, style = Tokens.Text.small.style(Ink.textSecondary))
        if (changedFrom != null) BasicText("Changed from $changedFrom", style = Tokens.Text.small.style(Ink.warn))
    }
}

@Composable
private fun SectionHead(left: String, right: String = "") {
    Row(Modifier.fillMaxWidth()) {
        BasicText(left, Modifier.weight(1f), Tokens.Text.cardLabel.style())
        if (right.isNotEmpty()) BasicText(right, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

// ---- Demo state ----

/** Indices into the step lists in [DemoSharing]. */
data class MeshSettings(
    val keepLow: Int, val keepHigh: Int, val wifiShare: Int, val upload: Int, val mobileLimit: Int,
    val connections: Int, val findable: Int, val memory: Int, val allowance: Int,
)

/** Made-up profile state (user, 4 Nov: demo data until the spike's engine is in the app). Kept for this app session only. */
object DemoSharing {
    const val AUTO = 0; const val LIGHT = 1; const val BALANCED = 2; const val GENEROUS = 3; const val CUSTOM = 4
    val NAMES = listOf("Auto", "Light", "Balanced", "Generous", "Custom")
    const val AUTO_PICK = BALANCED
    const val AUTO_REASON = "because you're on Wi-Fi with 64% battery"

    val KEEP_S = listOf(30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400)
    val WIFI_SHARE = listOf("Off", "Charging", "Over 30%", "Always")
    val UPLOAD = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0, Double.POSITIVE_INFINITY)
    val MOBILE_MB = listOf(10, 20, 50, 100, 200, 500, 1000, -1)
    val CONNECTIONS = listOf(2, 4, 8, 16, 24, 32)
    val FINDABLE = listOf("Never", "While sharing", "Always")
    val MEMORY_MB = listOf(16, 24, 32, 64, 128, 192, 256)
    val ALLOWANCE_GB = (0..20).toList()
    val LOW_BATTERY = (10..50 step 5).toList()
    val CAP_MB = listOf(10, 20, 50, 100, 200, 500, 1000)

    // The profile table in design/ui-spec.md › Data and sharing.
    val PRESETS = mapOf(
        LIGHT to MeshSettings(2, 5, 1, 2, 3, 1, 0, 1, 1),
        BALANCED to MeshSettings(3, 8, 2, 4, 5, 2, 1, 3, 4),
        GENEROUS to MeshSettings(6, 12, 3, 7, 7, 4, 2, 5, 10),
    )

    var profile by mutableIntStateOf(AUTO)
    var customBase by mutableIntStateOf(BALANCED)
    var custom by mutableStateOf(PRESETS.getValue(BALANCED))
    var shareOn by mutableStateOf(true)
    var shareMobile by mutableStateOf(false)
    var mobileCap by mutableIntStateOf(2)
    var shareWhilePlaying by mutableStateOf(true)
    var partiesOnly by mutableStateOf(false)
    var lowBattery by mutableIntStateOf(2)
    var mayGenerous by mutableStateOf(true)
    var generousCharging by mutableStateOf(true)
    var hotSevere by mutableStateOf(true)

    /** The profile whose figures apply now: Auto's pick, or the one chosen. */
    val inForce: Int get() = if (profile == AUTO) AUTO_PICK else profile
    val settings: MeshSettings get() = if (profile == CUSTOM) custom else PRESETS.getValue(inForce)
    val profileLine: String get() = when (profile) { AUTO -> "Auto · ${NAMES[AUTO_PICK]} now"; CUSTOM -> "Custom"; else -> NAMES[profile] }

    /** Any manual change makes the profile Custom and stops Auto (hand-off 22). */
    fun change(f: (MeshSettings) -> MeshSettings) {
        if (profile != CUSTOM) { customBase = inForce; custom = PRESETS.getValue(inForce); profile = CUSTOM }
        custom = f(custom)
    }
}

private fun keepText(s: Int) = when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} min"; else -> "${s / 3600} h" }
/** How much music a data figure buys (hand-off 35): real data is the music plus about 17% for proofs and connections, at 160 kbps. */
internal fun musicTimeText(mb: Double): String {
    val min = (mb / 1.17 / 72.0 * 60).roundToInt()
    return when { min < 60 -> "$min min"; min < 600 && min % 60 >= 15 && min % 60 < 45 -> "${min / 60}½ h"; else -> "${((min + 30) / 60)} h" }
}
private fun mbText(mb: Int) = when { mb < 0 -> "no limit"; mb >= 1000 -> "${mb / 1000} GB"; else -> "$mb MB" }
private fun mbpsText(v: Double) = when { v.isInfinite() -> "no limit"; v < 1 -> "$v Mbps"; else -> "${v.toInt()} Mbps" }
private fun wifiShareWords(i: Int) = when (i) { 0 -> "Off"; 1 -> "Only while charging"; 2 -> "Unless battery under 30%"; else -> "Always" }

// ---- Data and sharing ----

@Composable
fun DataSharingScreen(back: () -> Unit, openLog: () -> Unit, openAdvanced: () -> Unit) {
    val d = DemoSharing
    val s = d.settings
    var capSheet by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<Pair<Int, MeshSettings>?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(10_000); undo = null } }
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Data and sharing", back)
            DemoBanner("Made up until the mesh engine is in the app. These settings don't change anything yet.")
            BasicText("How much data, battery, memory and storage Brocast may use to fetch music and pass it on to other listeners. None of it changes the timing.", style = Tokens.Text.body.style(Ink.textSecondary))
            SectionHead("PROFILE")
            BasicText(d.profileLine, style = Tokens.Text.small.style(Ink.accent))
            Choice(if (d.profile == DemoSharing.CUSTOM) DemoSharing.NAMES else DemoSharing.NAMES.take(4), d.profile, { i ->
                if (i != d.profile) {
                    if (d.profile == DemoSharing.CUSTOM) undo = d.customBase to d.custom
                    d.profile = i
                }
            }, underline = if (d.profile == DemoSharing.AUTO) DemoSharing.AUTO_PICK else null)
            BasicText(
                when (d.profile) {
                    DemoSharing.AUTO -> "Auto picks a profile from your battery, charging, network, heat and Data Saver, and always says why. Right now: ${DemoSharing.NAMES[DemoSharing.AUTO_PICK]}, ${DemoSharing.AUTO_REASON}."
                    DemoSharing.CUSTOM -> "Custom: your own settings from Advanced, starting from ${DemoSharing.NAMES[d.customBase]}. Auto doesn't change them."
                    else -> "${DemoSharing.NAMES[d.profile]} stays until you change it. Auto would pick for you."
                },
                style = Tokens.Text.small.style(Ink.textSecondary),
            )
            Card {
                Row(Modifier.fillMaxWidth()) {
                    BasicText("WHAT ${DemoSharing.NAMES[if (d.profile == DemoSharing.CUSTOM) DemoSharing.CUSTOM else d.inForce].uppercase(Locale.UK)} DOES", Modifier.weight(1f), Tokens.Text.cardLabel.style())
                    if (d.profile == DemoSharing.AUTO) BasicText("chosen by Auto", style = Tokens.Text.small.style(Ink.accent))
                }
                for ((icon, line) in doesLines(s)) Row {
                    BasicText(icon, Modifier.width(26.dp), Tokens.Text.body.style(Ink.accent))
                    BasicText(line, Modifier.weight(1f), Tokens.Text.body.style())
                }
            }
            SectionHead("BY NETWORK")
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetCard(
                    "On Wi-Fi", true, "Up to ${keepText(DemoSharing.KEEP_S[s.keepHigh])} ahead, in bursts",
                    if (s.wifiShare == 0) "Off" else "${wifiShareWords(s.wifiShare)} · ${mbpsText(DemoSharing.UPLOAD[s.upload])}",
                    "Wi-Fi usually costs you nothing, so sharing here is generous.", "today ↓ 1.4 GB\nshared ↑ 1.2 GB", Modifier.weight(1f).fillMaxHeight(),
                )
                NetCard(
                    "On mobile data", false, DemoSharing.MOBILE_MB[s.mobileLimit].let { mb -> if (mb < 0) "No daily limit" else "Up to ${mbText(mb)} a day\n≈ ${musicTimeText(mb.toDouble())} of music" },
                    if (d.shareMobile) "Up to ${mbText(DemoSharing.CAP_MB[d.mobileCap])} a day" else "Receive-only · never shares unless you allow it",
                    if (d.shareMobile) "Stops at the cap until midnight. Data Saver always wins." else "Never uses your mobile data to share. Includes hotspots and paid Wi-Fi.",
                    "today ↓ 120 MB\nshared ↑ 0 MB", Modifier.weight(1f).fillMaxHeight(),
                )
            }
            Card {
                SwitchRow(
                    "Allow sharing on mobile data",
                    if (d.shareMobile) "Up to ${mbText(DemoSharing.CAP_MB[d.mobileCap])} a day, then off until midnight." else "Off. If you turn it on, you choose a daily cap first.",
                    d.shareMobile,
                ) { on -> if (on) capSheet = true else d.shareMobile = false }
                SwitchRow("Share while playing", "Sharing carries on while your music plays. It runs at low priority and never touches the timing.", d.shareWhilePlaying) { d.shareWhilePlaying = it }
                SwitchRow("Only during global parties", if (d.partiesOnly) "On: shares only while a scheduled Brocast party is on." else "Off. On: shares only while a scheduled Brocast party is on.", d.partiesOnly) { d.partiesOnly = it }
            }
            Card {
                SectionHead("MEASURED TODAY", "real figures, not estimates")
                Row(Modifier.fillMaxWidth()) {
                    Measured("BATTERY", "≈ 3%", "used by sharing", Modifier.weight(1f))
                    Measured("RADIO ASLEEP", "78%", "of the last hour", Modifier.weight(1f))
                    val c = DemoSharing.CONNECTIONS[s.connections]
                    Measured("CONNECTED", "$c", "of $c allowed", Modifier.weight(1f))
                }
            }
            LinkRow("Data log: every byte, today and this month", openLog)
            LinkRow("Advanced: buffer levels, connections, memory, allowance", openAdvanced)
        }
        undo?.let { u ->
            Toast("Custom settings dropped", "UNDO", { d.customBase = u.first; d.custom = u.second; d.profile = DemoSharing.CUSTOM; undo = null }, Modifier.align(Alignment.BottomCenter))
        }
        if (capSheet) MobileCapSheet { capSheet = false }
    }
}

private fun doesLines(s: MeshSettings): List<Pair<String, String>> {
    val d = DemoSharing
    val wifi = when (s.wifiShare) {
        0 -> "never shares with other listeners"
        1 -> "shares with other listeners only while charging"
        2 -> "shares with other listeners unless the battery is under 30%"
        else -> "always shares with other listeners"
    } + if (s.wifiShare == 0) "." else if (d.UPLOAD[s.upload].isInfinite()) ", with no speed limit." else ", up to ${mbpsText(d.UPLOAD[s.upload])}."
    val mobile = d.MOBILE_MB[s.mobileLimit].let { if (it < 0) "downloads with no daily limit." else "uses up to ${mbText(it)} of data a day, about ${musicTimeText(it.toDouble())} of music." }
    val find = when (s.findable) { 0 -> "Finds others but doesn't announce itself."; 1 -> "Announces itself while sharing."; else -> "Always announces itself." }
    return listOf(
        "↓" to "Downloads in bursts: tops up to ${keepText(d.KEEP_S[s.keepHigh])} ahead whenever it drops under ${keepText(d.KEEP_S[s.keepLow])}, then lets the radio sleep for long stretches.",
        "≋" to "On Wi-Fi: $wifi",
        "▴" to "On mobile data: $mobile Never shares unless you allow it.",
        "▣" to "Up to ${d.CONNECTIONS[s.connections]} connections and ${d.MEMORY_MB[s.memory]} MB of memory; holds up to ${d.ALLOWANCE_GB[s.allowance]} GB to share. $find",
        "◷" to "Timing is never affected: every profile plays in perfect sync.",
    )
}

@Composable
private fun NetCard(title: String, here: Boolean, download: String, share: String, why: String, today: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        modifier.background(Ink.surface, shape).border(if (here) 1.5.dp else Tokens.Stroke.card.dp, if (here) Ink.accent else Ink.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (here) BasicText("● YOU'RE ON IT", style = Tokens.Text.cardLabel.style(Ink.accent))
        BasicText(title, style = Tokens.Text.bodyStrong.style())
        BasicText("DOWNLOAD", style = Tokens.Text.cardLabel.style())
        BasicText(download, style = Tokens.Text.body.style())
        BasicText("SHARE", style = Tokens.Text.cardLabel.style())
        BasicText(share, style = Tokens.Text.body.style())
        BasicText(why, style = Tokens.Text.small.style(Ink.textSecondary))
        BasicText(today, style = Tokens.Text.valueSmall.style().copy(fontSize = 12.sp))
    }
}

@Composable
private fun Measured(label: String, value: String, detail: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = Tokens.Text.cardLabel.style().copy(fontSize = 9.5.sp))
        BasicText(value, style = Tokens.Text.value.style())
        BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/** The sheet before sharing on mobile data: a daily cap on a log scale, 10 MB to 1 GB, 50 MB by default. */
@Composable
internal fun MobileCapSheet(close: () -> Unit) {
    val d = DemoSharing
    var cap by remember { mutableIntStateOf(d.mobileCap) }
    Sheet(close) {
        BasicText("Share on mobile data?", style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp))
        BasicText("Other listeners would download from you over your paid allowance. Pick a daily cap: at the cap sharing stops until midnight, and Android's Data Saver always wins.", style = Tokens.Text.body.style(Ink.textSecondary))
        Row(Modifier.fillMaxWidth()) {
            BasicText("Daily cap", Modifier.weight(1f), Tokens.Text.bodyStrong.style())
            BasicText(mbText(DemoSharing.CAP_MB[cap]), style = Tokens.Text.valueSmall.style())
        }
        StepSlider("Daily cap", DemoSharing.CAP_MB.size, cap, { cap = it }, listOf(0 to "10 MB", 3 to "100 MB", 6 to "1 GB"), { mbText(DemoSharing.CAP_MB[it]) })
        BasicText("≈ ${musicTimeText(DemoSharing.CAP_MB[cap].toDouble())} of music a day. Real data: the music plus about 17% for proofs and connections.", style = Tokens.Text.small.style(Ink.textSecondary))
        PillButton("Allow up to ${mbText(DemoSharing.CAP_MB[cap])} a day", true) { d.mobileCap = cap; d.shareMobile = true; close() }
        PillButton("Cancel", false, close)
    }
}

// ---- Data and sharing: advanced ----

@Composable
fun DataSharingAdvancedScreen(back: () -> Unit) {
    val d = DemoSharing
    val s = d.settings
    val base = if (d.profile == DemoSharing.CUSTOM) DemoSharing.PRESETS.getValue(d.customBase) else null
    val baseName = DemoSharing.NAMES[d.customBase]
    fun changed(pick: (MeshSettings) -> Int, text: (Int) -> String): String? = base?.let { b -> if (pick(s) != pick(b)) "${text(pick(b))} ($baseName)" else null }
    var capSheet by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Screen {
            ScreenHeader("Advanced", back)
            DemoBanner("Made up until the mesh engine has a cost model. Changes here don't reach any engine yet.")
            BasicText("Every setting the profiles use, with what each one costs. Drag and the prediction updates before you leave the screen.", style = Tokens.Text.body.style(Ink.textSecondary))
            Card {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText("PROFILE", style = Tokens.Text.cardLabel.style())
                        BasicText(d.profileLine, style = Tokens.Text.bodyStrong.style(if (d.profile == DemoSharing.CUSTOM) Ink.warn else Ink.text))
                    }
                    BasicText("Change any setting below and the profile becomes Custom", Modifier.width(160.dp), Tokens.Text.small.style(Ink.textSecondary))
                }
                if (d.profile == DemoSharing.CUSTOM) BasicText("Start again from $baseName", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { d.profile = d.customBase }.wrapContentHeight(), Tokens.Text.body.style(Ink.accent))
            }
            CostCard(s)
            Card {
                SectionHead("AUTO", "how Auto chooses")
                val lowPct = DemoSharing.LOW_BATTERY[d.lowBattery]
                Setting("Low battery line", "$lowPct%", null, "At or under this, and not charging, Auto picks Light. It goes back up only at ${lowPct + 10}% (10 points higher), so it doesn't flip back and forth.") {
                    StepSlider("Low battery line", DemoSharing.LOW_BATTERY.size, d.lowBattery, { d.lowBattery = it }, listOf(0 to "10%", 4 to "30%", 8 to "50%"), { "${DemoSharing.LOW_BATTERY[it]}%" })
                }
                SwitchRow("Auto may pick Generous", "Off: Auto never goes above Balanced.", d.mayGenerous) { d.mayGenerous = it }
                SwitchRow("Generous only while charging", "Off: Generous on Wi-Fi with a good battery, too.", d.generousCharging) { d.generousCharging = it }
                Setting("Hot phone", if (d.hotSevere) "Severe" else "Moderate", null, "When Android says the phone is this hot, Auto picks Light until it cools. Moderate steps back sooner.") {
                    Choice(listOf("Moderate", "Severe"), if (d.hotSevere) 1 else 0, { d.hotSevere = it == 1 })
                }
            }
            Card {
                SectionHead("DOWNLOADING", "log scale")
                val lo = keepText(DemoSharing.KEEP_S[s.keepLow]); val hi = keepText(DemoSharing.KEEP_S[s.keepHigh])
                val was = base?.let { b -> if (b.keepLow != s.keepLow || b.keepHigh != s.keepHigh) "${keepText(DemoSharing.KEEP_S[b.keepLow])} → ${keepText(DemoSharing.KEEP_S[b.keepHigh])} ($baseName)" else null }
                Setting("Keep ahead", "$lo → $hi", was, "When less than $lo is downloaded, it fetches in one burst up to $hi, then the radio sleeps. A wider gap means longer sleeps and less battery; a higher top uses more storage.") {
                    StepSlider(
                        "Keep ahead", DemoSharing.KEEP_S.size, s.keepHigh, { v -> d.change { it.copy(keepHigh = v) } },
                        listOf(0 to "30 s", 3 to "5 m", 7 to "1 h", 12 to "24 h"), { keepText(DemoSharing.KEEP_S[it]) },
                        low = s.keepLow, onLow = { v -> d.change { it.copy(keepLow = v) } },
                    )
                }
            }
            Card {
                SectionHead("ON WI-FI")
                Setting("Share on Wi-Fi", if (s.wifiShare == 2) "Battery over 30%" else DemoSharing.WIFI_SHARE[s.wifiShare], changed({ it.wifiShare }) { DemoSharing.WIFI_SHARE[it] },
                    "When this phone passes music on to other listeners over Wi-Fi: never, only while charging, while the battery is over 30%, or always.") {
                    Choice(DemoSharing.WIFI_SHARE, s.wifiShare, { v -> d.change { it.copy(wifiShare = v) } })
                }
                Setting("Upload speed limit", mbpsText(DemoSharing.UPLOAD[s.upload]), changed({ it.upload }) { mbpsText(DemoSharing.UPLOAD[it]) },
                    "Megabits a second. Keeps sharing from crowding your own browsing and calls on the same Wi-Fi.") {
                    StepSlider("Upload speed limit", DemoSharing.UPLOAD.size, s.upload, { v -> d.change { it.copy(upload = v) } },
                        listOf(0 to "0.25", 2 to "1", 6 to "16", 7 to "no limit"), { mbpsText(DemoSharing.UPLOAD[it]) })
                }
            }
            Card {
                SectionHead("ON MOBILE DATA", "includes hotspots and paid Wi-Fi")
                Setting("Download limit a day", mbText(DemoSharing.MOBILE_MB[s.mobileLimit]), changed({ it.mobileLimit }) { mbText(DemoSharing.MOBILE_MB[it]) },
                    DemoSharing.MOBILE_MB[s.mobileLimit].let { mb -> if (mb < 0) "" else "Real data, about ${musicTimeText(mb.toDouble())} of music (music plus ~17% for proofs and connections). " } +
                    "Past this, it plays what's already downloaded, then fetches only what's needed, a minute at a time. Resets at midnight.") {
                    StepSlider("Download limit a day", DemoSharing.MOBILE_MB.size, s.mobileLimit, { v -> d.change { it.copy(mobileLimit = v) } },
                        listOf(0 to "10 MB", 3 to "100 MB", 6 to "1 GB", 7 to "no limit"), { mbText(DemoSharing.MOBILE_MB[it]) })
                }
                SwitchRow(
                    "Share on mobile data",
                    if (d.shareMobile) "Up to ${mbText(DemoSharing.CAP_MB[d.mobileCap])} a day." else "Off. Turning it on asks for a daily cap first (10 MB to 1 GB).",
                    d.shareMobile,
                ) { on -> if (on) capSheet = true else d.shareMobile = false }
            }
            Card {
                SectionHead("CONNECTIONS, MEMORY, STORAGE")
                Setting("Connections", "${DemoSharing.CONNECTIONS[s.connections]}", changed({ it.connections }) { "${DemoSharing.CONNECTIONS[it]}" },
                    "How many other phones and station servers it talks to at once. More finds missing blocks faster; each one costs a little memory and battery.") {
                    StepSlider("Connections", DemoSharing.CONNECTIONS.size, s.connections, { v -> d.change { it.copy(connections = v) } },
                        listOf(0 to "2", 1 to "4", 2 to "8", 3 to "16", 5 to "32"), { "${DemoSharing.CONNECTIONS[it]}" })
                }
                Setting("Be findable", DemoSharing.FINDABLE[s.findable], changed({ it.findable }) { DemoSharing.FINDABLE[it] },
                    "Whether it tells the network it has music to share. Never: it still finds others, but others don't find it.") {
                    Choice(DemoSharing.FINDABLE, s.findable, { v -> d.change { it.copy(findable = v) } })
                }
                Setting("Memory", "${DemoSharing.MEMORY_MB[s.memory]} MB", changed({ it.memory }) { "${DemoSharing.MEMORY_MB[it]} MB" },
                    "Working memory for connections and blocks in transit. Your music library isn't counted here.") {
                    StepSlider("Memory", DemoSharing.MEMORY_MB.size, s.memory, { v -> d.change { it.copy(memory = v) } },
                        listOf(0 to "16", 2 to "32", 3 to "64", 4 to "128", 6 to "256 MB"), { "${DemoSharing.MEMORY_MB[it]} MB" })
                }
                Setting("Sharing allowance", "${DemoSharing.ALLOWANCE_GB[s.allowance]} GB", changed({ it.allowance }) { "$it GB" },
                    "Storage for music kept to pass on, separate from your Archive. Oldest goes first; anything you Keep is never removed.") {
                    StepSlider("Sharing allowance", DemoSharing.ALLOWANCE_GB.size, s.allowance, { v -> d.change { it.copy(allowance = v) } },
                        listOf(0 to "0", 5 to "5", 10 to "10", 20 to "20 GB"), { "$it GB" })
                }
            }
            Card {
                BasicText("CAN'T GO LOWER", style = Tokens.Text.cardLabel.style())
                BasicText("Whatever you set, Brocast always keeps at least 30 s of music ahead, at least one connection, and the station's servers as a fallback, so playback never runs dry. No setting touches the timing.", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        if (capSheet) MobileCapSheet { capSheet = false }
    }
}

/** Predicted (outline) against today (a line), with a made-up cost model until the engine calibrates one from this phone's week. */
@Composable
private fun CostCard(s: MeshSettings) {
    val d = DemoSharing
    val shareF = listOf(0.0, 0.3, 1.0, 1.6)[s.wifiShare]
    val up = d.UPLOAD[s.upload].let { if (it.isInfinite()) 32.0 else it }
    val battery = 1.2 + 0.18 * d.CONNECTIONS[s.connections] + shareF * (1 + log2(up) / 4)
    val wifi = 0.6 + shareF * up * 0.5
    val mobile = d.MOBILE_MB[s.mobileLimit]
    val mem = d.MEMORY_MB[s.memory]
    val store = d.ALLOWANCE_GB[s.allowance]
    class Line(val name: String, val predicted: String, val pFrac: Float, val today: String, val tFrac: Float, val over: Boolean)
    val lines = listOf(
        Line("Battery", "≈ ${battery.roundToInt()}%", (battery / 10).toFloat(), "3%", 0.3f, 3 > battery * 1.25),
        Line("Wi-Fi data", "≈ ${"%.1f".format(wifi)} GB", (wifi / 10).toFloat(), "2.6 GB", 0.26f, 2.6 > wifi * 1.25),
        Line("Mobile data", "≤ ${mbText(mobile)}", if (mobile < 0) 1f else mobile / 1000f, "120 MB", 0.12f, mobile in 0..95),
        Line("Memory", "≤ $mem MB", mem / 256f, "41 MB", 41 / 256f, 41 > mem * 1.25),
        Line("Storage", "≤ $store GB", store / 20f, "3.1 GB", 3.1f / 20f, 3.1 > store * 1.25),
    )
    Card {
        SectionHead("COST OF THESE SETTINGS · A DAY", "updates as you drag")
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            BasicText("PREDICTED", Modifier.width(78.dp), Tokens.Text.cardLabel.style().copy(fontSize = 9.sp))
            BasicText("TODAY", Modifier.width(58.dp), Tokens.Text.cardLabel.style().copy(fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End))
        }
        for (l in lines) Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(l.name, Modifier.width(92.dp), Tokens.Text.body.style())
            Canvas(Modifier.weight(1f).height(14.dp).semantics { contentDescription = "${l.name}: predicted ${l.predicted}, today ${l.today}" }) {
                val y = size.height / 2
                drawLine(Ink.track, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                drawRoundRect(Ink.textSecondary, Offset(0f, y - 4.dp.toPx()), Size(size.width * l.pFrac.coerceIn(0.02f, 1f), 8.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
                val tx = size.width * l.tFrac.coerceIn(0f, 1f)
                drawLine(if (l.over) Ink.warn else Ink.text, Offset(tx, 0f), Offset(tx, size.height), 2.dp.toPx())
            }
            BasicText(l.predicted, Modifier.width(78.dp).padding(start = 8.dp), Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp), maxLines = 1)
            BasicText(l.today, Modifier.width(58.dp), Tokens.Text.valueSmall.style(if (l.over) Ink.warn else Ink.text).copy(fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End), maxLines = 1)
        }
        BasicText("Predicted from this phone's own last 7 days, not a typical phone. Outline = predicted, line = today so far. A line goes amber when today runs more than 25% over the prediction.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

// ---- Data log ----

private class Ledger(
    val wifiDown: Double, val wifiUp: Double, val mobileDown: Double, val mobileUp: Double,
    val protoMb: Double, val protoPct: Double, val dupMb: Double, val dupPct: Double, val dhtMb: Double,
) {
    val down get() = wifiDown + mobileDown
    val up get() = wifiUp + mobileUp
}

private val LEDGER_TODAY = Ledger(1.41, 1.20, 0.12, 0.0, 32.0, 2.1, 29.0, 1.9, 3.4)
private val LEDGER_MONTH = Ledger(9.59, 7.2, 0.71, 0.0, 216.0, 2.2, 190.0, 2.0, 24.0)

private fun gbText(gb: Double) = if (gb < 1) "${(gb * 1000).roundToInt()} MB" else "%.2f GB".format(gb)

@Composable
fun DataLogScreen(back: () -> Unit) {
    var month by remember { mutableStateOf(false) }
    val l = if (month) LEDGER_MONTH else LEDGER_TODAY
    Screen {
        ScreenHeader("Data log", back)
        DemoBanner("Made up until the mesh engine keeps a ledger.")
        BasicText("Every byte, by the network it went over. Counted on this phone, reset at local midnight and on the 1st.", style = Tokens.Text.body.style(Ink.textSecondary))
        Choice(listOf("Today", "This month"), if (month) 1 else 0, { month = it == 1 })
        Row(Modifier.fillMaxWidth()) {
            Total("DOWNLOADED", gbText(l.down), "≈ ${musicTimeText(l.down * 1000)} of music", Ink.text, Modifier.weight(1f))
            Total("SHARED", gbText(l.up), "≈ ${musicTimeText(l.up * 1000)} of listening given", Ink.accent, Modifier.weight(1f))
        }
        val scale = maxOf(l.wifiDown, l.wifiUp, l.mobileDown, 0.001)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerCard("Wi-Fi", true, l.wifiDown, l.wifiUp, scale, "Sharing on, as ${DemoSharing.NAMES[DemoSharing.inForce]} allows.", Modifier.weight(1f).fillMaxHeight())
            LedgerCard(
                "Mobile data", false, l.mobileDown, l.mobileUp, scale,
                if (month) "Never uses your mobile data to share." else "Never uses your mobile data to share. 380 MB of today's 500 MB left.",
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Card {
            BasicText("WHERE THE BYTES WENT · ${if (month) "THIS MONTH" else "TODAY"}", style = Tokens.Text.cardLabel.style())
            WentRow("Music downloaded", "", gbText(l.down - (l.protoMb + l.dupMb + l.dhtMb) / 1000), "Blocks you kept and will play or pass on.")
            WentRow("Music shared", "", gbText(l.up), "Blocks other listeners asked you for.")
            WentRow("Proof and protocol", "${l.protoPct}%", "${l.protoMb.roundToInt()} MB", "The hashes that prove each block, plus connection overhead.")
            WentRow("Duplicate blocks", "${l.dupPct}%", "${l.dupMb.roundToInt()} MB", "A block slow from one phone is asked of another after 1 s; whichever copy arrives second is dropped. Usually 1–4%.")
            WentRow("Finding peers", "", "${l.dhtMb} MB", "Lookups on the shared peer-to-peer network.")
        }
        MonthChart()
    }
}

@Composable
private fun Total(label: String, value: String, detail: String, color: Color, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = Tokens.Text.cardLabel.style())
        BasicText(value, style = Tokens.Text.value.style(color).copy(fontSize = 30.sp))
        BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

@Composable
private fun LedgerCard(title: String, here: Boolean, down: Double, up: Double, scale: Double, note: String, modifier: Modifier) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        modifier.background(Ink.surface, shape).border(if (here) 1.5.dp else Tokens.Stroke.card.dp, if (here) Ink.accent else Ink.border, shape).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (here) BasicText("● YOU'RE ON IT", style = Tokens.Text.cardLabel.style(Ink.accent))
        BasicText(title, style = Tokens.Text.bodyStrong.style())
        for ((word, v, solid) in listOf(Triple("↓ down", down, true), Triple("↑ shared", up, false))) {
            Row(Modifier.fillMaxWidth()) {
                BasicText(word, Modifier.weight(1f), Tokens.Text.small.style(Ink.textSecondary))
                BasicText(gbText(v), style = Tokens.Text.valueSmall.style())
            }
            Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                drawRoundRect(Ink.track, Offset.Zero, size, CornerRadius(6.dp.toPx()))
                val w = (size.width * (v / scale).toFloat()).coerceAtLeast(3.dp.toPx())
                if (solid) drawRoundRect(Ink.accent, Offset.Zero, Size(w, size.height), CornerRadius(6.dp.toPx()))
                else drawRoundRect(Ink.accent, Offset(1.dp.toPx(), 1.dp.toPx()), Size(w - 2.dp.toPx(), size.height - 2.dp.toPx()), CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            }
        }
        BasicText(note, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

@Composable
private fun WentRow(name: String, pct: String, value: String, line: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicText(name, Modifier.weight(1f), Tokens.Text.body.style())
            if (pct.isNotEmpty()) BasicText("$pct  ", style = Tokens.Text.valueSmall.style(Ink.textSecondary).copy(fontSize = 12.sp))
            BasicText(value, style = Tokens.Text.valueSmall.style())
        }
        BasicText(line, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

/** This month's days: downloaded above the line (Wi-Fi accent, mobile grey on top), shared below as outlines; days to come as dots. */
@Composable
private fun MonthChart() {
    val today = LocalDate.now()
    val days = today.lengthOfMonth()
    fun wifi(day: Int) = if (day == today.dayOfMonth) LEDGER_TODAY.wifiDown else 1.0 + 0.4 * (sin(day * 1.7) + 1)
    fun mobile(day: Int) = if (day == today.dayOfMonth) LEDGER_TODAY.mobileDown else 0.04 + 0.06 * ((day * 7) % 3)
    fun shared(day: Int) = if (day == today.dayOfMonth) LEDGER_TODAY.wifiUp else wifi(day) * (0.45 + 0.25 * (sin(day * 2.3) + 1))
    Card {
        Row(Modifier.fillMaxWidth()) {
            BasicText("${today.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.UK).uppercase(Locale.UK)} SO FAR", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            BasicText("↓ ${"%.1f".format(LEDGER_MONTH.down)} GB · ↑ ${"%.1f".format(LEDGER_MONTH.up)} GB", style = Tokens.Text.valueSmall.style().copy(fontSize = 12.sp))
        }
        Canvas(Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "Data by day this month, demo data" }) {
            val top = 14.dp.toPx(); val bottom = size.height - 18.dp.toPx()
            val mid = top + (bottom - top) * 0.55f
            val upH = mid - top; val downH = bottom - mid
            val slot = size.width / days; val bw = slot * 0.7f
            drawLine(Ink.faint, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
            for (day in 1..days) {
                val x = slot * (day - 1) + (slot - bw) / 2
                if (day > today.dayOfMonth) { drawCircle(Ink.faint, 1.5.dp.toPx(), Offset(x + bw / 2, mid)); continue }
                val w = (wifi(day) / 2.0).toFloat().coerceAtMost(1f) * upH
                val m = (mobile(day) / 2.0).toFloat() * upH
                drawRect(Ink.accent, Offset(x, mid - w), Size(bw, w))
                drawRect(Ink.muted, Offset(x, mid - w - m), Size(bw, m))
                val sh = (shared(day) / 2.0).toFloat().coerceAtMost(1f) * downH
                drawRect(Ink.accent, Offset(x + 0.75.dp.toPx(), mid + 2.dp.toPx()), Size(bw - 1.5.dp.toPx(), sh), style = Stroke(1.5.dp.toPx()))
                if (day == today.dayOfMonth) drawRoundRect(Ink.text, Offset(x - 3.dp.toPx(), top - 6.dp.toPx()), Size(bw + 6.dp.toPx(), bottom - top + 8.dp.toPx()), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
            monoText("2 GB ↓", size.width, top + 4.dp.toPx(), 9f, Ink.muted, Paint.Align.RIGHT)
            monoText("2 GB ↑", size.width, bottom - 4.dp.toPx(), 9f, Ink.muted, Paint.Align.RIGHT)
            for (day in listOf(1, 7, 14, 21, days)) monoText("$day", slot * (day - 0.5f), size.height - 2.dp.toPx(), 9f, Ink.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("■ Wi-Fi down", style = Tokens.Text.small.style(Ink.accent))
            BasicText("■ mobile down", style = Tokens.Text.small.style(Ink.muted))
            BasicText("□ shared (Wi-Fi only)", style = Tokens.Text.small.style(Ink.accent))
        }
        BasicText("Mobile data this month: ↓ ${gbText(LEDGER_MONTH.mobileDown)}, ↑ 0 MB.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}

// ---- Block provenance ----

private class DemoBlock(val station: Boolean, val nym: String, val key: String, val via: String, val ms: Int, val bytes: Int, val asks: Int = 1)

/** A made-up sender for each near-zone block, cycling through the four routes and one block asked twice, so each can be seen. */
private fun demoBlock(sec: Int): DemoBlock = when (sec % 5) {
    0 -> DemoBlock(false, "Teal Plum Otter", "a3f9…71c2", "hole-punched", 38, 20148)
    1 -> DemoBlock(true, "", "7c2e…19ab", "direct", 61, 20231)
    2 -> DemoBlock(false, "Rose Lime Wren", "5d10…e4a7", "relayed", 112, 19987)
    3 -> DemoBlock(false, "Jade Kiwi Lynx", "c88b…0f3d", "same Wi-Fi", 4, 20096)
    else -> DemoBlock(false, "Sand Fig Ibis", "e21d…8b40", "direct", 1240, 20177, asks = 2)
}

private val UK_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/London"))

@Composable
internal fun BlockProvenanceSheet(sec: Int, nowUtcNs: Long, setId: String, close: () -> Unit) {
    val b = demoBlock(sec)
    val plays = UK_CLOCK.format(Instant.ofEpochSecond(0, nowUtcNs).plusSeconds(sec.toLong()))
    val index = 1840 + sec
    val hash = "%04x…%04x".format((sec * 7919 + 40732) and 0xffff, (sec * 104729 + 1246) and 0xffff)
    var all by remember(sec) { mutableStateOf(false) }
    Sheet(close) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BasicText("BLOCK · +$sec s · PLAYS $plays", style = Tokens.Text.eyebrow.style())
                BasicText(if (b.station) "From the station" else "From ${b.nym}, a listener", style = Tokens.Text.screenTitle.style().copy(fontSize = 20.sp))
            }
            BasicText("✕", Modifier.size(44.dp).clickable(role = Role.Button, onClick = close).wrapContentSize(), Tokens.Text.bodyStrong.style(Ink.textSecondary))
        }
        DemoBanner("Made up until the mesh engine reports each block.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((t, accent) in listOf(b.via to false, fmtRouteMs(b.ms) to false, "${"%.1f".format(b.bytes / 1000.0)} KB" to false, "✓ verified" to true)) {
                BasicText(t, Modifier.border(1.dp, if (accent) Ink.accent else Ink.grid, RoundedCornerShape(Tokens.Radius.pill.dp)).padding(horizontal = 12.dp, vertical = 6.dp), Tokens.Text.valueSmall.style(if (accent) Ink.accent else Ink.text).copy(fontSize = 12.sp))
            }
        }
        Route(b)
        BasicText("WHY YOU CAN TRUST IT", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        val steps = listOf(
            Triple("Station key", "ed25519 7c2e…19ab", "Built into the app. Only the station holds the matching private key."),
            Triple("Signed tree", "signature ✓ · length 3,412", "The station signed the whole set as one tree of hashes: 3,412 blocks long."),
            Triple("Proof", "12 nodes · 384 B", "12 hashes from the sender link this block to the signed top of the tree. Change one bit and it no longer fits."),
            Triple("This block", "BLAKE2b $hash · ${"%.1f".format(b.bytes / 1000.0)} KB", "Second ${"%,d".format(index)} of the set, the hash checked against the proof before it was kept."),
            Triple("Plays", "$plays UK", "At its flowtime, on every phone at once."),
        )
        Column {
            steps.forEachIndexed { i, (title, value, line) ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    Column(Modifier.width(32.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(22.dp).border(2.dp, Ink.accent, CircleShape), Alignment.Center) { BasicText("✓", style = Tokens.Text.small.style(Ink.accent)) }
                        if (i < steps.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(Ink.accent))
                    }
                    Column(Modifier.weight(1f).padding(start = 6.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BasicText(title, Modifier.weight(1f), Tokens.Text.bodyStrong.style())
                            BasicText(value, style = Tokens.Text.valueSmall.style(Ink.accent).copy(fontSize = 11.sp), maxLines = 1)
                        }
                        BasicText(line, style = Tokens.Text.small.style(Ink.textSecondary))
                    }
                }
            }
        }
        BasicText("Brocast never keeps a block that fails this check, so every block you can tap has passed it. A listener can pass music on but can't change a single bit of it.", style = Tokens.Text.small.style(Ink.textSecondary))
        BasicText(if (all) "Hide details" else "All details", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { all = !all }.wrapContentHeight(), Tokens.Text.body.style(Ink.accent))
        if (all) Card {
            BasicText("ALL DETAILS", style = Tokens.Text.cardLabel.style())
            for ((label, field, value) in listOf(
                Triple("Sender's key", "blockInfo.peer", b.key),
                Triple("Kind", "blockInfo.from", if (b.station) "station" else "listener"),
                Triple("Route", "blockInfo.via", b.via),
                Triple("Asked → verified", "blockInfo.ms", "${b.ms} ms"),
                Triple("Asked", "blockInfo.asks", if (b.asks == 1) "once" else "twice"),
                Triple("Size", "blockInfo.bytes", "${"%,d".format(b.bytes)} B"),
                Triple("Set, second", "blockInfo.setId, index", "$setId · ${"%,d".format(index)}"),
                Triple("Transport block", "blockInfo.transportSec", "4 s"),
                Triple("Proof", "blockInfo.proof", "12 nodes · 384 B"),
                Triple("Signed by", "blockInfo.signedBy", "7c2e…19ab (station)"),
                Triple("Tree length", "blockInfo.treeLength", "3,412"),
            )) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        BasicText(label, style = Tokens.Text.body.style())
                        BasicText(field, style = Tokens.Text.valueSmall.style(Ink.muted).copy(fontSize = 10.sp))
                    }
                    BasicText(value, style = Tokens.Text.valueSmall.style().copy(fontSize = 12.sp))
                }
            }
        }
    }
}

private fun fmtRouteMs(ms: Int) = if (ms >= 1000) "%.2f s".format(ms / 1000.0) else "$ms ms"

/** Sender → you: a pill for a listener, a solid tile for the station; dashed through a padlock when relayed. */
@Composable
private fun Route(b: DemoBlock) {
    val relayed = b.via == "relayed"
    Canvas(Modifier.fillMaxWidth().height(if (relayed || b.asks > 1) 78.dp else 60.dp).semantics { contentDescription = "Route: ${b.via}, ${b.ms} ms" }) {
        val y = 32.dp.toPx()
        val sw = 72.dp.toPx(); val sh = 34.dp.toPx()
        val r = 18.dp.toPx()
        val you = Offset(size.width - r - 2.dp.toPx(), y)
        if (b.station) {
            drawRoundRect(Ink.accent.copy(alpha = 0.25f), Offset(0f, y - sh / 2), Size(sw, sh), CornerRadius(6.dp.toPx()))
            drawRoundRect(Ink.accent, Offset(0f, y - sh / 2), Size(sw, sh), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
            monoText("station", sw / 2, y + 4.dp.toPx(), 11f, Ink.text)
        } else {
            drawRoundRect(Ink.accent, Offset(1.dp.toPx(), y - sh / 2), Size(sw, sh), CornerRadius(sh / 2), style = Stroke(1.5.dp.toPx()))
            monoText(b.nym.substringAfterLast(' ').lowercase(Locale.UK), sw / 2, y + 4.dp.toPx(), 11f, Ink.text)
        }
        drawCircle(Ink.text, r, you)
        monoText("you", you.x, y + 4.dp.toPx(), 11f, Ink.bg)
        val a = sw + 8.dp.toPx(); val e = you.x - r - 6.dp.toPx()
        drawLine(Ink.accent, Offset(a, y), Offset(e, y), 1.5.dp.toPx(), pathEffect = if (relayed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())) else null)
        val h = 6.dp.toPx()
        drawLine(Ink.accent, Offset(e, y), Offset(e - h, y - h * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
        drawLine(Ink.accent, Offset(e, y), Offset(e - h, y + h * 0.6f), 1.5.dp.toPx(), StrokeCap.Round)
        monoText("${b.via} · ${fmtRouteMs(b.ms)}", (a + e) / 2, y - 10.dp.toPx(), 10f, Ink.textSecondary)
        if (b.asks > 1) monoText("asked twice · first sender slow", (a + e) / 2, y + 18.dp.toPx(), 9f, Ink.muted)
        if (relayed) {
            val c = Offset((a + e) / 2, y); val bs = 22.dp.toPx()
            drawRoundRect(Ink.surface, Offset(c.x - bs / 2, c.y - bs / 2), Size(bs, bs), CornerRadius(4.dp.toPx()))
            drawRoundRect(Ink.textSecondary, Offset(c.x - bs / 2, c.y - bs / 2), Size(bs, bs), CornerRadius(4.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawRect(Ink.textSecondary, Offset(c.x - 5.dp.toPx(), c.y - 1.dp.toPx()), Size(10.dp.toPx(), 7.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawArc(Ink.textSecondary, 180f, 180f, false, Offset(c.x - 3.5.dp.toPx(), c.y - 6.dp.toPx()), Size(7.dp.toPx(), 9.dp.toPx()), style = Stroke(1.dp.toPx()))
            monoText("blind relay · can't read it", c.x, y + 28.dp.toPx(), 9f, Ink.muted)
        }
    }
}
