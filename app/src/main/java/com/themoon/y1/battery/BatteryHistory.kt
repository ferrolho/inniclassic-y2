package com.themoon.y1.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The battery history of Settings → Battery: a small text file (`files/battery-history.txt`, one
 * line a record, [BatteryHistoryLogic.format]) of the last 11 days.
 *
 * A record is written only when a broadcast that the app gets anyway comes (battery changed, charger, screen on or
 * off, shutdown) or the music or the radio starts or stops ([setPlaying]), and the level, the charger, the screen or
 * the play state changed, or the last record is 15 min old. The history has no timer, alarm or wake lock of its own,
 * so it never wakes the device. A typical day has some hundred lines
 * (about 5 KB). On a new boot (kernel boot id) a boot record marks the end of the time when the device was off.
 */
object BatteryHistory : BatterySampleSource {
    private const val TAG = "BatteryHistory"
    private const val FILE_NAME = "battery-history.txt"
    /** Write the file again without the old lines after this many new lines. */
    private const val PRUNE_EVERY = 500

    private val lock = Any()
    private val records = ArrayList<BatterySample>()
    private var loaded = false
    private var appended = 0
    private var file: File? = null
    private var worker: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    // The last values of the broadcasts (main thread) and of the players (any thread)
    @Volatile private var level = -1
    @Volatile private var plugged = false
    @Volatile private var screenOn = true
    @Volatile private var playSources = 0

    /** A source of sound for [setPlaying]. */
    const val MUSIC = 1
    const val RADIO = 2

    /** The music player or the FM radio starts ([on]) or stops playing. A change of the play state is a new record. */
    fun setPlaying(source: Int, on: Boolean) {
        val before: Int
        val after: Int
        synchronized(lock) {
            before = playSources
            after = if (on) before or source else before and source.inv()
            playSources = after
        }
        if ((before != 0) != (after != 0)) recordNow()
    }

    private fun recordNow() {
        if (level < 0) return
        val sample = BatterySample.state(System.currentTimeMillis(), level, plugged, screenOn, playSources != 0)
        worker?.post { record(sample) }
    }

    fun start(context: Context) {
        if (worker != null) return
        val app = context.applicationContext
        file = File(app.filesDir, FILE_NAME)
        val thread = HandlerThread("battery-history", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        worker = Handler(thread.looper).also { it.post { load() } }
        @Suppress("DEPRECATION")
        screenOn = (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isScreenOn != false
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
            addAction("android.intent.action.QUICKBOOT_POWEROFF")
        }
        // ACTION_BATTERY_CHANGED is sticky: the receiver gets the level now at once
        app.registerReceiver(receiver, filter)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (raw >= 0 && scale > 0) level = (raw * 100 / scale).coerceIn(0, 100)
                    plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) > 0
                }
                Intent.ACTION_POWER_CONNECTED -> plugged = true
                Intent.ACTION_POWER_DISCONNECTED -> plugged = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                else -> {
                    shutdown()
                    return
                }
            }
            recordNow()
        }
    }

    /** The shutdown record: written before Android stops the process (it waits at most 0.5 s). */
    private fun shutdown() {
        val w = worker ?: return
        val done = CountDownLatch(1)
        w.post {
            append(BatterySample.down(System.currentTimeMillis()))
            done.countDown()
        }
        try {
            done.await(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
    }

    // --- Worker thread ---

    private fun load() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        val read = try {
            if (f.exists()) f.readLines().mapNotNull(BatteryHistoryLogic::parse) else emptyList()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the battery history: $t")
            emptyList()
        }
        val kept = BatteryHistoryLogic.prune(read, now)
        synchronized(lock) {
            records.clear()
            records.addAll(kept)
            loaded = true
        }
        if (kept.size < read.size) rewrite()
        // A new boot: the device was off from the last record (or the shutdown record) to the boot
        val bootId = bootId()
        val lastBoot = kept.lastOrNull { it.kind == BatterySample.Kind.BOOT }
        val bootTime = now - SystemClock.elapsedRealtime()
        val sameBoot = if (bootId.isNotEmpty() && lastBoot?.boot?.isNotEmpty() == true) lastBoot.boot == bootId
        else lastBoot != null && Math.abs(lastBoot.t - bootTime) < BatteryHistoryLogic.MINUTE_MS
        if (!sameBoot) append(BatterySample.boot(bootTime, bootId))
        notifyListeners()
    }

    private fun record(sample: BatterySample) {
        val last = synchronized(lock) { if (!loaded) return else records.lastOrNull() }
        if (!BatteryHistoryLogic.needsRecord(last, sample)) return
        append(sample)
        if (++appended >= PRUNE_EVERY) {
            appended = 0
            val kept = synchronized(lock) {
                val k = BatteryHistoryLogic.prune(ArrayList(records), sample.t)
                records.clear()
                records.addAll(k)
                k
            }
            if (kept.isNotEmpty()) rewrite()
        }
        notifyListeners()
    }

    private fun append(sample: BatterySample) {
        synchronized(lock) { records.add(sample) }
        val f = file ?: return
        try {
            FileOutputStream(f, true).use { it.write((BatteryHistoryLogic.format(sample) + "\n").toByteArray()) }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write the battery history: $t")
        }
    }

    /** Writes all records to a new file, then puts it in place of the old one. */
    private fun rewrite() {
        val f = file ?: return
        val text = synchronized(lock) { records.joinToString("") { BatteryHistoryLogic.format(it) + "\n" } }
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(f)) Log.w(TAG, "Could not replace the battery history")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not write the battery history: $t")
        }
    }

    private fun bootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim().replace("-", "").take(12)
    } catch (_: Throwable) {
        ""
    }

    private fun notifyListeners() {
        main.post { for (l in ArrayList(listeners)) l() }
    }

    // --- For the screen (main thread) ---

    override fun samples(): List<BatterySample> = synchronized(lock) { ArrayList(records) }

    override fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    override fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
