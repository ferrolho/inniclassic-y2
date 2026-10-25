package com.themoon.y1.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatusSpacingTest {
    private val eps = 1e-4f
    private val ink = 0xFF1A5DB0.toInt()

    /** [rows] of '#' (ink), '.' (empty) and '+' (faint ink, alpha 0x40). */
    private fun pixels(vararg rows: String): IntArray =
        rows.joinToString("").map {
            when (it) {
                '#' -> ink
                '+' -> 0x401A5DB0
                else -> 0
            }
        }.toIntArray()

    @Test
    fun flatItemsKeepTheGapOf8() {
        val bar = pixels("##", "##", "##")
        val b = StatusSpacing.bearings(bar, 2, 0, 3)
        assertArrayEquals(floatArrayOf(0f, 0f), b, eps)
        assertEquals(8, StatusSpacing.gap(b[1], b[0]))
    }

    @Test
    fun emptySidesCountUpToTheCapPerRow() {
        // Rows: ink at the left only; ink in the middle; no ink (counts as the cap, 3).
        val icon = pixels(
            "#.......",
            "...##...",
            "........"
        )
        val b = StatusSpacing.bearings(icon, 8, 0, 3)
        assertEquals((0f + 3f + 3f) / 3f, b[0], eps)
        assertEquals((3f + 3f + 3f) / 3f, b[1], eps)
    }

    @Test
    fun onlyTheRowsOfTheBoxCountAndFaintPixelsAreNotInk() {
        val icon = pixels(
            "....",
            "+..#",
            "#..#",
            "...."
        )
        val b = StatusSpacing.bearings(icon, 4, 1, 3)
        // Row 1: the faint pixel is not ink (left 3); row 2: left 0.
        assertEquals(1.5f, b[0], eps)
        assertEquals(0f, b[1], eps)
    }

    @Test
    fun gapIsTheTargetMinusTheFacingBearingsWithAMinimum() {
        assertEquals(6, StatusSpacing.gap(1.2f, 0.9f))
        assertEquals(4, StatusSpacing.gap(2.4f, 1.7f))
        assertEquals(StatusSpacing.MIN_GAP, StatusSpacing.gap(3f, 3f))
    }

    @Test
    fun inkColumnsOfTheDrawnTextAlsoCountFaintPixels() {
        val text = pixels(
            "..#....",
            "..##..+",
            "......."
        )
        assertArrayEquals(intArrayOf(2, 6), StatusSpacing.inkColumns(text, 7))
        assertNull(StatusSpacing.inkColumns(pixels("...", "..."), 3))
    }
}
