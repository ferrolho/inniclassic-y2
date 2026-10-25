package com.themoon.y1.testhooks

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import com.themoon.y1.MainActivity
import com.themoon.y1.testhooks.TestPowerLogic.WakeStep
import org.json.JSONObject

/**
 * Screen power for the test interface. Main thread only.
 *
 * - Before each command: wake the screen the app's way and wait until the app is awake ([wakeThen]).
 *   Android screen off: a screen wake lock with ACQUIRE_CAUSES_WAKEUP turns the screen on, as the power key
 *   does; the app then gets ACTION_SCREEN_ON and onResume as usual. The app's fake screen-off overlay:
 *   [MainActivity.wakeFromFakeScreenOff], the call of the first key press. No key event is sent, so the wake
 *   is not a key press of the command.
 * - Keep awake ([keepAwake]): FLAG_KEEP_SCREEN_ON on the activity window, and the app's backlight timer reset
 *   every [TestPowerLogic.BACKLIGHT_REFRESH_MS], until 10 minutes after the last command (or `awake minutes=N`).
 *   No system setting changes.
 * - A partial wake lock holds the CPU on from the command until its reply, so that the reply is not late when
 *   the device sleeps (adb over Wi-Fi).
 */
internal object TestPower {
    private const val CPU_LOCK_MAX_MS = 70_000L

    private val handler = Handler(Looper.getMainLooper())
    private var cpuLock: PowerManager.WakeLock? = null
    private var screenLock: PowerManager.WakeLock? = null

    /** Uptime (ms) when the keep-awake ends; 0 = off. */
    var keepAwakeUntil = 0L
        private set
    /** What the last command woke ("screen", "fakeScreenOff"), and how long the wake took. */
    private var woke = ArrayList<String>()
    private var wakeMs = 0L

    private val tick = Runnable { onTick() }

    private fun pm(main: MainActivity) = main.getSystemService(Context.POWER_SERVICE) as PowerManager

    // --- command start and end ---

