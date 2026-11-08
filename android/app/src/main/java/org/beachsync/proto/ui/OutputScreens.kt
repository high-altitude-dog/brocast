package org.beachsync.proto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.AudioOutput
import org.beachsync.proto.CalSource
import org.beachsync.proto.Calibration
import org.beachsync.proto.OutputIcon
import kotlin.math.min

/*
 * Hand-off 34: the output pictures, Now playing's output tile and the one-time "Show your headphones' name?" card.
 * Each picture is drawn on the design's 24-unit grid with a 1.8 line, round ends, in the colour it's given.
 */

private sealed class Mark
private class Line(val d: String) : Mark()
private class Box24(val x: Float, val y: Float, val w: Float, val h: Float, val r: Float) : Mark()
private class Ring(val cx: Float, val cy: Float, val r: Float, val filled: Boolean = false) : Mark()

private val MARKS = mapOf(
    OutputIcon.SPEAKER to listOf(Box24(7f, 2.5f, 10f, 19f, 2.2f), Ring(12f, 17.5f, 1.2f, true), Line("M10 6h4")),
    OutputIcon.WIRED to listOf(Line("M4 14v-2a8 8 0 0 1 16 0v2"), Box24(3f, 14f, 4f, 6f, 1.5f), Box24(17f, 14f, 4f, 6f, 1.5f), Line("M19 20v1.5a1.5 1.5 0 0 1-1.5 1.5H14")),
    OutputIcon.USB to listOf(Box24(8f, 2.5f, 8f, 5f, 1.5f), Line("M9.5 7.5v4h5v-4M12 11.5v3"), Line("M12 14.5c0 4-4 3-4 6.5"), Ring(8f, 21.5f, 1f, true)),
    OutputIcon.BT_HEADPHONES to listOf(Line("M4 15v-3a8 8 0 0 1 16 0v3"), Box24(3f, 14f, 4f, 7f, 1.5f), Box24(17f, 14f, 4f, 7f, 1.5f), Line("M10.5 6.5l3 2.2-1.5 1.1 1.5 1.1-3 2.2V6.5")),
    OutputIcon.BT_EARBUDS to listOf(Ring(7.5f, 7.5f, 3.5f), Line("M8.5 11v9.5"), Ring(16.5f, 7.5f, 3.5f), Line("M15.5 11v9.5")),
    OutputIcon.BT_SPEAKER to listOf(Box24(3f, 6f, 18f, 13f, 4f), Ring(12f, 12.5f, 3.2f), Line("M7 4h10")),
    OutputIcon.OTHER to listOf(Box24(2.5f, 5f, 19f, 13f, 2f), Line("M2.5 15a3 3 0 0 1 3 3M2.5 11.5a6.5 6.5 0 0 1 6.5 6.5")),
)

private val PATHS = MARKS.values.flatten().filterIsInstance<Line>().associate { it.d to PathParser().parsePathString(it.d).toPath() }

@Composable
fun OutputGlyph(icon: OutputIcon, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawOutput(icon, tint) }
}

private fun DrawScope.drawOutput(icon: OutputIcon, tint: Color) {
    val k = this.size.minDimension / 24f
    scale(k, Offset.Zero) {
        val line = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (m in MARKS.getValue(icon)) when (m) {
            is Line -> drawPath(PATHS.getValue(m.d), tint, style = line)
            is Box24 -> drawRoundRect(tint, Offset(m.x, m.y), Size(m.w, m.h), CornerRadius(m.r), style = line)
            is Ring -> if (m.filled) drawCircle(tint, m.r, Offset(m.cx, m.cy)) else drawCircle(tint, m.r, Offset(m.cx, m.cy), style = line)
        }
    }
}

/**
 * Now playing's output tile: picture and heading, that output's lag, and its name (plus codec for Bluetooth). Amber "Not calibrated" when the output has no lag yet;
 * "Bluetooth · name?" without the nearby devices permission. [lagMs] is the lag playing now, or null when stopped (then the saved or table value).
 */
@Composable
internal fun OutputTile(out: AudioOutput, cal: Calibration, lagMs: Double?, modifier: Modifier, open: () -> Unit) {
    val uncalibrated = lagMs == null && cal.source == CalSource.NONE
    val detail = when {
        out.icon == OutputIcon.SPEAKER -> "This phone"
        out.icon.bluetooth && !out.named -> "Bluetooth · name?"
        out.icon == OutputIcon.WIRED && out.name == out.route -> "Wired"
        else -> out.name + (out.codec?.let { " · $it" } ?: "")
    }
    Column(
        modifier.fillMaxHeight().background(Ink.surface, RoundedCornerShape(Tokens.Radius.tile.dp)).border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.tile.dp))
            .clickable(role = Role.Button, onClick = open).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutputGlyph(out.icon, 12.dp, Ink.textSecondary, Modifier.padding(end = 4.dp))
            BasicText(out.icon.heading, style = Tokens.Text.cardLabel.style().copy(fontSize = min(Tokens.Text.cardLabel.size, 9.5f).sp, letterSpacing = 0.8.sp), maxLines = 1)
        }
        BasicText(if (uncalibrated) "—" else org.beachsync.proto.fmtMillis(lagMs ?: cal.lagMs), style = Tokens.Text.value.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicText(if (uncalibrated) "Not calibrated" else detail, style = Tokens.Text.small.style(if (uncalibrated) Ink.warn else null), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Shown once, on Now playing, when Bluetooth headphones are first heard without the permission (never at first launch). */
@Composable
internal fun HeadphonesNameCard(onContinue: () -> Unit, notNow: () -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutputGlyph(OutputIcon.BT_HEADPHONES, 28.dp, Ink.accent, Modifier.padding(end = 12.dp))
            BasicText("Show your headphones' name?", style = Tokens.Text.bodyStrong.style().copy(fontSize = 17.sp))
        }
        BasicText(
            "Brocast can show which headphones are playing, like \"Sony WH-1000XM5\", and remember a separate lag for each pair. Android calls this permission Nearby devices. " +
                "Brocast only reads the name and sound settings of headphones already connected to this phone. It doesn't look for other devices, and it isn't used to find other listeners.",
            style = Tokens.Text.body.style(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.6f)) { PillButton("Continue", true, onContinue) }
            Box(Modifier.weight(1f)) { PillButton("Not now", false, notNow) }
        }
    }
}

/** Calibration's way back to the permission after "Not now" or a refusal. After a second refusal Android won't ask again, so the caller opens app settings. */
@Composable
internal fun HeadphonesNameRow(open: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).background(Ink.surface, RoundedCornerShape(Tokens.Radius.card.dp))
            .border(Tokens.Stroke.card.dp, Ink.border, RoundedCornerShape(Tokens.Radius.card.dp)).clickable(role = Role.Button, onClick = open).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        BasicText("Show headphones' name", style = Tokens.Text.bodyStrong.style())
        BasicText("Without it, all Bluetooth headphones share one lag.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}
