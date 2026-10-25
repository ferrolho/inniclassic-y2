package com.themoon.y1.testhooks

/**
 * Pure parts of the screen wake and keep-awake of the test interface (no Android classes), so that unit tests
 * can check them. See [TestPower].
 */
object TestPowerLogic {

    /** A command that must wake the screen waits at most this long, then replies with an error. */
    const val WAKE_TIMEOUT_MS = 5000L
    const val WAKE_POLL_MS = 50L
    /** A screen that stays off (or goes off again) this long after a wake request gets a new one. */
    const val WAKE_RETRY_MS = 1000L
    /**
     * After a wake, wait at most this long (from the first poll with the screen on) for the app's own
     * "awake" signs: isScreenSleeping cleared (ACTION_SCREEN_ON, onResume) and window focus.
     */
    const val SETTLE_GRACE_MS = 1500L

    const val DEFAULT_KEEP_AWAKE_MINUTES = 10
    const val MAX_KEEP_AWAKE_MINUTES = 24 * 60
    /** While the screen is kept awake, the app's backlight timer is reset this often (its shortest option is 10 s). */
    const val BACKLIGHT_REFRESH_MS = 4000L

    data class Snapshot(
        val screenOn: Boolean,
        val fakeScreenOff: Boolean,
        val screenSleeping: Boolean,
        val windowFocus: Boolean
    )

    enum class WakeStep { READY, WAKE_SCREEN, WAKE_FAKE, WAIT, TIMEOUT }

    /**
     * The next step of the wake before a command.
     * [elapsedMs]: time since the command started. [woke]: a wake was requested for this command.
     * [onForMs]: time since the screen was first seen on and without the fake screen-off overlay (-1 = not yet).
     * [sinceScreenWakeMs]: time since the last screen wake request (-1 = none).
     */
    fun wakeStep(s: Snapshot, elapsedMs: Long, woke: Boolean, onForMs: Long, sinceScreenWakeMs: Long): WakeStep {
        if (s.screenOn && !s.fakeScreenOff) {
            val settled = onForMs >= SETTLE_GRACE_MS
            // isScreenSleeping with the screen on: the app's own screen-off (a power key) can be on its way
            val sleepOk = !s.screenSleeping || settled
            val focusOk = s.windowFocus || !woke || settled
            if (sleepOk && focusOk) return WakeStep.READY
        }
        if (elapsedMs >= WAKE_TIMEOUT_MS) return WakeStep.TIMEOUT
        if (!s.screenOn) {
            return if (sinceScreenWakeMs < 0 || sinceScreenWakeMs >= WAKE_RETRY_MS) WakeStep.WAKE_SCREEN else WakeStep.WAIT
        }
        if (s.fakeScreenOff) return WakeStep.WAKE_FAKE
        return WakeStep.WAIT
    }

    /** The text of the error when the wake times out. */
    fun wakeError(s: Snapshot): String =
        "screen did not wake in $WAKE_TIMEOUT_MS ms (screenOn=${s.screenOn}, fakeScreenOff=${s.fakeScreenOff}, " +
            "screenSleeping=${s.screenSleeping}, windowFocus=${s.windowFocus}); the command did not run"

    /**
     * The new keep-awake end (uptime ms; 0 = off). `awake` ([explicit]) sets it to [minutes] from now (0 = off).
     * Other commands only extend it to at least [DEFAULT_KEEP_AWAKE_MINUTES] from now.
     */
    fun keepAwakeUntil(nowMs: Long, currentUntilMs: Long, minutes: Int?, explicit: Boolean): Long {
        if (explicit) {
            val m = (minutes ?: DEFAULT_KEEP_AWAKE_MINUTES).coerceIn(0, MAX_KEEP_AWAKE_MINUTES)
            return if (m == 0) 0L else nowMs + m * 60_000L
        }
        return maxOf(currentUntilMs, nowMs + DEFAULT_KEEP_AWAKE_MINUTES * 60_000L)
    }

    /** Delay to the next keep-awake tick, or null when the keep-awake time is over. */
    fun nextTickMs(nowMs: Long, untilMs: Long): Long? =
        if (untilMs <= nowMs) null else minOf(BACKLIGHT_REFRESH_MS, untilMs - nowMs)

    /** `minutes` of `awake` from `--ei`, `--el` or `--es`: 0..[MAX_KEEP_AWAKE_MINUTES], else null. */
    fun parseMinutes(value: Any?): Int? {
        val m = when (value) {
            null -> DEFAULT_KEEP_AWAKE_MINUTES.toLong()
            is Number -> value.toLong()
            else -> value.toString().trim().toLongOrNull() ?: return null
        }
        return if (m in 0..MAX_KEEP_AWAKE_MINUTES.toLong()) m.toInt() else null
    }
}
