package com.themoon.y1.managers

/**
 * Settings → Controls → Vibration Strength: the length of the one vibration for each push and each wheel step
 * (clickFeedback). A longer pulse feels stronger on the Y2's motor, so the lengths go up from Weak to Strong.
 * Android's own key vibration is off (HAPTIC_FEEDBACK_ENABLED = 0), so a push does not vibrate twice.
 */
object ButtonVibration {
    const val PREF = "vibrate_strength"
    const val DEFAULT = 1

    /** The labels of the levels (translation keys). */
    val LABELS = arrayOf("Weak", "Normal", "Strong")
    private val LENGTH_MS = longArrayOf(15L, 25L, 40L)

    fun validLevel(level: Int): Int = if (level in LENGTH_MS.indices) level else DEFAULT

    fun lengthMs(level: Int): Long = LENGTH_MS[validLevel(level)]
}
