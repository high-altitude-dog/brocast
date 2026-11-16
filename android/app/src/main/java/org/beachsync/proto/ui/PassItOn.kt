package org.beachsync.proto.ui

import android.net.Uri
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.LibraryItem
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.PearsEngine

/**
 * Pass it on (hand-off 53): hand a set to the person next to you. The giver shows a code holding the station key, the set and the giver's
 * own key; the receiver's phone joins that station, connects straight to the giver by key and fetches that set first, checking every block
 * against the station's signature as it always does. Nothing goes through a server.
 */
class PassLink(val station: String, val setId: String, val via: String?) {
    fun uri() = "brocast://pass/$station/$setId" + (via?.let { "?via=$it" } ?: "")

    companion object {
        fun parse(text: String?): PassLink? {
            val u = runCatching { Uri.parse(text ?: return null) }.getOrNull() ?: return null
            if (u.scheme != "brocast" || u.host != "pass") return null
            val parts = u.pathSegments
            val station = parts.getOrNull(0)?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
            val set = parts.getOrNull(1)?.takeIf { it.matches(Regex("[0-9a-zA-Z_-]{1,64}")) } ?: return null
            return PassLink(station, set, u.getQueryParameter("via")?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) })
        }
    }
}

private fun mb(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"
private fun hm(seconds: Long) = if (seconds >= 3600) "${seconds / 3600} h ${seconds % 3600 / 60} m" else "${seconds / 60} m"
private fun initials(t: String) = t.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }
private fun peerName(key: String?) = key?.let { k -> PearsEngine.view?.peers?.firstOrNull { it.key == k }?.nym?.lowercase()?.split(' ')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } ?: k.take(4) } ?: "them"

@Composable
private fun SetHeader(item: LibraryItem?, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(Ink.accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).border(1.dp, Ink.accent, RoundedCornerShape(10.dp)), Alignment.Center) {
            BasicText(initials(item?.title ?: title), style = Tokens.Text.bodyStrong.style(Ink.accent))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            BasicText(item?.title ?: title, style = Tokens.Text.bodyStrong.style().copy(fontSize = 17.sp))
            if (item != null) BasicText(listOf(item.artist.ifEmpty { null }, hm(item.seconds), mb(item.bytes)).filterNotNull().joinToString(" · "), style = Tokens.Text.small.style(Ink.textSecondary))
        }
    }
}

@Composable
private fun PassPage(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = { ScreenHeader(title, back); content() },
    )
}

