package com.themoon.y1.core.extensions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * On/off setting values: the same "On"/"Off" text on every row, and a translation in every language file.
 */
class OnOffTest {

    @Test
    fun keyIsOnOrOffInIpodCase() {
        assertEquals("On", onOffKey(true))
        assertEquals("Off", onOffKey(false))
    }

    @Test
    fun valueGoesThroughLocalize() {
        val polish = mapOf("On" to "Wł.", "Off" to "Wył.")
        assertEquals("Wł.", onOff(true) { polish[it] ?: it })
        assertEquals("Wył.", onOff(false) { polish[it] ?: it })
        assertEquals("On", onOff(true) { it })
        assertEquals("Off", onOff(false) { it })
    }

    @Test
    fun everyLanguageFileTranslatesOnAndOff() {
        val dir = listOf(File("src/main/assets/languages"), File("app/src/main/assets/languages"))
            .first { it.isDirectory }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("no language files in $dir", files.isNotEmpty())
        for (file in files) {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in listOf(onOffKey(true), onOffKey(false))) {
                assertTrue("${file.name} has no \"$key\"", json.optString(key).isNotBlank())
            }
        }
    }
}
