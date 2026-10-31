package com.themoon.y1.battery

import java.util.Calendar
import java.util.TimeZone

/**
 * One record of the battery history ([BatteryHistory]).
 * - [Kind.STATE]: from [t] the battery is at [level] %, the charger is [plugged] or not, the screen is on or off, and
 *   music or the radio is [playing] or not (lines from before 2026-10-31 have no play flag: not playing).
 * - [Kind.BOOT]: the device started at [t] ([boot]: the kernel boot id, so that an app restart is not a new boot).
 * - [Kind.DOWN]: the device shut down at [t] (Android's shutdown broadcast).
 */
data class BatterySample(
    val t: Long,
    val kind: Kind,
    val level: Int = -1,
    val plugged: Boolean = false,
    val screenOn: Boolean = false,
    val boot: String = "",
    val playing: Boolean = false
) {
    enum class Kind { STATE, BOOT, DOWN }

    companion object {
        fun state(t: Long, level: Int, plugged: Boolean, screenOn: Boolean, playing: Boolean = false) =
            BatterySample(t, Kind.STATE, level, plugged, screenOn, playing = playing)
        fun boot(t: Long, id: String = "") = BatterySample(t, Kind.BOOT, boot = id)
        fun down(t: Long) = BatterySample(t, Kind.DOWN)
    }
}

/** Where the battery screen gets its samples: our own recording ([BatteryHistory]), or another log of the same facts. */
fun interface BatterySampleSource {
    /** The samples of the last days, in time order. */
    fun samples(): List<BatterySample>

    /** [listener] runs on the main thread after each new sample (a source that does not change: never). */
    fun addListener(listener: () -> Unit) {}

    fun removeListener(listener: () -> Unit) {}
}

/** A time when the device was on, with one state: from [start] to [end] (ms). */
data class BatterySpan(
    val start: Long, val end: Long, val level: Int, val plugged: Boolean, val screenOn: Boolean, val playing: Boolean = false
) {
    /** Music or the radio played with the screen off (the Screen Off bars). */
    val playingScreenOff: Boolean get() = playing && !screenOn
}

/** The Last 24 Hours view: 96 battery bars of 15 min and 24 screen bars of 1 hour, the last one has now in it. */
class DayChart(
    /** Start of the first bar (a full hour, local time). */
    val start: Long,
    /** Battery level of each 15-min bar (the last level in it), -1 = no bar (device off, no record, future). */
    val levels: IntArray,
    /** True when the charger was connected during the 15-min bar. */
    val charging: BooleanArray,
    /** Screen-on minutes of each hour, -1 = no bar (device off all the hour, or future). */
    val screenMinutes: IntArray,
    /** Minutes of each hour with music or radio playing and the screen off, -1 = no bar. */
    val offMinutes: IntArray,
    /** The hour of the day (0..23, local time) at the start of each hour bar. */
    val hours: IntArray
) {
    val screenTotalMinutes: Int get() = screenMinutes.filter { it > 0 }.sum()
    val offTotalMinutes: Int get() = offMinutes.filter { it > 0 }.sum()
}

/** The Last 10 Days view: one bar a day (local midnight to midnight), today is the last. */
class TenDayChart(
    /** Start of each day, and the end of the last day (11 values). */
    val dayStarts: LongArray,
    /** Battery percent used each day (the sum of the drops while not charging), -1 = no bar. */
    val used: IntArray,
    /** Screen-on minutes each day, -1 = no bar. */
    val screenMinutes: IntArray,
    /** Minutes each day with music or radio playing and the screen off, -1 = no bar. */
    val offMinutes: IntArray,
    /** True when the charger was connected during the day. */
    val charging: BooleanArray,
    /** Day of the month of each day, for the labels. */
    val dayOfMonth: IntArray,
    /** Calendar.DAY_OF_WEEK of each day. */
    val dayOfWeek: IntArray
) {
    val screenTotalMinutes: Int get() = screenMinutes.filter { it > 0 }.sum()
    val offTotalMinutes: Int get() = offMinutes.filter { it > 0 }.sum()
}

