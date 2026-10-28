package com.themoon.y1.migrations

import com.themoon.y1.migrations.MoveMisplacedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoveBackgroundPathTest {

    @Test
    fun removesTheBackgroundPathOnly() {
        val prefs = FakePrefs(mutableMapOf("bg_path" to "THEME_DEFAULT", "shuffle" to true))

        assertTrue(Migration100RemoveBackgroundPath.removeKey(prefs))
        assertEquals(mapOf("shuffle" to true), prefs.all)
    }

    @Test
    fun noValueChangesNothing() {
        val prefs = FakePrefs(mutableMapOf("shuffle" to true))

        assertFalse(Migration100RemoveBackgroundPath.removeKey(prefs))
        assertEquals(mapOf("shuffle" to true), prefs.all)
    }

    @Test
    fun runsAfterTheMoveOfMigration4() {
        val manager = MigrationManager(null, FakePrefs())
        manager.registerMigration(Migration100RemoveBackgroundPath())
        manager.registerMigration(Migration004MoveMisplacedPrefs())
        assertEquals(listOf(4, 100), manager.getRegisteredMigrations().map { it.version })
    }

    @Test
    fun migrationRemovesTheValueFromAppPrefs() {
        val app = FakePrefs(mutableMapOf("bg_path" to "/sdcard/bg.png"))
        Migration100RemoveBackgroundPath().migrate(MigrationContext(null, FakePrefs(), app, 5))
        assertFalse(app.contains("bg_path"))
    }
}
