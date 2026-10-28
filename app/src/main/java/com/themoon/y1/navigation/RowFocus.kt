package com.themoon.y1.navigation

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * One wheel step in a list of rows, the same for all row lists: the next selectable row (visible and focusable,
 * so headers and info rows are skipped) in the wheel direction. At an end of the list the step goes round to the
 * other end when Settings → Controls → Wheel Loop Scroll is On, else it stays. With no selected row, the first
 * selectable row is selected.
 */
object RowFocus {

    /** Settings → Controls → Wheel Loop Scroll (default On). */
    fun isLoopOn(context: Context): Boolean =
        context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getBoolean("loop_scroll_on", true)

    fun isSelectable(row: View?): Boolean = row != null && row.visibility == View.VISIBLE && row.isFocusable

    /** The row after one step from [current] (-1 = no row) in the direction of [delta], or null to stay. */
    fun next(count: Int, current: Int, delta: Int, loop: Boolean, selectable: (Int) -> Boolean): Int? {
        if (current !in 0 until count) return (0 until count).firstOrNull(selectable)
        val direction = if (delta < 0) -1 else 1
        for (n in 1 until count) {
            var i = current + direction * n
            if (i !in 0 until count) {
                if (!loop) return null
                i = i.mod(count)
            }
            if (selectable(i)) return i
        }
        return null
    }

    /** Moves the focus one step in the rows of [list]. Returns the index of the new row, or null if it did not move. */
    fun step(list: ViewGroup, delta: Int, loop: Boolean = isLoopOn(list.context)): Int? {
        val current = (0 until list.childCount).firstOrNull { list.getChildAt(it)?.hasFocus() == true } ?: -1
        val i = next(list.childCount, current, delta, loop) { isSelectable(list.getChildAt(it)) } ?: return null
        list.getChildAt(i).requestFocus()
        return i
    }
}