/** The last charge: the battery was at [level] % when the charger was disconnected at [t]. */
data class LastCharge(val level: Int, val t: Long)

/**
 * The pure rules of the battery history: the file lines, the time spans, the bars of the two views, and the texts.
 * No Android dependencies, so the unit tests can check them.
 */
object BatteryHistoryLogic {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS
    const val SLOT_MS = 15 * MINUTE_MS
    const val DAY_SLOTS = 96
    const val DAYS = 10

    /** The history keeps 11 days: 10 full days and today. */
    const val KEEP_MS = 11 * DAY_MS

    /**
     * A state record at least each 15 min while broadcasts come (see BatteryHistory). When the device stops without
     * a shutdown record (a crash, a battery pull), its last state lasts this long at most.
     */
    const val HEARTBEAT_MS = 15 * MINUTE_MS

    // --- File lines: "<seconds> <level> <flags>" (flags: 1 = charger, 2 = screen on, 4 = playing), "<seconds> B <boot id>",
    // "<seconds> D" ---

    fun format(s: BatterySample): String {
        val sec = s.t / 1000
        return when (s.kind) {
            BatterySample.Kind.STATE ->
                "$sec ${s.level} ${(if (s.plugged) 1 else 0) or (if (s.screenOn) 2 else 0) or (if (s.playing) 4 else 0)}"
            BatterySample.Kind.BOOT -> if (s.boot.isEmpty()) "$sec B" else "$sec B ${s.boot}"
            BatterySample.Kind.DOWN -> "$sec D"
        }
    }

    /** The sample of one file line, or null for a line that is not valid (for example a line cut by a power loss). */
    fun parse(line: String): BatterySample? {
        val parts = line.trim().split(' ')
        if (parts.size < 2) return null
        val t = (parts[0].toLongOrNull() ?: return null) * 1000
        return when (parts[1]) {
            "B" -> BatterySample.boot(t, parts.getOrNull(2).orEmpty())
            "D" -> if (parts.size == 2) BatterySample.down(t) else null
            else -> {
                if (parts.size != 3) return null
                val level = parts[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
                val flags = parts[2].toIntOrNull()?.takeIf { it in 0..7 } ?: return null
                BatterySample.state(t, level, flags and 1 != 0, flags and 2 != 0, flags and 4 != 0)
            }
        }
    }

    /**
     * The samples to keep at [now]: the ones of the last [keepMs], and the last state before them (the state at the
     * start of the period).
     */
    fun prune(samples: List<BatterySample>, now: Long, keepMs: Long = KEEP_MS): List<BatterySample> {
        val cut = now - keepMs
        val first = samples.indexOfFirst { it.t >= cut }
        if (first <= 0) return if (first < 0) samples.takeLast(1).filter { it.kind == BatterySample.Kind.STATE } else samples
        val before = samples.subList(0, first).lastOrNull()?.takeIf { it.kind == BatterySample.Kind.STATE }
        return listOfNotNull(before) + samples.subList(first, samples.size)
    }

    /** True when [next] must be written after [last] (the last state record): a new value, or a heartbeat. */
    fun needsRecord(last: BatterySample?, next: BatterySample): Boolean {
        if (last == null || last.kind != BatterySample.Kind.STATE) return true
        return last.level != next.level || last.plugged != next.plugged || last.screenOn != next.screenOn || last.playing != next.playing ||
            next.t - last.t >= HEARTBEAT_MS || next.t < last.t
    }

    // --- Spans ---

    /**
     * The times when the device was on with a known state, up to [now]. A state lasts until the next record. After a
     * shutdown record the device is off until the next state; a boot with no shutdown record before it (a crash)
     * ends the state before it after [HEARTBEAT_MS] at most. The time from a boot to its first state has no span.
     */
    fun spans(samples: List<BatterySample>, now: Long): List<BatterySpan> {
        val sorted = samples.sortedBy { it.t }
        val out = ArrayList<BatterySpan>()
        for (i in sorted.indices) {
            val s = sorted[i]
            if (s.kind != BatterySample.Kind.STATE || s.t >= now) continue
            val next = sorted.getOrNull(i + 1)
            val end = when {
                next == null -> now
                next.kind == BatterySample.Kind.BOOT -> minOf(next.t, s.t + HEARTBEAT_MS)
                else -> next.t
            }.coerceAtMost(now)
            if (end > s.t) out += BatterySpan(s.t, end, s.level, s.plugged, s.screenOn, s.playing)
        }
        return out
    }

    // --- The two views ---

    /** Start of the hour after [now] (local time in [zone]): the end of the Last 24 Hours view. */
    fun nextHour(now: Long, zone: TimeZone): Long {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = now
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.HOUR_OF_DAY, 1)
        return c.timeInMillis
    }

