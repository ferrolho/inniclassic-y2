package com.themoon.y1.eventlog

import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Puts warnings and errors from logcat (Log.w, Log.e, Log.wtf and native W/E/F lines) into the event log, without a
 * change at each call:
 * - all lines of this process (the app, ExoPlayer, the framework in the app);
 * - the lines of the Bluetooth processes (`com.mediatek.bluetooth`, `com.android.bluetooth`, the native `mtkbt`)
 *   and Bluetooth tags of other processes (system_server, the A2DP audio HAL). These get the field `proc`.
 *
 * One `logcat -v threadtime *:W` child process, which blocks until a line comes: no polling.
 * Since Android 4.1 an app without READ_LOGS reads only the lines of its own user id, so the tap starts logcat
 * with su (root, as other parts of the app). Without su it reads only the app's own lines (a `note` says so).
 * The pids of the Bluetooth processes come from /proc: at the start, after each Bluetooth state change
 * ([refreshBluetoothPids]), and once for each new pid that writes a line.
 * Lines of one Log call (a stack trace) become one event. Own lines and Bluetooth lines have separate rate limits.
 * Own warnings that do not help ([EventLogLogic.noiseKey]) and repeats of the same warning in 10 minutes are
 * only counted: one `repeats` line gives the counts. The Dalvik GC lines of the app (debug level) are not logged:
 * one `gc` line each minute gives the sum of the pauses, when they stopped the app for [GC_MIN_PAUSED_MS] or more
 * (each GC_FOR_ALLOC stops all threads, also the UI thread).
 * If the app process dies, the child stays until its next write fails; at the next start, the tap stops the
 * child of the previous process (its pid is in `logcat.pid`).
 */
internal class LogcatTap(private val dir: File) {

    private class Entry(val line: EventLogLogic.LogcatLine, val proc: String?) {
        val text = StringBuilder(line.message)
        var lines = 1

        fun sameCall(o: EventLogLogic.LogcatLine) =
            o.pid == line.pid && o.tid == line.tid && o.level == line.level && o.tag == line.tag && o.time == line.time

        fun add(message: String) {
            if (lines < MAX_LINES) text.append('\n').append(message)
            lines++
        }
    }

    // Rate limits: a loop that logs errors without end must not fill the log. Bluetooth lines also have a
    // limit for 10 minutes, so that a chatty stack cannot push the pairing events out of the 2 × 512 KB files.
    private val ownRate = EventLogLogic.RateWindow(WINDOW_MS, MAX_PER_WINDOW)
    private val btRate = EventLogLogic.RateWindow(WINDOW_MS, BT_MAX_PER_WINDOW)
    private val btLongRate = EventLogLogic.RateWindow(BT_LONG_WINDOW_MS, BT_MAX_PER_LONG_WINDOW)

    // Guarded by themselves: the tap thread adds, [drain] (power log thread) can also write the summaries
    private val repeats = EventLogLogic.Repeats(REPEAT_WINDOW_MS)
    private val gc = EventLogLogic.GcWindow(GC_WINDOW_MS, GC_MIN_PAUSED_MS)

    /** Bluetooth processes found in /proc: pid → short name. Written by [refreshBluetoothPids]. */
    @Volatile
    private var btPids: Map<Int, String> = emptyMap()

    /** Tap thread only: pid → Bluetooth process name, or "" for another process. */
    private val pidNames = HashMap<Int, String>()

    @Volatile
    private var resetPidNames = false

