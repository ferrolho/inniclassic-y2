package com.themoon.y1.eventlog

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.SystemClock
import com.themoon.y1.BuildConfig
import com.themoon.y1.StoragePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Save Log (Settings → Developer): copies the event log and the power log into one zip on the memory card
 * (`InniClassic logs/log-<date>-<time>.zip`; the internal storage when no card is in), for a tester who has no adb:
 * connect USB, open the folder, attach the zip to a GitHub issue. The zip also has `info.txt` (version, device).
 */
object EventLogExport {
    const val FOLDER = "InniClassic logs"

    /** Writes the zip and returns it. Slow (file copy): call it off the main thread. */
    fun save(context: Context): File {
        EventLog.note("log saved by the user")
        EventLog.flush(sync = true)
        val src = EventLog.dir ?: throw IllegalStateException("no event log")
        val root = if (StoragePaths.hasSecondaryStorage()) File(StoragePaths.SECONDARY_PATH) else StoragePaths.getPrimaryRoot()
        val dir = File(root, FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("cannot make $dir")
        val out = File(dir, fileName(Date()))
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("info.txt"))
            zip.write(info().toByteArray())
            zip.closeEntry()
            src.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.forEach { f ->
                zip.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        // MTP shows a new file only after the media scanner knows it.
        MediaScannerConnection.scanFile(context.applicationContext, arrayOf(out.absolutePath), null, null)
        return out
    }

    fun fileName(now: Date): String = "log-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(now) + ".zip"

    private fun info(): String = buildString {
        append("version ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ")
            .append(Build.VERSION.RELEASE).append(", ").append(Build.DISPLAY).append('\n')
        append("saved ").append(SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            .append(' ').append(TimeZone.getDefault().id).append('\n')
        append("uptime ").append(SystemClock.elapsedRealtime() / 1000).append(" s\n")
    }
}
