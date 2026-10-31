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

    // "api" also finds "apikey" and "api_key".
    private val SECRET_PARTS = listOf("password", "token", "session", "secret", "api")

    /**
     * Keys that can hold a secret. The log and the test state never show their value.
     * Keys with a file path (ratings, positions) are not secrets: the path can have any word.
     */
    fun isSecretKey(key: String): Boolean {
        if (key.contains('/')) return false
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

    /** A run of wheel steps in one direction, on one screen, with one consumer and one source: one log line. */
    class WheelBurst(
        val dir: Int, val by: String, val screen: String, val startUp: Long, val startWall: Long, val src: String = "own"
    ) {
        var lastUp: Long = startUp
        var count: Int = 1

        fun continuesWith(dir: Int, by: String, screen: String, up: Long, src: String = "own"): Boolean =
            dir == this.dir && by == this.by && screen == this.screen && src == this.src && up - lastUp <= WHEEL_GAP_MS

        fun isIdle(up: Long): Boolean = up - lastUp > WHEEL_GAP_MS
    }

    // --- key source ---

    /** Input devices of the Y2's own buttons: the click wheel and its buttons, and the side keys (`getevent -i`). */
    private val OWN_KEY_DEVICES = setOf("mtk-tpd-kpd", "mtk-kpd", "mtk-tpd")

    /** KeyCharacterMap.VIRTUAL_KEYBOARD: keys from `adb shell input`, the test interface and app code. */
    const val VIRTUAL_DEVICE_ID = -1

    /**
     * Short class of the input device of a key: `own` (the Y2's buttons), `bluetooth` (AVRCP: car, headphones),
     * `wired` (ACCDET: headset remote), `virtual` (injected), `other`, or `unknown` (no device with this id now).
     */
    fun keySource(deviceId: Int, deviceName: String?): String {
        if (deviceId == VIRTUAL_DEVICE_ID) return "virtual"
        val n = deviceName ?: return "unknown"
        val lower = n.lowercase(Locale.US)
        return when {
            n in OWN_KEY_DEVICES -> "own"
            lower.contains("avrcp") || lower.contains("bluetooth") -> "bluetooth"
            lower.contains("accdet") || lower.contains("h2w") || lower.contains("headset") -> "wired"
            lower == "virtual" -> "virtual"
            else -> "other"
        }
    }

    // --- Bluetooth ---

    private fun named(value: Int, names: Map<Int, String>): String = names[value] ?: value.toString()

    // Values of BluetoothAdapter, BluetoothDevice, BluetoothProfile, BluetoothA2dp and BluetoothHeadset (some hidden on 4.4)
    private val ADAPTER_STATES = mapOf(10 to "OFF", 11 to "TURNING_ON", 12 to "ON", 13 to "TURNING_OFF")
    private val SCAN_MODES = mapOf(20 to "NONE", 21 to "CONNECTABLE", 23 to "DISCOVERABLE")
    private val BOND_STATES = mapOf(10 to "NONE", 11 to "BONDING", 12 to "BONDED")
    private val PROFILE_STATES = mapOf(0 to "DISCONNECTED", 1 to "CONNECTING", 2 to "CONNECTED", 3 to "DISCONNECTING")
    private val A2DP_PLAYING = mapOf(10 to "PLAYING", 11 to "NOT_PLAYING")
    private val HFP_AUDIO = mapOf(10 to "DISCONNECTED", 11 to "CONNECTING", 12 to "CONNECTED")
    private val PAIRING_VARIANTS = mapOf(
        0 to "PIN", 1 to "PASSKEY", 2 to "PASSKEY_CONFIRMATION", 3 to "CONSENT",
        4 to "DISPLAY_PASSKEY", 5 to "DISPLAY_PIN", 6 to "OOB_CONSENT"
    )
    // BluetoothDevice.UNBOND_REASON_* (hidden): the reason of a failed bond, in the bond change to NONE
    private val UNBOND_REASONS = mapOf(
        0 to "SUCCESS", 1 to "AUTH_FAILED", 2 to "AUTH_REJECTED", 3 to "AUTH_CANCELED", 4 to "REMOTE_DEVICE_DOWN",
        5 to "DISCOVERY_IN_PROGRESS", 6 to "AUTH_TIMEOUT", 7 to "REPEATED_ATTEMPTS", 8 to "REMOTE_AUTH_CANCELED",
        9 to "REMOVED"
    )
    private val ACCESS_TYPES = mapOf(1 to "PROFILE", 2 to "PHONEBOOK", 3 to "MESSAGE")
    private val ACCESS_RESULTS = mapOf(1 to "YES", 2 to "NO")

    fun btAdapterState(v: Int) = named(v, ADAPTER_STATES)
    fun btScanMode(v: Int) = named(v, SCAN_MODES)
    fun btBondState(v: Int) = named(v, BOND_STATES)
    fun btProfileState(v: Int) = named(v, PROFILE_STATES)
    fun btA2dpPlaying(v: Int) = named(v, A2DP_PLAYING)
    fun btHfpAudio(v: Int) = named(v, HFP_AUDIO)
    fun btPairingVariant(v: Int) = named(v, PAIRING_VARIANTS)
    fun btUnbondReason(v: Int) = named(v, UNBOND_REASONS)
    fun btAccessType(v: Int) = named(v, ACCESS_TYPES)
    fun btAccessResult(v: Int) = named(v, ACCESS_RESULTS)

    /** Short name of a BluetoothClass.getDeviceClass() value, with the value: "car 0x420". */
    fun btDeviceClass(deviceClass: Int?): String? {
        if (deviceClass == null) return null
        val name = when (deviceClass) {
            0x0420 -> "car"
            0x0408 -> "handsfree"
            0x0404 -> "headset"
            0x0418 -> "headphones"
            0x0414 -> "speaker"
            0x041C -> "portable-audio"
            0x0424 -> "set-top-box"
            0x0428 -> "hifi"
            else -> when (deviceClass and 0x1F00) {
                0x0100 -> "computer"
                0x0200 -> "phone"
                0x0400 -> "audio"
                0x0500 -> "peripheral"
                0x0700 -> "wearable"
                else -> "other"
            }
        }
        return name + " 0x" + Integer.toHexString(deviceClass)
    }

    /** Processes of the Bluetooth stack: the profile services (Bluetooth.apk), MtkBt.apk and the native daemon. */
    private val BT_PROCESS_PREFIXES = listOf("com.android.bluetooth", "com.mediatek.bluetooth")

    /**
     * Short process name if [cmdline] (`/proc/<pid>/cmdline`, arguments split by NUL) is a Bluetooth process,
     * else null: "com.mediatek.bluetooth", "com.android.bluetooth", "mtkbt".
     */
    fun bluetoothProcess(cmdline: String?): String? {
        val first = cmdline?.substringBefore('\u0000')?.trim().orEmpty()
        if (first.isEmpty()) return null
        if (BT_PROCESS_PREFIXES.any { first == it || first.startsWith("$it:") }) return first
        val base = first.substringAfterLast('/')
        return if (base == "mtkbt") base else null
    }

    private val BT_TAG = Regex("(?i)bluetooth|a2dp|avrcp|blueangel|mtkbt|^bt|hfp|hfg")

    /** Log tags of Bluetooth code in other processes (system_server BluetoothManagerService, the A2DP audio HAL). */
    fun isBluetoothTag(tag: String): Boolean = BT_TAG.containsMatchIn(tag)

    /**
     * At most [max] lines in [windowMs]. [take] returns false for a line over the limit; [endedDropped] then
     * gives, once, the number of lines dropped in the window that just ended.
     */
    class RateWindow(private val windowMs: Long, private val max: Int) {
        private var started = false
        private var start = 0L
        private var used = 0
        private var dropped = 0

        var endedDropped = 0
            private set

        fun take(now: Long): Boolean {
            endedDropped = 0
            if (!started || now - start > windowMs) {
                endedDropped = dropped
                started = true
                start = now
                used = 0
                dropped = 0
            }
            if (used >= max) {
                dropped++
                return false
            }
            used++
            return true
        }
    }

    // --- noise and repeats ---

    private val DALVIK_NOISE = Regex("^(VFY: |Unable to resolve |Could not find class |Link of class |Refusing to reopen boot DEX)")

    /**
     * Warnings of the app that do not help to find a fault: the class checks of Android 4.4 for newer APIs
     * (about 30 at each start), layout warnings of the framework, MP3 files with no embedded picture or a wrong
     * XING size. The log keeps only their count (see [Repeats]). Returns the name of the count, or null.
     */
    fun noiseKey(tag: String, message: String): String? = when {
        tag == "dalvikvm" && DALVIK_NOISE.containsMatchIn(message) -> "dalvikvm: class check"
        tag == "View" && message.startsWith("requestLayout() improperly called") -> "View: requestLayout during layout"
        tag == "MediaMetadataRetrieverJNI" && message.contains("getEmbeddedPicture failed") -> "no embedded picture"
        tag == "FfmpegLibrary" && message.contains("decoder available") -> "FfmpegLibrary: no decoder"
        tag == "XingSeeker" -> "XingSeeker: data size mismatch"
        tag == "MemoryDealer" && message.contains("MADV_REMOVE") -> "MemoryDealer: madvise"
        else -> null
    }

    private val NUMBERS = Regex("0x[0-9a-fA-F]+|\\d+")

    /** The same warning with other numbers (ids, sizes, times) gives the same key. */
    fun repeatKey(tag: String, message: String): String =
        tag + ": " + cut(NUMBERS.replace(message.substringBefore('\n'), "#"), 100)

    /**
     * Counts repeated warnings in windows of [windowMs]: in a window, the first line of each key is logged and the
     * next ones are only counted. [drain] gives the counts of a window that ended (then a new window starts).
     * Not thread-safe: one thread uses it.
     */
    class Repeats(private val windowMs: Long) {
        private val seen = HashSet<String>()
        private val counts = LinkedHashMap<String, Int>()
        private var start = -1L

        /** True when the line with [key] must be logged: the first of its key in this window. */
        fun first(key: String, now: Long): Boolean {
            if (start < 0) start = now
            if (seen.add(key)) return true
            count(key, now)
            return false
        }

        /** Counts a line that is not logged. */
        fun count(key: String, now: Long) {
            if (start < 0) start = now
            counts[key] = (counts[key] ?: 0) + 1
        }

        /** The counts of the window when it ended at [now] (or at once with [force]); null when nothing was counted. */
        fun drain(now: Long, force: Boolean = false): Map<String, Int>? {
            if (start < 0 || (!force && now - start < windowMs)) return null
            val out = if (counts.isEmpty()) null else LinkedHashMap(counts)
            seen.clear()
            counts.clear()
            start = -1L
            return out
        }
    }

    // --- garbage collection (Dalvik) ---

    data class Gc(val kind: String, val pausedMs: Int, val usedKb: Int, val heapKb: Int)

    private val GC_LINE = Regex("^(GC_[A-Z_]+) .*?(\\d+)K/(\\d+)K, paused ([\\d+ms]+)")

    /**
     * A Dalvik GC line, for example "GC_FOR_ALLOC freed 3697K (50950), 38% free 16790K/26900K, paused 72ms,
     * total 72ms". A concurrent GC has two pauses ("paused 2ms+3ms"): their sum. Null for other lines.
     */
    fun parseGc(message: String): Gc? {
        val m = GC_LINE.find(message) ?: return null
        val paused = m.groupValues[4].split('+').sumOf { it.removeSuffix("ms").toIntOrNull() ?: 0 }
        return Gc(m.groupValues[1], paused, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** GC pauses in windows of [windowMs]. [drain] gives a summary when the pauses of the ended window were [minPausedMs] or more. */
    class GcWindow(private val windowMs: Long, private val minPausedMs: Int) {
        private var start = -1L
        private var n = 0
        private var forAlloc = 0
        private var paused = 0
        private var max = 0
        private var last: Gc? = null

        fun add(gc: Gc, now: Long) {
            if (start < 0) start = now
            n++
            if (gc.kind == "GC_FOR_ALLOC") forAlloc++
            paused += gc.pausedMs
            if (gc.pausedMs > max) max = gc.pausedMs
            last = gc
        }

        /** {"n", "forAlloc", "pausedMs", "maxMs", "usedKb", "heapKb"} of the ended window, or null. */
        fun drain(now: Long): JSONObject? {
            if (start < 0 || now - start < windowMs) return null
            val g = last
            val out = if (paused >= minPausedMs && g != null) JSONObject()
                .put("n", n).put("forAlloc", forAlloc).put("pausedMs", paused).put("maxMs", max)
                .put("usedKb", g.usedKb).put("heapKb", g.heapKb) else null
            start = -1L
            n = 0
            forAlloc = 0
            paused = 0
            max = 0
            last = null
            return out
        }
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
