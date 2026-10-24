package com.themoon.y1.eventlog

import com.themoon.y1.navigation.ScreenState
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure parts of the event log (no Android classes), so that unit tests can check them. See [EventLog].
 */
object EventLogLogic {

    /** Each log file is at most this size; [FILES] files are kept (the current file and the previous one). */
    const val MAX_FILE_BYTES = 512L * 1024
    const val FILES = 2

    /** A wheel step later than this after the previous step starts a new wheel line. */
    const val WHEEL_GAP_MS = 1000L

    /** Longest string value (setting value, log message) that a line keeps. */
    const val MAX_TEXT = 300
    const val MAX_LOG_MESSAGE = 2000

    // --- settings ---

    private val SECRET_PARTS = listOf("password", "token", "session", "secret", "api")

    /** Keys that can hold a secret. The log never shows their change. */
    fun isSecretKey(key: String): Boolean {
        val k = key.lowercase(Locale.US)
        return SECRET_PARTS.any { k.contains(it) }
    }

    // Playback state that the app saves every few seconds; the playback events show it already.
    private val QUIET_PREFIXES = listOf("last_played_", "book_pos_", "book_dur_")
    private val QUIET_KEYS = setOf("has_saved_playback_state")

    fun isQuietKey(key: String): Boolean = key in QUIET_KEYS || QUIET_PREFIXES.any { key.startsWith(it) }

    /** A setting value as a JSON value: scalars as they are, long text cut, sets as a sorted (cut) array. */
    fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long -> value
        is Float -> value.toDouble()
        is Double -> value
        is String -> cut(value, MAX_TEXT)
        is Set<*> -> {
            val arr = JSONArray()
            var chars = 0
            for (item in value.map { it.toString() }.sorted()) {
                chars += item.length
                if (chars > MAX_TEXT) {
                    arr.put("… (${value.size} items)")
                    break
                }
                arr.put(item)
            }
            arr
        }
        else -> cut(value.toString(), MAX_TEXT)
    }

    fun cut(text: String, max: Int): String = if (text.length <= max) text else text.substring(0, max) + "…"

    // --- log messages ---

    private val SECRET_IN_TEXT = Regex(
        "(?i)(password|passwd|token|api_?key|secret|session(?:_?id)?|authorization)([\"']?\\s*[=:]\\s*[\"']?)([^&\\s\"',;]+)"
    )

    /** Removes values of `token=…`, `api_key: …`, `Token="…"` and similar from a log message. */
    fun redact(text: String): String = SECRET_IN_TEXT.replace(text) { it.groupValues[1] + it.groupValues[2] + "<redacted>" }

    data class LogcatLine(val time: String, val pid: Int, val tid: Int, val level: Char, val tag: String, val message: String)

    // `logcat -v threadtime`: "10-24 12:34:56.789  1234  1250 W Tag     : message"
    private val THREADTIME = Regex("^(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:\\s?(.*)$")

    fun parseLogcat(line: String): LogcatLine? {
        val m = THREADTIME.matchEntire(line) ?: return null
        val g = m.groupValues
        val pid = g[2].toIntOrNull() ?: return null
        val tid = g[3].toIntOrNull() ?: return null
        return LogcatLine(g[1], pid, tid, g[4][0], g[5].trim(), g[6])
    }

    // --- screens ---

    /** Short screen name: the ScreenState class, plus its mode for Browser, Settings and Videos ("Browser/Albums"). */
    fun screenName(state: Any?, browserMode: Any? = null): String {
        if (state == null) return "none"
        val base = state.javaClass.simpleName
        val sub = when (state) {
            is ScreenState.Browser -> browserMode ?: state.mode
            is ScreenState.Settings -> state.subMode
            is ScreenState.Videos -> state.mode
            else -> null
        }
        return if (sub == null) base else base + "/" + sub.javaClass.simpleName
    }

    /** Full text of a screen state (data classes show their parameters), cut. */
    fun screenDetail(state: Any?): String? {
        if (state == null) return null
        val text = state.toString()
        return if (text == state.javaClass.simpleName) null else cut(text, MAX_TEXT)
    }

    // --- keys ---

    fun isWheel(keyCode: Int): Boolean = keyCode == 21 || keyCode == 22

    /** Wheel direction: +1 = clockwise (DPAD_RIGHT, 22), -1 = anticlockwise (DPAD_LEFT, 21). */
    fun wheelDir(keyCode: Int): Int = if (keyCode == 22) 1 else -1

    /** A run of wheel steps in one direction, on one screen, with one consumer: one log line. */
    class WheelBurst(val dir: Int, val by: String, val screen: String, val startUp: Long, val startWall: Long) {
        var lastUp: Long = startUp
        var count: Int = 1

        fun continuesWith(dir: Int, by: String, screen: String, up: Long): Boolean =
            dir == this.dir && by == this.by && screen == this.screen && up - lastUp <= WHEEL_GAP_MS

        fun isIdle(up: Long): Boolean = up - lastUp > WHEEL_GAP_MS
    }

    // --- reading ---

    private val UP_FIELD = Regex("\"up\":(\\d+)")

    /** The `up` field (uptime ms) of a log line, or null. */
    fun uptimeOf(line: String): Long? = UP_FIELD.find(line)?.groupValues?.get(1)?.toLongOrNull()

    fun tail(lines: List<String>, n: Int): List<String> = if (n >= lines.size) lines else lines.subList(lines.size - n, lines.size)

    /**
     * The lines after uptime [since] of the current boot: from the end back to the first line at or before
     * [since], or to a boot change (read backwards, the uptime goes up there).
     */
    fun since(lines: List<String>, since: Long): List<String> {
        var start = lines.size
        var newer = Long.MAX_VALUE
        for (i in lines.indices.reversed()) {
            val up = uptimeOf(lines[i]) ?: continue
            if (up <= since || up > newer) break
            newer = up
            start = i
        }
        return lines.subList(start, lines.size)
    }

    // --- exceptions ---

    /** Stack frames as short strings, at most [max]. */
    fun frames(stack: Array<StackTraceElement>, max: Int): JSONArray {
        val arr = JSONArray()
        for (f in stack.take(max)) arr.put(f.toString())
        if (stack.size > max) arr.put("… ${stack.size - max} more")
        return arr
    }

    /** {"ex": class, "msg": message, "stack": [...], "cause": {...}} for a throwable and up to 3 causes. */
    fun throwableJson(t: Throwable, frames: Int = 25, depth: Int = 0): JSONObject {
        val o = JSONObject()
        o.put("ex", t.javaClass.name)
        o.put("msg", t.message?.let { cut(redact(it), MAX_LOG_MESSAGE) } ?: JSONObject.NULL)
        o.put("stack", frames(t.stackTrace, frames))
        val cause = t.cause
        if (cause != null && cause !== t && depth < 3) o.put("cause", throwableJson(cause, 10, depth + 1))
        return o
    }
}
