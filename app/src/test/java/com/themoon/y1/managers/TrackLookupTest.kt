package com.themoon.y1.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLookupTest {

    private class Item(val path: String, val title: String)

    private fun index() = LibraryPathIndex<Item> { it.path }

    @Test
    fun findsAnItemByPathInAnyList() {
        val music = arrayListOf(Item("/m/a.mp3", "A"), Item("/m/b.mp3", "B"))
        val books = arrayListOf(Item("/b/c.m4b", "C"))
        val idx = index()
        assertEquals("B", idx.find("/m/b.mp3", listOf(music, books))?.title)
        assertEquals("C", idx.find("/b/c.m4b", listOf(music, books))?.title)
        assertNull(idx.find("/m/missing.mp3", listOf(music, books)))
    }

    @Test
    fun theFirstItemOfAPathWins() {
        val music = arrayListOf(Item("/x.mp3", "music"))
        val books = arrayListOf(Item("/x.mp3", "book"))
        assertEquals("music", index().find("/x.mp3", listOf(music, books))?.title)
    }

    @Test
    fun seesAnAddedOrRemovedItem() {
        val music = arrayListOf(Item("/a.mp3", "A"))
        val idx = index()
        assertNull(idx.find("/b.mp3", listOf(music)))
        music.add(Item("/b.mp3", "B"))
        assertEquals("B", idx.find("/b.mp3", listOf(music))?.title)
        music.removeAt(1)
        assertNull(idx.find("/b.mp3", listOf(music)))
    }

    @Test
    fun seesAReloadWithTheSameSize() {
        // A cache load or a scan replaces the items: the same paths and size, new tags
        val music = arrayListOf(Item("/a.mp3", "old A"), Item("/b.mp3", "old B"))
        val idx = index()
        assertEquals("old B", idx.find("/b.mp3", listOf(music))?.title)
        val reloaded = listOf(Item("/a.mp3", "new A"), Item("/b.mp3", "new B"))
        music.clear()
        music.addAll(reloaded)
        assertEquals("new B", idx.find("/b.mp3", listOf(music))?.title)
    }

    @Test
    fun keepsTheMapWhileTheListsDoNotChange() {
        val item = Item("/a.mp3", "A")
        val music = arrayListOf(item)
        val idx = index()
        assertSame(item, idx.find("/a.mp3", listOf(music)))
        assertSame(item, idx.find("/a.mp3", listOf(music)))
    }

    @Test
    fun onlyTheNewestTicketShowsItsCover() {
        val requests = TrackArtRequests()
        val first = requests.start("/a.mp3")
        assertTrue(requests.isLoading)
        assertTrue(requests.isCurrent(first))

        // A fast skip: the cover of the first track comes after the second track started
        val second = requests.start("/b.mp3")
        assertFalse(requests.isCurrent(first))
        assertFalse(requests.finish(first))
        assertTrue(requests.isLoading)

        assertTrue(requests.finish(second))
        assertFalse(requests.isLoading)
        // A result that comes twice shows once
        assertFalse(requests.finish(second))
    }

    @Test
    fun cancelMakesAllTicketsStale() {
        val requests = TrackArtRequests()
        val ticket = requests.start("/a.mp3")
        requests.cancel()
        assertFalse(requests.isLoading)
        assertFalse(requests.isCurrent(ticket))
        assertFalse(requests.finish(ticket))
    }

    @Test
    fun theSameFileAgainIsANewTicket() {
        // Repeat One or Previous on the first track loads the same file again
        val requests = TrackArtRequests()
        val first = requests.start("/a.mp3")
        val again = requests.start("/a.mp3")
        assertFalse(requests.finish(first))
        assertTrue(requests.finish(again))
    }

    @Test
    fun theQueueStampChangesOnlyWithTheQueue() {
        val apm = AudioPlayerManager.getInstance()
        val queue = arrayListOf(java.io.File("/a.mp3"), java.io.File("/b.mp3"), java.io.File("/c.mp3"))
        val original = ArrayList(queue)
        val stamp = apm.queueStamp(queue, original)
        assertEquals(stamp, apm.queueStamp(ArrayList(queue), ArrayList(original)))
        // Another order (shuffle), another track, a shorter queue
        assertFalse(stamp == apm.queueStamp(arrayListOf(queue[1], queue[0], queue[2]), original))
        assertFalse(stamp == apm.queueStamp(arrayListOf(queue[0], queue[1], java.io.File("/d.mp3")), original))
        assertFalse(stamp == apm.queueStamp(queue.subList(0, 2), original))
    }
}
