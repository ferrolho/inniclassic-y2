package com.themoon.y1.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterJumpTest {

    @Test
    fun letterOf_normalisesTheFirstLetter() {
        assertEquals("A", LetterJump.letterOf("abba"))
        assertEquals("E", LetterJump.letterOf("Émile Simon"))
        assertEquals("Ø", LetterJump.letterOf("  ørn")) // Ø has no base letter in Unicode
        assertEquals("C", LetterJump.letterOf("Ça Va"))
        assertEquals("Е", LetterJump.letterOf("Ёлка")) // Cyrillic
        assertEquals("ㄱ", LetterJump.letterOf("가을"))
        assertEquals("ㄱ", LetterJump.letterOf("까치")) // double consonants group with the single one
    }

    @Test
    fun letterOf_digitsAndSymbolsAreHash() {
        assertEquals("#", LetterJump.letterOf("2Pac"))
        assertEquals("#", LetterJump.letterOf("(What's the Story) Morning Glory?"))
        assertEquals("#", LetterJump.letterOf("!!!"))
        assertNull(LetterJump.letterOf("   "))
        assertNull(LetterJump.letterOf(null))
    }

    @Test
    fun letterOf_ignoresLeadingThe() {
        assertEquals("W", LetterJump.letterOf("The Who"))
        assertEquals("W", LetterJump.letterOf("the who"))
        assertEquals("T", LetterJump.letterOf("The Who", skipThe = false))
        assertEquals("T", LetterJump.letterOf("Them"))
        assertEquals("T", LetterJump.letterOf("The"))
    }

    @Test
    fun lettersFor_ignoresTheWhenTheRowsAreSortedThatWay() {
        val rows = listOf("Abba", "The Beatles", "Coldplay", "Daft Punk")
        assertEquals(listOf("A", "B", "C", "D"), LetterJump.lettersFor(rows))
    }

    @Test
    fun lettersFor_keepsTheWhenTheRowsAreSortedWithIt() {
        val rows = listOf("Abba", "Coldplay", "Television", "The Beatles", "The Who", "Thin Lizzy", "U2")
        assertEquals(listOf("A", "C", "T", "T", "T", "T", "U"), LetterJump.lettersFor(rows))
    }

    @Test
    fun lettersFor_specialRowsHaveNoLetter() {
        val rows = listOf(null, "Abba", "Blur", "Cream")
        assertEquals(listOf(null, "A", "B", "C"), LetterJump.lettersFor(rows))
    }

    @Test
    fun lettersFor_allowsAFewRowsOutOfOrder() {
        val rows = ('A'..'Y').map { "$it song" } + "Émile"
        assertNotNull(LetterJump.lettersFor(rows))
    }

    @Test
    fun lettersFor_rejectsListsThatAreNotAlphabetical() {
        // An album in track order.
        val album = listOf("Speak to Me", "Breathe", "On the Run", "Time", "The Great Gig in the Sky", "Money", "Us and Them")
        assertNull(LetterJump.lettersFor(album))
        // Years: all "#", nothing to jump between.
        assertNull(LetterJump.lettersFor(listOf("1999", "2004", "2016")))
    }

    @Test
    fun target_jumpsToTheFirstRowOfTheNextLetter() {
        val letters = listOf(null, "A", "A", "B", "B", "B", "D")
        assertEquals(3, LetterJump.target(letters, 1, 1))
        assertEquals(3, LetterJump.target(letters, 2, 1))
        assertEquals(6, LetterJump.target(letters, 4, 1))
        assertEquals(-1, LetterJump.target(letters, 6, 1))
        assertEquals(1, LetterJump.target(letters, 0, 1)) // from "All Songs" to the first letter
    }

    @Test
    fun target_jumpsToTheFirstRowOfThePreviousLetter() {
        val letters = listOf(null, "A", "A", "B", "B", "B", "D")
        assertEquals(3, LetterJump.target(letters, 6, -1))
        assertEquals(1, LetterJump.target(letters, 5, -1))
        assertEquals(1, LetterJump.target(letters, 3, -1))
        assertEquals(-1, LetterJump.target(letters, 2, -1)) // "All Songs" is not a letter
        assertEquals(-1, LetterJump.target(letters, 9, 1))
    }

    @Test
    fun wheelSpeed_isFastAfterFourQuickStepsTheSameWay() {
        val speed = LetterJump.WheelSpeed()
        assertFalse(speed.onStep(1, 0))
        assertFalse(speed.onStep(1, 60))
        assertFalse(speed.onStep(1, 120))
        assertTrue(speed.onStep(1, 180))
        assertTrue(speed.onStep(1, 280)) // a gap of exactly 100 ms is still fast
    }

    @Test
    fun wheelSpeed_slowStepOrDirectionChangeEndsFastTurning() {
        val speed = LetterJump.WheelSpeed()
        for (t in 0L..150L step 50) speed.onStep(1, t)
        assertTrue(speed.onStep(1, 200))
        assertFalse(speed.onStep(1, 350)) // 150 ms gap: back to row by row
        assertFalse(speed.onStep(-1, 380)) // the other way starts again
        assertFalse(speed.onStep(-1, 400))
        assertFalse(speed.onStep(-1, 420))
        assertTrue(speed.onStep(-1, 440))
    }

    @Test
    fun wheelSpeed_steadyTurningIsNotFast() {
        val speed = LetterJump.WheelSpeed()
        for (t in 0L..2000L step 120) assertFalse(speed.onStep(1, t))
    }
}
