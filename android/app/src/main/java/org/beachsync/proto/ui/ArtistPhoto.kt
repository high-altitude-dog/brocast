package org.beachsync.proto.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.beachsync.proto.FlowTime
import org.beachsync.proto.LibraryItem
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.playingAt
import java.io.File

/** The library item the shared timeline has at the ear, from flowtime (or the wall clock before there is a fit). */
internal fun nowPlayingItem(lib: LibraryStore?, boot: Long): LibraryItem? {
    lib ?: return null
    val utc = FlowTime.fit()?.utcAt(boot) ?: (System.currentTimeMillis() * 1_000_000L)
    return playingAt(lib.editions, lib.dMs, utc)?.first
}

/** Decodes a photo off the main thread, scaled down so no more than about [maxPx] pixels wide are held in memory. Null when there is none. */
@Composable
internal fun rememberPhoto(file: File?, maxPx: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, file?.path, file?.lastModified()) {
        value = if (file == null || !file.exists()) null else withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    return bitmap
}

private fun initials(text: String): String =
    text.split(' ', '&', '-').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

/** A 52 px artist photo beside the title; with no photo yet, the artist's initials. Opens the full-screen viewer. */
@Composable
internal fun ArtistThumb(lib: LibraryStore?, item: LibraryItem?, open: () -> Unit) {
    val photo = rememberPhoto(item?.let { lib?.photo(it.id) }, 200)
    val shape = RoundedCornerShape(10.dp)
    val label = item?.artist?.ifEmpty { null } ?: item?.title ?: "Artist"
    Box(
        Modifier.size(52.dp).clip(shape).background(Ink.surface, shape).border(Tokens.Stroke.card.dp, Ink.border, shape)
            .clickable(enabled = item != null, role = Role.Button, onClick = open).semantics { contentDescription = "Photo of $label. Opens full screen." },
        Alignment.Center,
    ) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BasicText(initials(label), style = Tokens.Text.bodyStrong.style(Ink.textSecondary))
    }
}

/** Full-screen artist photo (hand-off 16): the photo on black, a close button, and a caption over a dark gradient. Tap anywhere to close. */
@Composable
fun ArtistPhotoScreen(state: BenchState, back: () -> Unit) {
    val boot = org.beachsync.proto.Session.nowBoot()
    val lib = state.library
    val item = nowPlayingItem(lib, boot)
    val photo = rememberPhoto(item?.let { lib?.photo(it.id) }, 1440)
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = back)) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else BasicText(initials(item?.artist?.ifEmpty { null } ?: item?.title ?: "?"), Modifier.align(Alignment.Center), Tokens.Text.screenTitle.style(Ink.textSecondary).copy(fontSize = 72.sp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Box(
            Modifier.safeDrawingPadding().padding(12.dp).size(Tokens.Space.touchTarget.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).align(Alignment.TopEnd)
                .clickable(role = Role.Button, onClick = back).semantics { contentDescription = "Close" },
            Alignment.Center,
        ) { BasicText("×", style = Tokens.Text.screenTitle.style(Color.White)) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("NOW PLAYING", style = Tokens.Text.eyebrow.style(Color.White.copy(alpha = 0.7f)))
            BasicText(item?.artist?.ifEmpty { null } ?: item?.title ?: "", style = Tokens.Text.screenTitle.style(Color.White).copy(fontSize = 30.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item?.artist?.isNotEmpty() == true) BasicText(item.title, style = Tokens.Text.body.style(Color.White.copy(alpha = 0.8f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
