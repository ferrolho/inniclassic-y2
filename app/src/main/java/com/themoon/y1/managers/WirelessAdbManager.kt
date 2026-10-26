package com.themoon.y1.managers

import android.app.Activity
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.themoon.y1.core.extensions.onOff

/**
 * Turns adb over Wi-Fi (TCP port 5555) on and off, as `adb tcpip 5555` and `adb usb` do, without a USB cable.
 *
 * Only `service.adb.tcp.port` is set, never `persist.adb.tcp.port`: a restart always goes back to USB adb.
 * This is for safety: the ROM has root adb with no key check, so while this is on, each device on the
 * Wi-Fi network can open a root shell. The state is always read from the property, never stored.
 */
object WirelessAdbManager {
    private const val TAG = "Y1WirelessAdb"
    const val PORT = 5555
    const val PROP = "service.adb.tcp.port"
    private const val PERSIST_PROP = "persist.adb.tcp.port"

    @Volatile
    private var isBusy = false

    /** Root shell command: set the port (-1 = USB only), then restart adbd so that it reads the port. */
    fun command(on: Boolean): String = "setprop $PROP ${if (on) PORT else -1}; stop adbd; start adbd"

    /**
     * The TCP port that adbd uses for these property values, or null for USB only.
     * adbd's rule: `service.adb.tcp.port`, or `persist.adb.tcp.port` if the first is empty; TCP if the value is > 0.
     */
    fun tcpPort(serviceProp: String?, persistProp: String?): Int? {
        val value = if (serviceProp.isNullOrBlank()) persistProp else serviceProp
        val port = value?.trim()?.toIntOrNull() ?: return null
        return if (port > 0) port else null
    }

    /** Dotted IPv4 text of [WifiManager]'s `ipAddress` (low byte first), or null for 0 (no address). */
    fun ipText(ip: Int): String? =
        if (ip == 0) null
        else "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip ushr 24) and 0xFF}"

    /** Address to give to `adb connect`: "192.168.1.5:5555", or "port 5555" when Wi-Fi has no address. */
    fun address(port: Int, ip: String?): String = if (ip == null) "port $port" else "$ip:$port"

    /** Row value: "On" or "Off", with the on/off mark (Toggle Style = Switch shows a switch). */
    fun label(port: Int?, localize: (String) -> String): String = onOff(port != null, localize)

    /** Text on the left of the row value: the address when adb over Wi-Fi is on, else null (no text). */
    fun addressLabel(port: Int?, ip: String?): String? = port?.let { address(it, ip) }

    private fun readProp(key: String): String? =
        try {
            val sp = Class.forName("android.os.SystemProperties")
            sp.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String
        } catch (_: Throwable) {
            ExternalSdMountMonitor.readProp(key)
        }

    /** The TCP port that adbd uses now, or null for USB only. */
    fun currentPort(): Int? = tcpPort(readProp(PROP), readProp(PERSIST_PROP))

    fun wifiIp(context: Context): String? =
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ipText(wm?.connectionInfo?.ipAddress ?: 0)
        } catch (_: Throwable) {
            null
        }


    /**
     * Turns adb over Wi-Fi on or off off the main thread (root is necessary for setprop and adbd), then calls
     * [onDone] on the main thread with the new port (null = USB only). Returns false if a change is in progress.
     */
    fun setOn(activity: Activity, on: Boolean, onDone: (port: Int?) -> Unit): Boolean {
        if (isBusy) return false
        isBusy = true
        Thread({
            try {
                ExternalSdMountMonitor.runSuTimed(command(on))
            } catch (t: Throwable) {
                Log.w(TAG, "su command failed (on=$on): ${t.message}")
            } finally {
                isBusy = false
                val port = currentPort()
                activity.runOnUiThread { onDone(port) }
            }
        }, "WirelessAdb").start()
        return true
    }
}
