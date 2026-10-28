package com.themoon.y1.managers

import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvrcpMetadataLogicTest {

    private val track = AvrcpMetadataLogic.Track("Title", "Artist", "Album", 215_000L)

    @Test
    fun stringsHaveTitleArtistAlbumAndArtistAsAlbumArtist() {
        val s = AvrcpMetadataLogic.strings(track)
        assertEquals("Title", s[MediaMetadataRetriever.METADATA_KEY_TITLE])
        assertEquals("Artist", s[MediaMetadataRetriever.METADATA_KEY_ARTIST])
        assertEquals("Artist", s[MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST])
        assertEquals("Album", s[MediaMetadataRetriever.METADATA_KEY_ALBUM])
        // Key values of Android 4.4 (the MediaTek AVRCP reads key 13 as the artist).
        assertEquals(7, MediaMetadataRetriever.METADATA_KEY_TITLE)
        assertEquals(2, MediaMetadataRetriever.METADATA_KEY_ARTIST)
        assertEquals(13, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        assertEquals(1, MediaMetadataRetriever.METADATA_KEY_ALBUM)
    }

    @Test
    fun durationOnlyWhenKnown() {
        assertEquals(215_000L, AvrcpMetadataLogic.duration(track))
        assertNull(AvrcpMetadataLogic.duration(track.copy(durationMs = 0)))
        assertNull(AvrcpMetadataLogic.duration(track.copy(durationMs = -1)))
    }

    @Test
    fun playState() {
        assertEquals(RemoteControlClient.PLAYSTATE_STOPPED, AvrcpMetadataLogic.playState(false, true))
        assertEquals(RemoteControlClient.PLAYSTATE_PLAYING, AvrcpMetadataLogic.playState(true, true))
        assertEquals(RemoteControlClient.PLAYSTATE_PAUSED, AvrcpMetadataLogic.playState(true, false))
    }

    @Test
    fun artSizeKeepsAspectAndLimitsLargestSide() {
        assertEquals(256 to 256, AvrcpMetadataLogic.artSize(600, 600))
        assertEquals(256 to 128, AvrcpMetadataLogic.artSize(800, 400))
        assertEquals(128 to 256, AvrcpMetadataLogic.artSize(400, 800))
        assertEquals(200 to 100, AvrcpMetadataLogic.artSize(200, 100))
        assertNull(AvrcpMetadataLogic.artSize(0, 100))
    }

    @Test
    fun shortTitleIsCutForTheLog() {
        assertEquals("Title", AvrcpMetadataLogic.shortTitle("Title"))
        val cut = AvrcpMetadataLogic.shortTitle("x".repeat(100))
        assertEquals(40, cut.length)
        assertEquals('…', cut.last())
    }
}
