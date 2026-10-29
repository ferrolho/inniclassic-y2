package com.themoon.y1.eventlog

import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer

/**
 * Finds lag after a screen change (stalls shorter than the [StallWatch] limit). For [WINDOW_MS] after each
 * change, a Choreographer frame callback runs at each vsync, and the longest time between two callbacks is the
 * longest time the screen did not change. When it is [SLOW_MS] or more, one `frames` line is logged. A
 * background thread takes the main-thread stack when no frame came for [SAMPLE_AFTER_MS], so the line
 * also shows what the main thread did in the longest gap. The first gap includes the work of the new screen.
 */
internal object FrameWatch {
    private const val WINDOW_MS = 1000L
    private const val SLOW_MS = 100L
    private const val SAMPLE_AFTER_MS = 80L
    private const val SAMPLE_EVERY_MS = 20L

    private class Sample(val from: Long, val stack: Array<StackTraceElement>)

    // Written on the main thread; the sampler reads [lastFrame] and [active]
    @Volatile private var lastFrame = 0L
    @Volatile private var active = false
    @Volatile private var generation = 0 // a new window stops the sampler of the old one
    private var start = 0L
    private var screen = ""
    private var frames = 0
    private var maxGap = 0L
    private var maxGapFrom = 0L
    private var posted = false
    private val samples = ArrayList<Sample>() // guarded by itself

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            val gap = now - lastFrame
            if (gap > maxGap) {
                maxGap = gap
                maxGapFrom = lastFrame
            }
            lastFrame = now
            frames++
            if (now - start < WINDOW_MS) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                posted = false
                active = false
                finish()
            }
        }
    }

    /** On the main thread, at each screen change. A change in the window starts a new window. */
    fun screenChanged(to: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        if (active) finish()
        start = SystemClock.uptimeMillis()
        lastFrame = start
        screen = to
        frames = 0
        maxGap = 0L
        maxGapFrom = start
        synchronized(samples) { samples.clear() }
        active = true
        val gen = ++generation
        if (!posted) {
            Choreographer.getInstance().postFrameCallback(callback)
            posted = true
        }
        Thread({ sample(gen) }, "event-log-frames").apply {
            isDaemon = true
            start()
        }
    }

    private fun sample(gen: Int) {
        val mainThread = Looper.getMainLooper().thread
        var sampledFrom = -1L
        try {
            while (active && generation == gen) {
                Thread.sleep(SAMPLE_EVERY_MS)
                val from = lastFrame
                if (from != sampledFrom && SystemClock.uptimeMillis() - from >= SAMPLE_AFTER_MS) {
                    val stack = mainThread.stackTrace
                    synchronized(samples) { if (samples.size < 5) samples.add(Sample(from, stack)) }
                    sampledFrom = from
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun finish() {
        active = false
        if (maxGap < SLOW_MS) return
        val stack = synchronized(samples) { samples.firstOrNull { it.from == maxGapFrom }?.stack }
        val rt = Runtime.getRuntime()
        EventLog.frames(screen, maxGap, maxGapFrom - start, frames, (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024, stack)
    }
}
