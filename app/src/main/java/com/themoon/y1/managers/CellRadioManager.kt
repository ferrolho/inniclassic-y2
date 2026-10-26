package com.themoon.y1.managers

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Turns the cell (mobile) radio off and on with airplane mode. Airplane mode is
 * set to affect only the cell radio first, so Wi-Fi, Bluetooth and FM continue
 * to work. The state is always read from the system settings, never stored.
 */
object CellRadioManager {
    private const val TAG = "Y1CellRadio"
    private const val CELL = "cell"

    /** Android's default list when `airplane_mode_radios` is not set. */
    private const val DEFAULT_RADIOS = "cell,bluetooth,wifi,nfc,wimax"

    @Volatile
    private var isBusy = false

    fun parseRadios(radios: String?): List<String> =
        (if (radios.isNullOrBlank()) DEFAULT_RADIOS else radios)
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** True when airplane mode turns off only the cell radio. */
    fun isCellOnly(radios: String?): Boolean = parseRadios(radios).toSet() == setOf(CELL)

    /** True when the cell radio is on for this airplane-mode state. */
    fun isCellRadioOn(airplaneModeOn: Boolean, radios: String?): Boolean =
        !airplaneModeOn || CELL !in parseRadios(radios)

    /** Root shell command that turns the cell radio on or off. */
    fun command(cellOn: Boolean): String =
        if (cellOn) {
            "settings put global airplane_mode_on 0; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false"
        } else {
            "settings put global airplane_mode_radios $CELL; " +
                "settings put global airplane_mode_on 1; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
        }

    fun readRadios(context: Context): String? =
        try {
            Settings.Global.getString(context.contentResolver, Settings.Global.AIRPLANE_MODE_RADIOS)
        } catch (_: Throwable) {
            null
        }

    fun isCellRadioOn(context: Context): Boolean {
        val airplaneOn = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Throwable) {
            false
        }
        return isCellRadioOn(airplaneOn, readRadios(context))
    }

    /** True when airplane mode does not affect FM, so FM need not turn it off. */
    fun airplaneModeSparesFm(context: Context): Boolean = isCellOnly(readRadios(context))

    /**
     * Sets the cell radio off the main thread (root is necessary for the
     * protected AIRPLANE_MODE broadcast), then calls [onDone] on the main thread
     * with the new system state. Returns false if a change is already in progress.
     */
    fun setCellRadioOn(activity: Activity, cellOn: Boolean, onDone: (isOn: Boolean) -> Unit): Boolean {
        if (isBusy) return false
        isBusy = true
        val appContext = activity.applicationContext
        Thread({
            try {
                if (!FmRadioManager.runSuQuiet(command(cellOn))) {
                    Log.w(TAG, "su command failed (cellOn=$cellOn)")
                }
            } finally {
                isBusy = false
                val isOn = isCellRadioOn(appContext)
                activity.runOnUiThread { onDone(isOn) }
            }
        }, "CellRadio").start()
        return true
    }
}
