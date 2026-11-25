package org.beachsync.proto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.beachsync.proto.SharedLibrary

/** Settings › Music folder: where the sets are kept. The current choice is preselected (the default when there is none). */
@Composable
internal fun MusicChoiceScreen(current: String, error: String, choose: (String) -> Unit, back: () -> Unit) {
    var picked by remember { mutableStateOf(current.ifEmpty { SharedLibrary.DOCS }) }
    Screen {
        ScreenHeader("Where should your music live?", back)
        BasicText("Sets are big, so Brocast keeps them on this phone. Choose what happens to them if the app is removed.", style = Tokens.Text.body.style())
        MusicOption(picked == SharedLibrary.DOCS, "Documents/Brocast (recommended)",
            "Survives the app being removed, with no permission. After a reinstall Brocast asks once to use the folder again.") { picked = SharedLibrary.DOCS }
        MusicOption(picked == SharedLibrary.FOLDER, "Another folder",
            "Survives removal too. You pick a folder once, and the app can see only that folder. After a reinstall, pick it again and the music is taken back in a few seconds.") { picked = SharedLibrary.FOLDER }
        MusicOption(picked == SharedLibrary.ALL, "All files access",
            "Survives removal too, and after a reinstall nothing is copied. But Android lets the app see every file in shared storage. You turn it on in Android settings.") { picked = SharedLibrary.ALL }
        MusicOption(picked == SharedLibrary.APP, "This app only",
            "No permission needed. Everything is removed with the app.") { picked = SharedLibrary.APP }
        if (error.isNotEmpty()) BasicText(error, style = Tokens.Text.body.style(Ink.accent))
        PillButton(when (picked) { SharedLibrary.FOLDER -> "Choose a folder"; SharedLibrary.ALL -> "Open Android settings"; else -> "Use this" }, true) { choose(picked) }
    }
}

/** A fresh install that finds Documents/Brocast from an earlier one: the library waits for the answer. */
@Composable
internal fun FoundMusicScreen(error: String, answer: (Boolean) -> Unit) {
    Screen {
        BasicText("Found music from an earlier install", style = Tokens.Text.screenTitle.style())
        BasicText("Brocast can use the music already in Documents/Brocast, so it doesn't download it again. Android asks you to allow this once.", style = Tokens.Text.body.style())
        if (error.isNotEmpty()) BasicText(error, style = Tokens.Text.body.style(Ink.accent))
        PillButton("Use it", true) { answer(true) }
        PillButton("Start fresh", false) { answer(false) }
    }
}

@Composable
private fun MusicOption(on: Boolean, title: String, detail: String, select: () -> Unit) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        Modifier.fillMaxWidth().background(Ink.surface, shape).border(if (on) Tokens.Stroke.emphasis.dp else Tokens.Stroke.card.dp, if (on) Ink.accent else Ink.border, shape)
            .selectable(on, role = Role.RadioButton, onClick = select).padding(vertical = 12.dp, horizontal = Tokens.Space.cardPaddingHorizontal.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicText((if (on) "● " else "○ ") + title, style = Tokens.Text.bodyStrong.style().copy(fontSize = 17.sp))
        BasicText(detail, style = Tokens.Text.small.style(Ink.textSecondary))
    }
}
