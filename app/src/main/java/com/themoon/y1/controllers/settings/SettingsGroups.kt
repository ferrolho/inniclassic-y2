package com.themoon.y1.controllers.settings

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iPod-style groups for the Settings top level (Playback, Display, Appearance...).
 *
 * The rows keep their code in [SettingsController.buildSettingsUI]. After it adds all rows, [SettingsGroupPages]
 * moves them: the top level shows one row for each group, and a group shows its rows in the order below.
 * Rows are found by their English label (the text that `localize()` gets), so the pref keys and the row code
 * do not change. A row that is in no group stays on the top level, after the groups.
 */
object SettingsGroups {

    class Group(val title: String, val rows: List<String>)

    /** Rows that are in a build only with a feature or a branch are in the list too; a missing row is skipped. */
    val GROUPS: List<Group> = listOf(
        Group("Playback", listOf(
            "Shuffle Mode", "Repeat Mode", "Equalizer & Audio Effects", "Sound Check", "Volume Limit", "Playback Speed"
        )),
        Group("Display", listOf(
            "Display Brightness", "Backlight Timer", "Screen Timeout", "Battery Percentage"
        )),
        Group("Appearance", listOf(
            "Theme", "Background", "iPod Classic Layout", "Toggle Style", "List Edges", "Album Cover Tilt",
            "Fill Video Screen"
        )),
        Group("Menus", listOf(
            "Main Menu Items", "Music Menu Items", "Group Albums"
        )),
        Group("Controls", listOf(
            "Button Sound", "Vibration", "Wheel Loop Scroll", "Screen-Off Control", "Double-Click to Favorite",
            "Keyboard Layout"
        )),
        Group("Connections", listOf(
            "Bluetooth", "Wi-Fi", "Cell Radio", "Web Server", "Ask on USB Connect"
        )),
        Group("Library", listOf(
            "Auto Fetch Album Art", "Clear Album Art & Info", "Jellyfin Sync", "Sync Scrobbles to Last.fm",
            "Last.fm Account"
        )),
        Group("General", listOf(
            "About", "Date & Time", "Language", "System Update", "Legal", "Export Settings", "Import Settings",
            "Reset All Settings", "Power Off"
        )),
        Group("Developer", listOf(
            "Wireless ADB"
        )),
    )

    /** Shorter iPod labels, shown in place of the row's own label (the row code and its pref key do not change). */
    val RENAMES: Map<String, String> = mapOf(
        "Shuffle Mode" to "Shuffle",
        "Repeat Mode" to "Repeat",
        "Equalizer & Audio Effects" to "EQ",
        "Display Brightness" to "Brightness",
        "Backlight Timer" to "Backlight",
        "Reset All Settings" to "Reset Settings",
    )

    const val TOP = -1

    /** Index of the group that has the row [label] (English), or [TOP]. */
    fun groupOf(label: String): Int = GROUPS.indexOfFirst { label in it.rows }

    sealed class Entry {
        /** The row that opens group [group] (an index in [GROUPS]). */
        data class GroupRow(val group: Int) : Entry()
        /** A settings row, by its English label (or its text, for a row that is in no group). */
        data class Row(val label: String) : Entry()
    }

    /**
     * What one page shows. [present] = the labels of the rows that the build made, [open] = the open group or [TOP].
     * Returns the group that the page shows ([TOP] if [open] has no row in this build) and its entries.
     */
    fun page(present: List<String>, open: Int): Pair<Int, List<Entry>> {
        if (open in GROUPS.indices) {
            val rows = GROUPS[open].rows.filter { it in present }
            if (rows.isNotEmpty()) return open to rows.map { Entry.Row(it) }
        }
        val groups = GROUPS.indices.filter { g -> GROUPS[g].rows.any { it in present } }.map { Entry.GroupRow(it) }
        val loose = present.filter { groupOf(it) == TOP }.map { Entry.Row(it) }
        return TOP to groups + loose
    }
}

/** The open group of the Settings screen, and the step that moves the finished rows into the groups. */
class SettingsGroupPages {

    var openGroup: Int = SettingsGroups.TOP
        private set
    private var groupRowIndex = 0

    val isOpen: Boolean get() = openGroup != SettingsGroups.TOP

    /** Back to the top level. Returns the row of the group that was open (for the focus), or null. */
    fun close(): Int? {
        if (!isOpen) return null
        openGroup = SettingsGroups.TOP
        return groupRowIndex
    }

    /**
     * Moves the rows in [container] (made by buildSettingsUI) into the page of the open group, or puts the group rows
     * on the top level. [onOpenGroup] runs when a group row is pushed (after [openGroup] changes).
     */
    fun arrange(
        container: LinearLayout,
        createSettingRow: (title: String, value: String) -> LinearLayout,
        clickFeedback: () -> Unit,
        localize: (String) -> String,
        title: TextView?,
        onOpenGroup: () -> Unit
    ) {
        val english = HashMap<String, String>()
        for (g in SettingsGroups.GROUPS) for (r in g.rows) english[localize(r)] = r
        val views = LinkedHashMap<String, View>()
        for (i in 0 until container.childCount) {
            val row = container.getChildAt(i)
            val text = ((row as? ViewGroup)?.getChildAt(0) as? TextView)?.text?.toString() ?: "#$i"
            val label = english[text] ?: text
            if (label !in views) views[label] = row else views["$label#$i"] = row
        }

        val (shown, entries) = SettingsGroups.page(views.keys.toList(), openGroup)
        openGroup = shown
        container.removeAllViews()
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is SettingsGroups.Entry.GroupRow -> {
                    val row = createSettingRow(localize(SettingsGroups.GROUPS[entry.group].title), "〉 ")
                    row.setOnClickListener {
                        clickFeedback()
                        openGroup = entry.group
                        groupRowIndex = index
                        onOpenGroup()
                    }
                    container.addView(row)
                }
                is SettingsGroups.Entry.Row -> {
                    val row = views[entry.label] ?: return@forEachIndexed
                    SettingsGroups.RENAMES[entry.label]?.let { ((row as? ViewGroup)?.getChildAt(0) as? TextView)?.text = localize(it) }
                    container.addView(row)
                }
            }
        }
        // A second row with the same label (none today) has the key "label#i": it is in no group, so it stays on the top level.

        // After changeScreen() and the screen-change listener, which write "Settings"
        val titleText = localize(if (shown == SettingsGroups.TOP) "Settings" else SettingsGroups.GROUPS[shown].title)
        title?.post { if (container.isShown) title.text = titleText }
    }
}
