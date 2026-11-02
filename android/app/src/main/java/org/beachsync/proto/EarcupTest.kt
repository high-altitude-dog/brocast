package org.beachsync.proto

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The output a listener would hear, and the name Android gives it: for a Bluetooth headset, usually the name it reports. */
class AudioOutput(val device: AudioDeviceInfo?, val name: String, val route: String) {
    val key get() = "$route|$name"
    val external get() = device != null && device.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
}

fun currentOutput(ctx: Context): AudioOutput {
    val all = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val order = listOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET to "Bluetooth LE", AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Bluetooth",
        AudioDeviceInfo.TYPE_USB_HEADSET to "USB headset", AudioDeviceInfo.TYPE_WIRED_HEADPHONES to "Wired headphones", AudioDeviceInfo.TYPE_WIRED_HEADSET to "Wired headset",
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Phone speaker",
    )
    for ((type, route) in order) {
        val d = all.firstOrNull { it.type == type } ?: continue
        val product = d.productName?.toString().orEmpty()
        val name = if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || product.isBlank() || product == Build.MODEL) route else product
        return AudioOutput(d, name, route)
    }
    return AudioOutput(null, "No output", "None")
}

/**
 * One earcup test, on its own threads and its own audio objects: never during a run, so it can't touch the timing path.
 * The same mic session hears both legs, so its latency cancels. Each leg is timed from the output time stamp, the same one
 * playback uses, so the difference is exactly the extra lag playback would need.
 */
class EarcupRun(private val ctx: Context, private val output: AudioOutput) : Thread("earcup") {
    enum class Phase { SPEAKER, HEADPHONES, ANALYSING, DONE }

    @Volatile var phase = Phase.SPEAKER
    @Volatile var outcome: EarcupOutcome? = null
    @Volatile private var cancelled = false
    @Volatile private var recording = true
    val startedMs = System.currentTimeMillis()

    fun cancel() { cancelled = true; interrupt() }

    @SuppressLint("MissingPermission")     // MainActivity asks before starting this
    override fun run() {
        val am = ctx.getSystemService(AudioManager::class.java)
        val speaker = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val mic = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (speaker == null || mic == null || output.device == null) { finish(EarcupOutcome.Problem("This phone's speaker or mic couldn't be found.")); return }
        val rec = try { makeRecorder(am, mic) } catch (e: Exception) { finish(EarcupOutcome.Problem("The mic couldn't start: ${e.message}")); return }
        val samples = FloatArray((2 * legSeconds() + 4).toInt() * Earcup.RATE)
        var filled = 0
        val reader = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val chunk = ShortArray(960)
            rec.startRecording()
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val take = minOf(n, samples.size - filled)
                for (i in 0 until take) samples[filled + i] = chunk[i] / 32768f
                filled += take
            }
        }, "earcup-mic")
        reader.start()
        try {
            sleep(300)
            val first = playLeg(speaker, rec)
            if (cancelled) return
            phase = Phase.HEADPHONES
            val second = playLeg(output.device, rec)
            recording = false
            reader.join(500)
            if (cancelled) return
            phase = Phase.ANALYSING
            val result = if (first == null || second == null) EarcupOutcome.Problem("Android wouldn't play through both the speaker and the headphones.")
            else earcupVerdict(analyseLeg(samples, first), analyseLeg(samples, second))
            log(result)
            finish(result)
        } catch (_: InterruptedException) {
        } finally {
            recording = false
            reader.join(500)
            rec.stop(); rec.release()
            if (cancelled) phase = Phase.DONE
        }
    }

    private fun finish(o: EarcupOutcome) { outcome = o; phase = Phase.DONE }

    @SuppressLint("MissingPermission")
    private fun makeRecorder(am: AudioManager, mic: AudioDeviceInfo): AudioRecord {
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val min = AudioRecord.getMinBufferSize(Earcup.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            .setAudioSource(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Earcup.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(min * 4, Earcup.RATE))
            .build()
        r.preferredDevice = mic
        return r
    }

    /** Built like Playout's track, so its output time stamp means the same thing. Returns where each click would be in the recording. */
    private fun playLeg(device: AudioDeviceInfo, rec: AudioRecord): LongArray? {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Earcup.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val min = AudioTrack.getMinBufferSize(Earcup.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 2, 3840))
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        val ts = AudioTimestamp()
        val rs = AudioTimestamp()
        try {
            t.preferredDevice = device
            t.play()
            val click = Earcup.click()
            val spacing = (Earcup.SPACING_S * Earcup.RATE).toInt()
            val firstClick = (Earcup.PREROLL_S * Earcup.RATE).toInt()
            val total = firstClick + Earcup.CLICKS * spacing + (Earcup.TAIL_S * Earcup.RATE).toInt()
            val stampAt = firstClick + Earcup.CLICKS * spacing / 2
            val buf = FloatArray(960)
            var frame = 0
            var stamped = false
            var routed = false
            while (frame < total && !cancelled) {
                java.util.Arrays.fill(buf, 0f)
                val frames = minOf(480, total - frame)
                for (c in 0 until Earcup.CLICKS) {
                    val start = firstClick + c * spacing
                    for (i in 0 until frames) {
                        val at = frame + i - start
                        if (at >= 0 && at < click.size) { buf[2 * i] = click[at] * 0.8f; buf[2 * i + 1] = click[at] * 0.8f }
                    }
                }
                if (t.write(buf, 0, frames * 2, AudioTrack.WRITE_BLOCKING) < 0) return null
                frame += frames
                if (!routed && frame > firstClick / 2) {
                    if (t.routedDevice?.id != device.id) return null
                    routed = true
                }
                if (!stamped && frame >= stampAt) stamped = t.getTimestamp(ts) && rec.getTimestamp(rs, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            }
            if (!stamped || cancelled) return null
            return LongArray(Earcup.CLICKS) { c ->
                val start = firstClick + c * spacing
                val outNs = ts.nanoTime + (start - ts.framePosition) * 1_000_000_000L / Earcup.RATE
                rs.framePosition + (outNs - rs.nanoTime) * Earcup.RATE / 1_000_000_000L
            }
        } finally {
            t.pause(); t.flush(); t.release()
        }
    }

    private fun log(o: EarcupOutcome) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = File(dir, "earcup-results.csv")
        val fresh = !f.exists()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val row = when (o) {
            is EarcupOutcome.Pass -> "pass,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.3f,%.3f".format(Locale.ROOT, o.laterMs, o.speaker.peakMs, o.headphones.peakMs, o.scatterMs, o.speaker.strength, o.headphones.strength, o.speaker.level, o.headphones.level)
            is EarcupOutcome.Fail -> "${o.fault},,,,,,,,"
            is EarcupOutcome.Problem -> "problem,,,,,,,,"
        }
        FileWriter(f, true).use {
            if (fresh) it.write("time,phone,route,output,result,later_ms,speaker_ms,headphones_ms,scatter_ms,speaker_strength,headphones_strength,speaker_level,headphones_level\n")
            it.write("$time,${Build.MODEL},${output.route},\"${output.name.replace("\"", "'")}\",$row\n")
        }
    }

    companion object {
        fun legSeconds() = Earcup.PREROLL_S + Earcup.CLICKS * Earcup.SPACING_S + Earcup.TAIL_S
    }
}
