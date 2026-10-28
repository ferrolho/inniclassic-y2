package com.themoon.y1.eventlog

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Finds main-thread stalls of more than [THRESHOLD_MS] (ANR-like): a background thread posts an empty task to
 * the main thread every [PING_MS] and checks that it runs. Logs the stall time and the main-thread stack at
 * the threshold. Runs only while the activity is resumed (screen on), so it does not wake the CPU when the
 * screen is off. A Choreographer frame callback is not used: it requests a frame at each vsync.
 *
 * Stalls of 1.5 s and more are always found; stalls of 1–1.5 s sometimes. The time is a lower limit.
 */
internal object StallWatch {
    private const val PING_MS = 500L
    private const val THRESHOLD_MS = 1000L
    private const val ONGOING_MS = 4000L // before the system ANR (5 s): write the log now

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var thread: Thread? = null
    @Volatile private var ackAt = 0L
    private val ack = Runnable { ackAt = SystemClock.uptimeMillis() }

    fun resume() {
        if (thread != null) return
        thread = Thread({ loop() }, "event-log-stall").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    fun pause() {
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val me = Thread.currentThread()
        try {
            while (thread === me) {
                ackAt = 0L
                val sent = SystemClock.uptimeMillis()
                main.post(ack)
                Thread.sleep(PING_MS)
                if (ackAt != 0L) continue
                while (ackAt == 0L && SystemClock.uptimeMillis() - sent < THRESHOLD_MS) Thread.sleep(50)
                if (ackAt != 0L) continue
                val stack = Looper.getMainLooper().thread.stackTrace
                var reported = false
                while (ackAt == 0L) {
                    if (!reported && SystemClock.uptimeMillis() - sent >= ONGOING_MS) {
                        EventLog.stall(SystemClock.uptimeMillis() - sent, stack, ongoing = true)
                        reported = true
                    }
                    Thread.sleep(100)
                }
                EventLog.stall(ackAt - sent, stack, ongoing = false)
            }
        } catch (_: InterruptedException) {
        } catch (_: Throwable) {
        }
    }
}
