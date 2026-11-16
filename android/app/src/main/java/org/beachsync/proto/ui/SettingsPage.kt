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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Only the LISTENING section for now: the design's other sections have nothing behind them yet. */
@Composable
fun SettingsPage(state: BenchState, actions: BenchActions, back: () -> Unit, openCalibration: () -> Unit, openSatellites: () -> Unit, openSync: () -> Unit, openBeat: () -> Unit, openStereo: () -> Unit, openData: () -> Unit, openIdentity: (step: Int, role: Int) -> Unit, openKept: () -> Unit, openTestCard: () -> Unit = {}, openNews: () -> Unit = {}) {
    rememberTick(SETTINGS_TICK_MS).value    // the output's name can change during a run
    val s = state.session
    Column(
        Modifier.fillMaxSize().background(Ink.bg).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(top = Tokens.Space.screenPaddingTop.dp, start = Tokens.Space.screenPaddingSide.dp, end = Tokens.Space.screenPaddingSide.dp, bottom = Tokens.Space.screenPaddingBottom.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScreenHeader("Settings", back)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.runtime.LaunchedEffect(Unit) { ReactPrefs.load(ctx) }
        BasicText("LISTENING", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            SatellitesRow(state.satellites, actions::setSatellites, "GPS time only · your position stays on this phone")
            SwitchRow("Be counted as a listener", "Adds you to the listener estimate · off: you still listen and share", state.counted, actions::setCounted)
            SwitchRow("Show reactions", "Counts and floating emoji from other listeners", ReactPrefs.show) { ReactPrefs.set(ctx, show = it) }
            SwitchRow("Send reactions", "Off hides the tray on Now playing", ReactPrefs.send) { ReactPrefs.set(ctx, send = it) }
            SwitchRow("Show my country", "Others may see it from your internet address · off by default", state.showCountry, actions::setShowCountry)
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openNews), Arrangement.Center) {
                BasicText("News on the headphone button", style = Tokens.Text.bodyStrong.style())
                BasicText(newsSummary(state.library), style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openSatellites), Arrangement.Center) {
                BasicText("Satellites", style = Tokens.Text.bodyStrong.style())
                BasicText("Your chip, your sky and what limits timing", style = Tokens.Text.small.style(Ink.textSecondary))
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
        // You (hand-off 64): identity was only reachable at first start. Each row opens the identity flow at its own step.
        BasicText("YOU", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            val nym = (org.beachsync.proto.PearsEngine.view?.nym?.ifEmpty { null } ?: DemoMesh.NYM).lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            NavRow("Your name", "$nym · made from your key") { openIdentity(4, 0) }
            NavRow("Back up your key", "24 words · demo, nothing is saved yet") { openIdentity(5, 0) }
            NavRow("Brocast on a computer", "Run a seeder that keeps sets online") { openIdentity(5, 1) }
        }
        androidx.compose.runtime.LaunchedEffect(Unit) { ScreenPrefs.load(ctx) }
        BasicText("SCREEN", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            SwitchRow("No flashing", "Nothing on screen ever flashes, whatever else is set", ScreenPrefs.noFlashing) { ScreenPrefs.set(ctx, noFlashing = it) }
            Column(Modifier.alpha(if (ScreenPrefs.noFlashing) 0.4f else 1f)) {
                SwitchRow("Flash on each new bar", if (ScreenPrefs.noFlashing) "Off while No flashing is on" else "The beat counter's border flashes on beat 1", ScreenPrefs.flashBar) { if (!ScreenPrefs.noFlashing) ScreenPrefs.set(ctx, barFlash = it) }
            }
        }
        // Archive (hand-off 64): only Open Kept for now. In the beta every set is kept, so Keep and Size limit have nothing to set, and there's no Archive screen yet.
        BasicText("ARCHIVE", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card { NavRow("Open Kept", "Every set on this phone · all kept in the beta", openKept) }
        BasicText("DELIVERY", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        // Swarm › Block (hand-off 46): the one place to undo it.
        // Once shown, the card stays while this page is open, so unblocking never shifts the page under the finger (a second tap opened Your identity).
        val blocked = org.beachsync.proto.PearsEngine.blocked
        var undo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet()) }
        if (blocked.isNotEmpty() || undo.isNotEmpty()) Card {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Row(Modifier.heightIn(min = Tokens.Space.touchTarget.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("Blocked listeners", style = Tokens.Text.bodyStrong.style())
                    BasicText(if (blocked.isNotEmpty()) "${blocked.size} blocked · they can't connect to you" else "${undo.size} unblocked · they can connect again", style = Tokens.Text.small.style(Ink.textSecondary))
                }
                if (blocked.isNotEmpty()) BasicText("Unblock all", Modifier.clickable(role = Role.Button) { undo = blocked; org.beachsync.proto.PearsEngine.setBlocked(ctx, emptySet()) }.padding(10.dp), Tokens.Text.body.style(Ink.accent))
                else BasicText("Undo", Modifier.clickable(role = Role.Button) { org.beachsync.proto.PearsEngine.setBlocked(ctx, undo); undo = emptySet() }.padding(10.dp), Tokens.Text.body.style(Ink.accent))
            }
        }
        Card { SwitchRow("Thank-you notes", "Once a day, after you've kept someone listening an hour", ReactPrefs.thanks) { ReactPrefs.set(ctx, thanks = it) } }
        Card {
            BasicText("DEMO: nothing here reaches a mesh yet", style = Tokens.Text.cardLabel.style(Ink.warn))
            SwitchRow("Share with other listeners", "On Wi-Fi as your profile allows · never on mobile data unless you choose", DemoSharing.shareOn) { DemoSharing.shareOn = it }
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openData), Arrangement.Center) {
                BasicText("Data and sharing", style = Tokens.Text.bodyStrong.style())
                BasicText("${DemoSharing.profileLine} · shared 1.2 GB today on Wi-Fi", style = Tokens.Text.small.style(Ink.textSecondary))
            }
        }
        // Advanced (hand-off 64): the Sync view row the spec promised, with the other sync checks beside it.
        BasicText("ADVANCED", Modifier.padding(top = 6.dp), Tokens.Text.cardLabel.style())
        Card {
            NavRow("Sync view", "Check two phones side by side, by eye and ear", openSync)
            NavRow("Beat sync view", "The same, flashing on a snare in the music", openBeat)
            NavRow("Camera test card", "Film phones side by side to time screen and sound", openTestCard)
        }
    }
}

private const val SETTINGS_TICK_MS = 1000L

/** A 60 px row that opens another screen: title, one grey line and a chevron. */
@Composable
internal fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(title, style = Tokens.Text.bodyStrong.style())
            BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        BasicText("›", Modifier.padding(start = 10.dp), Tokens.Text.screenTitle.style(Ink.textSecondary))
    }
}
