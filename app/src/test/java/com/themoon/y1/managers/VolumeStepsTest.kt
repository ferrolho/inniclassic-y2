package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals(25, memory.stepFor(AudioOutput.SPEAKER) { 11 })
        assertEquals(25, store.map[AudioOutput.SPEAKER.prefKey])
        assertFalse(store.contains(AudioOutput.A2DP.prefKey))
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
}