    fun start() {
        instance = this
        Thread({ run() }, "event-log-logcat").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /** Finds the Bluetooth processes again (call after a Bluetooth state change; reads /proc, not on the main thread). */
    fun refreshBluetoothPids() {
        val found = HashMap<Int, String>()
        try {
            File("/proc").listFiles()?.forEach { f ->
                val pid = f.name.toIntOrNull() ?: return@forEach
                EventLogLogic.bluetoothProcess(cmdline(pid))?.let { found[pid] = it }
            }
        } catch (_: Throwable) {}
        if (found != btPids) {
            btPids = found
            EventLog.bluetoothPids(found)
        }
        resetPidNames = true
    }

    private fun cmdline(pid: Int): String? = try {
        File("/proc/$pid/cmdline").readText()
    } catch (_: Throwable) {
        null
    }

    /** Bluetooth process name of [pid], or null. */
    private fun bluetoothName(pid: Int): String? {
        if (resetPidNames) {
            resetPidNames = false
            pidNames.clear()
        }
        btPids[pid]?.let { return it }
        val known = pidNames[pid]
        if (known != null) return known.ifEmpty { null }
        if (pidNames.size > 512) pidNames.clear()
        val name = EventLogLogic.bluetoothProcess(cmdline(pid))
        pidNames[pid] = name ?: ""
        return name
    }

    private fun startLogcat(useRoot: Boolean): Pair<java.lang.Process, BufferedReader>? {
        // `echo $$` gives the pid; exec keeps it for logcat
        val script = "echo \$\$; exec logcat -v threadtime '*:W' 'dalvikvm:D'"
        val cmd = if (useRoot) listOf("su", "-c", script) else listOf("sh", "-c", script)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val reader = BufferedReader(InputStreamReader(proc.inputStream), 8192)
        val pid = reader.readLine()?.trim()?.toIntOrNull()
        if (pid == null) {
            proc.destroy()
            return null
        }
        File(dir, "logcat.pid").writeText(pid.toString())
        return proc to reader
    }

    private fun run() {
        val pidFile = File(dir, "logcat.pid")
        try {
            stopPrevious(pidFile)
            dir.mkdirs()
            refreshBluetoothPids()
            val root = try { startLogcat(useRoot = true) } catch (_: Throwable) { null }
            val (_, reader) = root ?: startLogcat(useRoot = false) ?: run {
                EventLog.note("logcat tap failed: no logcat")
                return
            }
            if (root == null) EventLog.note("logcat tap: no su, only the app's own lines")
            val myPid = Process.myPid()
            var entry: Entry? = null
            while (true) {
                val raw = reader.readLine() ?: break
                val line = EventLogLogic.parseLogcat(raw)
                if (line != null && line.level in "VDI") {
                    // Debug lines come only for the GC summary
                    if (line.pid == myPid) EventLogLogic.parseGc(line.message)?.let { addGc(it) }
                } else if (line != null && !(line.pid == myPid && line.tag == TAG_SELF)) {
                    val keep: Boolean
                    var proc: String? = null
                    if (line.pid == myPid) {
                        keep = true
                    } else {
                        proc = bluetoothName(line.pid)
                        keep = proc != null || EventLogLogic.isBluetoothTag(line.tag)
                        if (keep && proc == null) {
                            proc = cmdline(line.pid)?.substringBefore('\u0000')?.trim()?.ifEmpty { null } ?: "pid ${line.pid}"
                        }
                    }
                    if (keep) {
                        val e = entry
                        if (e != null && e.sameCall(line)) {
                            e.add(line.message)
                        } else {
                            if (e != null) emit(e)
                            entry = Entry(line, proc)
                        }
                    }
                }
                // End of a burst: write the open entry now, not at the next line
                if (!reader.ready()) {
                    entry?.let { emit(it) }
                    entry = null
                }
            }
            entry?.let { emit(it) }
            EventLog.note("logcat tap ended")
        } catch (t: Throwable) {
            EventLog.note("logcat tap failed: $t")
        }
    }

    private fun emit(e: Entry) {
        val now = SystemClock.uptimeMillis()
        val ok = if (e.proc == null) {
            ownRate.take(now).also { report(ownRate.endedDropped, "warnings/errors of the app", MAX_PER_WINDOW, WINDOW_MS) }
        } else {
            (btRate.take(now) && btLongRate.take(now)).also {
                report(btRate.endedDropped, "Bluetooth warnings/errors", BT_MAX_PER_WINDOW, WINDOW_MS)
                report(btLongRate.endedDropped, "Bluetooth warnings/errors", BT_MAX_PER_LONG_WINDOW, BT_LONG_WINDOW_MS)
            }
        }
        if (!ok) return
        if (e.proc == null && !keepOwn(e.line.tag, e.text.toString(), now)) return
        var text = e.text.toString()
        if (e.lines > MAX_LINES) text += "\n… ${e.lines - MAX_LINES} more lines"
        EventLog.logcat(e.line.level, e.line.tag, e.line.tid, e.line.time, text, e.proc, e.line.pid)
    }

    /** False for noise and for a repeat in the window: those are only counted. */
    private fun keepOwn(tag: String, message: String, now: Long): Boolean = synchronized(repeats) {
        val noise = EventLogLogic.noiseKey(tag, message)
        if (noise != null) {
            repeats.count(noise, now)
            false
        } else {
            repeats.first(EventLogLogic.repeatKey(tag, message), now)
        }
    }.also { drain(now) }

    private fun addGc(g: EventLogLogic.Gc) {
        val now = SystemClock.uptimeMillis()
        synchronized(gc) { gc.add(g, now) }
        drain(now)
    }

    /** Writes the `repeats` and `gc` lines of the windows that ended. Also called each minute by the power log. */
    fun drain(now: Long = SystemClock.uptimeMillis(), force: Boolean = false) {
        synchronized(repeats) { repeats.drain(now, force) }?.let { counts ->
            val o = org.json.JSONObject()
            for ((k, n) in counts) o.put(k, n)
            EventLog.emit("repeats", org.json.JSONObject().put("min", REPEAT_WINDOW_MS / 60_000).put("n", o))
        }
        synchronized(gc) { gc.drain(now) }?.let { EventLog.emit("gc", it) }
    }

    private fun report(dropped: Int, what: String, max: Int, windowMs: Long) {
        if (dropped > 0) EventLog.note("logcat: $dropped $what not logged (more than $max in ${windowMs / 1000} s)")
    }

    private fun stopPrevious(pidFile: File) {
        try {
            val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return
            val cmdline = File("/proc/$pid/cmdline")
            if (!cmdline.isFile || !cmdline.readText().contains("logcat")) return
            Process.killProcess(pid)
            SystemClock.sleep(100)
            // A root logcat (started with su): the app cannot send it a signal itself
            if (File("/proc/$pid").exists()) Runtime.getRuntime().exec(arrayOf("su", "-c", "kill $pid")).waitFor()
        } catch (_: Throwable) {
        } finally {
            pidFile.delete()
        }
    }

    companion object {
        private const val TAG_SELF = "EventLog"
        private const val MAX_LINES = 40
        private const val WINDOW_MS = 10_000L
        private const val MAX_PER_WINDOW = 30
        private const val BT_MAX_PER_WINDOW = 20
        private const val BT_LONG_WINDOW_MS = 600_000L
        private const val BT_MAX_PER_LONG_WINDOW = 300
        private const val REPEAT_WINDOW_MS = 600_000L
        private const val GC_WINDOW_MS = 60_000L
        private const val GC_MIN_PAUSED_MS = 200

        @Volatile
        var instance: LogcatTap? = null
            private set
    }
}
