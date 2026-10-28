package com.themoon.y1.testhooks

import com.themoon.y1.testhooks.TestPowerLogic.Snapshot
import com.themoon.y1.testhooks.TestPowerLogic.WakeStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPowerLogicTest {

    private val awake = Snapshot(screenOn = true, fakeScreenOff = false, screenSleeping = false, windowFocus = true)
    private val off = Snapshot(screenOn = false, fakeScreenOff = false, screenSleeping = true, windowFocus = false)
    private val fake = Snapshot(screenOn = true, fakeScreenOff = true, screenSleeping = true, windowFocus = true)

    private fun step(s: Snapshot, elapsed: Long = 0, woke: Boolean = false, onFor: Long = -1, sinceWake: Long = -1) =
        TestPowerLogic.wakeStep(s, elapsed, woke, onFor, sinceWake)

    @Test
    fun awakeAppIsReadyAtOnce() {
        assertEquals(WakeStep.READY, step(awake, onFor = 0))
        // No wake was necessary: no wait for focus (a dialog of the system can have it)
        assertEquals(WakeStep.READY, step(awake.copy(windowFocus = false), onFor = 0))
    }

    @Test
    fun screenOffIsWokenAndWakeIsRepeatedAfterRetryTime() {
        assertEquals(WakeStep.WAKE_SCREEN, step(off))
        assertEquals(WakeStep.WAIT, step(off, elapsed = 300, woke = true, sinceWake = 300))
        assertEquals(WakeStep.WAKE_SCREEN, step(off, elapsed = 1000, woke = true, sinceWake = TestPowerLogic.WAKE_RETRY_MS))
    }

    @Test
    fun fakeScreenOffIsWokenTheAppWay() {
        assertEquals(WakeStep.WAKE_FAKE, step(fake))
        // Real screen off and the overlay: the screen first
        assertEquals(WakeStep.WAKE_SCREEN, step(fake.copy(screenOn = false)))
    }

    @Test
    fun afterWakeWaitForAppSignsUntilGrace() {
        val noSignsYet = awake.copy(screenSleeping = true, windowFocus = false)
        assertEquals(WakeStep.WAIT, step(noSignsYet, elapsed = 400, woke = true, onFor = 100))
        assertEquals(WakeStep.WAIT, step(awake.copy(windowFocus = false), elapsed = 400, woke = true, onFor = 100))
        assertEquals(WakeStep.READY, step(awake, elapsed = 400, woke = true, onFor = 100))
        assertEquals(WakeStep.READY, step(noSignsYet, elapsed = 2000, woke = true, onFor = TestPowerLogic.SETTLE_GRACE_MS))
    }

    @Test
    fun appScreenOffOnItsWayIsWaitedFor() {
        // isScreenSleeping with the screen on: the app's power key can come; wait, also without a wake
        assertEquals(WakeStep.WAIT, step(awake.copy(screenSleeping = true), onFor = 0))
        assertEquals(WakeStep.READY, step(awake.copy(screenSleeping = true), elapsed = 1600, onFor = 1600))
    }

    @Test
    fun wakeTimesOutAfterCap() {
        assertEquals(WakeStep.TIMEOUT, step(off, elapsed = TestPowerLogic.WAKE_TIMEOUT_MS, woke = true, sinceWake = 200))
        assertEquals(WakeStep.TIMEOUT, step(fake, elapsed = TestPowerLogic.WAKE_TIMEOUT_MS, woke = true))
        // Ready wins over the timeout
        assertEquals(WakeStep.READY, step(awake, elapsed = 9000, woke = true, onFor = 50))
        assertTrue(TestPowerLogic.wakeError(off).contains("did not run"))
    }

    @Test
    fun commandsExtendKeepAwakeToAtLeastTenMinutes() {
        val ten = 10 * 60_000L
        assertEquals(1000 + ten, TestPowerLogic.keepAwakeUntil(1000, 0, null, explicit = false))
        // A later command moves the end
        assertEquals(5000 + ten, TestPowerLogic.keepAwakeUntil(5000, 1000 + ten, null, explicit = false))
        // A longer explicit time stays
        val hour = 1000 + 60 * 60_000L
        assertEquals(hour, TestPowerLogic.keepAwakeUntil(5000, hour, null, explicit = false))
    }

    @Test
    fun awakeSetsExactTimeOrStops() {
        assertEquals(1000 + 30 * 60_000L, TestPowerLogic.keepAwakeUntil(1000, 0, 30, explicit = true))
        assertEquals(1000 + 2 * 60_000L, TestPowerLogic.keepAwakeUntil(1000, 1000 + 60 * 60_000L, 2, explicit = true))
        assertEquals(0L, TestPowerLogic.keepAwakeUntil(1000, 99999, 0, explicit = true))
        assertEquals(1000 + 10 * 60_000L, TestPowerLogic.keepAwakeUntil(1000, 0, null, explicit = true))
    }

    @Test
    fun tickRefreshesBacklightUntilEnd() {
        assertEquals(TestPowerLogic.BACKLIGHT_REFRESH_MS, TestPowerLogic.nextTickMs(0, 60_000))
        assertEquals(1500L, TestPowerLogic.nextTickMs(58_500, 60_000))
        assertNull(TestPowerLogic.nextTickMs(60_000, 60_000))
        assertNull(TestPowerLogic.nextTickMs(1000, 0))
    }

    @Test
    fun parseMinutesAcceptsIntLongAndText() {
        assertEquals(TestPowerLogic.DEFAULT_KEEP_AWAKE_MINUTES, TestPowerLogic.parseMinutes(null))
        assertEquals(5, TestPowerLogic.parseMinutes(5))
        assertEquals(5, TestPowerLogic.parseMinutes(5L))
        assertEquals(0, TestPowerLogic.parseMinutes(" 0 "))
        assertEquals(TestPowerLogic.MAX_KEEP_AWAKE_MINUTES, TestPowerLogic.parseMinutes(TestPowerLogic.MAX_KEEP_AWAKE_MINUTES))
        assertNull(TestPowerLogic.parseMinutes(-1))
        assertNull(TestPowerLogic.parseMinutes(TestPowerLogic.MAX_KEEP_AWAKE_MINUTES + 1))
        assertNull(TestPowerLogic.parseMinutes("ten"))
    }

    @Test
    fun keyCommandTimeIsCapped() {
        assertEquals(60L, TestHookLogic.keyDurationMs(60, 1, 150))
        assertEquals(3 * 60L + 2 * 150L, TestHookLogic.keyDurationMs(60, 3, 150))
        assertNull(TestHookLogic.keyDurationError(60, 200, 150))
        assertTrue(TestHookLogic.keyDurationError(8000, 6, 0)!!.contains("limit"))
    }

    @Test
    fun shortBroadcastDataKeepsPower() {
        val json = JSONObject().put("ok", true).put("state", JSONObject().put("x", "y".repeat(100))).toString()
        val power = JSONObject().put("screenOn", true)
        val short = JSONObject(TestHookLogic.broadcastData(json, true, null, "state", "/f.json", max = 10, power = power))
        assertEquals(true, short.getJSONObject("power").getBoolean("screenOn"))
    }
}
