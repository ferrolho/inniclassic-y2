package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.sin

class StatusIconsTest {
    private val eps = 1e-3f

    @Test
    fun widthsAtTheIpodAndTheDefaultHeight() {
        // 15 px = the round icons in the iPod Classic Layout; 16 px = the status bar without it.
        assertEquals(19, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 15))
        assertEquals(15, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 15))
        assertEquals(10, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 15))
        assertEquals(18, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 15, connected = true))
        assertEquals(21, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 16))
        assertEquals(16, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 16))
        assertEquals(10, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16))
        assertEquals(18, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16, connected = true))
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
    fun runeHasTheClassicShapeAndFillsItsHeight() {
        for (h in 10..40) {
            val s = 1.5f
            val r = StatusIcons.runeGeometry(h.toFloat(), s)
            val p = r.points
            assertEquals(12, p.size)
            // Stem: bottom tip (point 2) and top tip (point 3) on x = 0; the miter tips touch the top and the bottom.
            assertEquals(0f, p[4], eps)
            assertEquals(0f, p[6], eps)
            assertEquals(0f, p[7] - r.tipRise, eps)
            assertEquals(h.toFloat(), p[5] + r.tipRise, eps)
            // Right points at 1/4 and 3/4 of the line, arm ends level with them; mirror image about mid-height.
            val top = p[7]
            val line = p[5] - p[7]
            assertEquals(top + line / 4f, p[9], eps)
            assertEquals(top + 3f * line / 4f, p[3], eps)
            assertEquals(p[1], p[9], eps)
            assertEquals(p[11], p[3], eps)
            assertEquals(r.halfWidth, p[8], eps)
            assertEquals(-r.halfWidth, p[0], eps)
            // Both arms cross the stem at mid-height.
            assertEquals(h / 2f, (p[1] + p[3]) / 2f, eps)
            assertEquals(h / 2f, (p[9] + p[11]) / 2f, eps)
            // Rune about twice as tall as wide (macOS proportions).
            assertEquals(StatusIcons.RUNE_WIDTH * h, 2f * r.halfWidth, eps)
            // The joins stay sharp: the miters are below the default limit of Paint (4).
            val q = line / 4f
            val phi = atan2(r.halfWidth, q)
            assertTrue(1f / sin(phi / 2f) < 4f)
            assertTrue(Math.hypot(r.halfWidth.toDouble(), q.toDouble()) / q < 4.0)
            assertTrue(r.inkRight > r.halfWidth && r.inkLeft > r.halfWidth)
        }
    }

    @Test
    fun bluetoothStemAndDotsAreOnPixelCentres() {
        for (h in 10..40) {
            for (connected in listOf(false, true)) {
                val l = StatusIcons.bluetoothLayout(h, connected)
                assertEquals(0.5f, l.stemX % 1f, eps)
                // The ink of the rune is in the bitmap, and touches its left and right edges (within 1 px).
                val left = l.stemX - l.rune.inkLeft
                val right = l.stemX + l.rune.inkRight
                assertTrue(left >= 0f && right <= l.width)
                if (!connected) {
                    assertTrue("h=$h", left < 1f && l.width - right < 1f)
                    assertEquals(0, l.dotX.size)
                    continue
                }
                assertEquals(2, l.dotX.size)
                assertEquals(h / 2f, l.dotY, eps)
                for (x in l.dotX) assertEquals(0.5f, x % 1f, eps)
                // Left dot touches the left edge; the right dot is in the bitmap, within 1 px of its edge.
                assertEquals(0f, l.dotX[0] - l.dotRadius, 0.5f)
                assertTrue(l.dotX[1] + l.dotRadius <= l.width && l.width - (l.dotX[1] + l.dotRadius) < 1f)
                // At least dotGap between each dot and the ink of the rune, and less than 1 px more.
                val gapLeft = left - (l.dotX[0] + l.dotRadius)
                val gapRight = (l.dotX[1] - l.dotRadius) - right
                assertTrue(gapLeft >= l.dotGap - eps && gapLeft < l.dotGap + 1f)
                assertTrue(gapRight >= l.dotGap - eps && gapRight < l.dotGap + 1f)
            }
        }
    }

    @Test
    fun bluetoothAtTheIpodHeight() {
        val l = StatusIcons.bluetoothLayout(15, connected = true)
        assertEquals(1.5f, l.rune.stroke, eps)
        assertEquals(1.5f, l.dotRadius, eps)
        assertEquals(8.5f, l.stemX, eps)
        assertEquals(1.5f, l.dotX[0], eps)
        assertEquals(16.5f, l.dotX[1], eps)
        assertEquals(7.5f, l.dotY, eps)
    }
}
