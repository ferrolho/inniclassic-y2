package com.themoon.y1.battery

import kotlin.math.roundToInt

/**
 * The battery level from the voltage, and the shutdown before the cut-off (no Android dependencies, for the unit tests).
 *
 * The Y2 has the MediaTek software fuel gauge (no current sensor while discharging). Its % can stay high while the
 * battery empties: on 2026-10-31 it showed 89 % at 3.78 V, and 69 % 20 min before the Y2 turned off. So:
 *
 * - Charging: the shown level is the gauge (the charger raises the voltage).
 * - Discharging: the shown level comes from the voltage ([percentAt], measured on this Y2's cell, see
 *   `records/battery-voltage-guard-2026-10-31.md`). The voltage of each sample gets a load allowance ([equivalentMv]),
 *   the median of the last [windowMs] gives the estimate, and a lower estimate counts only when it stays lower for
 *   [holdMs]. The shown level never goes up while discharging, and it falls by at most 1 % each [stepMs] (more only
 *   after a time without samples, when the CPU slept, so the screen was off), so it has no jumps. At the unplug it starts from the gauge, and the estimate counts only after [settleMs] (the kernel's
 *   voltage is a mean of some minutes, so it still has the charging voltage in it).
 * - Shutdown: when the median of the raw voltage (no allowance: the kernel's own cut-off is on the raw voltage) is at
 *   or below [SHUTDOWN_MV] for [shutdownHoldMs], [shutdownDue] becomes true, one time in each discharge.
 *
 * Times are elapsed real time in ms (with the time in deep sleep).
 */
