package com.themoon.y1.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListModelTest {
    private val keys = listOf("Recently Played", "Top 25 Most Played", "Chinese Songs", "Playlist 1")

    @Test
    fun opensAtTheFirstRow() {
        assertEquals(0, ListModel.firstRow(keys, Focus.FIRST, "Chinese Songs"))
        assertEquals(0, ListModel.firstRow(keys, Focus.LAST, null))
    }

    @Test
    fun opensAtTheRememberedRowWhenTheListAsksForIt() {
        assertEquals(2, ListModel.firstRow(keys, Focus.LAST, "Chinese Songs"))
        // The remembered row is gone (playlist deleted): the first row
        assertEquals(0, ListModel.firstRow(keys, Focus.LAST, "Deleted"))
    }

    @Test
    fun anEmptyListSelectsNoRow() {
        assertEquals(-1, ListModel.firstRow(emptyList(), Focus.FIRST, null))
        assertEquals(-1, ListModel.keptRow(emptyList(), "a", 0))
    }

    @Test
    fun keepsTheSelectedRowWhenTheRowsChange() {
        // A new network came in above the selected one: the same network stays selected
        assertEquals(3, ListModel.keptRow(listOf("Power", "Scan", "New", "Home"), "Home", 2))
        // The selected network is gone: the row at the same place
        assertEquals(2, ListModel.keptRow(listOf("Power", "Scan", "Other", "Last"), "Home", 2))
        // The list is shorter now: the last row
        assertEquals(1, ListModel.keptRow(listOf("Power", "Scan"), "Home", 5))
        // No row was selected: none
        assertEquals(-1, ListModel.keptRow(listOf("Power", "Scan"), null, -1))
    }

    @Test
    fun anEmptyListShowsOneEmptyRow() {
        val rows = ListModel.shownRows(ListSpec("Songs", empty = "No Songs"))
        assertEquals(1, rows.size)
        assertEquals("No Songs", rows[0].label)
        assertEquals(ListModel.EMPTY_KEY, rows[0].key)
        assertTrue(rows[0].onClick == null)
    }

    @Test
    fun aListWithRowsShowsItsRows() {
        val spec = ListSpec("Playlists", rows = listOf(RowSpec("A"), RowSpec("B")), empty = "None")
        assertEquals(listOf("A", "B"), ListModel.shownRows(spec).map { it.key })
        assertFalse(spec.isLong)
        assertTrue(ListModel.shownRows(ListSpec("Wi-Fi")).isEmpty())
    }

    @Test
    fun theListIdIsTheTitleByDefault() {
        assertEquals("Playlists", ListSpec("Playlists").id)
        assertEquals("", ListSpec(null).id)
    }
}