    fun day(samples: List<BatterySample>, now: Long, zone: TimeZone): DayChart {
        val start = nextHour(now, zone) - DAY_MS
        val levels = IntArray(DAY_SLOTS) { -1 }
        val charging = BooleanArray(DAY_SLOTS)
        val screenMs = LongArray(24)
        val offMs = LongArray(24)
        val onMs = LongArray(24)
        for (span in spans(samples, now)) {
            val a = maxOf(span.start, start)
            val b = span.end
            if (b <= a) continue
            // Spans are in time order: the last span in a slot gives its level
            for (k in ((a - start) / SLOT_MS).toInt()..((b - 1 - start) / SLOT_MS).toInt().coerceAtMost(DAY_SLOTS - 1)) {
                levels[k] = span.level
                if (span.plugged) charging[k] = true
            }
            for (h in ((a - start) / HOUR_MS).toInt()..((b - 1 - start) / HOUR_MS).toInt().coerceAtMost(23)) {
                val overlap = minOf(b, start + (h + 1) * HOUR_MS) - maxOf(a, start + h * HOUR_MS)
                if (overlap <= 0) continue
                onMs[h] += overlap
                if (span.screenOn) screenMs[h] += overlap
                if (span.playingScreenOff) offMs[h] += overlap
            }
        }
        fun minutes(ms: LongArray) = IntArray(24) { h -> if (onMs[h] == 0L) -1 else Math.round(ms[h] / MINUTE_MS.toDouble()).toInt().coerceAtMost(60) }
        val c = Calendar.getInstance(zone)
        val hours = IntArray(24) { h ->
            c.timeInMillis = start + h * HOUR_MS
            c.get(Calendar.HOUR_OF_DAY)
        }
        return DayChart(start, levels, charging, minutes(screenMs), minutes(offMs), hours)
    }

