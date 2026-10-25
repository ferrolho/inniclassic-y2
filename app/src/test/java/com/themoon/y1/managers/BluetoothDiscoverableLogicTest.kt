package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothDiscoverableLogicTest {

    private val identity: (String) -> String = { it }

    @Test
    fun noTimerGivesNoRemainingTime() {
        assertNull(BluetoothDiscoverableLogic.remainingMs(0L, 5_000L))
    }

    @Test
    fun remainingTimeNeverGoesBelowZero() {
        assertEquals(45_000L, BluetoothDiscoverableLogic.remainingMs(50_000L, 5_000L))
        assertEquals(0L, BluetoothDiscoverableLogic.remainingMs(50_000L, 60_000L))
    }

    @Test
    fun remainingTimeIsRoundedUpToTheNextSecond() {
        assertEquals("2:00", BluetoothDiscoverableLogic.formatRemaining(120_000L))
        assertEquals("1:45", BluetoothDiscoverableLogic.formatRemaining(105_000L))
        assertEquals("1:45", BluetoothDiscoverableLogic.formatRemaining(104_001L))
        assertEquals("0:01", BluetoothDiscoverableLogic.formatRemaining(200L))
        assertEquals("0:00", BluetoothDiscoverableLogic.formatRemaining(0L))
        assertEquals("0:00", BluetoothDiscoverableLogic.formatRemaining(-5L))
    }

    @Test
    fun durationIsTwoMinutes() {
        assertEquals(120, BluetoothDiscoverableLogic.DURATION_S)
        assertEquals("2:00", BluetoothDiscoverableLogic.formatRemaining(BluetoothDiscoverableLogic.DURATION_S * 1000L))
    }

    @Test
    fun discoverableWithoutOurTimerMustTurnOff() {
        assertTrue(BluetoothDiscoverableLogic.mustTurnOff(discoverable = true, timerRunning = false))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = true, timerRunning = true))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = false, timerRunning = false))
        assertFalse(BluetoothDiscoverableLogic.mustTurnOff(discoverable = false, timerRunning = true))
    }

    @Test
    fun rowValueShowsStateAndTimeLeft() {
        assertEquals("Off", BluetoothDiscoverableLogic.value(false, null, identity))
        assertEquals("Off", BluetoothDiscoverableLogic.value(false, 30_000L, identity))
        assertEquals("On", BluetoothDiscoverableLogic.value(true, null, identity))
        assertEquals("On · 1:45", BluetoothDiscoverableLogic.value(true, 105_000L, identity))
    }

    @Test
    fun rowValueIsTranslated() {
        val german: (String) -> String = { mapOf("On" to "Ein", "Off" to "Aus")[it] ?: it }
        assertEquals("Ein · 0:30", BluetoothDiscoverableLogic.value(true, 30_000L, german))
        assertEquals("Aus", BluetoothDiscoverableLogic.value(false, null, german))
    }

    @Test
    fun namePressGivesInnioasisY2ThenTheSavedName() {
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameAfterPress("MT6582", null))
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameAfterPress("Y2", "MT6582"))
        assertEquals("Innioasis Y2", BluetoothDiscoverableLogic.nameAfterPress(null, null))
        assertEquals("MT6582", BluetoothDiscoverableLogic.nameAfterPress("Innioasis Y2", "MT6582"))
    }

    @Test
    fun namePressOnInnioasisY2WithoutSavedNameChangesNothing() {
        assertNull(BluetoothDiscoverableLogic.nameAfterPress("Innioasis Y2", null))
        assertNull(BluetoothDiscoverableLogic.nameAfterPress("Innioasis Y2", ""))
        assertNull(BluetoothDiscoverableLogic.nameAfterPress("Innioasis Y2", "Innioasis Y2"))
    }
}
