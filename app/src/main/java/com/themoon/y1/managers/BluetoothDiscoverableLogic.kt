package com.themoon.y1.managers

import java.util.Locale

/** Pure rules for the Discoverable and Name rows of the Bluetooth screen. */
object BluetoothDiscoverableLogic {

    /** How long the Y2 stays discoverable after the user turns it on. */
    const val DURATION_S = 300

    /** Name that a press on the Name row gives, so the user can find the Y2 in a car list. */
    const val SHORT_NAME = "Y2"

    /** Time left until [endAtMs], or null if no timer runs ([endAtMs] <= 0). */
    fun remainingMs(endAtMs: Long, nowMs: Long): Long? =
        if (endAtMs <= 0L) null else maxOf(0L, endAtMs - nowMs)

    /** "m:ss", rounded up to the next second: 300 s shows 5:00, 0.2 s shows 0:01. */
    fun formatRemaining(ms: Long): String {
        val totalS = (maxOf(0L, ms) + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", totalS / 60, totalS % 60)
    }

    /** Value of the Discoverable row: "Off", "On" (no timer of ours) or "On · 1:45". */
    fun value(discoverable: Boolean, remainingMs: Long?, localize: (String) -> String): String = when {
        !discoverable -> localize("Off")
        remainingMs == null -> localize("On")
        else -> localize("On") + " · " + formatRemaining(remainingMs)
    }

    /**
     * New adapter name after a press on the Name row, or null for no change.
     * A name other than [SHORT_NAME] becomes [SHORT_NAME]; [SHORT_NAME] goes back to [savedName].
     */
    fun nameAfterPress(current: String?, savedName: String?): String? {
        if (current != SHORT_NAME) return SHORT_NAME
        return savedName?.takeIf { it.isNotBlank() && it != SHORT_NAME }
    }
}
