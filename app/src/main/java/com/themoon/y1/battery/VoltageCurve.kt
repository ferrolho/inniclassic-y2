package com.themoon.y1.battery

/**
 * The voltage → real charge curve of the battery, as data: pairs of mV (screen on, light use) and %, with the mV
 * going up and the % going up, from 0 % to 100 %. The app loads it from `files/battery-curve.txt` when that file is
 * there and valid ([parse]), else it uses [DEFAULT]. So a later step can learn the curve of this cell (full charges
 * and discharges, ageing) and write the file, without a change of the code.
 */
class VoltageCurve private constructor(private val mv: IntArray, private val pct: IntArray) {

    fun percentAt(mV: Float): Float {
        if (mV <= mv[0]) return pct[0].toFloat()
        for (i in 1 until mv.size) {
            if (mV <= mv[i]) return pct[i - 1] + (pct[i] - pct[i - 1]) * (mV - mv[i - 1]) / (mv[i] - mv[i - 1])
        }
        return pct.last().toFloat()
    }

    /** The file text of the curve ([parse] reads it). */
    fun format(): String = buildString {
        append("# mV percent (screen on, light use)\n")
        for (i in mv.indices) append(mv[i]).append(' ').append(pct[i]).append('\n')
    }

    companion object {
        /**
         * From the 2026-10-29 charge (charge in from the off point to full), set at the end with the time to off of
         * 2026-10-31 (`records/battery-voltage-guard-2026-10-31.md`).
         */
        val DEFAULT = of(
            3400 to 0, 3440 to 3, 3457 to 5, 3510 to 10, 3575 to 15, 3620 to 20, 3660 to 25, 3690 to 30, 3720 to 35,
            3770 to 40, 3810 to 45, 3835 to 50, 3860 to 55, 3890 to 60, 3910 to 65, 3940 to 70, 3970 to 75,
            4030 to 80, 4090 to 85, 4140 to 90, 4180 to 95, 4200 to 100
        )!!

        /** A curve from points, or null when it is not valid (see [parse]). */
        fun of(vararg points: Pair<Int, Int>): VoltageCurve? {
            if (points.size < 2) return null
            val mv = IntArray(points.size) { points[it].first }
            val pct = IntArray(points.size) { points[it].second }
            if (pct.first() != 0 || pct.last() != 100) return null
            for (i in 1 until mv.size) {
                if (mv[i] <= mv[i - 1] || pct[i] < pct[i - 1]) return null
            }
            if (mv.first() < 3000 || mv.last() > 4500) return null
            return VoltageCurve(mv, pct)
        }

        /**
         * Reads "mV percent" lines (`#` starts a comment). Null when the curve is not valid: fewer than 2 points, not
         * 0 % at the start and 100 % at the end, mV not going up, % going down, or mV outside 3000–4500.
         */
        fun parse(text: String): VoltageCurve? {
            val points = ArrayList<Pair<Int, Int>>()
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size != 2) return null
                val v = parts[0].toIntOrNull() ?: return null
                val p = parts[1].toIntOrNull() ?: return null
                points.add(v to p)
            }
            return of(*points.toTypedArray())
        }
    }
}
