package com.themoon.y1.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryLayoutTest {

    // Nimbus Sans Bold (units per em 1000), ink width / flat digit height (709): "0" 488, "1" 310, "2" 485, ...
    private val nimbus = floatArrayOf(
        488f, 310f, 485f, 487f, 498f, 490f, 487f, 499f, 503f, 488f
    ).map { it / 709f }.toFloatArray()

    // The status bar battery: 30 x 16, 2 px outline (iPod Classic Layout) or 3 px (the original bar).
    private val thin = BatteryLayout.body(30, 16, 2)
    private val wide = BatteryLayout.body(30, 16, 3)

    @Test
    fun bodyIsOnWholePixelsAndCentred() {
        // Shell 0..27, nub 27..29 (the ink ends at 29, as before), nub rows 5..11 around the centre line 8.
        assertEquals(27, thin.shellRight)
        assertEquals(29, thin.nubRight)
        assertEquals(5, thin.nubTop)
        assertEquals(11, thin.nubBottom)
        // Fill 1 px inside the 2 px outline: x 3..24, y 3..13.
        assertEquals(3, thin.fillLeft)
        assertEquals(24, thin.fillRight)
        assertEquals(3, thin.fillTop)
        assertEquals(13, thin.fillBottom)
        assertEquals(4, wide.fillLeft)
        assertEquals(12, wide.fillBottom)
        for (b in arrayOf(thin, wide)) {
            assertEquals(16, b.fillTop + b.fillBottom)
            assertEquals(16, b.nubTop + b.nubBottom)
            // The fill is centred in the shell.
            assertEquals(b.fillLeft, b.shellRight - b.fillRight)
        }
    }

    @Test
    fun fillWidthIsWholePixelsAndFullOnlyAt100() {
        assertEquals(0, BatteryLayout.fillWidth(0, 21))
        assertEquals(1, BatteryLayout.fillWidth(1, 21))
        assertEquals(20, BatteryLayout.fillWidth(99, 21))
        assertEquals(21, BatteryLayout.fillWidth(100, 21))
        assertEquals(11, BatteryLayout.fillWidth(50, 21))
    }

    @Test
    fun digitsHaveOneHeightCentredOnTheBody() {
        // 8 px digits (rows 4..12) in the thin battery, 6 px (rows 5..11) in the wide one: centre line 8 in both.
        for ((b, height) in listOf(thin to 8, wide to 6)) {
            assertEquals(height, BatteryLayout.digitHeight(b.fillHeight))
            val top = BatteryLayout.digitTop(b.fillTop, b.fillHeight, height)
            assertEquals(16, 2 * top + height)
        }
    }

    @Test
    fun allDigitsButOneShareOneWidth() {
        // At 8 px the rounded ink widths are 5 or 6 px: all take the narrowest (5); "1" is 3 px.
        assertArrayEquals(intArrayOf(3, 5, 5), BatteryLayout.digitWidths("100", nimbus, 8))
        assertArrayEquals(intArrayOf(5, 5), BatteryLayout.digitWidths("86", nimbus, 8))
        assertArrayEquals(intArrayOf(3, 4, 4), BatteryLayout.digitWidths("100", nimbus, 6))
    }

    @Test
    fun chargingKeepsTheDigitHeightAtAllLevels() {
        // The user's report: the charging digits were 7 px (0.5 px below the centre line) and the bolt 8.32 px.
        for (b in arrayOf(thin, wide)) {
            val normal = BatteryLayout.digitHeight(b.fillHeight)
            for (level in 0..100) {
                for (bolt in booleanArrayOf(false, true)) {
                    val g = BatteryLayout.group(level.toString(), nimbus, b.fillWidth, b.fillHeight, bolt)
                    assertEquals("level $level bolt $bolt", normal, g.digitHeight)
                    assertTrue("level $level bolt $bolt fits", g.width <= b.fillWidth)
                }
            }
        }
        // "100" with the bolt fills the thin battery exactly: 5 + 1 + 3 + 1 + 5 + 1 + 5.
        val g = BatteryLayout.group("100", nimbus, thin.fillWidth, thin.fillHeight, true)
        assertEquals(5, g.boltWidth)
        assertEquals(21, g.width)
    }

    @Test
    fun groupBecomesSmallerOnlyWhenItDoesNotFit() {
        // 21 px at 8 px does not fit in 19 px; at 6 px the group is 18 px.
        val g = BatteryLayout.group("100", nimbus, 19, 10, true)
        assertEquals(6, g.digitHeight)
        assertEquals(18, g.width)
    }

    @Test
    fun groupIsCentredWithTheOddPixelOnTheRight() {
        assertEquals(5, BatteryLayout.groupLeft(3, 21, 17))
        assertEquals(6, BatteryLayout.groupLeft(3, 21, 15))
        assertEquals(2, BatteryLayout.groupLeft(3, 21, 23))
    }

    @Test
    fun boltIsSymmetricAndInItsBox() {
        for ((w, h) in listOf(5 to 10, 4 to 8, 7 to 12, 5 to 12)) {
            val p = BatteryLayout.boltPoints(w, h)
            assertEquals(16, p.size)
            var minX = Float.MAX_VALUE; var maxX = -1f; var minY = Float.MAX_VALUE; var maxY = -1f
            for (i in 0 until 8) {
                val x = p[2 * i]; val y = p[2 * i + 1]
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                // Turned 180° about the centre of the box, each point is the point 4 places later.
                val j = (i + 4) % 8
                assertEquals(w - x, p[2 * j], 1e-4f)
                assertEquals(h - y, p[2 * j + 1], 1e-4f)
            }
            assertEquals(0f, minX, 0f); assertEquals(w.toFloat(), maxX, 0f)
            assertEquals(0f, minY, 0f); assertEquals(h.toFloat(), maxY, 0f)
        }
    }

    @Test
    fun boltIsOnePixelTallerThanTheDigitsAtEachEnd() {
        assertEquals(10, BatteryLayout.boltHeight(8))
        assertEquals(5, BatteryLayout.boltWidth(8))
        assertEquals(4, BatteryLayout.boltWidth(6))
    }

    @Test
    fun soloBoltIsExactlyCentred() {
        // Battery Percentage off: 12 px tall in the thin battery (7 px wide), 10 px in the wide one (5 px).
        assertEquals(7, BatteryLayout.soloBoltWidth(12, thin.fillWidth))
        assertEquals(5, BatteryLayout.soloBoltWidth(10, wide.fillWidth))
        for (b in arrayOf(thin, wide)) {
            val w = BatteryLayout.soloBoltWidth(b.fillHeight + 2, b.fillWidth)
            assertEquals(0, (b.fillWidth - w) % 2)
        }
    }
}