class BatteryGuardLogic(
    val windowMs: Long = WINDOW_MS,
    val holdMs: Long = HOLD_MS,
    val stepMs: Long = STEP_MS,
    val settleMs: Long = SETTLE_MS,
    val shutdownHoldMs: Long = SHUTDOWN_HOLD_MS
) {
    /** The load during a sample: the screen, the music or the radio, the CPU (all cores, % of the awake time), Wi-Fi. */
    data class Load(val screenOn: Boolean, val playing: Boolean = false, val cpuPct: Int = 0, val wifi: Boolean = false)

    /** The shown level in %, -1 before the first sample. */
    var shown = -1
        private set

    /** The last voltage estimate in % (not held, not limited), -1 when there is none yet. */
    var estimate = -1f
        private set

    var charging = false
        private set

    /** True from the time the raw voltage was low for [shutdownHoldMs] until the next charge. */
    var shutdownDue = false
        private set

    private class Point(val t: Long, val v: Float)

    private val equivalent = ArrayList<Point>()
    private val raw = ArrayList<Point>()
    private val estimates = ArrayList<Point>()
    private var started = false
    private var unplugAt = Long.MIN_VALUE / 2
    private var lastDrop = Long.MIN_VALUE / 2
    private var lastSample = Long.MIN_VALUE / 2
    private var lowSince = NONE

    /**
     * Starts with a level that the app saved before (same boot, discharging), so a restart of the app does not show
     * the gauge again. Call before the first [onSample].
     */
    fun restore(level: Int) {
        if (!started && level in 0..100) shown = level
    }

    /**
     * A battery sample: [mV] the kernel's voltage, [gauge] the gauge level in %. Returns true when [shown] or
     * [shutdownDue] changed.
     */
    fun onSample(t: Long, mV: Int, gauge: Int, charging: Boolean, load: Load): Boolean {
        val before = shown
        val dueBefore = shutdownDue
        if (charging) {
            this.charging = true
            started = true
            if (gauge in 0..100) shown = gauge
            clear()
            shutdownDue = false
            return shown != before || dueBefore
        }
        if (this.charging || !started) {
            // The unplug (or the first sample of the app while discharging): start from the gauge, or from the
            // saved level when it is lower.
            if (this.charging) unplugAt = t
            this.charging = false
            started = true
            shown = when {
                gauge !in 0..100 -> shown
                shown < 0 -> gauge
                else -> minOf(shown, gauge)
            }
            clear()
        }
        val previous = lastSample
        lastSample = t
        if (mV < MIN_VALID_MV || mV > MAX_VALID_MV) return shown != before
        if (t - unplugAt < settleMs) return shown != before

        add(equivalent, t, equivalentMv(mV, load).toFloat())
        add(raw, t, mV.toFloat())
        estimate = percentAt(median(equivalent))
        add(estimates, t, estimate, keepBefore = holdMs)

        // The estimate that was in effect for all of the hold time: a short sag does not count.
        val held = heldEstimate(t)
        if (shown < 0) shown = held.roundToInt()
        val target = held.roundToInt()
        if (target < shown && t - lastDrop >= stepMs) {
            // 1 % each stepMs: when the samples are further apart (the CPU sleeps, so the screen is off), more.
            val steps = ((t - maxOf(previous, lastDrop)) / stepMs).coerceIn(1L, 100L).toInt()
            shown = maxOf(target, shown - steps)
            lastDrop = t
        }

        if (median(raw) <= SHUTDOWN_MV) {
            if (lowSince == NONE) lowSince = t
            if (t - lowSince >= shutdownHoldMs) shutdownDue = true
        } else {
            lowSince = NONE
        }
        return shown != before || shutdownDue != dueBefore
    }

    private fun heldEstimate(t: Long): Float {
        var max = -1f
        var lastBefore: Point? = null
        for (p in estimates) {
            if (p.t > t - holdMs) max = maxOf(max, p.v) else lastBefore = p
        }
        // The value in effect at the start of the hold time (a sample before it), so one new sample after a
        // long sleep does not lower the level at once.
        if (lastBefore != null) max = maxOf(max, lastBefore.v)
        return max
    }

    private fun add(list: ArrayList<Point>, t: Long, v: Float, keepBefore: Long = windowMs) {
        list.add(Point(t, v))
        // Keep the points of the time span, and the last one at or before its start (the value in effect then).
        while (list.size > 1 && list[1].t <= t - keepBefore) list.removeAt(0)
    }

    /** The median of the points in the window (the point before the window counts until a newer one is there). */
    private fun median(list: List<Point>): Float {
        val t = list.last().t
        val values = list.filter { it.t >= t - windowMs }.map { it.v }.ifEmpty { listOf(list.last().v) }.sorted()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2f
    }

    private fun clear() {
        equivalent.clear()
        raw.clear()
        estimates.clear()
        lowSince = NONE
        estimate = -1f
    }

    companion object {
        const val WINDOW_MS = 3 * 60_000L
        const val HOLD_MS = 2 * 60_000L
        const val STEP_MS = 30_000L
        const val SETTLE_MS = 5 * 60_000L
        const val SHUTDOWN_HOLD_MS = 2 * 60_000L

        /** The level of the "Battery low" banner and of the last notice ("Please charge"). */
        const val LOW_LEVEL = 10
        const val LAST_LEVEL = 3

        /**
         * Shutdown at this raw voltage. The kernel sets the gauge to 0 % at 3.40 V (then Android turns off at once);
         * on 2026-10-31 the voltage stayed at 3.42-3.43 V for ~8 min before that.
         */
        const val SHUTDOWN_MV = 3430

        private const val MIN_VALID_MV = 2800
        private const val MAX_VALID_MV = 4500
        private const val NONE = Long.MIN_VALUE

        /**
         * Voltage (mV, with the screen on, light use) to the real charge in %. From the 2026-10-29 charge (coulombs
         * from the off point to full), set at the end with the time to off of 2026-10-31; see the record.
         */
        private val TABLE = intArrayOf(
            3400, 0, 3440, 3, 3457, 5, 3510, 10, 3575, 15, 3620, 20, 3660, 25, 3690, 30, 3720, 35, 3770, 40,
            3810, 45, 3835, 50, 3860, 55, 3890, 60, 3910, 65, 3940, 70, 3970, 75, 4030, 80, 4090, 85, 4140, 90,
            4180, 95, 4200, 100
        )

        /** The load of the table: the screen on, Wi-Fi on, the CPU at 25 %. */
        const val REFERENCE_SAG_MV = 35

        fun percentAt(mV: Float): Float {
            if (mV <= TABLE[0]) return 0f
            var i = 2
            while (i < TABLE.size) {
                if (mV <= TABLE[i]) {
                    val v0 = TABLE[i - 2]
                    val p0 = TABLE[i - 1]
                    val v1 = TABLE[i]
                    val p1 = TABLE[i + 1]
                    return p0 + (p1 - p0) * (mV - v0) / (v1 - v0)
                }
                i += 2
            }
            return 100f
        }

        /**
         * The voltage drop of a load, in mV (estimated from screen on/off changes in the logs: ~25 mV for the screen).
         * The CPU part is for Wi-Fi syncs and scans (CPU at 100 %: 20 mV).
         */
        fun sagMv(load: Load): Int =
            (if (load.screenOn) 25 else 0) + (if (load.playing) 5 else 0) +
                load.cpuPct.coerceIn(0, 100) / 5 + (if (load.wifi) 5 else 0)

        /** The voltage at the load of the table: a heavy load lowers the voltage, so its sag is added back. */
        fun equivalentMv(mV: Int, load: Load): Int = mV + sagMv(load) - REFERENCE_SAG_MV
    }
}
