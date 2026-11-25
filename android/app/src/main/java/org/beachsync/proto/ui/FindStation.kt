package org.beachsync.proto.ui

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Features under test (user, 17 Nov): one switch on the Test bench, off by default, remembered. Release builds too (19 Nov).
 * New work shows only while it's on.
 */
object TestFeatures {
    var on by mutableStateOf(false)
        private set
    fun load(ctx: Context) { on = ctx.getSharedPreferences("test_features", 0).getBoolean("on", false) }
    fun set(ctx: Context, v: Boolean) { on = v; ctx.getSharedPreferences("test_features", 0).edit().putBoolean("on", v).apply() }
}

/** A station code: `brocast://station/<64 hex key>`, optionally `?relay=<64 hex key>` (the station's blind relay; critical path gap 5). */
internal fun parseStationCode(raw: String?): String? =
    Regex("^brocast://station/([0-9a-fA-F]{64})/?(\\?relay=[0-9a-fA-F]{64})?$").find(raw?.trim() ?: "")?.groupValues?.get(1)?.lowercase()

/** The relay key a station code carries, or null. */
internal fun parseStationRelay(raw: String?): String? =
    Regex("^brocast://station/[0-9a-fA-F]{64}/?\\?relay=([0-9a-fA-F]{64})$").find(raw?.trim() ?: "")?.groupValues?.get(1)?.lowercase()

/** Until the mesh directory gives stations their four key words, a key is shown as its first 16 hex digits in fours. */
internal fun keyShort(key: String) = key.take(16).chunked(4).joinToString(" ")

private fun findSeen(ctx: Context) = ctx.getSharedPreferences("test_features", 0).getBoolean("find_seen", false)
internal fun markFindSeen(ctx: Context) = ctx.getSharedPreferences("test_features", 0).edit().putBoolean("find_seen", true).apply()

private fun reducedMotion(ctx: Context) =
    android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Hand-off 62: the Find button, top right of the home header. On first start it's filled, pulses and shows a tip until first used. */
@Composable
internal fun FindButton(open: () -> Unit) {
    val ctx = LocalContext.current
    var first by remember { mutableStateOf(!findSeen(ctx)) }
    val still = remember { reducedMotion(ctx) }
    val tap = { if (first) { markFindSeen(ctx); first = false }; open() }
    Column(horizontalAlignment = Alignment.End) {
        Box(contentAlignment = Alignment.Center) {
            if (first && !still) {
                val t = rememberInfiniteTransition(label = "find")
                for (k in 0..1) {
                    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), initialStartOffset = StartOffset(k * 800)), label = "ring$k")
                    Box(Modifier.matchParentSize().scale(1f + 0.45f * p, 1f + 0.9f * p)
                        .border(1.5.dp, Ink.accent.copy(alpha = (1f - p) * 0.8f), RoundedCornerShape(Tokens.Radius.control.dp)))
                }
            }
            Row(
                Modifier.heightIn(min = Tokens.Space.touchTarget.dp)
                    .background(if (first) Ink.accent else Ink.accent.copy(alpha = 0.08f), RoundedCornerShape(Tokens.Radius.control.dp))
                    .border(1.5.dp, Ink.accent, RoundedCornerShape(Tokens.Radius.control.dp))
                    .clickable(role = Role.Button, onClick = tap).padding(horizontal = 12.dp)
                    .semantics { contentDescription = "Find a station" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadarIcon(if (first) Ink.bg else Ink.text, sweep = first && !still)
                Spacer(Modifier.width(8.dp))
                BasicText("Find", style = Tokens.Text.bodyStrong.style(if (first) Ink.bg else Ink.text))
            }
        }
        if (first) BasicText("New: find more stations, or scan one from a friend's phone", Modifier.padding(top = 8.dp).widthIn(max = 200.dp), Tokens.Text.small.style(Ink.text))
    }
}

@Composable
private fun RadarIcon(color: androidx.compose.ui.graphics.Color, sweep: Boolean) {
    val a = if (sweep) rememberInfiniteTransition(label = "sweep").animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a").value else -1f
    Canvas(Modifier.size(18.dp)) {
        val w = size.width; val s = Stroke(1.5.dp.toPx())
        drawCircle(color, w * 0.48f, style = s)
        drawCircle(color, w * 0.26f, style = s)
        drawCircle(color, w * 0.08f)
        if (a >= 0f) drawArc(color.copy(alpha = 0.4f), a - 60f, 60f, true, Offset(w * 0.02f, w * 0.02f), androidx.compose.ui.geometry.Size(w * 0.96f, w * 0.96f))
    }
}

/**
 * Hand-off 62: Find a station. The mesh directory (signed listings) isn't built in the engine yet, so On the mesh shows the
 * searching state, then nothing found, with the station you're on under NOW. Scan a code works now: a station code switches
 * station through the existing flow (the current one keeps playing until the new one is ready); a set code goes to Pass it on.
 * The scanner is the in-app camera (QrScan.kt), plain without the designed brackets.
 */
