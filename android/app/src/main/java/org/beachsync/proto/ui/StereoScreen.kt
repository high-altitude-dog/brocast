package org.beachsync.proto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.beachsync.proto.OutputKind
import org.beachsync.proto.Renderer

/**
 * Stereo pair (hand-off 15): one phone plays Left and the other Right. Both phones already follow flowtime, so this only picks the channel.
 * Pairing, the partner card and the stereo-image bar need a link between the phones and aren't built yet (see design/feedback.md).
 */
@Composable
fun StereoScreen(state: BenchState, actions: BenchActions, back: () -> Unit) {
    val headphones = actions.output().kind == OutputKind.HEADPHONES
    val running = state.session != null
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Stereo pair", back)
        BasicText("Two phones make stereo: one plays Left, the other Right. Both are already on the same clock.", style = Tokens.Text.body.style(Ink.textSecondary))
        Card {
            BasicText("THIS PHONE PLAYS", style = Tokens.Text.cardLabel.style())
            // Headphones already give each ear its own side, so the choice is locked to Both there.
            Segmented("Channel", listOf("Both", "Left", "Right"), if (headphones) Renderer.BOTH else state.channel, !running && !headphones) { state.channel = it }
            BasicText(
                when {
                    headphones -> "Headphones are the output, so this stays on Both. Stereo is for speakers."
                    running -> "Stop playing to change the side. It applies from the next Play."
                    else -> "Applies from the next Play."
                },
                style = Tokens.Text.small.style(Ink.textSecondary),
            )
        }
        Card {
            BasicText("PARTNER", style = Tokens.Text.cardLabel.style())
            BasicText("Pairing the two phones isn't built yet. Until then, set one phone to Left and the other to Right yourself; nothing checks the other phone or falls back to Both if it stops.", style = Tokens.Text.small.style(Ink.textSecondary))
        }
        Card {
            BasicText("SET UP", style = Tokens.Text.cardLabel.style())
            BasicText("Stand the phones at the same height, facing you, 1 to 2 m apart. The same model matches best; otherwise run the earcup test on each phone's speaker first.", style = Tokens.Text.small.style(Ink.textSecondary))
        }
    }
}
