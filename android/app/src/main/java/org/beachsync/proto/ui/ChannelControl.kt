package org.beachsync.proto.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.beachsync.proto.Renderer

/**
 * Hand-off 18's pocket guard: the chip ignores touches while the proximity sensor is covered and for 1 s after the app
 * comes back on screen. A screen that's off or locked gets no touches anyway. MainActivity keeps these up to date.
 */
object PocketGuard {
    @Volatile var resumedAt = 0L
    @Volatile var covered = false
    fun blocked() = covered || SystemClock.uptimeMillis() - resumedAt < 1000
}

/** What the channel control is showing: the tap hint, the picker sheet, and the Undo toast with the channel to go back to. */
class ChannelUi {
    var hint by mutableStateOf(false)
    var hintKey by mutableIntStateOf(0)
    var picker by mutableStateOf(false)
    var toast by mutableStateOf<Pair<String, Int>?>(null)
    var toastKey by mutableIntStateOf(0)
}

@Composable
fun rememberChannelUi() = remember { ChannelUi() }

private fun channelName(c: Int) = when (c) { Renderer.LEFT -> "Left"; Renderer.RIGHT -> "Right"; else -> "Stereo" }

/** The chip right of the track title: a plain tap only hints, press and hold for 0.8 s opens the picker. */
@Composable
fun ChannelChip(ui: ChannelUi, channel: Int, headphones: Boolean) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val stereo = headphones || channel == Renderer.BOTH
    val col = if (stereo) Ink.text else Ink.accent
    val shape = RoundedCornerShape(Tokens.Radius.pill.dp)
    Row(
        Modifier.padding(start = 6.dp).height(Tokens.Space.touchTarget.dp).alpha(if (headphones) 0.4f else 1f)
            .border(Tokens.Stroke.card.dp, if (stereo) Ink.grid else Ink.accent, shape)
            .semantics {
                contentDescription = "Channel: ${channelName(if (stereo) Renderer.BOTH else channel)}. " + if (headphones) "Headphones always play stereo." else "Press and hold to change."
                if (!headphones) onLongClick("Change channel") { ui.picker = true; true }
            }
            .pointerInput(headphones) {
                if (headphones) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    if (PocketGuard.blocked()) return@awaitEachGesture
                    val fill = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(800, easing = LinearEasing))
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        ui.picker = true
                    }
                    val up = waitForUpOrCancellation()
                    val short = fill.isActive && progress.value < 0.3f
                    fill.cancel()
                    scope.launch { progress.snapTo(0f) }
                    if (up != null && short) { ui.hint = true; ui.hintKey++ }
                }
            }
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val c = center; val w = 1.5.dp.toPx()
            if (stereo) {
                drawCircle(col, 6.dp.toPx(), Offset(c.x - 3.dp.toPx(), c.y), style = Stroke(w))
                drawCircle(col, 6.dp.toPx(), Offset(c.x + 3.dp.toPx(), c.y), style = Stroke(w))
            } else drawCircle(col, 7.dp.toPx(), c, style = Stroke(w))
            val p = progress.value
            if (p > 0f) drawArc(Ink.accent, -90f, 360f * p, false, Offset(c.x - 12.dp.toPx(), c.y - 12.dp.toPx()), Size(24.dp.toPx(), 24.dp.toPx()), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(4.dp))
        BasicText(when { stereo -> "STEREO"; channel == Renderer.LEFT -> "L LEFT"; else -> "R RIGHT" }, style = Tokens.Text.badge.style(col))
    }
}

/** The tap hint, for 2 s, under the title row. */
@Composable
fun ChannelHint(ui: ChannelUi) {
    if (!ui.hint) return
    LaunchedEffect(ui.hintKey) { delay(2000); ui.hint = false }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        BasicText("Hold to change channel", Modifier.background(Ink.text, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), Tokens.Text.small.style(Ink.bg))
    }
}

/** The picker sheet and the Undo toast. Changes last until the app restarts: every start is Stereo. */
@Composable
fun BoxScope.ChannelOverlays(ui: ChannelUi, state: BenchState, actions: BenchActions) {
    fun pick(c: Int) {
        ui.picker = false
        val prev = state.channel
        if (c == prev) return
        actions.setChannel(c)
        ui.toast = (if (c == Renderer.BOTH) "Playing in stereo" else "Playing ${channelName(c)} only") to prev
        ui.toastKey++
        state.session?.logChannel("Channel changed to ${channelName(c)}" + if (c == Renderer.BOTH) "" else ": until the app restarts")
    }
    ui.toast?.let { (text, prev) ->
        LaunchedEffect(ui.toastKey) { delay(6000); ui.toast = null }
        Toast(text, "UNDO", {
            ui.toast = null
            actions.setChannel(prev)
            state.session?.logChannel("Channel back to ${channelName(prev)}")
        }, Modifier.align(Alignment.BottomCenter))
    }
    if (ui.picker) Sheet({ ui.picker = false }) {
        BasicText("THIS PHONE PLAYS", style = Tokens.Text.cardLabel.style())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in listOf(Renderer.LEFT, Renderer.BOTH, Renderer.RIGHT)) {
                val on = c == state.channel
                val shape = RoundedCornerShape(Tokens.Radius.card.dp)
                Box(
                    Modifier.weight(1f).height(76.dp).background(if (on) Ink.accent else Ink.bg, shape).border(Tokens.Stroke.card.dp, if (on) Ink.accent else Ink.grid, shape)
                        .clickable(role = Role.Button) { pick(c) },
                    Alignment.Center,
                ) { BasicText(channelName(c), style = Tokens.Text.bodyStrong.style(if (on) Ink.onAccent else Ink.text)) }
            }
        }
        BasicText("Back to Stereo every time the app starts. Swipe down or tap outside to cancel.", style = Tokens.Text.small.style(Ink.textSecondary))
    }
}
