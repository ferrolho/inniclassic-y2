package com.themoon.y1.migrations

import android.content.Context
import android.content.SharedPreferences

/**
 * Migration 100: the custom background (Settings → Background) is removed, so "bg_path" has no use.
 * Removes it from AppPrefs and from the old "y1_settings" file. Our build only: the high version number
 * cannot be the number of an upstream migration.
 */
class Migration100RemoveBackgroundPath : Migration {

    override val version: Int
        get() = VERSION

    override val id: String
        get() = ID

    override val description: String
        get() = "Remove the unused bg_path preference"

    @Throws(Exception::class)
    override fun migrate(context: MigrationContext) {
        context.settingsPrefs?.let { removeKey(it) }
        context.context?.let { removeKey(it.getSharedPreferences(Migration004MoveMisplacedPrefs.LEGACY_FILE, Context.MODE_PRIVATE)) }
    }

    companion object {
        const val VERSION: Int = 100
        const val ID: String = "100_remove_background_path"
        const val KEY = "bg_path"

        /** Removes [KEY] from [prefs] if it has a value. True if a value was removed. */
        fun removeKey(prefs: SharedPreferences): Boolean {
            if (!prefs.contains(KEY)) return false
            check(prefs.edit().remove(KEY).commit()) { "Cannot remove $KEY" }
            return true
        }
    }
}
