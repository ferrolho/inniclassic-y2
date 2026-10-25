package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.themoon.y1.managers.VolumeSteps.IndexChange
import kotlin.math.log10

class VolumeStepsTest {

    private val curve = VolumeCurves.A2DP

    /** The level of a step: dB of the Android index plus the software gain in dB. */
    private fun levelDb(step: Int): Double {
        val s = VolumeSteps.settingFor(step, curve)
        return curve.dbAt(s.index) + 20.0 * log10(s.gain.toDouble())
    }

    private class MapStore(val map: MutableMap<String, Int> = HashMap()) : VolumeStore {
        override fun contains(key: String) = map.containsKey(key)
        override fun getInt(key: String, default: Int) = map[key] ?: default
        override fun putInt(key: String, value: Int) {
            map[key] = value
        }
    }

    @Test
    fun stepZeroIsMute() {
        assertEquals(VolumeSteps.Setting(0, 0f), VolumeSteps.settingFor(0, curve))
        assertEquals(VolumeSteps.Setting(0, 0f), VolumeSteps.settingFor(-3, curve))
    }

    @Test
    fun targetsAreEqualStepsFromTop() {
        assertEquals(0.0, VolumeSteps.targetDb(30), 1e-9)
        assertEquals(-58.0, VolumeSteps.targetDb(1), 1e-9)
        for (s in 2..30) assertEquals(2.0, VolumeSteps.targetDb(s) - VolumeSteps.targetDb(s - 1), 1e-9)
    }

    @Test
    fun eachStepIsTwoDbAboveTheStepBelow() {
        for (s in 1..30) assertEquals("step $s", VolumeSteps.targetDb(s), levelDb(s), 1e-4)
        for (s in 2..30) {
            assertEquals("step $s", 2.0, levelDb(s) - levelDb(s - 1), 1e-4)
            assertTrue("step $s louder than ${s - 1}", levelDb(s) > levelDb(s - 1))
        }
    }

    @Test
    fun hardwareDoesMostOfTheAttenuation() {
        for (s in 1..30) {
            val setting = VolumeSteps.settingFor(s, curve)
            assertTrue(setting.gain in 0f..1f)
            val target = VolumeSteps.targetDb(s)
            assertTrue("index of step $s is not quieter than the target", curve.dbAt(setting.index) >= target - 1e-9)
            if (setting.index > 1) {
                assertTrue("a quieter index also reaches step $s", curve.dbAt(setting.index - 1) < target)
            }
        }
    }

    @Test
    fun mappingForMeasuredA2dpTable() {
        assertEquals(VolumeSteps.Setting(15, 1f), VolumeSteps.settingFor(30, curve))
        assertEquals(VolumeSteps.Setting(14, 1f), VolumeSteps.settingFor(29, curve))
        assertEquals(VolumeSteps.Setting(13, 1f), VolumeSteps.settingFor(28, curve))
        assertEquals(11, VolumeSteps.settingFor(25, curve).index) // -10 dB: index 11 (-8.5) - 1.5 dB
        assertEquals(12, VolumeSteps.settingFor(26, curve).index) // -8 dB: index 11 (-8.5) is too quiet
        assertEquals(VolumeSteps.Setting(12, 1f), VolumeSteps.settingFor(27, curve))
        assertEquals(1, VolumeSteps.settingFor(9, curve).index) // -42 dB
        assertEquals(1f, VolumeSteps.settingFor(9, curve).gain, 1e-6f)
        assertEquals(2, VolumeSteps.settingFor(10, curve).index) // -40 dB: index 2 (-39) - 1 dB
        val step1 = VolumeSteps.settingFor(1, curve) // -58 dB: index 1 (-42) - 16 dB
        assertEquals(1, step1.index)
        assertEquals(0.1585f, step1.gain, 1e-4f)
        // No two steps share the same setting.
        assertEquals(30, (1..30).map { VolumeSteps.settingFor(it, curve) }.toSet().size)
    }

    @Test
    fun indexIsLimitedToTheSystemMaximum() {
        val s = VolumeSteps.settingFor(30, curve, sysMaxIndex = 10)
        assertEquals(10, s.index)
        assertEquals(1f, s.gain, 1e-6f)
    }

    @Test
    fun stepForIndexIsNotLouderThanTheIndex() {
        assertEquals(0, VolumeSteps.stepForIndex(0, curve))
        assertEquals(30, VolumeSteps.stepForIndex(15, curve))
        assertEquals(25, VolumeSteps.stepForIndex(11, curve)) // -8.5 dB → -10 dB
        assertEquals(9, VolumeSteps.stepForIndex(1, curve)) // -42 dB
        for (i in 1..15) assertTrue(VolumeSteps.targetDb(VolumeSteps.stepForIndex(i, curve)) <= curve.dbAt(i))
    }

