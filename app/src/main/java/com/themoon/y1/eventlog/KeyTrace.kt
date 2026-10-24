package com.themoon.y1.eventlog

import android.view.KeyEvent
import com.themoon.y1.controllers.ScreenController

/**
 * Finds which part of the key path used a key event, for the event log. MainActivity calls it around
 * each InputRouter call (main thread). When the log is off, each function gives back its argument.
 *
 * `by` values:
 * - `<Controller>.<method>`: the active ScreenController returned true (dispatchKeyEvent, onWheel, onKeyDown, …).
 * - `router`: InputRouter.dispatchKeyEvent kept the key before the views (volume, USB storage screen,
 *   screen off, wake, Back debounce).
 * - `view`: a view of the window (for example the focused list) used the key; Activity.onKeyDown/Up did not run.
 * - `router.down|up|long`: InputRouter.onKeyDown/onKeyUp/onKeyLongPress used it (Back, media keys, focus, click).
 * - `activity.down|up|long`: Activity's own key handling used it.
 * - `none`: nothing used it.
 */
object KeyTrace {
    private var consumer: String? = null
    private var stage = "dispatch"
    private var passedRouter = false

    /** Wraps the controller that MainActivity gives to InputRouter. [stage]: dispatch, down, up or long. */
    fun controller(c: ScreenController?, stage: String): ScreenController? {
        if (!EventLog.enabled) return c
        if (stage == "dispatch") {
            consumer = null
            passedRouter = false
        }
        this.stage = stage
        return if (c == null) null else Traced(c)
    }

    /** InputRouter.dispatchKeyEvent did not keep the key: the window gets it now. Always true. */
    fun passed(): Boolean {
        if (EventLog.enabled) {
            passedRouter = true
            stage = "window"
        }
        return true
    }

    /** Result of Activity.onKeyDown/Up/LongPress (super). */
    fun activity(result: Boolean): Boolean {
        if (result && EventLog.enabled && consumer == null) consumer = "activity.$stage"
        return result
    }

    /** End of MainActivity.dispatchKeyEvent: logs the event. */
    fun done(event: KeyEvent, result: Boolean): Boolean {
        if (!EventLog.enabled) return result
        val by = consumer ?: when {
            !result -> "none"
            !passedRouter -> "router"
            stage == "window" -> "view"
            else -> "router.$stage"
        }
        EventLog.key(event, by)
        consumer = null
        return result
    }

    private class Traced(private val inner: ScreenController) : ScreenController by inner {
        private fun mark(result: Boolean, method: String): Boolean {
            if (result && consumer == null) consumer = inner.javaClass.simpleName + "." + method
            return result
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean = mark(inner.dispatchKeyEvent(event), "dispatchKeyEvent")
        override fun onWheel(delta: Int, event: KeyEvent): Boolean = mark(inner.onWheel(delta, event), "onWheel")
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = mark(inner.onKeyDown(keyCode, event), "onKeyDown")
        override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = mark(inner.onKeyUp(keyCode, event), "onKeyUp")
        override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean = mark(inner.onKeyLongPress(keyCode, event), "onKeyLongPress")
        override fun onBackPressed(): Boolean = mark(inner.onBackPressed(), "onBackPressed")
    }
}
