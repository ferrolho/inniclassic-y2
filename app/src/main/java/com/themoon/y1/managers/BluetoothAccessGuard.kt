package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Keeps the Android access dialogs away: "Phone book request" (PBAP, also HFP phone book) and
 * "Message access request" (MAP). These dialogs of the Settings app need a touch screen; the
 * click wheel cannot answer them. The Y2 is a music player, so the answer is always No.
 *
 * 1. For each paired device, the Settings choice files say "rejected" (as after two No answers).
 *    Then Settings answers No itself and shows no dialog.
 * 2. If a request comes before that (a new car), the guard answers No at once and force-stops
 *    Settings, which removes a dialog or notification that it already shows. A force-stopped
 *    Settings gets no more access requests until it opens again (the Y2 does not use it).
 * 3. Car kits, hands-free units, phones and computers get hands-free (HFP) priority Off, so the Y2
 *    is not a phone for the car. Audio (A2DP) and remote control (AVRCP) stay on.
 *
 * The access reply is a protected broadcast and the Settings files belong to the system,
 * so the guard uses su (like other parts of the app).
 */
class BluetoothAccessGuard(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var registered = false
    private var headset: BluetoothProfile? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_CONNECTION_ACCESS_REQUEST -> onAccessRequest(intent)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null && (state == BluetoothDevice.BOND_BONDED || state == BluetoothDevice.BOND_BONDING)) {
                        protect(device)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                        connectHeadsetProxy()
                        protectAll()
                    }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECTION_ACCESS_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        registered = true
        connectHeadsetProxy()
        protectAll()
    }

    fun unregister() {
        if (!registered) return
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        handler.removeCallbacksAndMessages(null)
    }

    /** Before and after a bond: no access, and no hands-free for a car (the stack keeps an Off priority). */
    fun protect(device: BluetoothDevice) {
        turnOffHandsFree(device)
        val address = device.address ?: return
        worker.execute { ensureSettingsRejects(bondedAddresses() + address) }
    }

    private fun protectAll() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!adapter.isEnabled) return
        try { adapter.bondedDevices?.forEach { turnOffHandsFree(it) } } catch (_: Exception) {}
        worker.execute { ensureSettingsRejects(bondedAddresses()) }
    }

    @SuppressLint("MissingPermission")
    private fun onAccessRequest(intent: Intent) {
        val type = intent.getIntExtra(EXTRA_ACCESS_REQUEST_TYPE, BluetoothAccessPolicy.REQUEST_TYPE_PROFILE_CONNECTION)
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val rejects = BluetoothAccessPolicy.rejects(type)
        Log.w(TAG, "Access request type=$type from ${device?.address}: " +
            if (rejects) "answer No, close Settings" else "no answer (the system asks)")
        if (!rejects) return
        device?.let { turnOffHandsFree(it) }
        worker.execute {
            // The services (PBAP, MAP, HFP phone book) do not read the device from the reply.
            su("am broadcast -a $ACTION_CONNECTION_ACCESS_REPLY" +
                " --ei $EXTRA_ACCESS_REQUEST_TYPE $type" +
                " --ei $EXTRA_CONNECTION_ACCESS_RESULT ${BluetoothAccessPolicy.CONNECTION_ACCESS_NO}")
            ensureSettingsRejects(bondedAddresses() + listOfNotNull(device?.address), forceRestart = false)
        }
        // Settings may already show its dialog (or start it a moment later): close Settings.
        for (delay in CLOSE_SETTINGS_DELAYS_MS) {
            handler.postDelayed({ worker.execute { su("am force-stop $SETTINGS_PACKAGE") } }, delay)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bondedAddresses(): List<String> = try {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.mapNotNull { it.address } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Write "rejected" for each address into the Settings choice files, if one is missing. Settings
     * reads the files when its process starts, so it is stopped after a change.
     */
    private fun ensureSettingsRejects(addresses: List<String>, forceRestart: Boolean = true) {
        if (addresses.isEmpty()) return
        val files = BluetoothAccessPolicy.SETTINGS_PREF_FILES
        val current = files.map { su("cat $SETTINGS_PREFS/$it.xml 2>/dev/null") }
        if (files.indices.all { BluetoothAccessPolicy.prefsRejectAll(current[it], addresses) }) return
        val xml = BluetoothAccessPolicy.rejectedPrefsXml(addresses)
        val tmp = File(context.cacheDir, "bt_access_reject.xml")
        try {
            tmp.writeText(xml)
            tmp.setReadable(true, false)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot write ${tmp.path}", e)
            return
        }
        val script = StringBuilder()
        for (f in files) {
            val path = "$SETTINGS_PREFS/$f.xml"
            // Writing into the file keeps its owner and SELinux label; set them for a new file.
            script.append("cat ${tmp.path} > $path; chown system:system $path; chmod 660 $path; restorecon $path; ")
        }
        if (forceRestart) script.append("am force-stop $SETTINGS_PACKAGE; ")
        su(script.toString())
        tmp.delete()
        Log.w(TAG, "Settings: phone book and message access rejected for $addresses")
    }

    private fun connectHeadsetProxy() {
        if (headset != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    headset = proxy
                    protectAll()
                }

                override fun onServiceDisconnected(profile: Int) {
                    headset = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.w(TAG, "Headset proxy failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun turnOffHandsFree(device: BluetoothDevice) {
        val hs = headset ?: return
        val deviceClass = try { device.bluetoothClass?.deviceClass } catch (_: Exception) { null }
        if (!BluetoothAccessPolicy.turnOffHandsFree(deviceClass)) return
        try {
            val cls = hs.javaClass
            val priority = cls.getMethod("getPriority", BluetoothDevice::class.java).invoke(hs, device) as? Int
            if (priority != PRIORITY_OFF) {
                cls.getMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                    .invoke(hs, device, PRIORITY_OFF)
                Log.w(TAG, "Hands-free Off for ${device.address} (class $deviceClass, was $priority)")
            }
            if (hs.getConnectionState(device) != BluetoothProfile.STATE_DISCONNECTED) {
                cls.getMethod("disconnect", BluetoothDevice::class.java).invoke(hs, device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Hands-free Off failed for ${device.address}", e)
        }
    }

    private fun su(command: String): String? {
        var p: Process? = null
        return try {
            p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Exception) {
            Log.w(TAG, "su failed: ${e.message}")
            null
        } finally {
            try { p?.destroy() } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "BtAccess"
        /** BluetoothProfile.PRIORITY_OFF (hidden). */
        private const val PRIORITY_OFF = 0
        private const val ACTION_CONNECTION_ACCESS_REQUEST = "android.bluetooth.device.action.CONNECTION_ACCESS_REQUEST"
        private const val ACTION_CONNECTION_ACCESS_REPLY = "android.bluetooth.device.action.CONNECTION_ACCESS_REPLY"
        private const val EXTRA_ACCESS_REQUEST_TYPE = "android.bluetooth.device.extra.ACCESS_REQUEST_TYPE"
        private const val EXTRA_CONNECTION_ACCESS_RESULT = "android.bluetooth.device.extra.CONNECTION_ACCESS_RESULT"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val SETTINGS_PREFS = "/data/data/com.android.settings/shared_prefs"
        private val CLOSE_SETTINGS_DELAYS_MS = longArrayOf(1500L, 4000L)
    }
}
