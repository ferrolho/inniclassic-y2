package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickVolumeTest {

    @Test
    fun levelsAreTenDbApart() {
        assertEquals(-30.0, ClickVolume.clickDb(ClickVolume.LOW, AudioOutput.SPEAKER, 15), 1e-9)
        assertEquals(-20.0, ClickVolume.clickDb(ClickVolume.MEDIUM, AudioOutput.SPEAKER, 15), 1e-9)
        assertEquals(-10.0, ClickVolume.clickDb(ClickVolume.HIGH, AudioOutput.SPEAKER, 15), 1e-9)
        assertEquals(0.1f, ClickVolume.clickGain(ClickVolume.MEDIUM, AudioOutput.SPEAKER, 15), 1e-4f)
    }

    @Test
    fun speakerClickDoesNotChangeWithTheMusic() {
        for (step in 0..30) {
            assertEquals(-20.0, ClickVolume.clickDb(ClickVolume.MEDIUM, AudioOutput.SPEAKER, step), 1e-9)
        }
    }

    @Test
    fun headphoneClickIsNotFarAboveQuietMusic() {
        for (output in listOf(AudioOutput.WIRED, AudioOutput.A2DP)) {
            // Step 10 = -40 dB music: the click is at most -34 dB.
            assertEquals(-34.0, ClickVolume.clickDb(ClickVolume.HIGH, output, 10), 1e-9)
            assertEquals(-34.0, ClickVolume.clickDb(ClickVolume.LOW, output, 10), 1e-9)
            // Step 13 = -34 dB music: cap -28 dB, so Low keeps its -30 dB.
            assertEquals(-30.0, ClickVolume.clickDb(ClickVolume.LOW, output, 13), 1e-9)
            for (step in 1..30) {
                val music = VolumeSteps.targetDb(step)
                val click = ClickVolume.clickDb(ClickVolume.HIGH, output, step)
                assertTrue("step $step", click <= maxOf(music + ClickVolume.HEADPHONE_ABOVE_MUSIC_DB, ClickVolume.HEADPHONE_FLOOR_DB) + 1e-9)
            }
        }
    }

    @Test
    fun headphoneClickHasTheLevelWhenMusicIsLoud() {
        assertEquals(-10.0, ClickVolume.clickDb(ClickVolume.HIGH, AudioOutput.WIRED, 30), 1e-9)
        assertEquals(-20.0, ClickVolume.clickDb(ClickVolume.MEDIUM, AudioOutput.WIRED, 20), 1e-9) // music -20 dB
    }

    @Test
    fun headphoneClickStaysAudible() {
        assertEquals(ClickVolume.HEADPHONE_FLOOR_DB, ClickVolume.clickDb(ClickVolume.HIGH, AudioOutput.WIRED, 0), 1e-9)
        assertEquals(ClickVolume.HEADPHONE_FLOOR_DB, ClickVolume.clickDb(ClickVolume.MEDIUM, AudioOutput.A2DP, 1), 1e-9)
    }

    @Test
    fun levelNeverGoesUpWithAQuieterMusicStep() {
        for (level in 0..2) for (step in 1..30) {
            assertTrue(
                ClickVolume.clickDb(level, AudioOutput.WIRED, step - 1) <= ClickVolume.clickDb(level, AudioOutput.WIRED, step) + 1e-9
            )
        }
    }

    @Test
    fun rowCyclesLowMediumHigh() {
        assertEquals(ClickVolume.MEDIUM, ClickVolume.next(ClickVolume.LOW))
        assertEquals(ClickVolume.HIGH, ClickVolume.next(ClickVolume.MEDIUM))
        assertEquals(ClickVolume.LOW, ClickVolume.next(ClickVolume.HIGH))
        assertEquals(ClickVolume.HIGH, ClickVolume.next(7)) // bad value → Medium → next
        assertEquals(ClickVolume.LABELS.size, ClickVolume.LEVEL_DB.size)
    }
}
