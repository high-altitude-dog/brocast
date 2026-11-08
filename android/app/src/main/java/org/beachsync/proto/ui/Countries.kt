package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.beachsync.proto.MeshPeer
import java.util.Locale

/**
 * Co-listener countries (hand-offs 38 and 40): from the addresses phones already see, looked up on this phone in DB-IP's table, only for
 * listeners who allow it. Always of the listeners this phone is connected to, never station-wide, and no minimum count.
 */
internal fun countryName(code: String): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

/** An ISO code in a small bordered box. */
@Composable
internal fun CountryChip(code: String) {
    Box(Modifier.border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        BasicText(code, style = Tokens.Text.badge.style(Ink.text))
    }
}

/** The list column: a chip, or `not shown` / `unknown` in muted mono. Nothing from an engine that doesn't say. */
@Composable
internal fun CountryCell(country: String?, modifier: Modifier = Modifier) {
    Box(modifier, Alignment.Center) {
        when {
            country == null -> {}
            country.length == 2 -> CountryChip(country)
            else -> BasicText(if (country == "hidden") "not shown" else "unknown", style = Tokens.Text.badge.style(Ink.muted))
        }
    }
}

/** Codes of the connected listeners who show theirs, most listeners first. */
internal fun topCountries(listeners: List<MeshPeer>): List<Pair<String, List<MeshPeer>>> =
    listeners.filter { it.countryCode != null }.groupBy { it.countryCode!! }.toList().sortedWith(compareByDescending<Pair<String, List<MeshPeer>>> { it.second.size }.thenBy { it.first })

/** Swarm's WHERE YOUR CO-LISTENERS ARE card, under the stats. */
@Composable
internal fun CountriesCard(listeners: List<MeshPeer>, source: String?) {
    if (listeners.none { it.country != null }) return
    val rows = topCountries(listeners)
    val most = rows.maxOfOrNull { it.second.size } ?: 1
    val hidden = listeners.count { it.country == "hidden" }
    val unknown = listeners.filter { it.country == "unknown" }
    Card {
        Row(Modifier.fillMaxWidth()) {
            BasicText("WHERE YOUR CO-LISTENERS ARE", Modifier.weight(1f), Tokens.Text.cardLabel.style())
            BasicText(if (rows.size == 1) "1 country" else "${rows.size} countries", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        for ((code, ps) in rows) {
            val lan = ps.count { it.path == "lan" }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(44.dp)) { CountryChip(code) }
                Column(Modifier.weight(1f)) {
                    BasicText(countryName(code), style = Tokens.Text.body.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (lan > 0) BasicText("$lan on your Wi-Fi", style = Tokens.Text.small.style(Ink.accent))
                }
                Canvas(Modifier.width(96.dp).height(6.dp)) {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                    drawRoundRect(Ink.track, cornerRadius = r)
                    drawRoundRect(Ink.accent, size = Size(size.width * ps.size / most, size.height), cornerRadius = r)
                }
                BasicText("${ps.size}", Modifier.width(28.dp), Tokens.Text.valueSmall.style().copy(textAlign = androidx.compose.ui.text.style.TextAlign.End))
            }
        }
        if (rows.isEmpty()) BasicText("No connected listener shows their country yet.", style = Tokens.Text.small.style(Ink.textSecondary))
        val foot = listOfNotNull(
            hidden.takeIf { it > 0 }?.let { "$it not shown" },
            unknown.size.takeIf { it > 0 }?.let { n -> "$n unknown" + if (unknown.all { it.path == "relayed" }) " · relayed" else "" },
        )
        if (foot.isNotEmpty()) BasicText(foot.joinToString("    "), Modifier.padding(top = 4.dp), Tokens.Text.small.style(Ink.textSecondary))
        val n = listeners.size
        BasicText(
            "Of the $n listener${if (n == 1) "" else "s"} this phone is connected to. Countries come from the internet addresses phones already see when they connect, " +
                "looked up on this phone, and only for listeners who allow it. A VPN shows the VPN's country.",
            style = Tokens.Text.small.style(Ink.textSecondary),
        )
        // DB-IP's licence (CC BY 4.0) asks for this credit wherever its data is shown.
        val uri = LocalUriHandler.current
        BasicText(
            "IP to country by DB-IP${source?.removePrefix("DB-IP")?.let { " ·$it" } ?: ""} · CC BY 4.0",
            Modifier.clickable(role = Role.Button) { runCatching { uri.openUri("https://db-ip.com") } }.padding(vertical = 4.dp),
            Tokens.Text.small.style(Ink.muted),
        )
    }
}

/** The Mesh card's fourth line: up to five codes, most listeners first. */
@Composable
internal fun CountriesLine(listeners: List<MeshPeer>) {
    val codes = topCountries(listeners).take(5).map { it.first }
    if (codes.isEmpty()) return
    Row(Modifier.semantics { contentDescription = "Countries: " + codes.joinToString { countryName(it) } }, verticalAlignment = Alignment.CenterVertically) {
        BasicText("Countries", Modifier.width(76.dp), Tokens.Text.small.style(Ink.textSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (c in codes) CountryChip(c) }
    }
}

/** Asked once, at the end of first listen: Show my country or Not now. Either answer stays; Settings can change it. */
@Composable
internal fun CountryAskSheet(answer: (Boolean) -> Unit) {
    Sheet({ answer(false) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(30.dp)) {
                val w = 1.6.dp.toPx(); val r = size.minDimension / 2 - w
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(Ink.accent, r, c, style = Stroke(w))
                drawOval(Ink.accent, Offset(c.x - r * 0.45f, c.y - r), Size(r * 0.9f, r * 2), style = Stroke(w))
                drawLine(Ink.accent, Offset(c.x - r, c.y), Offset(c.x + r, c.y), w)
            }
            Spacer(Modifier.width(12.dp))
            BasicText("Show your country to other listeners?", style = Tokens.Text.screenTitle.style())
        }
        BasicText(
            "Phones that connect to yours already see your internet address. With this on, they may look up which country it's in and show it " +
                "next to your name, like \"Canada\". Nothing more precise: no city, no position, and your GPS never leaves this phone. With a VPN, " +
                "they'll see the VPN's country.",
            style = Tokens.Text.body.style(),
        )
        BasicText("Off: you still see other listeners' countries if they've allowed it, and you're still counted as a listener.", style = Tokens.Text.small.style(Ink.textSecondary))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1.6f)) { PillButton("Show my country", true) { answer(true) } }
            Box(Modifier.weight(1f)) { PillButton("Not now", false) { answer(false) } }
        }
    }
}
