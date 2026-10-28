package com.themoon.y1.navigation

import android.view.ViewGroup
import com.themoon.y1.controllers.ScreenController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Back rule: Back selects the row that opened the deeper screen; going down selects no kept row. */
class BackSelectionTest {

    private class FakeList(
        override val rootView: ViewGroup = NavigationManagerTest.createDummyViewGroup()
    ) : ScreenController {
        var row: RowKey? = null
        val selected = ArrayList<RowKey>()
        var back: ((ScreenState) -> ScreenState)? = null

        override fun selectedRow(): RowKey? = row
        override fun selectRow(row: RowKey) {
            selected.add(row)
        }
        override fun backState(state: ScreenState): ScreenState = back?.invoke(state) ?: state
    }

    private val nav = NavigationManager.getInstance()
    private val menu = FakeList()
    private val browser = FakeList()
    private val playlists = FakeList()
    private val player = FakeList()
    private val settings = FakeList()
    private val bluetooth = FakeList()

    @Before
    fun setUp() {
        nav.reset()
        nav.registerController(ScreenState.MainMenu::class.java, menu)
        nav.registerController(ScreenState.Browser::class.java, browser)
        nav.registerController(ScreenState.Playlists::class.java, playlists)
        nav.registerController(ScreenState.M3uPlaylistSongs::class.java, playlists)
        nav.registerController(ScreenState.Player::class.java, player)
        nav.registerController(ScreenState.Settings::class.java, settings)
        nav.registerController(ScreenState.Bluetooth::class.java, bluetooth)
    }

    @After
    fun tearDown() {
        nav.reset()
    }

    @Test
    fun backSelectsTheRowThatOpenedEachScreen() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Music", 1, 37)
        nav.navigateTo(ScreenState.Browser(BrowserMode.Root))
        browser.row = RowKey("Playlists", 1, 37)
        nav.navigateTo(ScreenState.Playlists)
        playlists.row = RowKey("Rock", 4, 148)
        nav.navigateTo(ScreenState.M3uPlaylistSongs(java.io.File("/Playlists/Rock.m3u")))

        // Going down selects no kept row
        assertTrue(menu.selected.isEmpty() && browser.selected.isEmpty() && playlists.selected.isEmpty())

        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Rock", 4, 148)), playlists.selected)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Playlists", 1, 37)), browser.selected)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Music", 1, 37)), menu.selected)
    }

    @Test
    fun theMainMenuKeepsItsRowAlsoWhenItClearsTheBackStack() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Settings", 7, 259)
        nav.navigateTo(ScreenState.Settings())
        settings.row = RowKey("Bluetooth", 0, 0)
        nav.navigateTo(ScreenState.Bluetooth)

        // A screen that goes to the main menu directly (not with Back) also comes to the row that opened it
        nav.navigateTo(ScreenState.MainMenu)
        assertEquals(listOf(RowKey("Settings", 7, 259)), menu.selected)
        assertTrue(settings.selected.isEmpty())
    }

    @Test
    fun aRowOnlyComesBackOnce() {
        nav.navigateTo(ScreenState.MainMenu)
        menu.row = RowKey("Settings", 7, 259)
        nav.navigateTo(ScreenState.Settings())
        settings.row = RowKey("Bluetooth", 0, 0)
        nav.navigateTo(ScreenState.Bluetooth)
        assertTrue(nav.popBackStack())
        assertEquals(1, settings.selected.size)

        // Down again from the same screen: a new row is kept, the old one is gone
        settings.row = RowKey("Wi-Fi", 1, 37)
        nav.navigateTo(ScreenState.Bluetooth)
        assertTrue(nav.popBackStack())
        assertEquals(listOf(RowKey("Bluetooth", 0, 0), RowKey("Wi-Fi", 1, 37)), settings.selected)
    }

    @Test
    fun backComesToTheListThatTheScreenShowsNow() {
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(ScreenState.Browser(BrowserMode.Root))
        // The Music browser changed its list in place (Genres -> Fado -> All Songs)
        browser.back = { ScreenState.Browser(BrowserMode.VirtualSongs) }
        browser.row = RowKey("A Fadista", 1, 37)
        nav.navigateTo(ScreenState.Player)

        assertTrue(nav.popBackStack())
        assertEquals(ScreenState.Browser(BrowserMode.VirtualSongs), nav.currentState)
        assertEquals(listOf(RowKey("A Fadista", 1, 37)), browser.selected)
    }

    @Test
    fun aScreenWithNoSelectedRowGetsNoRow() {
        nav.navigateTo(ScreenState.MainMenu)
        nav.navigateTo(ScreenState.Browser(BrowserMode.Root))
        nav.navigateTo(ScreenState.Player)
        assertTrue(nav.popBackStack())
        assertTrue(browser.selected.isEmpty())
        assertTrue(nav.popBackStack())
        assertTrue(menu.selected.isEmpty())
    }

    @Test
    fun rowKeyFindsTheRowByTextThenByIndex() {
        val rows = listOf("Now Playing", "Music", "Podcasts", "Settings")
        assertEquals(3, RowKey("Settings", 7).indexIn(rows))          // a row above it was hidden
        assertEquals(1, RowKey("Music", 1).indexIn(rows))
        assertEquals(2, RowKey("Videos", 2).indexIn(rows))            // the row is gone: same position
        assertEquals(3, RowKey("Videos", 9).indexIn(rows))            // ... in the list
        assertEquals(0, RowKey(null, -4).indexIn(rows))
        assertEquals(-1, RowKey("Music", 1).indexIn(emptyList()))
        // Two rows with the same text: the one nearest to the old index
        val songs = listOf("Intro", "Song", "Intro", "Song", "Intro")
        assertEquals(4, RowKey("Intro", 4).indexIn(songs))
        assertEquals(2, RowKey("Intro", 2).indexIn(songs))
        assertEquals(1, RowKey("Song", 0).indexIn(songs))
    }

    @Test
    fun rowKeyKeepsTheFullRowInView() {
        assertEquals(148, RowKey("x", 4, 148).topIn(listHeight = 333, rowHeight = 37))
        assertEquals(296, RowKey("x", 9, 320).topIn(listHeight = 333, rowHeight = 37))
        assertEquals(0, RowKey("x", 0, -20).topIn(listHeight = 333, rowHeight = 37))
        assertNull(RowKey(null, 0).text)
    }
}
