package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoverFlowLetterJumpTest {

    private class FakeUi : CoverFlowLetterJump.Ui {
        val shown = mutableListOf<String>()
        var hidden = 0
        var wentTo: Pair<Int, Int>? = null
        var clicks = 0
        var pending: Runnable? = null
        override fun showLetter(letter: String) { shown += letter }
        override fun hideLetter() { hidden++ }
        override fun goTo(index: Int, direction: Int) { wentTo = index to direction }
        override fun click() { clicks++ }
        override fun postDelayed(task: Runnable, delayMs: Long) { pending = task }
        override fun cancel(task: Runnable) { if (pending === task) pending = null }
    }

    // A A B B C C D D E E: the first album of each letter is at an even index.
    private val albums = "ABCDE".flatMap { l ->
        (1..2).map { SongItem(File("/Music/$l$it.mp3"), "01", "$l artist", "$l album $it", null, null, null) }
    }

    /** Four steps 50 ms apart from [start]: the fourth is fast. Returns the result of each step. */
    private fun fastTurn(jump: CoverFlowLetterJump, dir: Int, start: Long, current: Int, steps: Int = 4) =
        (0 until steps).map { jump.onStep(dir, start + 50L * it, current) }

    @Test
    fun slowStepsMoveTheCovers() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        for (t in 0L..1000L step 120) assertFalse(jump.onStep(1, t, 0))
        assertTrue(ui.shown.isEmpty())
    }

    @Test
    fun fastTurnMovesThroughTheLettersThenSettlesOnTheFirstAlbum() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        assertEquals(listOf(false, false, false, true), fastTurn(jump, 1, 0, current = 1))
        assertEquals(listOf("B"), ui.shown)
        assertTrue(jump.onStep(1, 200, 1))
        assertTrue(jump.onStep(1, 250, 1))
        assertEquals(listOf("B", "C", "D"), ui.shown)
        assertEquals(3, ui.clicks)
        assertNull(ui.wentTo) // the covers wait for the end of the turn
        ui.pending!!.run() // the wheel stops
        assertEquals(6 to 1, ui.wentTo)
        assertEquals(0, ui.hidden) // the box fades by itself
    }

    @Test
    fun slowStepEndsTheJumpAndHidesTheBox() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, -1, 0, current = 9) // from E back to D
        assertTrue(jump.onStep(-1, 400, 9)) // slow: the step goes to D, not one album further
        assertEquals(6 to -1, ui.wentTo)
        assertEquals(1, ui.hidden)
        assertNull(ui.pending)
        assertFalse(jump.onStep(-1, 600, 6)) // then album by album again
    }

    @Test
    fun noLetterAfterTheLastOneStays() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, 1, 0, current = 9)
        assertEquals(listOf("E"), ui.shown)
        ui.pending!!.run()
        assertEquals(9 to 0, ui.wentTo) // the album in the centre: Cover Flow does not move
    }

    @Test
    fun resetEndsTheJumpWithoutAMove() {
        val ui = FakeUi()
        val jump = CoverFlowLetterJump(albums, ui)
        fastTurn(jump, 1, 0, current = 0)
        jump.reset()
        assertNull(ui.pending)
        assertFalse(jump.onStep(1, 1000, 0))
        assertNull(ui.wentTo)
    }
}