/** The giver: the set, its code, and who it's going to once they connect. */
@Composable
fun PassGiveScreen(lib: LibraryStore?, setId: String, stationKey: String, back: () -> Unit) {
    rememberTick(1000).value
    val item = lib?.editions?.flatMap { it.items }?.firstOrNull { it.id == setId }
    val me = PearsEngine.myKey
    val link = PassLink(stationKey, setId, me)
    // Who it's going to: the listener taking the most from this phone since the code went up.
    val start = remember { HashMap<String, Long>() }
    PearsEngine.view?.listenerPeers?.forEach { start.putIfAbsent(it.key, it.txBytes) }
    val taker = PearsEngine.view?.listenerPeers?.filter { it.fromYouKbps > 0 }?.maxByOrNull { it.txBytes - (start[it.key] ?: it.txBytes) }
    PassPage("Pass it on", back) {
        Card {
            SetHeader(item, setId)
            if (stationKey.isEmpty() || me == null) BasicText("Pass it on works once Brocast is on the station's mesh. Open Now playing and wait for the mesh card to say SHARING.", style = Tokens.Text.body.style(Ink.warn))
            else {
                QrCode(link.uri(), Modifier.fillMaxWidth(0.7f).aspectRatio(1f).align(Alignment.CenterHorizontally))
                BasicText("Let them scan this with Brocast", Modifier.fillMaxWidth(), Tokens.Text.bodyStrong.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                BasicText("Their phone fetches the set straight from yours: same Wi-Fi or hotspot, or over the internet. Nothing goes through the station.", style = Tokens.Text.small.style(Ink.textSecondary))
                BasicText("SIGNED BY BEACH SYNC RADIO", Modifier.align(Alignment.CenterHorizontally).border(1.dp, Ink.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), Tokens.Text.cardLabel.style(Ink.accent))
            }
        }
        if (taker != null && item != null) Card {
            val sent = taker.txBytes - (start[taker.key] ?: taker.txBytes)
            val pct = (100 * sent / item.bytes.coerceAtLeast(1)).coerceIn(0, 100)
            Row { BasicText("Sending to ${peerName(taker.key)}", Modifier.weight(1f), Tokens.Text.bodyStrong.style()); BasicText("$pct%", style = Tokens.Text.valueSmall.style()) }
            Bar(pct / 100f)
            val left = if (taker.fromYouKbps > 0) (item.bytes - sent) * 8 / 1000 / taker.fromYouKbps else -1
            BasicText("${if (taker.path == "lan") "Same Wi-Fi · straight between you" else taker.path ?: "direct"} · ${"%.1f".format(taker.fromYouKbps / 8000.0)} MB/s" + (if (left >= 0) " · ${left} s left" else ""), style = Tokens.Text.small.style(Ink.textSecondary))
        }
    }
}

/** The receiver: what it is and who it's from before anything downloads, then the progress, done, or can't connect. */
@Composable
fun PassReceiveScreen(lib: LibraryStore?, link: PassLink, currentStation: String, join: (String) -> Unit, back: () -> Unit) {
    rememberTick(1000).value
    var accepted by remember(link) { mutableStateOf(false) }
    var since by remember(link) { mutableLongStateOf(0L) }
    var lastPct by remember(link) { mutableIntStateOf(-1) }
    var lastMove by remember(link) { mutableLongStateOf(0L) }
    val item = lib?.editions?.flatMap { it.items }?.firstOrNull { it.id == link.setId }
    val built = lib?.file(link.setId)?.exists() == true
    val sets = PearsEngine.status?.optJSONObject("library")?.optJSONArray("sets")
    val pct = (0 until (sets?.length() ?: 0)).map { sets!!.getJSONObject(it) }.firstOrNull { it.optString("id") == link.setId }?.optInt("pct") ?: 0
    val now = System.currentTimeMillis()
    if (accepted) {
        // Keep asking the engine for it (it may still be starting on the new station), and notice when nothing moves.
        LaunchedEffect(link, accepted) { while (true) { if (PearsEngine.view != null) PearsEngine.want(link.setId, link.via); kotlinx.coroutines.delay(5000) } }
        if (pct != lastPct) { lastPct = pct; lastMove = now }
    }
    val stuck = accepted && !built && now - maxOf(lastMove, since) > 45_000
    PassPage("Pass it on", back) {
        Card {
            SetHeader(item, item?.title ?: "A set from Brocast Radio")
            when {
                built -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("KEPT", Modifier.border(1.dp, Ink.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), Tokens.Text.cardLabel.style(Ink.accent))
                        Spacer(Modifier.width(10.dp))
                        BasicText("Plays offline, in sync with the station when it's on air.", Modifier.weight(1f), Tokens.Text.body.style())
                    }
                    BasicText("Checked against Brocast Radio's signature, block by block. If the station later withdraws the set, it goes from Kept too.", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                !accepted -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("from ${peerName(link.via)}", Modifier.weight(1f), Tokens.Text.body.style())
                        BasicText(if (item != null) "SIGNED" else "STATION SIGNED", Modifier.border(1.dp, Ink.accent, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), Tokens.Text.cardLabel.style(Ink.accent))
                    }
                    BasicText(if (link.station == currentStation) "From the station you listen to. It comes straight from their phone, checked against the station's signature." else "From another station: getting it switches Brocast to that station.", style = Tokens.Text.small.style(Ink.textSecondary))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicText("Get it", Modifier.weight(1f).background(Ink.accent, RoundedCornerShape(50)).clickable(role = Role.Button) { if (link.station != currentStation) join(link.station); accepted = true; since = System.currentTimeMillis() }.padding(14.dp), Tokens.Text.bodyStrong.style(Ink.onAccent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                        BasicText("Cancel", Modifier.weight(1f).border(1.dp, Ink.grid, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = back).padding(14.dp), Tokens.Text.bodyStrong.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    }
                }
                stuck -> {
                    BasicText("CAN'T REACH THEIR PHONE", Modifier.border(1.dp, Ink.warn, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), Tokens.Text.cardLabel.style(Ink.warn))
                    BasicText("No shared Wi-Fi and no internet. Join the same Wi-Fi, or one of you turns on a hotspot and the other joins it; it carries on from where it stopped.", style = Tokens.Text.body.style())
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BasicText("Try again", Modifier.weight(1f).background(Ink.accent, RoundedCornerShape(50)).clickable(role = Role.Button) { since = System.currentTimeMillis(); lastMove = since; PearsEngine.want(link.setId, link.via) }.padding(14.dp), Tokens.Text.bodyStrong.style(Ink.onAccent).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                        BasicText("Cancel", Modifier.weight(1f).border(1.dp, Ink.grid, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = back).padding(14.dp), Tokens.Text.bodyStrong.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
                    }
                }
                else -> {
                    Row { BasicText("Getting it from ${peerName(link.via)}", Modifier.weight(1f), Tokens.Text.bodyStrong.style()); BasicText("$pct%", style = Tokens.Text.valueSmall.style()) }
                    Bar(pct / 100f)
                    val giver = PearsEngine.view?.peers?.firstOrNull { it.key == link.via }
                    BasicText(when { giver == null -> "Connecting to their phone…"; giver.path == "lan" -> "Same Wi-Fi · straight between you · ${"%.1f".format(giver.toYouKbps / 8000.0)} MB/s"; else -> "${giver.path ?: "direct"} · ${"%.1f".format(giver.toYouKbps / 8000.0)} MB/s" }, style = Tokens.Text.small.style(Ink.textSecondary))
                    BasicText("Listeners nearby who hold it help too.", style = Tokens.Text.small.style(Ink.muted))
                }
            }
        }
    }
}

@Composable
private fun Bar(f: Float) {
    Box(Modifier.fillMaxWidth().height(6.dp).background(Ink.track, RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(6.dp).background(Ink.accent, RoundedCornerShape(3.dp)))
    }
}

/** A QR code drawn square by square, dark on white with a quiet zone. */
@Composable
fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2)) }.getOrNull() }
    Canvas(modifier.background(Color.White, RoundedCornerShape(12.dp)).semantics { contentDescription = "Code for this set" }) {
        val m = matrix ?: return@Canvas
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

/** Brocast › Scan: Google's code scanner (no camera permission). A Brocast code opens the receiver. */
fun scanPassCode(ctx: android.content.Context, found: (PassLink?) -> Unit) {
    val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
        .addOnSuccessListener { found(PassLink.parse(it.rawValue)) }
        .addOnFailureListener { found(null) }
}