@Composable
fun FindStationScreen(state: BenchState, currentKey: String, join: (String) -> Unit, openPass: (PassLink) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { markFindSeen(ctx) }
    var tab by remember { mutableIntStateOf(0) }
    var looking by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(12_000); looking = false }
    var found by remember { mutableStateOf<String?>(null) }
    var setCode by remember { mutableStateOf<PassLink?>(null) }
    var bad by remember { mutableStateOf(false) }
    fun scan() = QrScan.start { raw ->
        if (raw != null) {
            val key = parseStationCode(raw)
            when {
                key != null -> {
                    // The code's relay key goes to the engine's setting, so a fresh GitHub install can reach peers behind client isolation.
                    parseStationRelay(raw)?.let { ctx.getSharedPreferences("proto", android.content.Context.MODE_PRIVATE).edit().putString("relay", it).apply() }
                    found = key
                }
                PassLink.parse(raw) != null -> setCode = PassLink.parse(raw)
                else -> bad = true
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("‹ Find a station", Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = back).padding(vertical = 10.dp), Tokens.Text.screenTitle.style())
            Choice(listOf("On the mesh", "Scan a code"), tab, { tab = it; if (it == 1) scan() })
            if (tab == 0) {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Radar(Modifier.size(84.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (looking) {
                                BasicText("Looking on the mesh…", style = Tokens.Text.bodyStrong.style())
                                BasicText("Stations you follow show at once", style = Tokens.Text.small.style(Ink.textSecondary))
                            } else {
                                BasicText("No stations found yet", style = Tokens.Text.bodyStrong.style())
                                BasicText("It can take a minute on a new network. Or scan a station code from a friend's phone.", style = Tokens.Text.small.style(Ink.textSecondary))
                            }
                        }
                    }
                }
                BasicText("NOW", style = Tokens.Text.eyebrow.style())
                Row(
                    Modifier.fillMaxWidth().border(1.5.dp, Ink.accent, RoundedCornerShape(Tokens.Radius.card.dp)).background(Ink.accent.copy(alpha = 0.07f), RoundedCornerShape(Tokens.Radius.card.dp)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText("Brocast Radio", style = Tokens.Text.bodyStrong.style())
                        // The station's nym once its hello has checked out (user, 25 Nov: every device shows it).
                        val stationNym = org.beachsync.proto.PearsEngine.view?.stations?.firstOrNull { it.key.equals(currentKey, true) }?.nym
                        BasicText(if (currentKey.isEmpty()) "From the server" else (stationNym?.let { "${nymCase(it)} · " } ?: "") + "Station code ${keyShort(currentKey)}", style = Tokens.Text.small.style(Ink.textSecondary))
                    }
                    BasicText("ON NOW", style = Tokens.Text.small.style(Ink.accent))
                }
                BasicText("Every listing is signed by its station's key. Anyone can list a station; stations with the same name show different key words.", style = Tokens.Text.small.style(Ink.muted))
                BasicText("Test feature: the list of stations on the mesh needs the station directory, which isn't built yet.", style = Tokens.Text.small.style(Ink.muted))
            } else {
                Card {
                    BasicText("Point the camera at a station code on a friend's phone.", style = Tokens.Text.body.style())
                    PillButton("Scan a code", true) { scan() }
                    if (bad) BasicText("That isn't a Brocast code", style = Tokens.Text.small.style(Ink.warn))
                }
            }
        }
        found?.let { key ->
            Sheet({ found = null }) {
                BasicText(if (key == currentKey) "You're on this station" else "A station", style = Tokens.Text.screenTitle.style())
                BasicText("Station code", style = Tokens.Text.eyebrow.style())
                BasicText(keyShort(key), style = Tokens.Text.valueSmall.style(Ink.accent))
                BasicText("Not listed on the mesh yet: check these characters match the code on the other phone.", style = Tokens.Text.small.style(Ink.textSecondary))
                if (key != currentKey) PillButton("Listen now", true) { found = null; join(key) }
            }
        }
        setCode?.let { link ->
            Sheet({ setCode = null }) {
                BasicText("That's a code for one set, not a station.", style = Tokens.Text.bodyStrong.style())
                PillButton("Open it in Pass it on", true) { setCode = null; openPass(link) }
            }
        }
    }
}

@Composable
private fun Radar(modifier: Modifier) {
    val a by rememberInfiniteTransition(label = "radar").animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "a")
    val still = reducedMotion(LocalContext.current)
    Canvas(modifier) {
        val w = size.width; val s = Stroke(1.dp.toPx())
        for (r in listOf(0.48f, 0.32f, 0.16f)) drawCircle(Ink.border, w * r, style = s)
        drawCircle(Ink.accent, w * 0.03f)
        if (!still) drawArc(Ink.accent.copy(alpha = 0.25f), a - 50f, 50f, true, Offset(w * 0.02f, w * 0.02f), androidx.compose.ui.geometry.Size(w * 0.96f, w * 0.96f))
    }
}
