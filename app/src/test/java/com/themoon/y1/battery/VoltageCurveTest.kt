package com.themoon.y1.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VoltageCurveTest {
    @Test
    fun theDefaultCurveReadsBackFromItsFileText() {
        val back = VoltageCurve.parse(VoltageCurve.DEFAULT.format())
        assertNotNull(back)
        for (mV in 3300..4300 step 7) {
            assertEquals(VoltageCurve.DEFAULT.percentAt(mV.toFloat()), back!!.percentAt(mV.toFloat()), 0f)
        }
    }

    @Test
    fun aFileCurveIsUsedBetweenItsPoints() {
        val c = VoltageCurve.parse("# test\n3400 0\n3600 50  # middle\n4200 100\n")!!
        assertEquals(0f, c.percentAt(3300f), 0f)
        assertEquals(25f, c.percentAt(3500f), 0f)
        assertEquals(50f, c.percentAt(3600f), 0f)
        assertEquals(100f, c.percentAt(4300f), 0f)
    }

    @Test
    fun badCurvesAreRefused() {
        assertNull(VoltageCurve.parse(""))
        assertNull(VoltageCurve.parse("3400 0"))
        assertNull(VoltageCurve.parse("3400 5\n4200 100"))
        assertNull(VoltageCurve.parse("3400 0\n4200 90"))
        assertNull(VoltageCurve.parse("3400 0\n3400 50\n4200 100"))
        assertNull(VoltageCurve.parse("3400 0\n3600 60\n3700 50\n4200 100"))
        assertNull(VoltageCurve.parse("3400 0\nabc 50\n4200 100"))
        assertNull(VoltageCurve.parse("2000 0\n4200 100"))
    }
}
