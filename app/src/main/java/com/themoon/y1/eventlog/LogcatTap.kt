package com.themoon.y1.eventlog

import android.os.Process
import android.os.SystemClock
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Puts each warning and error that this process writes to logcat (Log.w, Log.e, Log.wtf: from the app, from
 * ExoPlayer and from the framework) into the event log, without a change at each call.
 *
 * One `logcat -v threadtime *:W` child process, which blocks until a line comes: no polling.
 * Since Android 4.1 an app without READ_LOGS reads only the lines of its own user id; the tap also keeps
 * only the lines of this process. Lines of one Log call (a stack trace) become one event.
 * If the app process dies, the child stays until its next write fails; at the next start, the tap stops the
 * child of the previous process (its pid is in `logcat.pid`).
 */
internal class LogcatTap(private val dir: File) {

    private class Entry(val line: EventLogLogic.LogcatLine) {
        val text = StringBuilder(line.message)
        var lines = 1

        fun sameCall(o: EventLogLogic.LogcatLine) =
            o.tid == line.tid && o.level == line.level && o.tag == line.tag && o.time == line.time

        fun add(message: String) {
            if (lines < MAX_LINES) text.append('\n').append(message)
            lines++
        }
    }

    // Rate limit: a loop that logs errors without end must not fill the log
    private var windowStart = 0L
    private var inWindow = 0
    private var dropped = 0

    fun start() {
        Thread({ run() }, "event-log-logcat").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun run() {
        val pidFile = File(dir, "logcat.pid")
        try {
            stopPrevious(pidFile)
            dir.mkdirs()
            // `echo $$` gives the pid; exec keeps it for logcat
            val proc = ProcessBuilder("sh", "-c", "echo \$\$; exec logcat -v threadtime '*:W'")
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream), 8192)
            reader.readLine()?.trim()?.toIntOrNull()?.let { pidFile.writeText(it.toString()) }
            val myPid = Process.myPid()
            var entry: Entry? = null
            while (true) {
                val raw = reader.readLine() ?: break
                val line = EventLogLogic.parseLogcat(raw)
                if (line != null && line.pid == myPid && line.tag != TAG_SELF) {
                    val e = entry
                    if (e != null && e.sameCall(line)) {
                        e.add(line.message)
                    } else {
                        if (e != null) emit(e)
                        entry = Entry(line)
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
        if (now - windowStart > WINDOW_MS) {
            if (dropped > 0) EventLog.note("logcat: $dropped warnings/errors not logged (more than $MAX_PER_WINDOW in ${WINDOW_MS / 1000} s)")
            windowStart = now
            inWindow = 0
            dropped = 0
        }
        if (++inWindow > MAX_PER_WINDOW) {
            dropped++
            return
        }
        var text = e.text.toString()
        if (e.lines > MAX_LINES) text += "\n… ${e.lines - MAX_LINES} more lines"
        EventLog.logcat(e.line.level, e.line.tag, e.line.tid, e.line.time, text)
    }

    private fun stopPrevious(pidFile: File) {
        try {
            val pid = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return
            val cmdline = File("/proc/$pid/cmdline")
            if (cmdline.isFile && cmdline.readText().contains("logcat")) Process.killProcess(pid)
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
    }
}
