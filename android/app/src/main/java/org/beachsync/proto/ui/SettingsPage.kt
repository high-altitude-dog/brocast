package org.beachsync.proto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Only the LISTENING section for now: the design's other sections have nothing behind them yet. */
@Composable
fun SettingsPage(state: BenchState, actions: BenchActions, back: () -> Unit, openCalibration: () -> Unit, openSatellites: () -> Unit, openSync: () -> Unit, openBeat: () -> Unit, openStereo: () -> Unit, openData: () -> Unit, openIdentity: () -> Unit, openTestCard: () -> Unit = {}) {
    rememberTick(SETTINGS_TICK_MS).value    // the output's name can change during a run
    val s = state.session
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Settings", back)
        BasicText("LISTENING", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            SatellitesRow(state.satellites, actions::setSatellites, "GPS time only · your position stays on this phone")
            SwitchRow("Be counted as a listener", "Adds you to the listener estimate · off: you still listen and share", state.counted, actions::setCounted)
            SwitchRow("Show my country", "Others may see it from your internet address · off by default", state.showCountry, actions::setShowCountry)
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openSatellites), Arrangement.Center) {
                BasicText("Satellites", style = Tokens.Text.bodyStrong.style())
                BasicText("Your chip, your sky and what limits timing", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openSync), Arrangement.Center) {
                BasicText("Sync view", style = Tokens.Text.bodyStrong.style())
                BasicText("See whether phones agree, side by side", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openBeat), Arrangement.Center) {
                BasicText("Beat sync view", style = Tokens.Text.bodyStrong.style())
                BasicText("The same, flashing on a snare in the music", style = Tokens.Text.small.style(Ink.textSecondary))
            }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openStereo), Arrangement.Center) {
                BasicText("Stereo pair", style = Tokens.Text.bodyStrong.style())
                BasicText(when (state.channel) { org.beachsync.proto.Renderer.LEFT -> "This phone plays Left"; org.beachsync.proto.Renderer.RIGHT -> "This phone plays Right"; else -> "Stereo · back to Stereo each start" }.let { if (it.startsWith("This phone plays ")) it.removePrefix("This phone plays ") + " · until the app restarts" else it }, style = Tokens.Text.small.style(Ink.textSecondary))
            }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openCalibration), Arrangement.Center) {
                BasicText("Calibration", style = Tokens.Text.bodyStrong.style())
                val out = actions.output()
                val cal = actions.savedCalibration(out) ?: actions.tableCalibration(out)
                val value = if (cal.source == org.beachsync.proto.CalSource.NONE) "Not measured" else "${org.beachsync.proto.fmtMillis(cal.lagMs)} · ${cal.source.name.lowercase().replaceFirstChar { it.uppercase() }}"
                BasicText("${out.name} · $value", style = Tokens.Text.small.style(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).toggleable(state.localTime, role = Role.Switch, onValueChange = actions::setLocalTime),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("Local time under the clock", style = Tokens.Text.bodyStrong.style())
                    BasicText("Teddington · Greenwich stays on top", style = Tokens.Text.small.style())
                }
                Spacer(Modifier.width(10.dp))
                SatellitesSwitch(state.localTime)
            }
        }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) { ScreenPrefs.load(ctx) }
        Card {
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openTestCard), Arrangement.Center) {
                BasicText("Camera test card", style = Tokens.Text.bodyStrong.style())
                BasicText("Film phones side by side to time screen and sound", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        BasicText("SCREEN", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            SwitchRow("No flashing", "Nothing on screen ever flashes, whatever else is set", ScreenPrefs.noFlashing) { ScreenPrefs.set(ctx, noFlashing = it) }
            Column(Modifier.alpha(if (ScreenPrefs.noFlashing) 0.4f else 1f)) {
                SwitchRow("Flash on each new bar", if (ScreenPrefs.noFlashing) "Off while No flashing is on" else "The beat counter's border flashes on beat 1", ScreenPrefs.flashBar) { if (!ScreenPrefs.noFlashing) ScreenPrefs.set(ctx, barFlash = it) }
            }
        }
        BasicText("IDENTITY", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openIdentity), Arrangement.Center) {
                BasicText("Your identity", style = Tokens.Text.bodyStrong.style())
                BasicText("${(org.beachsync.proto.PearsEngine.view?.nym?.ifEmpty { null } ?: DemoMesh.NYM).lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }} · run the first-run wizard (demo)", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        BasicText("DELIVERY", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            BasicText("DEMO: nothing here reaches a mesh yet", style = Tokens.Text.cardLabel.style(Ink.warn))
            SwitchRow("Share with other listeners", "On Wi-Fi as your profile allows · never on mobile data unless you choose", DemoSharing.shareOn) { DemoSharing.shareOn = it }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openData), Arrangement.Center) {
                BasicText("Data and sharing", style = Tokens.Text.bodyStrong.style())
                BasicText("${DemoSharing.profileLine} · shared 1.2 GB today on Wi-Fi", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
    }
}

private const val SETTINGS_TICK_MS = 1000L
