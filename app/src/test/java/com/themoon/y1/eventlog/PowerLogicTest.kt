package com.themoon.y1.eventlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerLogicTest {

    @Test
    fun cpuBusyPercent() {
        val a = PowerLogic.parseCpu("cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 2 3 4 5")!!
        val b = PowerLogic.parseCpu("cpu  150 0 150 750 150 0 0 0 0 0")!!
        assertEquals(200L, a.busy)
        assertEquals(1000L, a.total)
        assertEquals(50, PowerLogic.busyPct(a, b))
        assertNull(PowerLogic.parseCpu("intr 1 2 3"))
    }

    @Test
    fun processStatWithSpacesInTheName() {
        val stat = "1234 (Binder (2) x) S 1 1 0 0 -1 4194624 100 0 0 0 250 50 0 0 20 0 10 0 300 0 0"
        assertEquals("Binder (2) x" to 300L, PowerLogic.parsePidStat(stat))
        assertNull(PowerLogic.parsePidStat("garbage"))
    }

    @Test
    fun topProcessesByUsedTicks() {
        val before = mapOf(1 to 100L, 2 to 50L, 3 to 10L)
        val now = mapOf(1 to 110L, 2 to 150L, 3 to 10L, 4 to 30L)
        assertEquals(listOf(2 to 100L, 4 to 30L, 1 to 10L), PowerLogic.top(before, now, 3))
    }

    @Test
    fun percentWithOneDecimal() {
        assertEquals(12.5, PowerLogic.pct(125, 1000), 0.0)
        assertEquals(0.0, PowerLogic.pct(5, 0), 0.0)
    }
}
