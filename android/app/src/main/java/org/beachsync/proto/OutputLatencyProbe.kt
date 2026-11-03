package org.beachsync.proto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads what Android says about the current output's latency, with a silent track: it writes zeros, so nothing is heard.
 * Only adb can send it (the manifest requires DUMP), and it does nothing while a run is active, so it never shares the
 * audio path with a measurement. Results go to `output-latency.csv` in the app's files. It runs on its own thread and
 * touches none of the timing code.
 */
class OutputLatencyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (MainActivity.session != null) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try { OutputLatencyProbe.run(app) } catch (_: Throwable) { } finally { pending.finish() }
        }, "latency-probe").start()
    }
}

object OutputLatencyProbe {
    private fun hidden(block: () -> Any?): String = try { block()?.toString() ?: "n/a" } catch (_: Throwable) { "n/a" }

    fun run(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 2, 3840))
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        val zeros = FloatArray(960 * 2)
        var ts = android.media.AudioTimestamp()
        var stamped = false
        var route = "?"
        var routeType = -1
        var latency = "n/a"
        var sysLatency = "n/a"
        var buffer = -1
        var mode = -1
        try {
            t.play()
            for (i in 0 until 75) {              // 1.5 s of silence
                t.write(zeros, 0, zeros.size, AudioTrack.WRITE_BLOCKING)
                if (i == 40) {
                    stamped = t.getTimestamp(ts)
                    val d = t.routedDevice
                    if (d != null) { routeType = d.type; route = d.productName?.toString() ?: "?" }
                    latency = hidden { AudioTrack::class.java.getMethod("getLatency").invoke(t) }
                    sysLatency = hidden { AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType).invoke(am, AudioManager.STREAM_MUSIC) }
                    buffer = t.bufferSizeInFrames
                    mode = t.performanceMode
                }
            }
        } finally {
            t.pause(); t.flush(); t.release()
        }
        val fpb = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) ?: "?"
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "output-latency.csv")
        val fresh = !f.exists()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        FileWriter(f, true).use {
            if (fresh) it.write("time,phone,route_type,route,track_latency_ms,system_output_latency_ms,frames_per_buffer,buffer_frames,performance_mode,stamped\n")
            it.write("$time,${Build.MODEL},$routeType,\"${route.replace("\"", "'")}\",$latency,$sysLatency,$fpb,$buffer,$mode,$stamped\n")
        }
    }
}
