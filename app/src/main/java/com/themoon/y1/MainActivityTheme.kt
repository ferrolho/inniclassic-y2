package com.themoon.y1

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.views.QwertyKeyboardView

/**
 * Theme change without an activity restart. All theme colours and fonts come from [ThemeManager] at run
 * time, so the change is: select the theme, give the views that live for the whole app (status bar,
 * screen backgrounds, Now Playing, list divider) the new values, and make the visible screen again
 * ([com.themoon.y1.controllers.ScreenController.onThemeChanged]). Hidden screens make their rows again
 * in onEnter. Playback, the queue, the back stack and the library are not touched.
 */
object LiveTheme {
    /** Main-thread time of the last apply in ms, -1 before the first one. */
    @Volatile
    var lastApplyMs: Long = -1L
        internal set

    /** Number of applies since the app started. */
    @Volatile
    var applyCount: Int = 0
        internal set
}

/**
 * Makes [name] the current theme (and saves it) and applies it after the current event, so that a
 * click handler does not remove its own row. False if the theme is not installed.
 */
fun MainActivity.changeTheme(name: String): Boolean {
    if (ThemeManager.findThemeIndexByName(name) < 0) return false
    ThemeManager.selectTheme(prefs, name)
    window.decorView.post {
        if (!isFinishing) applyThemeLive()
    }
    return true
}

/** Applies the current theme to all views now (main thread). */
fun MainActivity.applyThemeLive() {
    val t0 = SystemClock.uptimeMillis()
    step("divider") {
        // The song list (MainActivityControllers) has a gap between rows, except in the Classic themes
        val gap = if (ThemeManager.isClassicTheme()) 0 else (4 * resources.displayMetrics.density).toInt()
        forEachView(layoutBrowserMode) { if (it is ListView && it.dividerHeight != gap) it.dividerHeight = gap }
    }
    step("keyboard font") { setKeyboardFont(layoutWifiKeyboard) }
    step("battery") { batteryIconView?.invalidate() }
    // Before applyTheme(), as at a screen change: Now Playing colours, then the layouts that applyTheme() runs
    step("now playing") { playerController.applyThemeColors() }
    step("chrome") { applyTheme() }
    step("screen") {
        // The same row at the same height, as after Back (ListRows): found by its text, else by its index
        val controller = NavigationManager.getInstance().activeController
        val row = controller?.selectedRow()
        controller?.onThemeChanged()
        if (row != null) controller.selectRow(row)
    }
    LiveTheme.lastApplyMs = SystemClock.uptimeMillis() - t0
    LiveTheme.applyCount++
}

// One failed part must not stop the others; the screens make their views again when the user opens them.
private inline fun step(name: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        Log.e("InniClassic", "Live theme change: $name failed", e)
    }
}

/** The Wi-Fi keyboard keys take the theme font only when they are made. */
private fun setKeyboardFont(root: View) {
    when (root) {
        is QwertyKeyboardView -> forEachView(root) { if (it is TextView) it.typeface = ThemeManager.getCustomFontBold() }
        is ViewGroup -> for (i in 0 until root.childCount) setKeyboardFont(root.getChildAt(i))
    }
}

private fun forEachView(root: View, action: (View) -> Unit) {
    action(root)
    if (root is ViewGroup) for (i in 0 until root.childCount) forEachView(root.getChildAt(i), action)
}
