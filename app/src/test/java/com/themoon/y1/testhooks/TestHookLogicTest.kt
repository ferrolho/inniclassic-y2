package com.themoon.y1.testhooks

import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.testhooks.TestHookLogic.BrowseType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TestHookLogicTest {

    private fun song(title: String, artist: String, album: String) =
        SongItem(File("/music/$artist/$album/$title.flac"), title, artist, album)

    @Test
    fun screenIdAcceptsAliasesInAnyCase() {
        assertEquals("NowPlaying", TestHookLogic.screenId("Now Playing"))
        assertEquals("NowPlaying", TestHookLogic.screenId("player"))
        assertEquals("Music", TestHookLogic.screenId("Browser root"))
        assertEquals("About", TestHookLogic.screenId("Storage"))
        assertEquals("CoverFlow", TestHookLogic.screenId("cover-flow"))
        assertEquals("MainMenu", TestHookLogic.screenId("MainMenu"))
        assertNull(TestHookLogic.screenId("nowhere"))
        assertNull(TestHookLogic.screenId(null))
    }

    @Test
    fun everyScreenIdResolvesToItself() {
        for (id in TestHookLogic.SCREEN_IDS) assertEquals(id, TestHookLogic.screenId(id))
    }

    @Test
    fun browseTypeParsesNames() {
        assertEquals(BrowseType.ALBUM, TestHookLogic.browseType("ALBUM"))
        assertEquals(BrowseType.ARTIST, TestHookLogic.browseType("artist"))
        assertEquals(BrowseType.ALBUM_ARTIST, TestHookLogic.browseType("ALBUM_ARTIST"))
        assertEquals(BrowseType.GENRE, TestHookLogic.browseType("Genres"))
        assertEquals(BrowseType.SONGS, TestHookLogic.browseType("SONGS"))
        assertNull(TestHookLogic.browseType("YEAR"))
    }

    @Test
    fun resolveNamePrefersExactMatch() {
        val names = listOf("abbey road", "Abbey Road", "Help!")
        assertEquals("Abbey Road", TestHookLogic.resolveName(names, "Abbey Road"))
        assertEquals("abbey road", TestHookLogic.resolveName(names, "ABBEY ROAD"))
        assertNull(TestHookLogic.resolveName(names, "Revolver"))
    }

    @Test
    fun findSongPrefersTitleThenArtistOrAlbum() {
        val songs = listOf(
            song("Come Together", "The Beatles", "Abbey Road"),
            song("Road Trip", "Someone", "Other"),
            song("Help!", "The Beatles", "Help!")
        )
        assertEquals("Road Trip", TestHookLogic.findSong(songs, "ROAD")?.title)
        assertEquals("Come Together", TestHookLogic.findSong(songs, "beatles")?.title)
        assertNull(TestHookLogic.findSong(songs, "zzz"))
        assertNull(TestHookLogic.findSong(songs, "  "))
    }

    @Test
    fun keyRepeatOffsetsFollowAndroidTiming() {
        assertEquals(emptyList<Long>(), TestHookLogic.keyRepeatOffsets(400))
        assertEquals(listOf(500L, 550L, 600L, 650L), TestHookLogic.keyRepeatOffsets(700))
    }

    @Test
    fun iconGlyphsAreRecognised() {
        assertTrue(TestHookLogic.isIconGlyph(""))
        assertTrue(TestHookLogic.isIconGlyph("  "))
        assertFalse(TestHookLogic.isIconGlyph("Albums"))
        assertFalse(TestHookLogic.isIconGlyph(""))
    }

    @Test
    fun describeShowsClassAndParams() {
        val d = TestHookLogic.describe(ScreenState.Browser(BrowserMode.Albums("ABBA", false)))!!
        assertEquals("Browser", d.getString("name"))
        assertTrue(d.getString("detail").contains("ABBA"))
        assertNull(TestHookLogic.describe(null))
    }

    @Test
    fun keySettingsKeepsOnlyKnownKeys() {
        val prefs = mapOf(
            "sound" to true,
            "loop_scroll_on" to false,
            "app_theme_name" to "Classic Light",
            "rating_/music/a.flac" to 4,
            "lastfm_password" to "x",
            "unrelated" to 1
        )
        val s = TestHookLogic.keySettings(prefs)
        assertEquals(3, s.length())
        assertEquals("Classic Light", s.getString("app_theme_name"))
        assertFalse(s.has("unrelated"))
    }

    @Test
    fun allSettingsDropsSecretsAndBulkKeys() {
        val prefs = mapOf(
            "sound" to true,
            "rating_/music/a.flac" to 4,
            "book_pos_/books/b.m4b" to 1000,
            "lastfm_password" to "x",
            "jellyfin_token" to "y",
            "favorite_songs_set" to setOf("a", "b"),
            "volume" to 0.5f
        )
        val s = TestHookLogic.allSettings(prefs)
        assertEquals(setOf("sound", "favorite_songs_set", "volume"), s.keys().asSequence().toSet())
        assertEquals(2, s.getJSONObject("favorite_songs_set").getInt("setSize"))
    }

    @Test
    fun resultHasOkErrorAndState() {
        val r = TestHookLogic.result(false, "boom", "seek", null)
        assertFalse(r.getBoolean("ok"))
        assertEquals("boom", r.getString("error"))
        assertTrue(r.isNull("state"))
        val ok = TestHookLogic.result(true, null, "state", JSONObject().put("a", 1))
        assertTrue(ok.isNull("error"))
        assertEquals(1, ok.getJSONObject("state").getInt("a"))
    }

    @Test
    fun broadcastDataIsOneLineAndPointsToFileWhenLong() {
        val json = TestHookLogic.result(true, null, "state", JSONObject().put("text", "a\nb")).toString()
        assertFalse(json.contains("\n"))
        assertEquals(json, TestHookLogic.broadcastData(json, true, null, "state", "/f.json"))
        val short = JSONObject(TestHookLogic.broadcastData(json, true, null, "state", "/f.json", max = 10))
        assertTrue(short.getBoolean("truncated"))
        assertEquals("/f.json", short.getString("file"))
        assertEquals(json.length, short.getInt("chars"))
    }
}
