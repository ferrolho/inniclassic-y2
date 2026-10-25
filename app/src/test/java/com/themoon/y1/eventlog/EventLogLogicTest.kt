package com.themoon.y1.eventlog

import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EventLogLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun line(up: Long, ev: String = "key") = "{\"t\":\"x\",\"up\":$up,\"v\":1,\"ev\":\"$ev\",\"a\":\"up\"}"

    @Test
    fun secretKeysAreFound() {
        assertTrue(EventLogLogic.isSecretKey("jellyfin_password"))
        assertTrue(EventLogLogic.isSecretKey("lastfm_session_key"))
        assertTrue(EventLogLogic.isSecretKey("AccessToken"))
        assertTrue(EventLogLogic.isSecretKey("lastfm_api_secret"))
        assertTrue(EventLogLogic.isSecretKey("apiKey"))
        assertFalse(EventLogLogic.isSecretKey("app_theme_name"))
        assertFalse(EventLogLogic.isSecretKey("vibrate_strength"))
    }

    @Test
    fun playbackStateKeysAreQuiet() {
        assertTrue(EventLogLogic.isQuietKey("last_played_position"))
        assertTrue(EventLogLogic.isQuietKey("book_pos_/storage/Audiobooks/a.m4b"))
        assertTrue(EventLogLogic.isQuietKey("has_saved_playback_state"))
        assertFalse(EventLogLogic.isQuietKey("shuffle"))
        assertFalse(EventLogLogic.isQuietKey("rating_/music/a.flac"))
    }

    @Test
    fun valuesBecomeShortJson() {
        assertEquals(true, EventLogLogic.jsonValue(true))
        assertEquals(3, EventLogLogic.jsonValue(3))
        assertEquals(0.5, EventLogLogic.jsonValue(0.5f))
        val long = "x".repeat(1000)
        assertEquals(EventLogLogic.MAX_TEXT + 1, (EventLogLogic.jsonValue(long) as String).length)
        val set = EventLogLogic.jsonValue(setOf("b", "a")) as JSONArray
        assertEquals("a", set.getString(0))
        assertEquals("b", set.getString(1))
        val big = EventLogLogic.jsonValue((1..500).map { "item$it" }.toSet()) as JSONArray
        assertTrue(big.getString(big.length() - 1).contains("500 items"))
    }

    @Test
    fun redactRemovesSecretValues() {
        assertEquals(
            "GET /Items?api_key=<redacted>&x=1",
            EventLogLogic.redact("GET /Items?api_key=abc123&x=1")
        )
        assertEquals("MediaBrowser Token=\"<redacted>\"", EventLogLogic.redact("MediaBrowser Token=\"s3cr3t\""))
        assertEquals("password: <redacted>", EventLogLogic.redact("password: hunter2"))
        assertEquals("no secret here", EventLogLogic.redact("no secret here"))
    }

    @Test
    fun logcatThreadtimeLinesParse() {
        val l = EventLogLogic.parseLogcat("10-24 12:34:56.789  1234  1250 W AudioPlayerManager: Saved song does not exist: /a b")!!
        assertEquals("10-24 12:34:56.789", l.time)
        assertEquals(1234, l.pid)
        assertEquals(1250, l.tid)
        assertEquals('W', l.level)
        assertEquals("AudioPlayerManager", l.tag)
        assertEquals("Saved song does not exist: /a b", l.message)

        val padded = EventLogLogic.parseLogcat("10-24 12:34:56.789   999   999 E Y1Ums   : \tat x.y(Z.java:1)")!!
        assertEquals("Y1Ums", padded.tag)
        assertEquals("\tat x.y(Z.java:1)", padded.message)

        assertEquals("", EventLogLogic.parseLogcat("10-24 12:34:56.789  1  2 E Tag: ")!!.message)
        assertNull(EventLogLogic.parseLogcat("--------- beginning of /dev/log/main"))
    }

    @Test
    fun screenNamesAreShort() {
        assertEquals("MainMenu", EventLogLogic.screenName(ScreenState.MainMenu))
        assertEquals("Browser/Albums", EventLogLogic.screenName(ScreenState.Browser(BrowserMode.Albums("X"))))
        assertEquals("Browser/VirtualSongs", EventLogLogic.screenName(ScreenState.Browser(BrowserMode.Root), BrowserMode.VirtualSongs))
        assertEquals("Settings/DateTime", EventLogLogic.screenName(ScreenState.Settings(SettingsSubMode.DateTime)))
        assertEquals("none", EventLogLogic.screenName(null))
        assertNull(EventLogLogic.screenDetail(ScreenState.Player))
        assertTrue(EventLogLogic.screenDetail(ScreenState.Browser(BrowserMode.Albums("X")))!!.contains("artistFilter=X"))
    }

    @Test
    fun wheelBurstContinuesOnlyInOneDirectionAndShortGaps() {
        val b = EventLogLogic.WheelBurst(1, "BrowserController.onWheel", "Browser/Root", 1000L, 0L)
        assertTrue(b.continuesWith(1, "BrowserController.onWheel", "Browser/Root", 1500L))
        assertFalse(b.continuesWith(-1, "BrowserController.onWheel", "Browser/Root", 1500L))
        assertFalse(b.continuesWith(1, "none", "Browser/Root", 1500L))
        assertFalse(b.continuesWith(1, "BrowserController.onWheel", "Player", 1500L))
        assertFalse(b.continuesWith(1, "BrowserController.onWheel", "Browser/Root", 1000L + EventLogLogic.WHEEL_GAP_MS + 1))
        assertTrue(b.isIdle(1000L + EventLogLogic.WHEEL_GAP_MS + 1))
        assertEquals(-1, EventLogLogic.wheelDir(21))
        assertEquals(1, EventLogLogic.wheelDir(22))
    }

    @Test
    fun uptimeIsReadFromTheLine() {
        assertEquals(12345L, EventLogLogic.uptimeOf(line(12345)))
        assertNull(EventLogLogic.uptimeOf("{\"ev\":\"dropped\"}"))
    }

    @Test
    fun tailGivesTheLastLines() {
        val lines = (1L..5L).map { line(it) }
        assertEquals(lines.subList(3, 5), EventLogLogic.tail(lines, 2))
        assertEquals(lines, EventLogLogic.tail(lines, 10))
    }

    @Test
    fun sinceStopsAtTheUptimeAndAtABootChange() {
        val lines = listOf(line(900_000), line(950_000), line(100), line(200), line(300), line(400))
        assertEquals(listOf(line(300), line(400)), EventLogLogic.since(lines, 200))
        // Before this boot: the uptime goes up when read backwards
        assertEquals(lines.subList(2, 6), EventLogLogic.since(lines, 0))
        assertEquals(emptyList<String>(), EventLogLogic.since(lines, 400))
    }

    @Test
    fun throwableJsonHasCauses() {
        val t = IllegalStateException("outer token=abc", RuntimeException("inner"))
        val o = EventLogLogic.throwableJson(t)
        assertEquals("java.lang.IllegalStateException", o.getString("ex"))
        assertEquals("outer token=<redacted>", o.getString("msg"))
        assertEquals("inner", o.getJSONObject("cause").getString("msg"))
        assertTrue(o.getJSONArray("stack").length() > 0)
    }

    @Test
    fun fileRotatesAndReadsOldestFirst() {
        val dir = tmp.newFolder("event-log")
        val f = EventLogFile(dir, maxBytes = 100, files = 2)
        val lines = (1..12).map { "{\"up\":$it,\"pad\":\"xxxxxxxxxxxx\"}" } // ~31 bytes each: 3 per file
        for (l in lines) f.append(listOf(l))
        val read = f.readAll()
        // Two files are kept: the last 3 + 3 lines
        assertEquals(lines.takeLast(read.size), read)
        assertTrue(read.size in 4..6)
        assertTrue(f.bytes() <= 200)
        assertTrue(dir.resolve("events.1.jsonl").isFile)

        f.append(listOf("{\"up\":99}", "{\"up\":100}"))
        assertEquals("{\"up\":100}", f.readAll().last())

        f.clear()
        assertEquals(emptyList<String>(), f.readAll())
        assertEquals(0L, f.bytes())
    }
}
