package com.themoon.y1.controllers.coverflow

import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.LetterJump

/**
 * iPod speed scrolling in Cover Flow. On a fast wheel turn (the same as in the lists, [LetterJump.WheelSpeed]),
 * the wheel moves through the artist letters of the albums and the letter box shows the letter. The covers do
 * not move. When the wheel slows down, or stops for [SETTLE_DELAY_MS], Cover Flow goes to the first album of
 * that letter.
 */
class CoverFlowLetterJump(private val albums: List<SongItem>, private val ui: Ui) {

    interface Ui {
        fun showLetter(letter: String)
        fun hideLetter()
        /** Goes to album [index]; [direction] is the direction of the last letter step. */
        fun goTo(index: Int, direction: Int)
        fun click()
        fun postDelayed(task: Runnable, delayMs: Long)
        fun cancel(task: Runnable)
    }

    private val wheelSpeed = LetterJump.WheelSpeed()
    private var letters: CoverFlowLetters? = null
    /** The album count that [letters] was made for; -1 = not made. */
    private var lettersSize = -1
    /** The first album of the letter in the box; -1 when there is no letter jump. */
    private var target = -1
    private var direction = 0
    private var letterShown = false
    private val settleTask = Runnable { settle() }

    /**
     * One wheel step ([dir] -1 or 1, at [timeMs]) with album [current] in the centre. Returns true when the step
     * is used here (a letter step, or the end of a letter jump), false when Cover Flow steps one album.
     */
    fun onStep(dir: Int, timeMs: Long, current: Int): Boolean {
        if (!wheelSpeed.onStep(dir, timeMs)) {
            val ending = target >= 0
            if (ending) settle()
            if (letterShown) {
                letterShown = false
                ui.hideLetter()
            }
            return ending
        }
        val index = letters() ?: return false
        val next = index.jump(if (target >= 0) target else current, dir)
        if (next >= 0) {
            target = next
            direction = dir
            ui.click()
        } else if (target < 0) {
            target = current // no letter that way: show the letter of this album
        }
        val letter = index.letterAt(target) ?: return true
        ui.showLetter(letter)
        letterShown = true
        ui.cancel(settleTask)
        ui.postDelayed(settleTask, SETTLE_DELAY_MS)
        return true
    }

    /** Ends a letter jump without a move, and forgets the letters (the album list can change). */
    fun reset() {
        ui.cancel(settleTask)
        target = -1
        letterShown = false
        wheelSpeed.reset()
        letters = null
        lettersSize = -1
    }

    private fun letters(): CoverFlowLetters? {
        if (lettersSize != albums.size) {
            letters = CoverFlowLetters.of(albums)
            lettersSize = albums.size
        }
        return letters
    }

    private fun settle() {
        ui.cancel(settleTask)
        val album = target
        target = -1
        if (album >= 0) ui.goTo(album, direction)
    }

    companion object {
        /** Cover Flow goes to the letter this long after the last wheel step (a fast turn has steps at most 100 ms apart). */
        const val SETTLE_DELAY_MS = 250L
    }
}
