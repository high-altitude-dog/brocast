package org.beachsync.proto

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The seven output pictures from hand-off 34, with the word each puts on Now playing's output tile. */
enum class OutputIcon(val heading: String) {
    SPEAKER("SPEAKER"), WIRED("HEADPHONES"), USB("USB-C"), BT_HEADPHONES("BLUETOOTH"), BT_EARBUDS("BLUETOOTH"), BT_SPEAKER("BLUETOOTH"), OTHER("OUTPUT");

    val bluetooth get() = this == BT_HEADPHONES || this == BT_EARBUDS || this == BT_SPEAKER
}

/**
 * The codec Bluetooth is using, for the output tile ("Sony WH-1000XM5 · LDAC"). Android hands out the A2DP profile asynchronously, so the first
 * read after the app starts may be null. Needs the nearby devices permission, and Android 13 for a public codec call.
 */
object A2dpCodec {
    @Volatile private var a2dp: BluetoothA2dp? = null
    @Volatile private var asked = false

    fun connect(ctx: Context) {
        if (asked || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        asked = true
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { a2dp = proxy as? BluetoothA2dp }
            override fun onServiceDisconnected(profile: Int) { a2dp = null; asked = false }
        }
        try { ctx.getSystemService(BluetoothManager::class.java).adapter?.getProfileProxy(ctx.applicationContext, listener, BluetoothProfile.A2DP) } catch (_: Exception) { asked = false }
    }

    /** getCodecStatus is a system API, reached by reflection. If Android blocks it, there's simply no codec to show. */
    private val codecStatus by lazy { try { BluetoothA2dp::class.java.getMethod("getCodecStatus", BluetoothDevice::class.java) } catch (_: Exception) { null } }

    fun of(device: BluetoothDevice): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val proxy = a2dp ?: return null
        val status = try { codecStatus?.invoke(proxy, device) as? BluetoothCodecStatus } catch (_: Exception) { null }
        @Suppress("DEPRECATION") val type = status?.codecConfig?.codecType ?: return null   // its replacement needs Android 15
        // BluetoothCodecConfig.SOURCE_CODEC_TYPE_*
        return when (type) { 0 -> "SBC"; 1 -> "AAC"; 2 -> "aptX"; 3 -> "aptX HD"; 4 -> "LDAC"; 5 -> "LC3"; 6 -> "Opus"; else -> null }
    }
}
