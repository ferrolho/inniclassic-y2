package com.themoon.y1.eventlog

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.themoon.y1.BuildConfig
import com.themoon.y1.managers.AudioPlayerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Power log: one `power` line each minute while the CPU is awake, in `event-log/power.jsonl` (2 files × 512 KB,
 * about 2 days), apart from the event log so that it does not push the events out.
 * The timer counts uptime, which stops in deep sleep: the log never wakes the CPU. The first line after a sleep
 * shows the sleep in `awakePct` (awake time / wall time since the line before).
 *
 * Fields: `bat` (%), `mV`, `cur` (BatteryAverageCurrent of the MediaTek gauge, raw), `tempC`, `plug` (usb, ac, none),
 * `chg` (charging, full, …), `scr` (screen on), `play` (music plays), `awakePct`, `cpuPct` (all cores: 100 = all
 * busy), `top` (the 3 processes with the most CPU time: [name, % of one core]), `wifi` (off, on, connected) and
 * `rssi`, `wl` (kernel wake locks held at the sample, from /sys/power/wake_lock with su).
 */
internal object PowerLog {
    private const val INTERVAL_MS = 60_000L
    private const val TOP = 3

    private var handler: Handler? = null
    private var file: EventLogFile? = null
    private lateinit var app: Context
    private val wallFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    // Power thread only
    private var lastUp = 0L
    private var lastReal = 0L
    private var lastCpu: PowerLogic.CpuTimes? = null
    private var lastTicks: Map<Int, Long> = emptyMap()
    private var root: RootShell? = null
    private var rootFailed = false

    fun start(context: Context, dir: File) {
        if (handler != null) return
        app = context.applicationContext
        file = EventLogFile(dir, name = "power")
        val thread = HandlerThread("event-log-power", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(thread.looper).also { it.post(tick) }
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                sample()
            } catch (t: Throwable) {
                EventLog.note("power log: $t")
            }
            LogcatTap.instance?.drain()
            handler?.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun sample() {
        val up = SystemClock.uptimeMillis()
        val real = SystemClock.elapsedRealtime()
        val o = JSONObject()
            .put("t", wallFormat.format(Date()))
            .put("up", up)
            .put("v", BuildConfig.VERSION_CODE)
            .put("ev", "power")

        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { b ->
            val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            o.put("bat", level * 100 / scale)
            o.put("mV", b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0))
            o.put("tempC", b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
            o.put("plug", PowerLogic.plugName(b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
            o.put("chg", PowerLogic.statusName(b.getIntExtra(BatteryManager.EXTRA_STATUS, 0)))
        }
        readInt("/sys/class/power_supply/battery/BatteryAverageCurrent")?.let { o.put("cur", it) }
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        @Suppress("DEPRECATION")
        o.put("scr", pm?.isScreenOn == true)
        o.put("play", try { AudioPlayerManager.getInstance().isPlaying() } catch (_: Throwable) { false })

        if (lastReal > 0 && real > lastReal) o.put("awakePct", PowerLogic.pct(up - lastUp, real - lastReal))
        val cpu = PowerLogic.parseCpu(readText("/proc/stat"))
        val prevCpu = lastCpu
        if (cpu != null && prevCpu != null) o.put("cpuPct", PowerLogic.busyPct(prevCpu, cpu))
        lastCpu = cpu

        val ticks = processTicks()
        if (lastReal > 0) {
            val top = JSONArray()
            for ((pid, used) in PowerLogic.top(lastTicks, ticks, TOP)) {
                top.put(JSONArray().put(processName(pid)).put(PowerLogic.pct(used * PowerLogic.MS_PER_TICK, real - lastReal)))
            }
            o.put("top", top)
        }
        lastTicks = ticks
        lastUp = up
        lastReal = real

        wifi(o)
        wakeLocks()?.let { o.put("wl", JSONArray(it)) }
        file?.append(listOf(o.toString()))
    }

    private fun wifi(o: JSONObject) {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (!wm.isWifiEnabled) {
            o.put("wifi", "off")
            return
        }
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connected = cm?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)?.isConnected == true
        o.put("wifi", if (connected) "connected" else "on")
        if (connected) o.put("rssi", wm.connectionInfo?.rssi ?: 0)
    }

    /** CPU ticks (user + system) of each process now. */
    private fun processTicks(): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        File("/proc").list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            PowerLogic.parsePidStat(readText("/proc/$pid/stat"))?.let { out[pid] = it.second }
        }
        return out
    }

