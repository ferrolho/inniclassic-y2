package com.themoon.y1.eventlog

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.google.android.exoplayer2.ExoPlaybackException
import com.google.android.exoplayer2.Player
import com.themoon.y1.BuildConfig
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * On-device event log of our build, for a developer who tests over adb: what the user did (keys, screens,
 * settings, playback) and what went wrong (warnings, errors, crashes, main-thread stalls).
 * Only in builds made with `-PtestHooks=true` (BuildConfig.TEST_HOOKS); otherwise each call returns at once.
 *
 * One JSON object per line in `getFilesDir()/event-log/events.jsonl` (2 files × 512 KB, see [EventLogFile]).
 * Each line has `t` (wall time), `up` (SystemClock.uptimeMillis), `v` (version code) and `ev` (event type).
 * Lines collect in memory and a background thread writes them at most every 2 s, and at once on
 * Activity pause/stop and on a crash. Read the log with the test command `log` (see TestCommands).
 */
object EventLog {
    private const val TAG = "EventLog"
    private const val WRITE_DELAY_MS = 2000L
    private const val MAX_PENDING = 2000
    private const val PREF_MIN_INTERVAL_MS = 2000L
    private const val PREFS = "AppPrefs"

    @Volatile
    var enabled = false
        private set

    private val lock = Any()
    private val pending = ArrayList<String>()
    private var droppedLines = 0
    private var writeScheduled = false
    private var wheel: EventLogLogic.WheelBurst? = null
    private var lastTheme: String? = null
    private val wallFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    private var file: EventLogFile? = null
    private var writer: Handler? = null
    private var mainHandler: Handler? = null

    // Main thread only
    private val keyRepeats = HashMap<Int, Int>()
    private val prefLast = HashMap<String, Long>()
    private val prefSkipped = HashMap<String, Int>()
    private var attachedPlayer: Any? = null

