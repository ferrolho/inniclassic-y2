package com.themoon.y1.managers

import kotlin.math.pow

/** The audio output that plays STREAM_MUSIC. Each output has its own volume step, as in Android. */
enum class AudioOutput(val prefKey: String) {
    A2DP("volume_step_a2dp"),
    WIRED("volume_step_wired"),
    SPEAKER("volume_step_speaker");

    companion object {
        /**
         * The output that plays music now. On Android 4.4, a wired headset takes music off A2DP
         * (AudioService turns A2DP off for media when a headset is plugged in), so wired comes first.
         */
        fun current(wiredHeadsetOn: Boolean, bluetoothA2dpOn: Boolean): AudioOutput = when {
            wiredHeadsetOn -> WIRED
            bluetoothA2dpOn -> A2DP
            else -> SPEAKER
        }
    }
}

/**
 * The attenuation in dB of each Android STREAM_MUSIC index (0..max) for one output.
 * [db] has one value for each index; index 0 is mute and its value is not used.
 */
class VolumeCurve(private val db: DoubleArray) {
    init {
        require(db.size >= 2) { "a curve needs index 0 (mute) and at least index 1" }
        for (i in 2 until db.size) require(db[i] > db[i - 1]) { "dB must increase with the index" }
    }

    val maxIndex: Int get() = db.size - 1

    fun dbAt(index: Int): Double {
        require(index in 1..maxIndex) { "no dB value for index $index" }
        return db[index]
    }
}

/**
 * Measured index→dB tables, one per output. All in one place: replace a TODO-measure table with the
 * values from the device.
 *
 * How to measure: set the index (`adb shell media volume --stream 3 --set N` or the volume keys), then read
 * "stream volumes in dB" for STREAM_MUSIC in `adb shell dumpsys media.audio_flinger` for the active output.
 */
object VolumeCurves {
    private val NOT_USED = Double.NEGATIVE_INFINITY

    /**
     * A2DP, measured on the Y2 on 2026-10-25 (AudioFlinger "stream volumes in dB", STREAM_MUSIC):
     * index 15:0, 14:-2, 13:-4, 12:-6, 11:-8.5, 10:-11, 9:-14, 8:-17, 7:-21, 6:-25, 5:-28, 4:-33,
     * 3:-36, 2:-39, 1:-42, 0:mute.
     */
    val A2DP = VolumeCurve(
        doubleArrayOf(
            NOT_USED, -42.0, -39.0, -36.0, -33.0, -28.0, -25.0, -21.0,
            -17.0, -14.0, -11.0, -8.5, -6.0, -4.0, -2.0, 0.0
        )
    )

    /** TODO-measure: wired headset/headphones. Same as A2DP until we measure it on the device. */
    val WIRED = A2DP

    /** TODO-measure: speaker. Same as A2DP until we measure it on the device. */
    val SPEAKER = A2DP

    fun forOutput(output: AudioOutput): VolumeCurve = when (output) {
        AudioOutput.A2DP -> A2DP
        AudioOutput.WIRED -> WIRED
        AudioOutput.SPEAKER -> SPEAKER
    }
}

/**
 * 30 volume steps, all [STEP_DB] apart: step s (1..30) is [TOP_DB] - (30 - s) × [STEP_DB]; step 0 is mute.
 * For each step, the Android index does most of the attenuation (the quietest index that is not quieter than
 * the step), and a software gain (≤ 1.0, through [VirtualVolumeManager]) does the rest.
 */
object VolumeSteps {
    const val STEPS = 30
    const val TOP_DB = 0.0
    const val STEP_DB = 2.0

    /** Pref with the Volume Limit as a step (1..30); -1 or missing = no limit. Before version 2 it was an Android index. */
    const val PREF_LIMIT = "volume_limit_max"

    /** Pref with the version of the volume prefs. 2 = steps of 2 dB, a step per output. */
    const val PREF_VERSION = "volume_prefs_version"
    const val VERSION = 2

    private const val EPSILON = 1e-9

    data class Setting(val index: Int, val gain: Float)

    fun targetDb(step: Int): Double = TOP_DB - (STEPS - step) * STEP_DB

    /** The Android index and the software gain for [step]. Step 0 (or less) = index 0, gain 0. */
    fun settingFor(step: Int, curve: VolumeCurve, sysMaxIndex: Int = curve.maxIndex): Setting {
        if (step <= 0) return Setting(0, 0f)
        val target = targetDb(step.coerceAtMost(STEPS))
        val maxIndex = minOf(curve.maxIndex, sysMaxIndex).coerceAtLeast(1)
        var index = maxIndex
        for (i in 1..maxIndex) {
            if (curve.dbAt(i) >= target - EPSILON) {
                index = i
                break
            }
        }
        val rest = (target - curve.dbAt(index)).coerceAtMost(0.0)
        return Setting(index, 10.0.pow(rest / 20.0).toFloat())
    }

    /** The loudest step that is not louder than Android [index]: for the first start of an output. */
    fun stepForIndex(index: Int, curve: VolumeCurve): Int {
        if (index <= 0) return 0
        val db = curve.dbAt(index.coerceAtMost(curve.maxIndex))
        var step = 1
        for (s in 1..STEPS) {
            if (targetDb(s) <= db + EPSILON) step = s
        }
        return step
    }

    /** [step] in 0..30, and not above [limitStep] (limit ≤ 0 = no limit). */
    fun clamp(step: Int, limitStep: Int): Int {
        val s = step.coerceIn(0, STEPS)
        return if (limitStep in 1..STEPS) minOf(s, limitStep) else s
    }
}

/** Small key-value store, so that the per-output logic has no Android dependency (prefs in the app). */
interface VolumeStore {
    fun contains(key: String): Boolean
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
}

/** The step of each output, and the migration of the old prefs. */
class VolumeMemory(private val store: VolumeStore) {

    /**
     * Converts the old prefs once. Version 1 had one volume for all outputs (in memory, from the Android index)
     * and the Volume Limit as an Android index (1..15). Version 2 keeps the limit as a step.
     */
    fun migrate() {
        if (store.getInt(VolumeSteps.PREF_VERSION, 1) >= VolumeSteps.VERSION) return
        val oldLimit = store.getInt(VolumeSteps.PREF_LIMIT, -1)
        if (oldLimit > 0) {
            store.putInt(VolumeSteps.PREF_LIMIT, VolumeSteps.stepForIndex(oldLimit, VolumeCurves.A2DP).coerceAtLeast(1))
        }
        store.putInt(VolumeSteps.PREF_VERSION, VolumeSteps.VERSION)
    }

    fun limitStep(): Int = store.getInt(VolumeSteps.PREF_LIMIT, -1)

    /**
     * The saved step of [output]. When the output has no saved step (first start, or the first time this output
     * is used), it is the step of the current Android index of that output, so the volume does not jump.
     */
    fun stepFor(output: AudioOutput, currentIndex: () -> Int): Int {
        val saved = if (store.contains(output.prefKey)) store.getInt(output.prefKey, -1) else -1
        val step = if (saved >= 0) saved else {
            VolumeSteps.stepForIndex(currentIndex(), VolumeCurves.forOutput(output)).also { save(output, it) }
        }
        return VolumeSteps.clamp(step, limitStep())
    }

    fun save(output: AudioOutput, step: Int) {
        store.putInt(output.prefKey, step.coerceIn(0, VolumeSteps.STEPS))
    }
}
