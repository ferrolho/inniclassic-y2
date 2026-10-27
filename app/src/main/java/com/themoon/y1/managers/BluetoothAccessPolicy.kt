package com.themoon.y1.managers

import android.bluetooth.BluetoothClass

/**
 * Pure rules for the access that the Y2 gives to a paired car or phone. The Y2 is a music player:
 * it gives no contacts, call history or messages (PBAP, MAP), and it does not act as a phone (HFP)
 * for a car. Audio (A2DP) and remote control (AVRCP) stay on.
 */
object BluetoothAccessPolicy {

    // BluetoothDevice.REQUEST_TYPE_* and CONNECTION_ACCESS_* (hidden on API 19).
    const val REQUEST_TYPE_PROFILE_CONNECTION = 1
    const val REQUEST_TYPE_PHONEBOOK_ACCESS = 2
    const val REQUEST_TYPE_MESSAGE_ACCESS = 3
    const val CONNECTION_ACCESS_NO = 2

    // CachedBluetoothDevice.ACCESS_REJECTED of the Android 4.4 Settings app.
    const val SETTINGS_ACCESS_REJECTED = 2

    /** Settings shared preference files that keep the phone book and message choice per device address. */
    val SETTINGS_PREF_FILES = listOf("bluetooth_phonebook_permission", "bluetooth_message_permission")

    /** Phone book and message requests get No. Other requests are left to the system. */
    fun rejects(requestType: Int): Boolean =
        requestType == REQUEST_TYPE_PHONEBOOK_ACCESS || requestType == REQUEST_TYPE_MESSAGE_ACCESS

    /**
     * True when the Y2 must not be a hands-free phone (HFP) for this device: car kits, hands-free
     * units, phones and computers. Headsets, headphones and speakers keep HFP, as before.
     * [deviceClass] is BluetoothClass.getDeviceClass(), or null when not known.
     */
    fun turnOffHandsFree(deviceClass: Int?): Boolean {
        if (deviceClass == null) return false
        return when (deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> true
            else -> {
                val major = deviceClass and 0x1F00
                major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER
            }
        }
    }

    /** Content of a Settings preference file that rejects access for each address. */
    fun rejectedPrefsXml(addresses: Collection<String>): String {
        val sb = StringBuilder("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        val sorted = addresses.filter { ADDRESS.matches(it) }.distinct().sorted()
        if (sorted.isEmpty()) return sb.append("<map />\n").toString()
        sb.append("<map>\n")
        for (a in sorted) sb.append("    <int name=\"").append(a).append("\" value=\"").append(SETTINGS_ACCESS_REJECTED).append("\" />\n")
        return sb.append("</map>\n").toString()
    }

    /** True when the file already rejects access for all [addresses]. */
    fun prefsRejectAll(content: String?, addresses: Collection<String>): Boolean {
        if (content == null) return false
        return addresses.all { a -> content.contains("name=\"$a\" value=\"$SETTINGS_ACCESS_REJECTED\"") }
    }

    private val ADDRESS = Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")
}
