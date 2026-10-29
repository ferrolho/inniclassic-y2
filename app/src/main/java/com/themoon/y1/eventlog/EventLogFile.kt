package com.themoon.y1.eventlog

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * The log files: `<name>.jsonl` (current) and `<name>.1.jsonl` … (older), one JSON object per line
 * (`events`: the event log; `power`: the power log).
 * When the current file would grow past [maxBytes], the files move up by one and the oldest is deleted.
 * No fsync: the kernel writes the data to flash in its own time.
 */
class EventLogFile(
    val dir: File,
    private val maxBytes: Long = EventLogLogic.MAX_FILE_BYTES,
    private val files: Int = EventLogLogic.FILES,
    private val name: String = "events"
) {
    val current: File get() = File(dir, "$name.jsonl")

    private fun older(i: Int) = File(dir, "$name.$i.jsonl")

    @Synchronized
    fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        if (!dir.isDirectory) dir.mkdirs()
        var size = current.length()
        var out: BufferedOutputStream? = null
        try {
            for (line in lines) {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (size > 0 && size + bytes.size > maxBytes) {
                    out?.close()
                    out = null
                    rotate()
                    size = 0
                }
                val o = out ?: BufferedOutputStream(FileOutputStream(current, true), 16 * 1024).also { out = it }
                o.write(bytes)
                size += bytes.size
            }
        } finally {
            try {
                out?.close()
            } catch (_: Exception) {}
        }
    }

    private fun rotate() {
        for (i in files - 1 downTo 1) {
            val src = if (i == 1) current else older(i - 1)
            val dst = older(i)
            dst.delete()
            if (src.exists()) src.renameTo(dst)
        }
        current.delete()
    }

    /** All files, oldest first. */
    fun allFiles(): List<File> = (files - 1 downTo 1).map { older(it) } + current

    /** All lines, oldest first. */
    @Synchronized
    fun readAll(): List<String> {
        val out = ArrayList<String>()
        for (f in allFiles()) {
            if (!f.isFile) continue
            f.forEachLine(Charsets.UTF_8) { if (it.isNotBlank()) out.add(it) }
        }
        return out
    }

    @Synchronized
    fun bytes(): Long = allFiles().sumOf { if (it.isFile) it.length() else 0L }

    @Synchronized
    fun clear() {
        for (f in allFiles()) f.delete()
    }
}
