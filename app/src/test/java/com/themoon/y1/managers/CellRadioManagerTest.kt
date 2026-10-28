package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CellRadioManagerTest {

    @Test
    fun parseRadios_trimsAndDropsEmptyItems() {
        assertEquals(listOf("cell", "wifi"), CellRadioManager.parseRadios(" cell , ,wifi,"))
    }

    @Test
    fun parseRadios_usesAndroidDefaultWhenNotSet() {
        val expected = listOf("cell", "bluetooth", "wifi", "nfc", "wimax")
        assertEquals(expected, CellRadioManager.parseRadios(null))
        assertEquals(expected, CellRadioManager.parseRadios(""))
    }

    @Test
    fun isCellOnly() {
        assertTrue(CellRadioManager.isCellOnly("cell"))
        assertTrue(CellRadioManager.isCellOnly(" cell,cell "))
        assertFalse(CellRadioManager.isCellOnly("cell,bluetooth,wifi,nfc,wimax"))
        assertFalse(CellRadioManager.isCellOnly("wifi"))
        assertFalse(CellRadioManager.isCellOnly(null))
    }

    @Test
    fun isCellRadioOn() {
        assertTrue(CellRadioManager.isCellRadioOn(false, "cell"))
        assertFalse(CellRadioManager.isCellRadioOn(true, "cell"))
        assertFalse(CellRadioManager.isCellRadioOn(true, null))
        assertTrue(CellRadioManager.isCellRadioOn(true, "bluetooth,wifi"))
    }

    @Test
    fun command_offLimitsAirplaneModeToCellFirst() {
        val cmd = CellRadioManager.command(cellOn = false)
        val radios = cmd.indexOf("settings put global airplane_mode_radios cell;")
        val on = cmd.indexOf("settings put global airplane_mode_on 1;")
        val broadcast = cmd.indexOf("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true")
        assertTrue(radios >= 0 && on > radios && broadcast > on)
    }

    @Test
    fun command_onTurnsAirplaneModeOff() {
        assertEquals(
            "settings put global airplane_mode_on 0; " +
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
            CellRadioManager.command(cellOn = true)
        )
    }
}
