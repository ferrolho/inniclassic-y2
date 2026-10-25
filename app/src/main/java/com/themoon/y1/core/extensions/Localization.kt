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
 * Invisible marks at the end of the [onOff] text. They tell the setting row that the value is an on/off value,
 * and which, so that the row can show a switch (Settings → Toggle Style). The row removes the mark before it
 * shows the text, so its text (for tests and accessibility) is still "On" or "Off".
 */
const val ON_MARK = '\u200B' // zero-width space
const val OFF_MARK = '\u200C' // zero-width non-joiner

/**
 * Translated value of an on/off setting. Use it for every on/off row, so that all rows show the same text.
 * The text ends with [ON_MARK] or [OFF_MARK]: give it to a setting row, not to a plain TextView.
 */
fun onOff(enabled: Boolean, localize: (String) -> String): String =
    localize(onOffKey(enabled)) + (if (enabled) ON_MARK else OFF_MARK)

/** The state that the [onOff] mark at the end of [text] gives, or null if [text] is not from [onOff]. */
fun onOffState(text: CharSequence?): Boolean? = when (text?.lastOrNull()) {
    ON_MARK -> true
    OFF_MARK -> false
    else -> null
}

/** [text] without the [onOff] mark at its end. */
fun withoutOnOffMark(text: CharSequence?): CharSequence? =
    if (onOffState(text) != null) text!!.subSequence(0, text.length - 1) else text
