package com.themoon.y1.core.extensions

import android.content.Context
import com.themoon.y1.managers.LanguageManager

/**
 * Global localization helper backed by LanguageManager.
 */
fun t(key: String, context: Context? = null): String {
    val manager = LanguageManager.getInstance(context)
    return manager.t(key)
}

/**
 * Language key for the value of an on/off setting: "On" or "Off", as on the iPod Classic.
 * All languages in assets/languages must have both keys.
 */
fun onOffKey(enabled: Boolean): String = if (enabled) "On" else "Off"

/**
 * Translated value of an on/off setting. Use it for every on/off row, so that all rows show the same text.
 */
fun onOff(enabled: Boolean, localize: (String) -> String): String = localize(onOffKey(enabled))
