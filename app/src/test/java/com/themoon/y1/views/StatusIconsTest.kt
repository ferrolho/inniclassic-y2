package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusIconsTest {

    @Test
    fun widthsAtTheIpodAndTheDefaultHeight() {
        // 18 px = the iPod play/pause icon; 16 px = the status bar without the iPod Classic Layout.
        assertEquals(22, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 18))
        assertEquals(18, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 18))
        assertEquals(12, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 18))
        assertEquals(20, StatusIcons.widthFor(StatusIcons.Icon.WIFI, 16))
        assertEquals(16, StatusIcons.widthFor(StatusIcons.Icon.HEADPHONES, 16))
        assertEquals(12, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, 16))
    }

    @Test
    fun bluetoothWidthIsEvenAtAllHeights() {
        for (h in 8..40) assertEquals(0, StatusIcons.widthFor(StatusIcons.Icon.BLUETOOTH, h) % 2)
    }
}