    @Test
    fun clampKeepsTheLimit() {
        assertEquals(20, VolumeSteps.clamp(25, 20))
        assertEquals(15, VolumeSteps.clamp(15, 20))
        assertEquals(30, VolumeSteps.clamp(31, -1))
        assertEquals(0, VolumeSteps.clamp(-1, 20))
    }

    @Test
    fun outputPriority() {
        assertEquals(AudioOutput.WIRED, AudioOutput.current(wiredHeadsetOn = true, bluetoothA2dpOn = true))
        assertEquals(AudioOutput.A2DP, AudioOutput.current(wiredHeadsetOn = false, bluetoothA2dpOn = true))
        assertEquals(AudioOutput.SPEAKER, AudioOutput.current(wiredHeadsetOn = false, bluetoothA2dpOn = false))
    }

    @Test
    fun eachOutputRemembersItsOwnStep() {
        val memory = VolumeMemory(MapStore())
        memory.save(AudioOutput.WIRED, 12)
        memory.save(AudioOutput.A2DP, 24)
        assertEquals(12, memory.stepFor(AudioOutput.WIRED) { error("not used") })
        assertEquals(24, memory.stepFor(AudioOutput.A2DP) { error("not used") })
        memory.save(AudioOutput.WIRED, 0)
        assertEquals(0, memory.stepFor(AudioOutput.WIRED) { error("not used") })
        assertEquals(24, memory.stepFor(AudioOutput.A2DP) { error("not used") })
    }

    @Test
    fun firstUseOfAnOutputTakesTheAndroidIndex() {
        val store = MapStore()
        val memory = VolumeMemory(store)
        assertEquals(26, memory.stepFor(AudioOutput.SPEAKER) { 11 }) // DAC index 11 = -8 dB
        assertEquals(26, store.map[AudioOutput.SPEAKER.prefKey])
        assertEquals(25, memory.stepFor(AudioOutput.A2DP) { 11 }) // A2DP index 11 = -8.5 dB → -10 dB
        assertFalse(store.contains(AudioOutput.WIRED.prefKey))
    }

    @Test
    fun savedStepIsClampedToTheLimit() {
        val store = MapStore(mutableMapOf(VolumeSteps.PREF_VERSION to 2, VolumeSteps.PREF_LIMIT to 20))
        val memory = VolumeMemory(store)
        memory.save(AudioOutput.A2DP, 28)
        assertEquals(20, memory.stepFor(AudioOutput.A2DP) { 15 })
    }

    @Test
    fun migratesTheOldIndexLimitToAStep() {
        val store = MapStore(mutableMapOf(VolumeSteps.PREF_LIMIT to 10)) // index 10 = -11 dB
        val memory = VolumeMemory(store)
        memory.migrate()
        assertEquals(24, store.map[VolumeSteps.PREF_LIMIT]) // -12 dB
        assertEquals(VolumeSteps.VERSION, store.map[VolumeSteps.PREF_VERSION])
        memory.migrate() // only once
        assertEquals(24, store.map[VolumeSteps.PREF_LIMIT])
    }

    @Test
    fun migrationKeepsNoLimit() {
        val store = MapStore(mutableMapOf(VolumeSteps.PREF_LIMIT to -1))
        VolumeMemory(store).migrate()
        assertEquals(-1, store.map[VolumeSteps.PREF_LIMIT])
        val empty = MapStore()
        VolumeMemory(empty).migrate()
        assertFalse(empty.contains(VolumeSteps.PREF_LIMIT))
        assertEquals(VolumeSteps.VERSION, empty.map[VolumeSteps.PREF_VERSION])
    }

    @Test
    fun tablesExistForEachOutput() {
        for (o in AudioOutput.values()) {
            val c = VolumeCurves.forOutput(o)
            assertEquals(15, c.maxIndex)
            for (s in 2..30) {
                val a = VolumeSteps.settingFor(s, c)
                val b = VolumeSteps.settingFor(s - 1, c)
                assertTrue(c.dbAt(a.index) + 20 * log10(a.gain.toDouble()) > c.dbAt(b.index) + 20 * log10(b.gain.toDouble()))
            }
        }
    }

    // --- CS43131 table (wired and speaker) ---

    @Test
    fun wiredAndSpeakerUseTheDacTable() {
        val dac = VolumeCurves.WIRED
        assertTrue(VolumeCurves.SPEAKER === dac)
        for (i in 1..15) assertEquals(-2.0 * (15 - i), dac.dbAt(i), 1e-9)
        assertEquals(VolumeSteps.Setting(15, 1f), VolumeSteps.settingFor(30, dac))
        assertEquals(VolumeSteps.Setting(1, 1f), VolumeSteps.settingFor(16, dac)) // -28 dB
        val step1 = VolumeSteps.settingFor(1, dac) // -58 dB: index 1 (-28) - 30 dB
        assertEquals(1, step1.index)
        assertEquals(0.0316f, step1.gain, 1e-4f)
        // Steps 16..30 are exactly one index each, with no software gain.
        for (s in 16..30) assertEquals(VolumeSteps.Setting(s - 15, 1f), VolumeSteps.settingFor(s, dac))
    }

