package org.beachsync.proto.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Settings › SCREEN (hand-offs 27 and 29): No flashing always wins; Flash on each new bar is off by default. Kept on the phone. */
object ScreenPrefs {
    var noFlashing by mutableStateOf(false)
        private set
    var barFlash by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences("screen", Context.MODE_PRIVATE)
        noFlashing = p.getBoolean("noFlashing", false); barFlash = p.getBoolean("barFlash", false); loaded = true
    }

    fun set(ctx: Context, noFlashing: Boolean = this.noFlashing, barFlash: Boolean = this.barFlash) {
        this.noFlashing = noFlashing; this.barFlash = barFlash
        ctx.getSharedPreferences("screen", Context.MODE_PRIVATE).edit().putBoolean("noFlashing", noFlashing).putBoolean("barFlash", barFlash).apply()
    }

    /** Whether the beat counter may flash on beat 1. */
    val flashBar get() = barFlash && !noFlashing
}
