package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSourcesTest {

    private var radio = false
    private var music = false
    private var radioStops = 0
    private var musicPauses = 0

    private val sources = AudioSources(
        radioOn = { radio },
        stopRadio = { radioStops++; radio = false },
        musicPlaying = { music },
        pauseMusic = { musicPauses++; music = false }
    )

    @Test
    fun musicThatStartsTurnsTheRadioOff() {
        radio = true
        sources.musicStarts()
        assertFalse(radio)
        assertEquals(1, radioStops)
        assertEquals(0, musicPauses)
    }

    @Test
    fun musicThatStartsWithTheRadioOffChangesNothing() {
        sources.musicStarts()
        assertEquals(0, radioStops)
        assertEquals(0, musicPauses)
    }

    @Test
    fun radioThatStartsPausesTheMusic() {
        music = true
        sources.radioStarts()
        assertFalse(music)
        assertEquals(1, musicPauses)
        assertEquals(0, radioStops)
    }

    @Test
    fun radioThatStartsWithTheMusicPausedChangesNothing() {
        sources.radioStarts()
        assertEquals(0, musicPauses)
    }

    @Test
    fun videoThatStartsStopsTheRadioAndPausesTheMusic() {
        // Not possible from the screens, but each start makes the new source the only one
        radio = true
        music = true
        sources.videoStarts()
        assertFalse(radio)
        assertFalse(music)
        assertEquals(1, radioStops)
        assertEquals(1, musicPauses)
    }

    @Test
    fun onlyOneSourceAfterEachStart() {
        // The bug of 2026-10-29: radio on, then Play
        sources.radioStarts(); radio = true
        sources.musicStarts(); music = true
        assertTrue(music && !radio)
        // And back to the radio
        sources.radioStarts(); radio = true
        assertTrue(radio && !music)
    }
}
