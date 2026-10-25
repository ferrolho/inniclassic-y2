package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusIconsTest {

    @Test
    fun widthsAtTheIpodAndTheDefaultHeight() {
        // 20 px = the battery height in the iPod Classic Layout; 16 px = the status bar without it.
        assertEquals(25, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 20))
        assertEquals(20, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 20))
        assertEquals(14, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 20))
        assertEquals(20, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 16))
        assertEquals(16, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 16))
        assertEquals(12, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16))
    }

    @Test
    fun bluetoothWidthIsEvenAtAllHeights() {
        for (h in 8..40) assertEquals(0, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, h) % 2)
    }
}
