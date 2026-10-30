package com.themoon.y1.lists

import android.view.View
import android.widget.ListAdapter
import com.themoon.y1.views.RowGlyphs

/**
 * One list screen as data: a screen gives a [ListSpec], and [ListScreen] makes the rows and does the common work
 * (first row, empty row, wheel step with Wheel Loop Scroll and List Edges, title, click feedback, holds, the row
 * after a theme change). The screen keeps only its content: what the rows are and what they do.
 */

/** How a row looks. Each look is one row factory of [com.themoon.y1.views.ListItemViewFactory]. */
enum class RowLook {
    /** Label; the chevron shows on the focused row (Music lists, Playlists). */
    MENU,

    /** Label and a value on the right (Settings rows; On/Off shows as a switch with Toggle Style = Switch). */
    VALUE,

    /** Tick column, label and status glyphs on the right (Wi-Fi networks, Bluetooth devices). */
    CHOICE,

    /** A view that the screen made itself ([RowSpec.view]); the renderer only places it and sets the actions. */
    CUSTOM,

    /** A section title ("━ Paired Devices ━": small, grey, centred). The wheel skips it. */
    HEADER,

    /** Grey text, on more lines when it is long (a message, a hint, a progress line). The wheel skips it. */
    INFO
}

/**
 * One row. [key] identifies the row for the remembered row ([Focus.LAST]) and for the row that stays selected
 * when the rows change ([ListScreen.update]); by default it is the label.
 */
class RowSpec(
    val label: String,
    val look: RowLook = RowLook.MENU,
    val value: String = "",
    val checked: Boolean = false,
    val glyphs: List<RowGlyphs.Slot> = emptyList(),
    /** Text colour of a [RowLook.MENU] row; 0 = the theme colour. */
    val color: Int = 0,
    val id: Int = View.NO_ID,
    val view: View? = null,
    val key: String = label,
    val onClick: (() -> Unit)? = null,
    val onHold: (() -> Unit)? = null
)

/** The row that a list selects when it opens going down. Back always selects the row that opened the deeper list. */
enum class Focus {
    /** The first row (the iPod rule). */
    FIRST,

    /** The row that the user last opened in this list ([ListSpec.id]), else the first row (Playlists, C-6). */
    LAST,

    /**
     * No row: the list itself has the focus, and the first wheel step selects the first row. Then one centre push
     * cannot do a destructive action by accident (action screens).
     */
    NONE
}

/**
 * A list screen. Short lists give [rows] (views in a LinearLayout); long lists give an [adapter] (a ListView that
 * makes views only for the rows on the screen). With no rows, the list shows one row with [empty].
 */
class ListSpec(
    val title: String?,
    val rows: List<RowSpec> = emptyList(),
    val adapter: ListAdapter? = null,
    val empty: String? = null,
    val focus: Focus = Focus.FIRST,
    /** Identifies the list for [Focus.LAST]. */
    val id: String = title.orEmpty()
) {
    val isLong: Boolean get() = adapter != null && adapter.count > 0
}

/** The pure rules of the renderer. */
object ListModel {

    /**
     * The row to select when the list opens: the remembered row ([lastKey]) for [Focus.LAST], else the first row
     * that can be selected ([selectable]: not a header or an info row). -1 = no row.
     */
    fun firstRow(keys: List<String>, focus: Focus, lastKey: String?, selectable: (Int) -> Boolean = { true }): Int {
        if (focus == Focus.NONE) return -1
        if (focus == Focus.LAST && lastKey != null) {
            val i = keys.indexOf(lastKey)
            if (i >= 0 && selectable(i)) return i
        }
        return keys.indices.firstOrNull(selectable) ?: -1
    }

    /**
     * The row to select after the rows changed: the row with the same key, else the row at the same place
     * (the last row if the list is now shorter), else none (-1).
     */
    fun keptRow(keys: List<String>, oldKey: String?, oldIndex: Int, selectable: (Int) -> Boolean = { true }): Int {
        if (keys.isEmpty() || oldIndex < 0) return -1
        if (oldKey != null) {
            val i = keys.indexOf(oldKey)
            if (i >= 0 && selectable(i)) return i
        }
        // The row at the same place, else the next row that can be selected, else the one before it
        val place = oldIndex.coerceAtMost(keys.size - 1)
        return (place until keys.size).firstOrNull(selectable) ?: (place - 1 downTo 0).firstOrNull(selectable) ?: -1
    }

    /** The rows that show: [ListSpec.rows], or the one [ListSpec.empty] row. */
    fun shownRows(spec: ListSpec): List<RowSpec> =
        if (spec.rows.isEmpty() && spec.empty != null && !spec.isLong) listOf(RowSpec(spec.empty, key = EMPTY_KEY)) else spec.rows

    const val EMPTY_KEY = "\u0000empty"
}
