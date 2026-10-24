package com.themoon.y1.managers

import android.app.Activity
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.View
import com.themoon.y1.ThemeManager
import com.themoon.y1.views.TablerIcons
import java.util.TimeZone

/**
 * Settings → Theme → Auto: Classic Light from sunrise to sunset, Classic Dark at night
 * (see [SunTimes]). A theme change recreates the activity, so the theme changes only at app start
 * and when the screen comes on, never while the screen is in use. There is no timer.
 *
 * The resolved theme is saved as the normal selected theme (app_theme_name), so code that reads
 * the theme name works as before.
 */
object AutoThemeManager {

    const val PREF_KEY = "auto_theme_enabled"
    const val DAY_THEME = "Classic Light"
    const val NIGHT_THEME = "Classic Dark"
    const val DEFAULT_THEME = "Classic Light"

    fun isEnabled(prefs: SharedPreferences): Boolean = prefs.getBoolean(PREF_KEY, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY, enabled).commit()
    }

    fun themeNameFor(isDay: Boolean): String = if (isDay) DAY_THEME else NIGHT_THEME

    fun resolvedThemeName(
        nowMs: Long = System.currentTimeMillis(),
        zoneId: String = TimeZone.getDefault().id
    ): String = themeNameFor(SunTimes.isDaytime(zoneId, nowMs))

    /** App start, before the views are made: select the theme for the time now. */
    fun applyAtStart(prefs: SharedPreferences) {
        if (isEnabled(prefs)) selectTheme(prefs, resolvedThemeName())
    }

    /** Screen comes on: if day/night changed since the last change, change the theme. */
    fun onScreenOn(activity: Activity, prefs: SharedPreferences) {
        if (!isEnabled(prefs) || activity.isFinishing) return
        val target = resolvedThemeName()
        if (ThemeManager.getCurrentTheme().name == target) return
        if (selectTheme(prefs, target)) recreateLater(activity)
    }

    /** Value of the Theme row in Settings, e.g. "Auto (Dark)". */
    fun settingsRowValue(prefs: SharedPreferences, localize: (String) -> String): String {
        val current = ThemeManager.getCurrentTheme().name
        if (!isEnabled(prefs)) return current
        val mode = when (current) {
            DAY_THEME -> localize("Light")
            NIGHT_THEME -> localize("Dark")
            else -> localize(current)
        }
        return localize("Auto") + " (" + mode + ")"
    }

    /** The "Auto" row of the theme list. */
    fun createSelectorRow(
        activity: Activity,
        prefs: SharedPreferences,
        createTablerListButton: (Array<String>?, String, Int, Int) -> View,
        clickFeedback: () -> Unit,
        localize: (String) -> String
    ): View {
        val isSelected = isEnabled(prefs)
        val row = createTablerListButton(
            if (isSelected) TablerIcons.CHECK else null,
            localize("Auto"),
            if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getTextColorPrimary(),
            if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getListButtonFocusedTextColor()
        )
        row.setOnClickListener {
            clickFeedback()
            setEnabled(prefs, true)
            selectTheme(prefs, resolvedThemeName())
            activity.recreate()
        }
        return row
    }

    /** Reset All Settings: turn Auto off and go back to the default theme. */
    fun reset(activity: Activity, prefs: SharedPreferences) {
        if (!isEnabled(prefs)) return
        setEnabled(prefs, false)
        if (selectTheme(prefs, DEFAULT_THEME)) recreateLater(activity)
    }

    // Same preferences as a tap on a theme in Settings → Theme. False if the theme is not installed.
    private fun selectTheme(prefs: SharedPreferences, name: String): Boolean {
        val index = ThemeManager.findThemeIndexByName(name)
        if (index < 0) return false
        ThemeManager.setThemeIndex(index)
        val theme = ThemeManager.availableThemes[index]
        val types = theme.menuElements.map { it.type }.toSet()
        try {
            val editor = prefs.edit()
                .putInt("app_theme_index", index)
                .putString("app_theme_name", theme.name)
                .putString("current_theme_name", theme.name)
            for (widget in listOf(
                "widget_clock", "widget_analog_clock", "widget_battery",
                "widget_circular_battery", "widget_album", "widget_focus_image"
            )) {
                editor.putBoolean(widget, widget in types)
            }
            if (hasThemeBackground(theme)) editor.putString("bg_path", "THEME_DEFAULT")
            editor.commit()
        } catch (_: Exception) {}
        return true
    }

    private fun hasThemeBackground(theme: ThemeManager.ThemeData): Boolean {
        if (theme.bgImage.isNotEmpty() && java.io.File(theme.folderPath, theme.bgImage).exists()) return true
        return java.io.File(theme.folderPath, "background.png").exists() ||
            java.io.File(theme.folderPath, "bg.png").exists()
    }

    // Do not recreate inside a broadcast receiver or a click handler; let them finish first
    private fun recreateLater(activity: Activity) {
        Handler(Looper.getMainLooper()).post { if (!activity.isFinishing) activity.recreate() }
    }
}
