package com.themoon.y1.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {

    @Test
    fun shortTextShowsForTheMinimumTime() {
        assertEquals(Notice.MIN_MS, Notice.durationMs("Track removed.", long = false))
        assertEquals(Notice.MIN_MS, Notice.durationMs("", long = false))
    }

    @Test
    fun longerTextShowsLongerUpToTheMaximum() {
        val one = Notice.durationMs("Audio Connected to Living Room Speaker", long = false)
        assertTrue(one > Notice.MIN_MS && one < Notice.MAX_MS)
        assertEquals(Notice.MAX_MS, Notice.durationMs("x".repeat(500), long = false))
    }

    @Test
    fun errorsAndLongMessagesShowAtLeastTheLongTime() {
        assertEquals(Notice.LONG_MIN_MS, Notice.durationMs("Import failed.", long = true))
        assertEquals(Notice.MAX_MS, Notice.durationMs("x".repeat(500), long = true))
    }
}