    fun commandStarted(main: MainActivity) {
        woke = ArrayList()
        wakeMs = 0L
        try {
            val lock = cpuLock ?: pm(main).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y1:TestCommand").also {
                it.setReferenceCounted(false)
                cpuLock = it
            }
            lock.acquire(CPU_LOCK_MAX_MS)
        } catch (_: Throwable) {}
    }

    fun commandFinished() {
        try {
            cpuLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
    }

    // --- wake ---

    fun snapshot(main: MainActivity): TestPowerLogic.Snapshot {
        @Suppress("DEPRECATION")
        val screenOn = pm(main).isScreenOn // API 19: no isInteractive
        return TestPowerLogic.Snapshot(
            screenOn = screenOn,
            fakeScreenOff = main.isFakeScreenOff,
            screenSleeping = main.isScreenSleeping,
            windowFocus = Windows.focusedRoot(main).hasWindowFocus()
        )
    }

    /**
     * Wakes the screen if necessary, then calls [then] with null when the app is awake, or with an error after
     * [TestPowerLogic.WAKE_TIMEOUT_MS]. When the app is already awake, [then] runs at once (in this call).
     * The wait polls on the main looper; it never blocks the main thread.
     */
    fun wakeThen(main: MainActivity, then: (String?) -> Unit) {
        val start = SystemClock.uptimeMillis()
        var requested = false
        var screenWakeAt = -1L
        var onSince = -1L
        lateinit var poll: () -> Unit
        poll = poll@{
            val now = SystemClock.uptimeMillis()
            val s = try {
                snapshot(main)
            } catch (t: Throwable) {
                releaseScreenLock()
                then("wake failed: $t")
                return@poll
            }
            if (s.screenOn && !s.fakeScreenOff) {
                if (onSince < 0) onSince = now
            } else {
                onSince = -1L
            }
            val step = TestPowerLogic.wakeStep(
                s, now - start, requested,
                if (onSince < 0) -1L else now - onSince,
                if (screenWakeAt < 0) -1L else now - screenWakeAt
            )
            when (step) {
                WakeStep.READY -> {
                    releaseScreenLock()
                    if (requested) wakeMs = now - start
                    then(null)
                }
                WakeStep.TIMEOUT -> {
                    releaseScreenLock()
                    wakeMs = now - start
                    then(TestPowerLogic.wakeError(s))
                }
                WakeStep.WAKE_SCREEN -> {
                    requested = true
                    screenWakeAt = now
                    if ("screen" !in woke) woke.add("screen")
                    wakeScreen(main)
                    handler.postDelayed(poll, TestPowerLogic.WAKE_POLL_MS)
                }
                WakeStep.WAKE_FAKE -> {
                    requested = true
                    if ("fakeScreenOff" !in woke) woke.add("fakeScreenOff")
                    try {
                        main.wakeFromFakeScreenOff()
                    } catch (_: Throwable) {}
                    handler.postDelayed(poll, TestPowerLogic.WAKE_POLL_MS)
                }
                WakeStep.WAIT -> handler.postDelayed(poll, TestPowerLogic.WAKE_POLL_MS)
            }
        }
        poll()
    }

    /** Turns the Android screen on. ON_AFTER_RELEASE restarts the system screen timeout at release. */
    private fun wakeScreen(main: MainActivity) {
        releaseScreenLock()
        try {
            @Suppress("DEPRECATION")
            val lock = pm(main).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Y1:TestWake"
            )
            lock.setReferenceCounted(false)
            lock.acquire(TestPowerLogic.WAKE_TIMEOUT_MS + 1000L)
            screenLock = lock
        } catch (_: Throwable) {}
    }

    private fun releaseScreenLock() {
        try {
            screenLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        screenLock = null
    }

    // --- keep awake ---

    /** Starts or extends the keep-awake (see [TestPowerLogic.keepAwakeUntil]); `minutes=0` with [explicit] ends it. */
    fun keepAwake(main: MainActivity, minutes: Int?, explicit: Boolean) {
        val now = SystemClock.uptimeMillis()
        keepAwakeUntil = TestPowerLogic.keepAwakeUntil(now, keepAwakeUntil, minutes, explicit)
        handler.removeCallbacks(tick)
        val next = TestPowerLogic.nextTickMs(now, keepAwakeUntil)
        if (next == null) {
            release(main)
            return
        }
        hold(main)
        handler.postDelayed(tick, next)
    }

    /** Ends the keep-awake now: the app's own backlight timer and the system screen timeout apply again. */
    fun release(main: MainActivity?) {
        keepAwakeUntil = 0L
        handler.removeCallbacks(tick)
        if (main == null) return
        try {
            // The Upload server and USB storage keep the screen on themselves
            if (!main.isServerRunning && !main.usbStorageManager.isStorageOverlayActive) {
                main.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (isAwake(main)) main.powerBacklightManager?.resetTimer()
        } catch (_: Throwable) {}
    }

    private fun hold(main: MainActivity) {
        try {
            main.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (isAwake(main)) main.powerBacklightManager?.resetTimer()
        } catch (_: Throwable) {}
    }

    /** The app shows its screen (not its own screen off). */
    private fun isAwake(main: MainActivity) = !main.isScreenSleeping && !main.isFakeScreenOff

    private fun onTick() {
        val now = SystemClock.uptimeMillis()
        val main = TestHooks.activity // the current activity, also after a restart
        val next = TestPowerLogic.nextTickMs(now, keepAwakeUntil)
        if (next == null) {
            release(main)
            return
        }
        if (main != null) hold(main)
        handler.postDelayed(tick, next)
    }

    // --- reply ---

    /** The `power` object of each reply. keepAwakeUntil is uptime ms (0 = off), as `app.uptimeMs`. */
    fun json(main: MainActivity): JSONObject {
        val now = SystemClock.uptimeMillis()
        val out = JSONObject()
        try {
            val s = snapshot(main)
            out.put("screenOn", s.screenOn)
                .put("fakeScreenOff", s.fakeScreenOff)
                .put("screenSleeping", s.screenSleeping)
                .put("windowFocus", s.windowFocus)
        } catch (t: Throwable) {
            out.put("snapshotError", t.toString())
        }
        out.put("keepAwakeUntil", keepAwakeUntil)
        out.put("keepAwakeLeftMs", (keepAwakeUntil - now).coerceAtLeast(0L))
        out.put("keepScreenOnFlag", (main.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0)
        out.put("woke", TestHookLogic.jsonArray(woke))
        out.put("wakeMs", wakeMs)
        return out
    }
}