    private fun processName(pid: Int): String {
        val cmd = readText("/proc/$pid/cmdline")?.substringBefore('\u0000')?.trim()
        if (!cmd.isNullOrEmpty()) return cmd.substringAfterLast('/')
        return PowerLogic.parsePidStat(readText("/proc/$pid/stat"))?.first ?: "pid $pid"
    }

    /** Kernel wake locks held now, or null when su does not work. */
    private fun wakeLocks(): List<String>? {
        if (rootFailed) return null
        val shell = root ?: RootShell.open()?.also { root = it } ?: run {
            rootFailed = true
            return null
        }
        val text = shell.run("cat /sys/power/wake_lock") ?: run {
            shell.close()
            root = null
            return null
        }
        return text.split(' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun readText(path: String): String? = try {
        File(path).readText()
    } catch (_: Throwable) {
        null
    }

    private fun readInt(path: String): Int? = readText(path)?.trim()?.toIntOrNull()

    /** One su shell for the life of the process: a new su for each sample costs more than the sample. */
    private class RootShell(private val proc: java.lang.Process) {
        private val out = OutputStreamWriter(proc.outputStream)
        private val reader = BufferedReader(InputStreamReader(proc.inputStream))

        /** The output of [cmd], or null after 3 s with no end mark. */
        fun run(cmd: String): String? = try {
            out.write("$cmd; echo $END\n")
            out.flush()
            val text = StringBuilder()
            val deadline = SystemClock.uptimeMillis() + 3000
            var done = false
            while (!done && SystemClock.uptimeMillis() < deadline) {
                if (!reader.ready()) {
                    SystemClock.sleep(20)
                    continue
                }
                val line = reader.readLine() ?: break
                if (line == END) done = true else text.append(line).append('\n')
            }
            if (done) text.toString() else null
        } catch (_: Throwable) {
            null
        }

        fun close() {
            try { proc.destroy() } catch (_: Throwable) {}
        }

        companion object {
            private const val END = "__power_end__"

            fun open(): RootShell? = try {
                RootShell(ProcessBuilder("su").redirectErrorStream(true).start()).takeIf { it.run("id") != null }
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/** Pure parts of the power log, for unit tests. */
internal object PowerLogic {
    /** Linux clock ticks: 100 a second. */
    const val MS_PER_TICK = 10L

    data class CpuTimes(val busy: Long, val total: Long)

    /** The first line of /proc/stat: "cpu  user nice system idle iowait irq softirq …". */
    fun parseCpu(stat: String?): CpuTimes? {
        val first = stat?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val v = first.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (v.size < 5) return null
        val total = v.sum()
        val idle = v[3] + v[4]
        return CpuTimes(total - idle, total)
    }

    fun busyPct(a: CpuTimes, b: CpuTimes): Int {
        val total = b.total - a.total
        return if (total <= 0) 0 else ((b.busy - a.busy) * 100 / total).toInt()
    }

    /** (name, utime + stime in ticks) from /proc/<pid>/stat. The name is in brackets and can have spaces. */
    fun parsePidStat(stat: String?): Pair<String, Long>? {
        if (stat == null) return null
        val open = stat.indexOf('(')
        val close = stat.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val f = stat.substring(close + 1).trim().split(' ')
        // After the name: state(0) ppid(1) … utime(11) stime(12)
        val utime = f.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = f.getOrNull(12)?.toLongOrNull() ?: return null
        return stat.substring(open + 1, close) to utime + stime
    }

    /** The [n] pids that used the most ticks between [before] and [now] (new pids count from 0), most first. */
    fun top(before: Map<Int, Long>, now: Map<Int, Long>, n: Int): List<Pair<Int, Long>> =
        now.map { (pid, t) -> pid to (t - (before[pid] ?: 0L)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(n)

    /** [part] as a percent of [whole], one decimal. */
    fun pct(part: Long, whole: Long): Double = if (whole <= 0) 0.0 else Math.round(part * 1000.0 / whole) / 10.0

    fun plugName(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "none"
        else -> plugged.toString()
    }

    fun statusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
        else -> "unknown"
    }
}
