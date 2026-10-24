package com.themoon.y1.managers

import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.navigation.VideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class AutoThemeManagerTest {

    @Test
    fun testScreensRestoredAfterThemeRestart() {
        assertEquals("OPEN_PLAYER", AutoThemeManager.screenActionFor(ScreenState.Player, false))
        assertEquals("OPEN_SETTINGS", AutoThemeManager.screenActionFor(ScreenState.Settings(), false))
        assertEquals("OPEN_SETTINGS", AutoThemeManager.screenActionFor(ScreenState.Settings(SettingsSubMode.DateTime), false))
        assertEquals("OPEN_BROWSER", AutoThemeManager.screenActionFor(ScreenState.Browser(BrowserMode.Root), false))
        assertEquals("OPEN_BROWSER", AutoThemeManager.screenActionFor(ScreenState.Browser(BrowserMode.Artists), false))
        assertEquals("OPEN_AUDIOBOOKS", AutoThemeManager.screenActionFor(ScreenState.Browser(BrowserMode.Audiobooks), true))
        assertEquals("OPEN_COVER_FLOW", AutoThemeManager.screenActionFor(ScreenState.CoverFlow, false))
        assertEquals("OPEN_PODCASTS", AutoThemeManager.screenActionFor(ScreenState.PodcastManage, false))
    }

    @Test
    fun testOtherScreensFallBackToMainMenu() {
        assertNull(AutoThemeManager.screenActionFor(null, false))
        assertNull(AutoThemeManager.screenActionFor(ScreenState.MainMenu, false))
        assertNull(AutoThemeManager.screenActionFor(ScreenState.FmRadio, false))
        assertNull(AutoThemeManager.screenActionFor(ScreenState.Bluetooth, false))
        assertNull(AutoThemeManager.screenActionFor(ScreenState.Videos(VideoMode.Root), false))
        assertNull(AutoThemeManager.screenActionFor(ScreenState.VideoPlayer(File("a.mp4")), false))
    }
}
