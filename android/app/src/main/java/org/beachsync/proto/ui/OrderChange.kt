package org.beachsync.proto.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.beachsync.proto.Edition
import org.beachsync.proto.Event
import org.beachsync.proto.EventLog
import org.beachsync.proto.FlowTime
import org.beachsync.proto.Lane
import org.beachsync.proto.LibraryStore
import org.beachsync.proto.MainActivity
import org.beachsync.proto.editionAt
import org.beachsync.proto.segmentAt
import org.beachsync.proto.upcomingEdition
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A new running order on the phone (hand-off 41): announced when the phone first sees it, counted down on Now playing, switched on the same
 * flowtime second as every other phone, or called off. Ticked once a second on the main thread by MainActivity; reads the library only.
 */
object OrderNews {
    /** The queued edition, while it's still to come at the ear. */
    var upcoming by mutableStateOf<Edition?>(null)
        private set
    /** A change that was called off, and until when (boot ms) the grey card shows: 1 min. */
    var calledOff by mutableStateOf<Pair<Long, Long>?>(null)
        private set
    /** The toast at the switch or after being away, and until when (boot ms): 6 s. */
    var toast by mutableStateOf<Pair<String, Long>?>(null)
        private set

    private const val PREFS = "orderNews"
    private const val CHANNEL = "news"

    private fun key(e: Edition) = "${e.startNs}/${e.number}"

    fun tick(ctx: Context, lib: LibraryStore?, log: EventLog?) {
        val eds = lib?.editions?.takeIf { it.isNotEmpty() } ?: return
        val boot = SystemClock.elapsedRealtimeNanos()
        val utc = FlowTime.fit()?.utcAt(boot) ?: (System.currentTimeMillis() * 1_000_000L)
        val dNs = lib.dMs * 1_000_000L
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowMs = SystemClock.elapsedRealtime()
        fun note(text: String) = log?.add(Event(boot, utc, Lane.NET, text))

        val up = upcomingEdition(eds, lib.dMs, utc)
        upcoming = up
        val announced = p.getString("announced", null)
        if (up != null && key(up) != announced) {
            p.edit().putString("announced", key(up)).apply()
            val msg = up.message.takeIf { it.isNotBlank() }?.let { ": “" + it.take(40) + (if (it.length > 40) "…”" else "”") }.orEmpty()
            note("The station published a new running order for ${hhmm(up.startNs + dNs)}$msg")
            notify(ctx, up, lib)
        }
        // Called off: a change this phone announced is gone before it began, and nothing replaced it.
        if (up == null && announced != null) {
            val start = announced.substringBefore('/').toLongOrNull()
            if (start != null && start + dNs > utc && (start in lib.calledOff || eds.none { it.startNs == start })) {
                calledOff = start to nowMs + 60_000L
                note("The change at ${hhmm(start + dNs)} was called off.")
                p.edit().remove("announced").apply()
            }
        }
        calledOff?.let { if (nowMs > it.second) calledOff = null }

        // The switch, or a change while the app wasn't watching.
        val live = editionAt(eds, utc - dNs) ?: return
        val lastLive = p.getString("live", null)
        if (lastLive != key(live)) {
            p.edit().putString("live", key(live)).apply()
            val prev = lastLive?.substringBefore('/')?.toLongOrNull()
            if (prev != null && live.startNs > prev) {
                val heard = live.startNs + dNs
                val on = segmentAt(eds, Math.floorDiv(utc - dNs - org.beachsync.proto.STREAM_EPOCH_NS, 1_000_000_000L))?.first?.title ?: "the new order"
                if (utc - heard < 10_000_000_000L) {
                    toast = "New running order: $on is on now." to nowMs + 6_000L
                    note("New running order${if (live.number > 0) " (edition ${live.number})" else ""}. $on is on.")
                } else {
                    toast = "The running order changed at ${hhmm(heard)} while you were away." to nowMs + 6_000L
                    note("The running order changed at ${hhmm(heard)} while you were away.")
                }
            }
        }
        toast?.let { if (nowMs > it.second) toast = null }
    }

