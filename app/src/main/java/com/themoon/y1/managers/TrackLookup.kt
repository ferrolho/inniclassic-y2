package com.themoon.y1.managers

import com.themoon.y1.MainActivity
import com.themoon.y1.models.SongItem

/**
 * Finds a library item by its file path in O(1). The map is made again when a list changed: another
 * size, or another first or last item (a cache load or a scan puts new items into the lists).
 */
internal class LibraryPathIndex<T : Any>(private val pathOf: (T) -> String) {
    private val map = HashMap<String, T>()
    private var stamp: Array<Any?> = emptyArray()

    fun find(path: String, lists: List<List<T>>): T? {
        val now = stampOf(lists)
        if (!sameStamp(now)) {
            rebuild(lists)
            stamp = now
        }
        return map[path]
    }

    private fun stampOf(lists: List<List<T>>): Array<Any?> {
        val s = arrayOfNulls<Any?>(lists.size * 3)
        for ((i, list) in lists.withIndex()) {
            synchronized(list) {
                s[i * 3] = list.size
                s[i * 3 + 1] = list.firstOrNull()
                s[i * 3 + 2] = list.lastOrNull()
            }
        }
        return s
    }

    private fun sameStamp(now: Array<Any?>): Boolean {
        if (now.size != stamp.size) return false
        for (i in now.indices) {
            // Sizes by value, items by identity
            if (i % 3 == 0) {
                if (now[i] != stamp[i]) return false
            } else if (now[i] !== stamp[i]) {
                return false
            }
        }
        return true
    }

    private fun rebuild(lists: List<List<T>>) {
        map.clear()
        for (list in lists) {
            synchronized(list) {
                // The first item of a path wins, as with a search from the start of the lists
                for (item in list) {
                    val key = pathOf(item)
                    if (!map.containsKey(key)) map[key] = item
                }
            }
        }
    }
}

/** The songs and audiobooks of the library (Scan Media Library) by file path. Main thread. */
object LibrarySongs {
    private val index = LibraryPathIndex<SongItem> { it.file.absolutePath }

    fun byPath(path: String): SongItem? =
        index.find(path, listOf(MainActivity.customLibrary, MainActivity.audiobookLibrary))
}

/**
 * Gives each track change a ticket, so that a cover which a worker thread reads late shows only
 * for the track that is still loaded (a fast run of skips does not show an old cover).
 */
internal class TrackArtRequests {
    class Ticket(val path: String)

    @Volatile
    private var current: Ticket? = null

    /** A new track: the tickets of older tracks are stale. */
    fun start(path: String): Ticket = Ticket(path).also { current = it }

    /** A track change with no cover to read: all tickets are stale. */
    fun cancel() {
        current = null
    }

    /** True while [ticket] is the newest one and its cover did not show yet. Any thread. */
    fun isCurrent(ticket: Ticket): Boolean = current === ticket

    /** True while the cover of the newest track is still loading. */
    val isLoading: Boolean get() = current != null

    /** The cover of [ticket] is ready: true if it must show. Then no cover is loading. */
    fun finish(ticket: Ticket): Boolean {
        if (current !== ticket) return false
        current = null
        return true
    }
}
