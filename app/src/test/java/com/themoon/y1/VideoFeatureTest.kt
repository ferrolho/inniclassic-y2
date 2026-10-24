package com.themoon.y1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFeatureTest {
    @Test
    fun menuHidesOnlyVideosWhenOff() {
        assertFalse(VideoFeature.showsMenuAction("OPEN_VIDEOS", enabled = false))
        assertFalse(VideoFeature.showsMenuAction("VIDEOS", enabled = false))
        assertTrue(VideoFeature.showsMenuAction("OPEN_BROWSER", enabled = false))
        assertTrue(VideoFeature.showsMenuAction("OPEN_PODCASTS", enabled = false))
        assertTrue(VideoFeature.showsMenuAction(null, enabled = false))
        assertTrue(VideoFeature.showsMenuAction("OPEN_VIDEOS", enabled = true))
    }

    @Test
    fun jellyfinSkipsOnlyVideoLibrariesWhenOff() {
        assertFalse(VideoFeature.syncsLibrary("movies", enabled = false))
        assertFalse(VideoFeature.syncsLibrary("tvshows", enabled = false))
        assertTrue(VideoFeature.syncsLibrary("music", enabled = false))
        assertTrue(VideoFeature.syncsLibrary("audiobooks", enabled = false))
        assertTrue(VideoFeature.syncsLibrary("books", enabled = false))
        assertTrue(VideoFeature.syncsLibrary("tvshows", enabled = true))
    }

    @Test
    fun defaultBuildKeepsVideos() {
        // Without -Pvideos=false the app is unchanged.
        assertTrue(VideoFeature.showsMenuAction("OPEN_VIDEOS") == BuildConfig.VIDEOS)
    }
}
