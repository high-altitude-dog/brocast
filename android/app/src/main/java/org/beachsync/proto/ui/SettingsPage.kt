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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Only the LISTENING section for now: the design's other sections have nothing behind them yet. */
@Composable
fun SettingsPage(state: BenchState, actions: BenchActions, back: () -> Unit, openCalibration: () -> Unit) {
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
            SatellitesRow(state.satellites, actions::setSatellites, "Position stays on this phone · off = Privacy mode")
            Column(Modifier.fillMaxWidth().heightIn(min = Tokens.Space.touchTarget.dp).clickable(role = Role.Button, onClick = openCalibration), Arrangement.Center) {
                BasicText("Calibration", style = Tokens.Text.bodyStrong.style())
                val out = actions.output()
                val cal = actions.savedCalibration(out) ?: actions.tableCalibration(out)
                val value = if (cal.source == org.beachsync.proto.CalSource.NONE) "Not measured" else "%.1f ms · ${cal.source.name.lowercase().replaceFirstChar { it.uppercase() }}".format(java.util.Locale.ROOT, cal.lagMs)
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
    }
}

private const val SETTINGS_TICK_MS = 1000L
