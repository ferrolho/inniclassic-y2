package com.themoon.y1.testhooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.themoon.y1.BuildConfig
import com.themoon.y1.MainActivity
import java.io.File
import java.util.Locale

/**
 * Developer test interface of our own build: machine-readable app state and direct navigation over adb,
 * for fast self-tests on the device. Only in builds made with `-PtestHooks=true` (BuildConfig.TEST_HOOKS);
 * otherwise nothing is registered.
 *
 *     adb shell am broadcast -a com.themoon.y1.TEST --es cmd state
 *
 * prints `Broadcast completed: result=1, data="<one-line JSON>"` (result 1 = ok, 0 = error). The full JSON is
 * also written to `getExternalFilesDir(null)/test-state.json`. Only senders with android.permission.DUMP
 * (adb shell, system) can use it. Commands: see [TestCommands].
 */
object TestHooks {
    const val ACTION = "com.themoon.y1.TEST"
    const val STATE_FILE = "test-state.json"
    private const val SENDER_PERMISSION = "android.permission.DUMP"

    private var receiver: TestHookReceiver? = null

    /** Activity starts since the process started, and the time of the last one: a restart changes both. */
    var activityStarts = 0
        private set
    var activityCreatedUptimeMs = -1L
        private set

    fun register(activity: MainActivity) {
        if (!BuildConfig.TEST_HOOKS) return
        // register() runs in MainActivity.onCreate
        activityStarts++
        activityCreatedUptimeMs = SystemClock.uptimeMillis()
        receiver?.let { unregister(it.main) }
        val r = TestHookReceiver(activity)
        activity.registerReceiver(r, IntentFilter(ACTION), SENDER_PERMISSION, null)
        receiver = r
    }

    fun unregister(activity: MainActivity) {
        val r = receiver ?: return
        if (r.main !== activity) return
        receiver = null
        try {
            activity.unregisterReceiver(r)
        } catch (_: Exception) {}
    }
}

/** Runs on the main thread (runtime-registered receiver). Finishes the ordered broadcast after the UI settles. */
internal class TestHookReceiver(val main: MainActivity) : BroadcastReceiver() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val cmd = intent.getStringExtra("cmd")?.trim()?.lowercase(Locale.US).let { if (it.isNullOrEmpty()) "state" else it }
        val settleMs = intent.getIntExtra("wait", if (cmd == "state" || cmd == "log") 0 else DEFAULT_SETTLE_MS).toLong().coerceIn(0L, 10000L)
        val options = TestState.Options(
            items = intent.getBooleanExtra("items", false),
            allPrefs = intent.getBooleanExtra("prefs", false)
        )
        var finished = false
        com.themoon.y1.eventlog.EventLog.testCommand(cmd, intent)
        val commands = TestCommands(main, handler)
        val done: (String?) -> Unit = { error ->
            if (!finished) {
                finished = true
                handler.postDelayed({ finish(pending, cmd, error, options, commands.reply) }, settleMs)
            }
        }
        try {
            commands.run(cmd, intent, done)
        } catch (t: Throwable) {
            done("$cmd failed: $t")
        }
    }

    /** [reply]: the result of a command that gives its own data (log) in place of the app state. */
    private fun finish(pending: PendingResult, cmd: String, error: String?, options: TestState.Options, reply: org.json.JSONObject?) {
        var err = error
        val state = if (reply != null) null else try {
            TestState(main, options).build()
        } catch (t: Throwable) {
            if (err == null) err = "state failed: $t"
            null
        }
        val ok = err == null
        val result = TestHookLogic.result(ok, err, cmd, state)
        if (reply != null) {
            result.remove("state")
            for (k in reply.keys()) result.put(k, reply.get(k))
        }
        var file: File? = null
        try {
            val dir = main.getExternalFilesDir(null)
            if (dir != null) {
                file = File(dir, TestHooks.STATE_FILE)
                result.put("file", file.absolutePath)
                file.writeText(result.toString())
            }
        } catch (t: Throwable) {
            file = null
            result.remove("file")
            result.put("fileError", t.toString())
        }
        val json = result.toString()
        try {
            pending.resultCode = if (ok) 1 else 0
            pending.resultData = TestHookLogic.broadcastData(json, ok, err, cmd, file?.absolutePath)
        } finally {
            pending.finish()
        }
    }

    companion object {
        /** Wait after a command before the state is read, so that posted UI work (list focus, layout) is done. */
        const val DEFAULT_SETTLE_MS = 400
    }
}
