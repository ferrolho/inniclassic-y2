package com.themoon.y1.managers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Makes the Y2 discoverable for a limited time, so that a car can find it in its own list.
 *
 * Uses the hidden BluetoothAdapter.setScanMode by reflection: in the Y2 Bluetooth.apk it needs only the
 * BLUETOOTH permission (ACTION_REQUEST_DISCOVERABLE opens a system dialog that needs a touch screen).
 * The timer is ours: the stack does not always end discoverable mode by itself (Settings uses an alarm too).
 */
object BluetoothDiscoverableManager {
    private const val TAG = "BtDiscoverable"

    private val handler = Handler(Looper.getMainLooper())
    private var endAtMs = 0L
    private var registered = false

    /** Called on the main thread when the scan mode, the name or the timer changes. */
    var listener: (() -> Unit)? = null

    private val turnOff = Runnable {
        Log.w(TAG, "Discoverable time ended")
        setDiscoverable(false)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Read the current mode, not the extra: an old broadcast must not stop a new timer.
            if (intent.action == BluetoothAdapter.ACTION_SCAN_MODE_CHANGED && !isDiscoverable()) cancelTimer()
            listener?.invoke()
        }
    }

    fun init(context: Context) {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
        }
        context.applicationContext.registerReceiver(receiver, filter)
        registered = true
    }

    @SuppressLint("MissingPermission")
    fun isDiscoverable(): Boolean = try {
        BluetoothAdapter.getDefaultAdapter()?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
    } catch (e: Exception) {
        false
    }

    /** Time left of our timer, or null if no timer runs. */
    fun remainingMs(): Long? = BluetoothDiscoverableLogic.remainingMs(endAtMs, SystemClock.elapsedRealtime())

    /** Turns discoverable mode on (for [BluetoothDiscoverableLogic.DURATION_S]) or off. Returns false on failure. */
    fun setDiscoverable(on: Boolean): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val durationS = BluetoothDiscoverableLogic.DURATION_S
        val ok = try {
            if (on) {
                adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, durationS) as? Boolean ?: false
            } else {
                adapter.javaClass.getMethod("setScanMode", Int::class.javaPrimitiveType)
                    .invoke(adapter, BluetoothAdapter.SCAN_MODE_CONNECTABLE) as? Boolean ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "setScanMode failed (on=$on)", e.cause ?: e)
            false
        }
        Log.w(TAG, "setScanMode(on=$on, ${durationS}s): $ok")
        cancelTimer()
        if (on && ok) {
            endAtMs = SystemClock.elapsedRealtime() + durationS * 1000L
            handler.postDelayed(turnOff, durationS * 1000L)
        }
        listener?.invoke()
        return ok
    }

    private fun cancelTimer() {
        handler.removeCallbacks(turnOff)
        endAtMs = 0L
    }
}
