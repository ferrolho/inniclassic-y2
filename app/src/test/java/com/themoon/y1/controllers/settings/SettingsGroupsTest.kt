package com.themoon.y1.controllers.settings

import com.themoon.y1.controllers.settings.SettingsGroups.Entry
import com.themoon.y1.controllers.settings.SettingsGroups.GROUPS
import com.themoon.y1.controllers.settings.SettingsGroups.TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsGroupsTest {

    private val dir = "src/main/java/com/themoon/y1/controllers/settings"

    private fun source(name: String): String? =
        listOf(File("$dir/$name"), File("app/$dir/$name")).firstOrNull { it.exists() }?.readText()

    /** The labels of the rows that buildSettingsUI makes (and the backup rows, if this build has them). */
    private fun rowLabels(): List<String> {
        val controller = source("SettingsController.kt") ?: error("SettingsController.kt not found")
        val start = controller.indexOf("fun buildSettingsUI()")
        val end = controller.indexOf("\n    fun ", start + 1).let { a ->
            val b = controller.indexOf("\n    private fun ", start + 1)
            listOf(a, b).filter { it > 0 }.minOrNull() ?: controller.length
        }
        val body = controller.substring(start, end) + (source("SettingsBackupUi.kt") ?: "")
        val re = Regex("""createSettingRow\(\s*localize\("([^"]+)"\)""")
        return re.findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun everyRowIsInExactlyOneGroup() {
        val labels = rowLabels()
        assertTrue("found only ${labels.size} rows", labels.size >= 30)
        for (label in labels) {
            val groups = GROUPS.filter { label in it.rows }.map { it.title }
            assertEquals("'$label' must be in exactly one group, is in $groups", 1, groups.size)
        }
    }

    @Test
    fun noRowIsTwiceInTheModel() {
        val all = GROUPS.flatMap { it.rows }
        assertEquals(all.size, all.toSet().size)
        assertEquals(GROUPS.size, GROUPS.map { it.title }.toSet().size)
    }

    @Test
    fun topLevelHasSixToNineShortGroups() {
        assertTrue(GROUPS.size in 6..9)
        assertEquals(
            listOf("Playback", "Display", "Appearance", "Menus", "Controls", "Connections", "Library", "General", "Developer"),
            GROUPS.map { it.title }
        )
        for (g in GROUPS) {
            assertTrue("${g.title}: label too long", g.title.length <= 12)
            assertTrue("${g.title}: ${g.rows.size} rows", g.rows.size <= 10)
        }
        for ((old, new) in SettingsGroups.RENAMES) {
            assertTrue("rename of a row that is in no group: $old", SettingsGroups.groupOf(old) != TOP)
            assertTrue("$new is not shorter than $old", new.length < old.length)
        }
    }

    @Test
    fun groupOrderIsMostUsedFirst() {
        assertEquals(listOf("Shuffle Mode", "Repeat Mode", "Equalizer & Audio Effects"), GROUPS[0].rows.take(3))
        assertEquals("About", GROUPS.first { it.title == "General" }.rows.first())
        assertEquals("Power Off", GROUPS.first { it.title == "General" }.rows.last())
        assertEquals(listOf("Wireless ADB"), GROUPS.last().rows)
    }

    @Test
    fun topLevelShowsOnlyGroupsWithRowsThenLooseRows() {
        val (shown, entries) = SettingsGroups.page(listOf("Power Off", "New Row", "Shuffle Mode", "Theme"), TOP)
        assertEquals(TOP, shown)
        val playback = GROUPS.indexOfFirst { it.title == "Playback" }
        val appearance = GROUPS.indexOfFirst { it.title == "Appearance" }
        val general = GROUPS.indexOfFirst { it.title == "General" }
        assertEquals(
            listOf(Entry.GroupRow(playback), Entry.GroupRow(appearance), Entry.GroupRow(general), Entry.Row("New Row")),
            entries
        )
    }

    @Test
    fun groupPageUsesTheModelOrder() {
        val playback = GROUPS.indexOfFirst { it.title == "Playback" }
        val present = listOf("Volume Limit", "Theme", "Shuffle Mode", "Playback Speed", "Repeat Mode")
        val (shown, entries) = SettingsGroups.page(present, playback)
        assertEquals(playback, shown)
        assertEquals(
            listOf("Shuffle Mode", "Repeat Mode", "Volume Limit", "Playback Speed").map { Entry.Row(it) },
            entries
        )
    }

    @Test
    fun openGroupWithNoRowsInThisBuildShowsTheTopLevel() {
        val developer = GROUPS.indexOfFirst { it.title == "Developer" }
        val (shown, entries) = SettingsGroups.page(listOf("Theme"), developer)
        assertEquals(TOP, shown)
        assertEquals(listOf(Entry.GroupRow(GROUPS.indexOfFirst { it.title == "Appearance" })), entries)
    }

    @Test
    fun everyBuildRowIsReachable() {
        val labels = rowLabels()
        val (_, top) = SettingsGroups.page(labels, TOP)
        val reachable = top.flatMap { e ->
            when (e) {
                is Entry.Row -> listOf(e.label)
                is Entry.GroupRow -> SettingsGroups.page(labels, e.group).second.map { (it as Entry.Row).label }
            }
        }
        assertEquals(labels.toSet(), reachable.toSet())
        assertEquals(labels.size, reachable.size)
    }
}
