package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class StatusIconsTest {
    private val eps = 1e-3f

    @Test
    fun widthsAtTheIpodAndTheDefaultHeight() {
        // 15 px = the round icons in the iPod Classic Layout; 16 px = the status bar without it.
        assertEquals(19, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 15))
        assertEquals(15, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 15))
        assertEquals(11, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 15))
        assertEquals(14, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 15, connected = true))
        assertEquals(21, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 16))
        assertEquals(16, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 16))
        assertEquals(11, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16))
        assertEquals(14, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16, connected = true))
    }

    @Test
    fun wifiAtTheIpodHeightHasEvenGapsAndAThickDot() {
        // 15 px with the 1 px edge: the shape is 14 px.
        val g = StatusIcons.wifiGeometry(14f)
        assertEquals(2.5f, g.stroke, eps)
        assertEquals(2.75f, g.gap, eps)
        assertEquals(1.75f, g.dot, eps)
        assertEquals(44f, g.halfAngleDeg, eps)
    }

    @Test
    fun wifiFillsItsHeightWithEvenGapsAndFitsItsWidth() {
        for (h in 10..40) {
            val g = StatusIcons.wifiGeometry(h - 1f)
            assertEquals(h - 1f, g.height, eps)
            assertEquals(h - 1f - g.dot, g.centreY, eps)
            // Dot to the first arc, first arc to the second arc: the same space.
            val gap1 = g.arcRadius1 - g.stroke / 2f - g.dot
            val gap2 = (g.arcRadius2 - g.stroke / 2f) - (g.arcRadius1 + g.stroke / 2f)
            assertEquals(gap1, gap2, eps)
            assertTrue(gap1 > 0f)
            // The dot is wider than an arc is thick, like the macOS symbol.
            assertTrue("h=$h", 2f * g.dot > g.stroke)
            // The round end of the outer arc is the widest point; with the edge it fits in the bitmap.
            val end = g.arcRadius2 * sin(Math.toRadians(g.halfAngleDeg.toDouble())).toFloat() + g.stroke / 2f
            assertEquals(2f * end, g.width, eps)
            assertTrue(g.width + 1f <= StatusIcons.widthFor(StatusIcons.Icon.WIFI, h))
        }
    }

    @Test
    fun runeIsAsTallAsTheIconWithASharpStemAndOpenHoles() {
        // The status bar uses 15 (iPod layout) and 16 px; below 14 px the holes close.
        for (h in 14..40) {
            for (connected in listOf(false, true)) {
                val l = StatusIcons.bluetoothLayout(h, connected)
                // Ink from the top row to the bottom row, like the Wi-Fi fan (the shape grows by grow / 2).
                assertEquals(0f, l.y(2f) - l.grow / 2f, eps)
                assertEquals(h.toFloat(), l.y(22f) + l.grow / 2f, eps)
                // Stem x 11..13 of the viewBox: whole pixels, on pixel edges.
                assertEquals(0f, l.stemLeft % 1f, eps)
                assertEquals(0f, l.stem % 1f, eps)
                assertEquals(l.stemLeft, l.x(11f) - l.grow / 2f, eps)
                assertEquals(l.stemLeft + l.stem, l.x(13f) + l.grow / 2f, eps)
                assertTrue(l.grow >= 0f)
                // The triangle holes (1.88 units wide) stay open after the growth.
                assertTrue("h=$h", 1.88f * l.scale - l.grow > 0.4f)
                // The ink is in the bitmap.
                assertTrue(l.stemLeft - l.inkLeft >= -eps && l.stemLeft + l.inkRight <= l.width + eps)
                if (!connected) {
                    assertTrue("h=$h", l.stemLeft - l.inkLeft < 1f && l.width - (l.stemLeft + l.inkRight) < 1f)
                    assertEquals(0, l.dotX.size)
                }
            }
        }
    }

    @Test
    fun connectedDotsAreAtTheMaterialPlacesOnPixelCentres() {
        for (h in 14..40) {
            val l = StatusIcons.bluetoothLayout(h, connected = true)
            assertEquals(2, l.dotX.size)
            assertEquals(h / 2f, l.dotY, eps)
            // Dot diameter = 1.5 x the stem (3 px at 15 px), like the Material diamonds.
            assertEquals(1.5f * l.stem, 2f * l.dotRadius, eps)
            for (x in l.dotX) assertEquals(0.5f, x % 1f, eps)
            // Within 1 px of x = 5 and x = 19 of the viewBox.
            assertTrue(abs(l.dotX[0] - l.x(5f)) <= 1f)
            assertTrue(abs(l.dotX[1] - l.x(19f)) <= 1f)
            assertTrue(l.dotX[0] < l.stemLeft && l.dotX[1] > l.stemLeft + l.stem)
            // In the bitmap, the outer dots touch its edges (within 1 px).
            assertTrue(l.dotX[0] - l.dotRadius >= -eps && l.dotX[0] - l.dotRadius < 1f)
            assertTrue(l.dotX[1] + l.dotRadius <= l.width + eps && l.width - (l.dotX[1] + l.dotRadius) < 1f)
        }
    }

    @Test
    fun bluetoothAtTheIpodHeight() {
        val off = StatusIcons.bluetoothLayout(15, connected = false)
        assertEquals(2f, off.stem, eps)
        assertEquals(5f, off.stemLeft, eps)
        val on = StatusIcons.bluetoothLayout(15, connected = true)
        assertEquals(6f, on.stemLeft, eps)
        assertEquals(1.5f, on.dotRadius, eps)
        assertEquals(1.5f, on.dotX[0], eps)
        assertEquals(12.5f, on.dotX[1], eps)
        assertEquals(7.5f, on.dotY, eps)
    }
}
