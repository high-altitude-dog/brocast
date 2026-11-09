package org.beachsync.proto.ui

import android.provider.Settings
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A name on one line (hand-off 41, Kept and track names): if it doesn't fit, it waits 1.5 s, scrolls left at 30 dp/s with faded edges,
 * loops with a 40 dp gap, and stops after 3 loops until it's next on screen. A finger on it holds it at the start. With animations off
 * (reduced motion) it ends in an ellipsis instead; the opened card shows the full name.
 */
@Composable
internal fun ScrollingName(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val still = remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val fits = remember(text, style, constraints.maxWidth) { measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= constraints.maxWidth }
        if (fits || still) {
            BasicText(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return@BoxWithConstraints
        }
        var held by remember { mutableStateOf(false) }
        val fade = 12.dp
        BasicText(
            text,
            Modifier
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); held = true; waitForUpOrCancellation(); held = false } }
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    val f = fade.toPx() / size.width
                    drawRect(Brush.horizontalGradient(0f to Color.Transparent, f to Color.Black, 1f - f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                }
                .basicMarquee(
                    iterations = if (held) 0 else 3, initialDelayMillis = 1500, repeatDelayMillis = 0,
                    spacing = MarqueeSpacing(40.dp), velocity = 30.dp,
                ),
            style = style, maxLines = 1, softWrap = false,
        )
    }
}
