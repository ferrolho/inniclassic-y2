package com.themoon.y1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureVisibilityTest {
    @Test
    fun hiddenOnlyWhenAllButtonsAreHidden() {
        val hidden = setOf("btn_videos", "btn_videos_2")
        assertTrue(FeatureVisibility.isHidden(listOf("btn_videos")) { it in hidden })
        assertTrue(FeatureVisibility.isHidden(listOf("btn_videos", "btn_videos_2")) { it in hidden })
        assertFalse(FeatureVisibility.isHidden(listOf("btn_videos", "btn_other")) { it in hidden })
        assertFalse(FeatureVisibility.isHidden(listOf("btn_podcasts")) { it in hidden })
    }

    @Test
    fun featureWithoutButtonIsNotHidden() {
        assertFalse(FeatureVisibility.isHidden(emptyList()) { true })
    }

    @Test
    fun jellyfinLibrariesMapToTheirFeature() {
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("movies"))
        assertEquals(FeatureVisibility.VIDEOS, FeatureVisibility.featureForLibrary("TvShows"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("books"))
        assertEquals(FeatureVisibility.AUDIOBOOKS, FeatureVisibility.featureForLibrary("audiobooks"))
        assertNull(FeatureVisibility.featureForLibrary("music"))
        assertNull(FeatureVisibility.featureForLibrary(null))
    }

    @Test
    fun missingContextMeansVisible() {
        assertFalse(FeatureVisibility.isHidden(null, FeatureVisibility.AUDIOBOOKS))
        assertFalse(FeatureVisibility.skipsJellyfinLibrary(null, "books"))
    }

    @Test
    fun buildFlagsLeaveOutOnlyTheirFeature() {
        assertEquals(emptySet<String>(), FeatureVisibility.notBuilt(videos = true, musicQuiz = true))
        assertEquals(setOf(FeatureVisibility.VIDEOS), FeatureVisibility.notBuilt(videos = false, musicQuiz = true))
        assertEquals(setOf(FeatureVisibility.MUSIC_QUIZ), FeatureVisibility.notBuilt(videos = true, musicQuiz = false))
        assertEquals(
            setOf(FeatureVisibility.VIDEOS, FeatureVisibility.MUSIC_QUIZ),
            FeatureVisibility.notBuilt(videos = false, musicQuiz = false)
        )
    }

    @Test
    fun menuActionsOfAFeatureThatIsNotBuiltAreRemoved() {
        val notBuilt = FeatureVisibility.notBuilt(videos = false, musicQuiz = false)
        for (action in listOf("OPEN_VIDEOS", "VIDEOS", "open_videos", "OPEN_MUSIC_QUIZ", "EXTRAS")) {
            assertFalse(action, FeatureVisibility.isBuilt(action, notBuilt))
        }
        for (action in listOf("OPEN_BROWSER", "OPEN_AUDIOBOOKS", "OPEN_WEBSERVER", "OPEN_SETTINGS", "UNKNOWN")) {
            assertTrue(action, FeatureVisibility.isBuilt(action, notBuilt))
        }
        assertTrue(FeatureVisibility.isBuilt(null, notBuilt))
    }

    @Test
    fun builtFeaturesStayInTheMenu() {
        val onlyQuizOut = FeatureVisibility.notBuilt(videos = true, musicQuiz = false)
        assertTrue(FeatureVisibility.isBuilt("OPEN_VIDEOS", onlyQuizOut))
        assertFalse(FeatureVisibility.isBuilt("OPEN_MUSIC_QUIZ", onlyQuizOut))
        val all = FeatureVisibility.notBuilt(videos = true, musicQuiz = true)
        assertTrue(FeatureVisibility.isBuilt("OPEN_VIDEOS", all))
        assertTrue(FeatureVisibility.isBuilt("EXTRAS", all))
    }

    @Test
    fun jellyfinLibraryOfAFeatureThatIsNotBuiltIsRemoved() {
        val notBuilt = FeatureVisibility.notBuilt(videos = false, musicQuiz = true)
        assertFalse(FeatureVisibility.isBuilt(FeatureVisibility.featureForLibrary("movies"), notBuilt))
        assertFalse(FeatureVisibility.isBuilt(FeatureVisibility.featureForLibrary("tvshows"), notBuilt))
        assertTrue(FeatureVisibility.isBuilt(FeatureVisibility.featureForLibrary("music"), notBuilt))
        assertTrue(FeatureVisibility.isBuilt(FeatureVisibility.featureForLibrary("books"), notBuilt))
    }

    @Test
    fun defaultBuildHasAllFeatures() {
        // Without -Pvideos=false or -PmusicQuiz=false the app is unchanged.
        assertEquals(BuildConfig.FEATURE_VIDEOS, FeatureVisibility.isBuilt(FeatureVisibility.VIDEOS))
        assertEquals(BuildConfig.FEATURE_MUSIC_QUIZ, FeatureVisibility.isBuilt(FeatureVisibility.MUSIC_QUIZ))
        assertEquals(!BuildConfig.FEATURE_VIDEOS, FeatureVisibility.isHidden(null, FeatureVisibility.VIDEOS))
    }
}