    // --- index schedule (kernel ignores a fast drop of more than one index) ---

    private val dac = VolumeCurves.WIRED

    private fun levelDb(c: IndexChange, curve: VolumeCurve) = curve.dbAt(c.index) + 20.0 * log10(c.gain.toDouble())

    @Test
    fun increaseIsOneWriteAtOnce() {
        assertEquals(listOf(IndexChange(15, 1f, 0)), VolumeSteps.indexSchedule(3, 30, dac, msSinceLastWrite = 0))
        assertEquals(listOf(IndexChange(5, 1f, 0)), VolumeSteps.indexSchedule(0, 20, dac, msSinceLastWrite = 0))
        val same = VolumeSteps.indexSchedule(1, 5, dac, msSinceLastWrite = 0) // gain only
        assertEquals(1, same.size)
        assertEquals(1, same[0].index)
    }

    @Test
    fun largeDropGoesOneIndexAtATime() {
        val plan = VolumeSteps.indexSchedule(15, 20, dac) // index 15 → 5
        assertEquals(listOf(15) + (14 downTo 5).toList(), plan.map { it.index })
        assertEquals(0L, plan[0].delayMs)
        assertEquals(0L, plan[1].delayMs) // the last write was long ago
        for (c in plan.drop(2)) assertEquals(VolumeSteps.MIN_DECREASE_GAP_MS, c.delayMs)
        // The level is the level of the step at once, and stays there.
        for (c in plan) assertEquals(VolumeSteps.targetDb(20), levelDb(c, dac), 1e-4)
        assertEquals(VolumeSteps.settingFor(20, dac), VolumeSteps.Setting(plan.last().index, plan.last().gain))
    }

    @Test
    fun firstDecreaseWaitsForTheGapAfterTheLastWrite() {
        val plan = VolumeSteps.indexSchedule(10, 24, dac, msSinceLastWrite = 50) // 10 → 9
        assertEquals(listOf(10, 9), plan.map { it.index })
        assertEquals(listOf(0L, 170L), plan.map { it.delayMs })
    }

    @Test
    fun muteGoesDownWithGainZero() {
        val plan = VolumeSteps.indexSchedule(3, 0, dac)
        assertEquals(listOf(3, 2, 1, 0), plan.map { it.index })
        for (c in plan) assertEquals(0f, c.gain, 0f)
    }

    @Test
    fun a2dpDropAlsoKeepsTheLevel() {
        val plan = VolumeSteps.indexSchedule(15, 9, curve) // A2DP 0 dB → -42 dB (index 1)
        assertEquals(1, plan.last().index)
        for (c in plan) assertEquals(VolumeSteps.targetDb(9), levelDb(c, curve), 1e-4)
    }

    /**
     * Fast wheel: one step down every [gapMs]. Simulates the app (a new request cancels the writes that wait)
     * and checks that each index decrease is one index, at least MIN_DECREASE_GAP_MS after the previous index
     * write, and that the index ends at the index of the last step.
     */
    private fun simulateWheel(fromStep: Int, steps: Int, gapMs: Long, up: Boolean = false) {
        var index = VolumeSteps.settingFor(fromStep, dac).index
        var lastWrite = -10_000L
        val writes = ArrayList<Pair<Long, Int>>() // time, index
        var pending: List<Pair<Long, IndexChange>> = emptyList()
        var step = fromStep
        fun runUntil(t: Long) {
            for ((at, c) in pending) {
                if (at > t) break
                if (c.index != index) {
                    writes.add(at to c.index)
                    index = c.index
                    lastWrite = at
                }
            }
            pending = pending.filter { it.first > t }
        }
        for (n in 0 until steps) {
            val now = n * gapMs
            runUntil(now)
            step += if (up) 1 else -1
            var at = now
            pending = VolumeSteps.indexSchedule(index, step, dac, msSinceLastWrite = now - lastWrite).map {
                at += it.delayMs
                at to it
            }
            runUntil(now)
        }
        runUntil(Long.MAX_VALUE)
        assertEquals(VolumeSteps.settingFor(step, dac).index, index)
        var prev: Pair<Long, Int>? = null
        for (w in writes) {
            val p = prev
            if (p != null && w.second < p.second) {
                assertEquals("one index at a time", p.second - 1, w.second)
                assertTrue("decrease ${w.first - p.first} ms after the last write", w.first - p.first >= VolumeSteps.MIN_DECREASE_GAP_MS)
            }
            prev = w
        }
    }

    @Test
    fun fastWheelDownNeverDropsTooFast() {
        simulateWheel(fromStep = 30, steps = 12, gapMs = 40)
        simulateWheel(fromStep = 25, steps = 20, gapMs = 150)
        simulateWheel(fromStep = 30, steps = 29, gapMs = 230)
    }

    @Test
    fun fastWheelUpIsAtOnce() {
        simulateWheel(fromStep = 10, steps = 15, gapMs = 30, up = true)
    }
}
