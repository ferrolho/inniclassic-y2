package com.themoon.y1.views

import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.core.extensions.onOffState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToggleStyleTest {

    @Test
    fun textStyleAlwaysShowsText() {
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, true))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, false))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(false, null))
    }

    @Test
    fun switchStyleShowsASwitchOnlyForOnOffValues() {
        assertEquals(ToggleStyle.Look.SWITCH_ON, ToggleStyle.look(true, onOffState(onOff(true) { it })))
        assertEquals(ToggleStyle.Look.SWITCH_OFF, ToggleStyle.look(true, onOffState(onOff(false) { it })))
        // "Off" of Bass Boost or Volume Limit is not from onOff(): it stays text.
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(true, onOffState("Off")))
        assertEquals(ToggleStyle.Look.TEXT, ToggleStyle.look(true, onOffState("Wait...")))
    }

    @Test
    fun prefValueRoundTripsAndDefaultsToText() {
        assertTrue(ToggleStyle.isSwitchPref(ToggleStyle.prefValue(true)))
        assertFalse(ToggleStyle.isSwitchPref(ToggleStyle.prefValue(false)))
        assertFalse(ToggleStyle.isSwitchPref(null))
        assertFalse(ToggleStyle.isSwitchPref("something else"))
        assertEquals("switch", ToggleStyle.prefValue(true))
        assertEquals("text", ToggleStyle.prefValue(false))
    }

    @Test
    fun colorsStayVisibleOnTheSelectedRow() {
        val accent = 0xFF2588DC.toInt()
        val white = 0xFFFFFFFF.toInt()
        val secondary = 0xFF666666.toInt()

        val on = ToggleStyle.colors(on = true, rowSelected = false, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(accent, on.track)
        assertEquals(white, on.knob)

        val off = ToggleStyle.colors(on = false, rowSelected = false, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(0x99666666.toInt(), off.track) // grey, not the accent
        assertEquals(white, off.knob)

        // The selected row background is the accent: the On track must not be the accent there.
        val onSelected = ToggleStyle.colors(on = true, rowSelected = true, accent = accent, selectedText = white, secondaryText = secondary)
        assertNotEquals(accent, onSelected.track)
        assertEquals(white, onSelected.track)
        assertEquals(accent, onSelected.knob)

        val offSelected = ToggleStyle.colors(on = false, rowSelected = true, accent = accent, selectedText = white, secondaryText = secondary)
        assertEquals(0x66FFFFFF, offSelected.track)
        assertEquals(white, offSelected.knob)
    }

    @Test
    fun greyKeepsTheLuminance() {
        assertEquals(0xFF666666.toInt(), ToggleStyle.grey(0xFF666666.toInt()))
        assertEquals(0xFF000000.toInt(), ToggleStyle.grey(0xFF000000.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ToggleStyle.grey(0xFFFFFFFF.toInt()))
        assertEquals(0xFF969696.toInt(), ToggleStyle.grey(0xFF00FF00.toInt())) // 0.587 × 255 = 150
    }

    @Test
    fun everyLanguageFileTranslatesTheSetting() {
        val dir = listOf(File("src/main/assets/languages"), File("app/src/main/assets/languages"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no language files in $dir", files.isNotEmpty())
        val keys = listOf("Toggle Style", ToggleStyle.labelKey(false), ToggleStyle.labelKey(true))
        for (file in files) {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in keys) {
                assertTrue("${file.name} has no \"$key\"", json.optString(key).isNotBlank())
            }
        }
        val english = JSONObject(File(dir, "english.json").readText(Charsets.UTF_8))
        assertEquals("Text", english.getString(ToggleStyle.labelKey(false)))
        assertEquals("Switch", english.getString(ToggleStyle.labelKey(true)))
    }
}
