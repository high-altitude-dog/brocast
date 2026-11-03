package org.beachsync.proto

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
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
enum class OutputKind(val label: String) { PHONE_SPEAKER("SPEAKER"), HEADPHONES("HEADPHONES"), PORTABLE_SPEAKER("PORTABLE SPEAKER"), CAR("CAR AUDIO") }

class AudioOutput(val device: AudioDeviceInfo?, val name: String, val route: String, val kind: OutputKind) {
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
        val bt = bluetoothDevice(ctx, d)
        // The name the device itself reports, never the one a person gave it on their phone: results get compared across listeners.
        @SuppressLint("MissingPermission") val reported = try { bt?.name } catch (_: Exception) { null }
        val product = reported ?: d.productName?.toString().orEmpty()
        val name = if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || product.isBlank() || product == Build.MODEL) route else product
        val kind = if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) OutputKind.PHONE_SPEAKER else bluetoothKind(bt)
        return AudioOutput(d, name, route, kind)
    }
    return AudioOutput(null, "No output", "None", OutputKind.PHONE_SPEAKER)
}

/** What's connected, as far as Bluetooth says. Null without the nearby devices permission. */
@SuppressLint("MissingPermission")     // checked here
private fun bluetoothDevice(ctx: Context, d: AudioDeviceInfo): BluetoothDevice? {
    if (d.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && d.type != AudioDeviceInfo.TYPE_BLE_HEADSET) return null
    if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return null
    if (!BluetoothAdapter.checkBluetoothAddress(d.address)) return null
    return try { ctx.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(d.address) } catch (_: Exception) { null }
}

/** The kind of Bluetooth audio device, from the class it declares. Some devices declare this wrongly or vaguely. */
@SuppressLint("MissingPermission")
private fun bluetoothKind(device: BluetoothDevice?): OutputKind {
    return when (try { device?.bluetoothClass?.deviceClass } catch (_: Exception) { null }) {
        BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> OutputKind.PORTABLE_SPEAKER
        BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> OutputKind.CAR
        else -> OutputKind.HEADPHONES
    }
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
            val first = playLeg(speaker, rec, driftSpeaker)
            if (cancelled) return
            phase = Phase.HEADPHONES
            val second = playLeg(output.device, rec, driftHeadphones)
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
    @Volatile private var micSource = "?"
    /** How far the output time stamp's offset moved during a leg, in ms: from near the start to the middle stamp, and the middle to the end. Logged only. */
    private val driftSpeaker = DoubleArray(2) { Double.NaN }
    private val driftHeadphones = DoubleArray(2) { Double.NaN }

    private fun makeRecorder(am: AudioManager, mic: AudioDeviceInfo): AudioRecord {
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        micSource = if (unprocessed) "UNPROCESSED" else "MIC"
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
    private fun playLeg(device: AudioDeviceInfo, rec: AudioRecord, drift: DoubleArray): LongArray? {
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Earcup.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val min = AudioTrack.getMinBufferSize(Earcup.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min * 2, 3840))
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        val ts = AudioTimestamp()
        val rs = AudioTimestamp()
        val tsx = AudioTimestamp()
        fun offsetMs(x: AudioTimestamp) = (x.nanoTime - x.framePosition * 1_000_000_000L / Earcup.RATE) / 1e6
        var offStart = Double.NaN
        var offEnd = Double.NaN
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
                if (offStart.isNaN() && frame >= Earcup.RATE / 2 && t.getTimestamp(tsx) && tsx.framePosition > 0) offStart = offsetMs(tsx)
                if (offEnd.isNaN() && total - frame <= 960 && t.getTimestamp(tsx) && tsx.framePosition > 0) offEnd = offsetMs(tsx)
            }
            if (!stamped || cancelled) return null
            val mid = offsetMs(ts)
            drift[0] = offStart - mid
            drift[1] = offEnd - mid
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
        // Rows gained a column: an older file keeps its own header, so it moves aside rather than mixing the two.
        if (f.exists() && !f.useLines { it.first() }.contains("headphones_drift_end_ms")) f.renameTo(File(dir, "earcup-results-old.csv"))
        val fresh = !f.exists()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.UK).format(Date())
        val row = when (o) {
            is EarcupOutcome.Pass -> {
                val a = summarise(o.speaker.readingsMs)
                val b = summarise(o.headphones.readingsMs)
                "pass,%.2f,%.2f,%.2f,%.2f,%.1f,%.1f,%.3f,%.3f,%.2f,%.2f,%d,%d,%s,%.3f,%.3f,%.3f,%.3f".format(Locale.ROOT, o.laterMs, o.speaker.peakMs, o.headphones.peakMs, o.scatterMs, o.speaker.strength, o.headphones.strength, o.speaker.level, o.headphones.level,
                    a?.scatterMs ?: Double.NaN, b?.scatterMs ?: Double.NaN, o.speaker.readingsMs.size, o.headphones.readingsMs.size, micSource,
                    driftSpeaker[0], driftSpeaker[1], driftHeadphones[0], driftHeadphones[1])
            }
            is EarcupOutcome.Fail -> "${o.fault},,,,,,,,,,,,,,,,,"
            is EarcupOutcome.Problem -> "problem,,,,,,,,,,,,,,,,,"
        }
        FileWriter(f, true).use {
            if (fresh) it.write("time,phone,route,kind,output,result,later_ms,speaker_ms,headphones_ms,scatter_ms,speaker_strength,headphones_strength,speaker_level,headphones_level,speaker_scatter_ms,headphones_scatter_ms,speaker_clicks,headphones_clicks,mic_source,speaker_drift_start_ms,speaker_drift_end_ms,headphones_drift_start_ms,headphones_drift_end_ms\n")
            it.write("$time,${Build.MODEL},${output.route},${output.kind},\"${output.name.replace("\"", "'")}\",$row\n")
        }
    }

    companion object {
        fun legSeconds() = Earcup.PREROLL_S + Earcup.CLICKS * Earcup.SPACING_S + Earcup.TAIL_S
    }
}