    /** Once per new order, if notifications are allowed. No second notification at the switch. */
    private fun notify(ctx: Context, up: Edition, lib: LibraryStore) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Station news", NotificationManager.IMPORTANCE_DEFAULT))
        val after = changeLine(lib.editions, lib.dMs, up)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Running order changes at ${hhmm(up.startNs + lib.dMs * 1_000_000L)}")
            .setContentText(up.message.ifBlank { after?.let { "${it.lead} ${it.new}" + (it.was?.let { w -> " (was $w)" } ?: "") }.orEmpty() })
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(41, n)
    }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal fun hhmm(utcNs: Long): String = CLOCK.format(Instant.ofEpochMilli(utcNs / 1_000_000L).atZone(ZoneId.systemDefault()))

/** "After Overpass:" (at a set boundary) or "At 12:31:" (a set time), the new set and what the old order had there. */
internal class ChangeLine(val lead: String, val new: String, val newId: String, val was: String?)

internal fun changeLine(eds: List<Edition>, dMs: Long, up: Edition): ChangeLine? {
    val n = Math.floorDiv(up.startNs - org.beachsync.proto.STREAM_EPOCH_NS, 1_000_000_000L)
    val new = segmentAt(eds, n)?.first ?: return null
    val before = eds.filter { it.startNs < up.startNs }
    val old = segmentAt(before, n)
    val ending = segmentAt(before, n - 1)?.first
    val lead = if (old != null && old.second == 0L && ending != null) "After ${ending.title}:" else "At ${hhmm(up.startNs + dMs * 1_000_000L)}:"
    return ChangeLine(lead, new.title, new.id, old?.first?.takeIf { it.id != new.id }?.title)
}

/** In the latest-event row's place: cyan, news not a warning. Whole minutes, then seconds in the last minute. Tap opens the event log. */
@Composable
internal fun OrderChangeCard(lib: LibraryStore, up: Edition, utcNs: Long, openLog: () -> Unit) {
    val cyan = Lane.NET.color()
    val heard = up.startNs + lib.dMs * 1_000_000L
    val left = maxOf(0L, (heard - utcNs) / 1_000_000_000L)
    val inText = if (left < 60) "in $left s" else "in ${(left + 59) / 60} m"
    val line = changeLine(lib.editions, lib.dMs, up)
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(
        Modifier.fillMaxWidth().border(1.dp, cyan, shape).clickable(role = Role.Button, onClick = openLog).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("RUNNING ORDER CHANGES", Modifier.weight(1f), Tokens.Text.cardLabel.style(cyan))
            BasicText("${hhmm(heard)} · $inText", style = Tokens.Text.valueSmall.style())
        }
        if (up.message.isNotBlank()) BasicText("“${up.message}”", style = Tokens.Text.body.style())
        if (line != null) BasicText(
            buildAnnotatedString {
                append(line.lead + " ")
                withStyle(SpanStyle(color = Ink.text)) { append(line.new) }
                line.was?.let { append(" (was $it)") }
                append(if (lib.file(line.newId).exists()) " · ready on this phone ✓" else " · downloading")
            },
            style = Tokens.Text.small.style(Ink.muted),
        )
    }
}

/** The grey card for 1 min after a change is called off. */
@Composable
internal fun CalledOffCard(startNs: Long, dMs: Long) {
    val shape = RoundedCornerShape(Tokens.Radius.card.dp)
    Column(Modifier.fillMaxWidth().border(1.dp, Ink.border, shape).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText("RUNNING ORDER", style = Tokens.Text.cardLabel.style(Ink.muted))
        BasicText("The change at ${hhmm(startNs + dMs * 1_000_000L)} was called off.", style = Tokens.Text.body.style(Ink.textSecondary))
    }
}
