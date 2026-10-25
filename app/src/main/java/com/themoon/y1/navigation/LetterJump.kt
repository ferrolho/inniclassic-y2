package com.themoon.y1.navigation

import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * iPod speed scrolling: when the wheel turns fast in an alphabetical list, the selection jumps to the
 * first row of the next (or previous) letter, and a letter box shows the letter.
 */
object LetterJump {

    /** A step is fast when it and the [FAST_STEPS] - 1 steps before it turn the same way, each at most [FAST_GAP_MS] apart. */
    const val FAST_STEPS = 4
    const val FAST_GAP_MS = 100L

    /** The letter box hides this long after the last letter jump. */
    const val HIDE_DELAY_MS = 600L

    private val HANGUL_INITIALS = "ㄱㄱㄴㄷㄷㄹㅁㅂㅂㅅㅅㅇㅈㅈㅊㅋㅌㅍㅎ"

    /**
     * The index letter of a row: the first letter in upper case without accents ("Émile" → "E"),
     * "#" for a digit or a symbol, the initial consonant for Hangul. With [skipThe], a leading "The " is
     * ignored ("The Who" → "W"). Null for a blank row.
     */
    fun letterOf(text: String?, skipThe: Boolean = true): String? {
        var s = text?.trim() ?: return null
        if (s.isEmpty()) return null
        if (skipThe && s.length > 4 && s.startsWith("The ", ignoreCase = true)) s = s.substring(4).trimStart()
        val cp = s.codePointAt(0)
        if (cp in 0xAC00..0xD7A3) return HANGUL_INITIALS[(cp - 0xAC00) / (21 * 28)].toString()
        if (!Character.isLetter(cp)) return "#"
        val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
        val first = base.codePointAt(0)
        val upper = String(Character.toChars(if (Character.isLetter(first)) first else cp)).uppercase(Locale.ROOT)
        return String(Character.toChars(upper.codePointAt(0)))
    }

    /**
     * The letter of each row, or null when the rows are not in alphabetical order (then the wheel steps
     * row by row). The letters ignore a leading "The " only when that matches the order of the rows.
     */
    fun lettersFor(texts: List<String?>): List<String?>? {
        val skipThe = texts.map { letterOf(it, skipThe = true) }
        val plain = texts.map { letterOf(it, skipThe = false) }
        val letters = if (descents(plain) < descents(skipThe)) plain else skipThe
        val present = letters.filterNotNull()
        // A few rows out of order (for example accented titles sorted after Z) are allowed.
        val alphabetical = present.toSet().size >= 2 && descents(letters) * 10 <= present.size
        return if (alphabetical) letters else null
    }

    /** "#" sorts first, then letters in code point order. */
    private fun rank(letter: String): Int = if (letter == "#") 0 else letter.codePointAt(0)

    private fun descents(letters: List<String?>): Int {
        var count = 0
        var prev: String? = null
        for (l in letters) {
            if (l == null) continue
            if (prev != null && rank(l) < rank(prev)) count++
            prev = l
        }
        return count
    }

    /**
     * The row to jump to from row [from]: the first row of the next letter ([direction] > 0) or of the
     * previous letter ([direction] < 0). Rows without a letter (for example "All Songs") are skipped.
     * -1 when there is no such letter.
     */
    fun target(letters: List<String?>, from: Int, direction: Int): Int {
        if (from !in letters.indices) return -1
        val current = letters[from]
        if (direction > 0) {
            var i = from + 1
            while (i < letters.size && (letters[i] == null || letters[i] == current)) i++
            return if (i < letters.size) i else -1
        }
        var start = from
        if (current != null) while (start > 0 && letters[start - 1] == current) start--
        var j = start - 1
        while (j >= 0 && letters[j] == null) j--
        if (j < 0) return -1
        val previous = letters[j]
        while (j > 0 && letters[j - 1] == previous) j--
        return j
    }

    /** Tells from the times of the wheel steps if the wheel turns fast. */
    class WheelSpeed {
        private val times = ArrayDeque<Long>()
        private var lastDirection = 0

        /** Adds one step ([direction] -1 or 1, at [timeMs]) and returns true if the wheel now turns fast. */
        fun onStep(direction: Int, timeMs: Long): Boolean {
            val dir = Integer.signum(direction)
            val last = times.peekLast()
            if (dir != lastDirection || (last != null && timeMs - last > FAST_GAP_MS)) times.clear()
            lastDirection = dir
            times.addLast(timeMs)
            while (times.size > FAST_STEPS) times.removeFirst()
            return times.size == FAST_STEPS
        }

        fun reset() {
            times.clear()
            lastDirection = 0
        }
    }
}