    // SharedPreferences keeps its listeners in a WeakHashMap: keep strong references
    private var prefs: SharedPreferences? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sp, key -> onPref(sp, key) }

    val dir: File? get() = file?.dir

    /** Call once, in Application.onCreate. */
    fun start(app: Application) {
        if (!BuildConfig.TEST_HOOKS || enabled) return
        try {
            val thread = HandlerThread("event-log", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
            writer = Handler(thread.looper)
            mainHandler = Handler(Looper.getMainLooper())
            file = EventLogFile(File(app.filesDir, "event-log"))
            enabled = true
            emit("start", JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("pid", Process.myPid())
                .put("bootWall", System.currentTimeMillis() - SystemClock.elapsedRealtime()))

            installCrashHandler()
            app.registerActivityLifecycleCallbacks(Lifecycle)
            app.registerComponentCallbacks(Memory)
            app.registerReceiver(Display, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(prefListener)
            }
            LogcatTap(File(app.filesDir, "event-log")).start()
        } catch (t: Throwable) {
            Log.w(TAG, "event log not started: $t")
        }
    }

    // --- writing ---

    /** Adds one line. Safe on any thread; never throws. */
    fun emit(ev: String, fields: JSONObject? = null) {
        if (!enabled) return
        try {
            synchronized(lock) {
                closeWheelLocked()
                enqueueLocked(ev, fields, SystemClock.uptimeMillis(), System.currentTimeMillis())
            }
        } catch (_: Throwable) {}
    }

    private fun enqueueLocked(ev: String, fields: JSONObject?, up: Long, wall: Long) {
        wallFormat.timeZone = TimeZone.getDefault() // the time zone can change while the app runs
        val o = JSONObject()
            .put("t", wallFormat.format(Date(wall)))
            .put("up", up)
            .put("v", BuildConfig.VERSION_CODE)
            .put("ev", ev)
        if (fields != null) for (k in fields.keys()) o.put(k, fields.get(k))
        pending.add(o.toString())
        if (pending.size > MAX_PENDING) {
            pending.removeAt(0)
            droppedLines++
        }
        if (!writeScheduled) {
            writeScheduled = true
            writer?.postDelayed(writeTask, WRITE_DELAY_MS)
        }
    }

    private val writeTask = Runnable { writePending() }

    private fun writePending() {
        val lines: List<String>
        synchronized(lock) {
            writeScheduled = false
            writer?.removeCallbacks(writeTask)
            if (droppedLines > 0) {
                pending.add(0, JSONObject().put("ev", "dropped").put("lines", droppedLines).toString())
                droppedLines = 0
            }
            lines = ArrayList(pending)
            pending.clear()
        }
        try {
            file?.append(lines)
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: $t")
        }
    }

    /** Writes the lines in memory: now on this thread ([sync]), or soon on the log thread. */
    fun flush(sync: Boolean) {
        if (!enabled) return
        try {
            synchronized(lock) { closeWheelLocked() }
            if (sync) writePending() else writer?.post(writeTask)
        } catch (_: Throwable) {}
    }

    // --- reading (test commands) ---

    fun readAll(): List<String> {
        if (!enabled) return emptyList()
        flush(sync = true)
        return file?.readAll() ?: emptyList()
    }

    fun bytes(): Long = file?.bytes() ?: 0L

    fun clear() {
        if (!enabled) return
        synchronized(lock) {
            pending.clear()
            wheel = null
        }
        file?.clear()
        emit("clear")
    }

    // --- keys (KeyTrace) ---

    internal fun key(event: KeyEvent, by: String) {
        if (!enabled) return
        try {
            val code = event.keyCode
            val screen = currentScreen()
            if (EventLogLogic.isWheel(code)) {
                // Each wheel step is a key down (repeats too: InputRouter turns them into wheel steps)
                if (event.action == KeyEvent.ACTION_DOWN) wheelStep(EventLogLogic.wheelDir(code), by, screen)
                return
            }
            val f = JSONObject().put("k", KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_"))
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        keyRepeats.remove(code)
                        f.put("a", "down")
                    } else {
                        keyRepeats[code] = event.repeatCount
                        if (!event.isLongPress) return
                        f.put("a", "long").put("ms", event.eventTime - event.downTime)
                    }
                }
                KeyEvent.ACTION_UP -> {
                    f.put("a", "up").put("ms", event.eventTime - event.downTime)
                    keyRepeats.remove(code)?.let { f.put("rep", it) }
                    if (event.isCanceled) f.put("cancel", true)
                }
                else -> return
            }
            f.put("by", by).put("s", screen)
            // Keys from `adb shell input` and from the test interface: not from the buttons
            if (event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) f.put("virt", true)
            emit("key", f)
        } catch (_: Throwable) {}
    }

    private fun wheelStep(dir: Int, by: String, screen: String) {
        synchronized(lock) {
            val up = SystemClock.uptimeMillis()
            val w = wheel
            if (w != null && w.continuesWith(dir, by, screen, up)) {
                w.count++
                w.lastUp = up
                return
            }
            closeWheelLocked()
            wheel = EventLogLogic.WheelBurst(dir, by, screen, up, System.currentTimeMillis())
            writer?.removeCallbacks(wheelCheck)
            writer?.postDelayed(wheelCheck, EventLogLogic.WHEEL_GAP_MS + 100)
        }
    }

    private val wheelCheck: Runnable = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val w = wheel ?: return
                if (w.isIdle(SystemClock.uptimeMillis())) closeWheelLocked() else writer?.postDelayed(this, EventLogLogic.WHEEL_GAP_MS + 100)
            }
        }
    }

    /** Writes the open wheel line (with the time of its first step). Each other event ends a wheel line. */
    private fun closeWheelLocked() {
        val w = wheel ?: return
        wheel = null
        val f = JSONObject()
            .put("dir", w.dir)
            .put("n", w.count)
            .put("ms", w.lastUp - w.startUp)
            .put("by", w.by)
            .put("s", w.screen)
        enqueueLocked("wheel", f, w.startUp, w.startWall)
    }

    private fun currentScreen(): String {
        val state = NavigationManager.getInstance().currentState
        val mode = if (state is ScreenState.Browser) {
            MainActivity.instance?.let { if (it.isBrowserControllerInitialized()) it.browserController.currentBrowserMode else null }
        } else {
            null
        }
        return EventLogLogic.screenName(state, mode)
    }

    // --- screens (NavigationManager) ---

    fun screen(from: ScreenState?, to: ScreenState, backStack: Int) {
        if (!enabled) return
        val f = JSONObject()
            .put("from", if (from == null) JSONObject.NULL else EventLogLogic.screenName(from))
            .put("to", EventLogLogic.screenName(to))
            .put("depth", backStack)
        EventLogLogic.screenDetail(to)?.let { f.put("detail", it) }
        emit("screen", f)
    }

    // --- settings (AppPrefs listener) ---

    private fun onPref(sp: SharedPreferences, key: String?) {
        if (!enabled || key == null) return
        if (EventLogLogic.isSecretKey(key) || EventLogLogic.isQuietKey(key)) return
        try {
            val now = SystemClock.uptimeMillis()
            val last = prefLast[key]
            if (last != null && now - last < PREF_MIN_INTERVAL_MS) {
                // Fast changes (a slider): one line now, then one line with the last value
                val skipped = prefSkipped[key]
                prefSkipped[key] = (skipped ?: 0) + 1
                if (skipped == null) mainHandler?.postDelayed({ logPref(sp, key) }, PREF_MIN_INTERVAL_MS - (now - last))
                return
            }
            logPref(sp, key)
        } catch (_: Throwable) {}
    }

    private fun logPref(sp: SharedPreferences, key: String) {
        prefLast[key] = SystemClock.uptimeMillis()
        val all = sp.all
        val f = JSONObject().put("key", key)
        if (all.containsKey(key)) f.put("value", EventLogLogic.jsonValue(all[key])) else f.put("removed", true)
        prefSkipped.remove(key)?.let { f.put("changes", it + 1) }
        emit("pref", f)
    }

    // --- theme (ThemeManager.setThemeIndex) ---

    fun theme(index: Int) {
        if (!enabled) return
        val name = try {
            ThemeManager.getCurrentTheme().name
        } catch (_: Throwable) {
            null
        }
        synchronized(lock) {
            if (name == lastTheme) return
            lastTheme = name
        }
        emit("theme", JSONObject().put("name", name ?: JSONObject.NULL).put("index", index))
    }

    // --- playback (AudioPlayerManager entry points, and the ExoPlayer state) ---

    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()

    private fun playFields(action: String): JSONObject {
        val f = JSONObject().put("a", action)
        val main = MainActivity.instance
        val apm = AudioPlayerManager.getInstance()
        val path = main?.let { it.currentPlaylist.getOrNull(it.currentIndex)?.absolutePath } ?: apm.currentlyPlayingFilePath
        f.put("path", path ?: JSONObject.NULL)
        if (onMain()) {
            attachPlayer()
            f.put("pos", apm.getCurrentPosition())
        }
        return f
    }

    /** next, prev, playPause (logged as play or pause). */
    fun playback(action: String) {
        if (!enabled) return
        try {
            var a = action
            if (action == "playPause") {
                val apm = AudioPlayerManager.getInstance()
                val playing = if (apm.isUsingLegacyPlayer && apm.legacyPlayer != null) {
                    apm.legacyPlayer?.isPlaying == true
                } else {
                    apm.exoPlayer?.playWhenReady == true
                }
                a = if (playing) "pause" else "play"
            }
            emit("play", playFields(a))
        } catch (_: Throwable) {}
    }

    fun seek(fromMs: Long, offsetMs: Int) {
        if (!enabled) return
        try {
            emit("play", playFields("seek").put("from", fromMs).put("to", (fromMs + offsetMs).coerceAtLeast(0L)))
        } catch (_: Throwable) {}
    }

    /** A new queue (AudioPlayerManager.playTrackList). */
    fun playList(list: List<File>, index: Int, seekMs: Long) {
        if (!enabled) return
        try {
            val f = JSONObject()
                .put("a", "queue")
                .put("path", list.getOrNull(index)?.absolutePath ?: JSONObject.NULL)
                .put("idx", index)
                .put("n", list.size)
            if (seekMs > 0) f.put("seek", seekMs)
            emit("play", f)
        } catch (_: Throwable) {}
    }

    /** A track starts to load (AudioPlayerManager.prepareMusicTrack): each track change, also automatic ones. */
    fun load(index: Int, seekMs: Long) {
        if (!enabled) return
        try {
            val main = MainActivity.instance
            val f = JSONObject()
                .put("a", "load")
                .put("path", main?.currentPlaylist?.getOrNull(index)?.absolutePath ?: JSONObject.NULL)
                .put("idx", index)
                .put("n", main?.currentPlaylist?.size ?: 0)
            if (seekMs > 0) f.put("seek", seekMs)
            if (onMain()) attachPlayer()
            emit("play", f)
        } catch (_: Throwable) {}
    }

    fun podcast(title: String, channel: String) {
        if (!enabled) return
        emit("play", JSONObject().put("a", "podcast").put("title", title).put("channel", channel))
    }

    private fun attachPlayer() {
        val exo = AudioPlayerManager.getInstance().exoPlayer ?: return
        if (exo === attachedPlayer) return
        attachedPlayer = exo
        exo.addListener(PlayerListener)
    }

    /** What ExoPlayer did (also for changes without an entry point: audio focus, unplugged headphones, Stop). */
    private object PlayerListener : Player.EventListener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val why = when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "request"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "focusLoss"
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "noisy"
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "endOfItem"
                else -> reason.toString()
            }
            emit("player", JSONObject().put("a", if (playWhenReady) "play" else "pause").put("why", why).put("pos", position()))
        }

        override fun onPlaybackStateChanged(state: Int) {
            val name = when (state) {
                Player.STATE_ENDED -> "ended"
                Player.STATE_IDLE -> "idle"
                else -> return
            }
            emit("player", JSONObject().put("a", name).put("pos", position()))
        }

        override fun onPlayerError(error: ExoPlaybackException) {
            emit("player", JSONObject().put("a", "error").put("error", EventLogLogic.throwableJson(error, 8)))
        }

        private fun position(): Long = try {
            AudioPlayerManager.getInstance().exoPlayer?.currentPosition ?: -1L
        } catch (_: Throwable) {
            -1L
        }
    }

    // --- USB storage (UsbStorageUiManager, UsbMassStorageController) ---

    fun usb(state: String) {
        if (!enabled) return
        emit("usb", JSONObject().put("a", state))
    }

    /** The root commands that share or eject the storage, or switch the USB function (not the state polls). */
    fun usbCommand(cmd: String, out: String?) {
        if (!enabled) return
        if (!cmd.contains("mount 4") && !cmd.contains("setFunction")) return
        emit("usb", JSONObject().put("cmd", cmd).put("out", EventLogLogic.cut(out?.trim().orEmpty(), 120)))
    }

    // --- library scan (MediaLibraryManager.startMediaLibraryScan) ---

    fun scan(listener: MediaLibraryManager.ScanProgressListener?): MediaLibraryManager.ScanProgressListener? {
        if (!enabled) return listener
        val start = SystemClock.uptimeMillis()
        emit("scan", JSONObject().put("a", "start"))
        return object : MediaLibraryManager.ScanProgressListener {
            @Volatile private var files = 0

            override fun onProgress(scanned: Int, total: Int) {
                files = total
                listener?.onProgress(scanned, total)
            }

            override fun onComplete(musicCount: Int, booksCount: Int) {
                emit("scan", JSONObject()
                    .put("a", "end")
                    .put("music", musicCount)
                    .put("books", booksCount)
                    .put("files", files)
                    .put("ms", SystemClock.uptimeMillis() - start))
                listener?.onComplete(musicCount, booksCount)
            }
        }
    }

    // --- test interface ---

    fun testCommand(cmd: String, intent: Intent) {
        if (!enabled || cmd == "log" || cmd == "state") return
        try {
            val f = JSONObject().put("cmd", cmd)
            intent.extras?.let { extras ->
                for (k in extras.keySet()) {
                    if (k == "cmd") continue
                    @Suppress("DEPRECATION")
                    f.put(k, EventLogLogic.cut(extras.get(k).toString(), 200))
                }
            }
            emit("test", f)
        } catch (_: Throwable) {}
    }

    // --- warnings, errors, crashes, stalls ---

    internal fun logcat(level: Char, tag: String, tid: Int, time: String, message: String) {
        emit("log", JSONObject()
            .put("lvl", level.toString())
            .put("tag", tag)
            .put("msg", EventLogLogic.cut(EventLogLogic.redact(message), EventLogLogic.MAX_LOG_MESSAGE))
            .put("tid", tid)
            .put("lt", time))
    }

    internal fun note(text: String) {
        emit("note", JSONObject().put("msg", text))
    }

    internal fun stall(ms: Long, stack: Array<StackTraceElement>, ongoing: Boolean) {
        val f = JSONObject().put("ms", ms).put("s", try { currentScreenSafe() } catch (_: Throwable) { "?" })
        if (ongoing) f.put("ongoing", true)
        f.put("stack", EventLogLogic.frames(stack, 15))
        emit("stall", f)
        if (ongoing) flush(sync = true) // the system can kill the app soon (ANR)
    }

    // Read from a background thread: only the NavigationManager state, no views
    private fun currentScreenSafe(): String = EventLogLogic.screenName(NavigationManager.getInstance().currentState)

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val f = EventLogLogic.throwableJson(error).put("thread", thread.name)
                emit("crash", f)
                flush(sync = true)
            } catch (_: Throwable) {}
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                System.exit(10)
            }
        }
    }

    // --- Activity lifecycle, memory, display ---

    private object Lifecycle : Application.ActivityLifecycleCallbacks {
        private fun log(activity: Activity, a: String, extra: JSONObject? = null) {
            val f = extra ?: JSONObject()
            f.put("a", a).put("name", activity.javaClass.simpleName).put("id", System.identityHashCode(activity))
            emit("activity", f)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) =
            log(activity, "created", JSONObject().put("restored", savedInstanceState != null))

        override fun onActivityStarted(activity: Activity) = log(activity, "started")

        override fun onActivityResumed(activity: Activity) {
            log(activity, "resumed")
            StallWatch.resume()
        }

        override fun onActivityPaused(activity: Activity) {
            log(activity, "paused")
            StallWatch.pause()
            flush(sync = false)
        }

        override fun onActivityStopped(activity: Activity) {
            log(activity, "stopped")
            flush(sync = false)
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            log(activity, "destroyed")
            flush(sync = false)
        }
    }

    private object Memory : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            emit("memory", JSONObject().put("trim", level))
        }

        override fun onLowMemory() {
            emit("memory", JSONObject().put("low", true))
            flush(sync = false)
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    private object Display : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            emit("display", JSONObject().put("on", intent.action == Intent.ACTION_SCREEN_ON))
            if (intent.action == Intent.ACTION_SCREEN_OFF) flush(sync = false)
        }
    }
}
