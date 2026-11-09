package org.beachsync.proto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.beachsync.proto.Lane
import org.beachsync.proto.R

/** One text style from design/tokens.json. Uppercase styles are written in capitals by the screen that uses them. */
class TextToken(val numeric: Boolean, val size: Float, val weight: Int, val trackingEm: Float, val color: Long?)

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk, 400), variable(R.font.space_grotesk, 500), variable(R.font.space_grotesk, 700),
)
private val JetBrainsMono = FontFamily(variable(R.font.jetbrains_mono, 400), variable(R.font.jetbrains_mono, 600))

fun TextToken.style(tint: Color? = null) = TextStyle(
    fontFamily = if (numeric) JetBrainsMono else SpaceGrotesk,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = trackingEm.em,
    color = tint ?: color?.let { Color(it) } ?: Ink.text,
)

/** The token colours the screens use, as Compose colours. */
object Ink {
    val bg = Color(Tokens.Colors.bg)
    val surface = Color(Tokens.Colors.surface)
    val border = Color(Tokens.Colors.border)
    val track = Color(Tokens.Colors.track)
    val grid = Color(Tokens.Colors.grid)
    val text = Color(Tokens.Colors.text)
    val textSecondary = Color(Tokens.Colors.textSecondary)
    val muted = Color(Tokens.Colors.muted)
    val faint = Color(Tokens.Colors.faint)
    /** The station colour (hand-off 43): one runtime token, Lime until a station says otherwise. Every tint derives from it. */
    val accent: Color get() = StationColour.accent
    val onAccent = Color(Tokens.Colors.onAccent)
    val warn = Color(Tokens.Colors.warn)
    val danger = Color(Tokens.Colors.danger)
}

fun Lane.color() = Color(
    when (this) {
        Lane.CLOCK -> Tokens.Colors.laneClock
        Lane.AUDIO -> Tokens.Colors.laneAudio
        Lane.NET -> Tokens.Colors.laneNet
        Lane.OUTPUT -> Tokens.Colors.laneOutput
    },
)

fun Lane.mark() = when (this) {
    Lane.CLOCK -> Tokens.Marks.laneClock
    Lane.AUDIO -> Tokens.Marks.laneAudio
    Lane.NET -> Tokens.Marks.laneNet
    Lane.OUTPUT -> Tokens.Marks.laneOutput
}