    /** The local midnights of the last [DAYS] days (today is the last), and the next midnight: [DAYS] + 1 values. */
    fun dayStarts(now: Long, zone: TimeZone): LongArray {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, -(DAYS - 1))
        return LongArray(DAYS + 1) { i ->
            if (i > 0) c.add(Calendar.DAY_OF_MONTH, 1)
            c.timeInMillis
        }
    }

    fun tenDays(samples: List<BatterySample>, now: Long, zone: TimeZone): TenDayChart {
        val starts = dayStarts(now, zone)
        fun dayOf(t: Long): Int {
            if (t < starts[0] || t >= starts[DAYS]) return -1
            for (d in 0 until DAYS) if (t < starts[d + 1]) return d
            return -1
        }
        val onMs = LongArray(DAYS)
        val screenMs = LongArray(DAYS)
        val offMs = LongArray(DAYS)
        val charging = BooleanArray(DAYS)
        for (span in spans(samples, now)) {
            for (d in 0 until DAYS) {
                val overlap = minOf(span.end, starts[d + 1]) - maxOf(span.start, starts[d])
                if (overlap <= 0) continue
                onMs[d] += overlap
                if (span.screenOn) screenMs[d] += overlap
                if (span.playingScreenOff) offMs[d] += overlap
                if (span.plugged) charging[d] = true
            }
        }
        val used = IntArray(DAYS)
        var prev: BatterySample? = null
        for (s in samples.sortedBy { it.t }) {
            if (s.kind != BatterySample.Kind.STATE) continue
            val p = prev
            prev = s
            if (p == null || s.plugged || s.level >= p.level) continue
            val d = dayOf(s.t)
            if (d >= 0) used[d] += p.level - s.level
        }
        val c = Calendar.getInstance(zone)
        fun minutes(ms: LongArray) = IntArray(DAYS) { if (onMs[it] == 0L) -1 else Math.round(ms[it] / MINUTE_MS.toDouble()).toInt() }
        return TenDayChart(
            starts,
            IntArray(DAYS) { if (onMs[it] == 0L) -1 else used[it] },
            minutes(screenMs),
            minutes(offMs),
            charging,
            IntArray(DAYS) { c.timeInMillis = starts[it]; c.get(Calendar.DAY_OF_MONTH) },
            IntArray(DAYS) { c.timeInMillis = starts[it]; c.get(Calendar.DAY_OF_WEEK) }
        )
    }

    /** The last state: the level now and the charger, or null with no record. */
    fun current(samples: List<BatterySample>): BatterySample? = samples.lastOrNull { it.kind == BatterySample.Kind.STATE }

    /** The last time the charger was disconnected, with the level then; null when it was not in the history. */
    fun lastCharge(samples: List<BatterySample>): LastCharge? {
        val states = samples.filter { it.kind == BatterySample.Kind.STATE }.sortedBy { it.t }
        for (i in states.size - 1 downTo 1) {
            if (states[i - 1].plugged && !states[i].plugged) return LastCharge(maxOf(states[i - 1].level, states[i].level), states[i].t)
        }
        return null
    }

    // --- Scales and texts ---

    /** The top of the Battery Used scale: 100 %, or 200 %, 300 %... when a day used more (charged during the day), as macOS. */
    fun usedScale(used: IntArray): Int {
        val max = used.maxOrNull() ?: 0
        return if (max <= 100) 100 else ((max + 99) / 100) * 100
    }

    /** The top of the 10-day Screen On scale in hours (screen on + screen off playing, stacked): 1, 2, 4, 6, 8, 12, 16 or 24. */
    fun screenScaleHours(minutes: IntArray): Int {
        val max = minutes.maxOrNull() ?: 0
        return intArrayOf(1, 2, 4, 6, 8, 12, 16).firstOrNull { it * 60 >= max } ?: 24
    }

    /** A label of the hour axis: "00", "03"... (24 hour, as macOS), or "12 AM", "3", "6", "9", "12 PM"... (12 hour). */
    fun hourLabel(hour: Int, is24Hour: Boolean): String {
        if (is24Hour) return if (hour < 10) "0$hour" else "$hour"
        return when (hour) {
            0 -> "12 AM"
            12 -> "12 PM"
            else -> (hour % 12).toString()
        }
    }

    /** "2h 5m", "45m", "3h", as the scale labels and macOS. */
    fun durationText(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    /**
     * The hour bars that get a date under their label: the first labelled hour (each 3 hours) and each midnight, as
     * macOS ("30 Oct" under "00").
     */
    fun dateMarks(hours: IntArray): List<Int> {
        val first = hours.indexOfFirst { it % 3 == 0 }
        return hours.indices.filter { it == first || hours[it] == 0 }
    }

    /** The days that get a date under their weekday: the first day and each Monday (the start of a week, as macOS). */
    fun weekMarks(dayOfWeek: IntArray): List<Int> = dayOfWeek.indices.filter { it == 0 || dayOfWeek[it] == Calendar.MONDAY }

    /** 0 when [t] is today, 1 yesterday, and so on (local days in [zone]). */
    fun daysAgo(t: Long, now: Long, zone: TimeZone): Int {
        fun dayNumber(ms: Long): Long {
            val c = Calendar.getInstance(zone)
            c.timeInMillis = ms
            return (ms + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)) / DAY_MS
        }
        return (dayNumber(now) - dayNumber(t)).toInt()
    }
}
