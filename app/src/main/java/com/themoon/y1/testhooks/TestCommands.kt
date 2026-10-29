package com.themoon.y1.testhooks

import android.content.Intent
import android.os.Handler
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import com.themoon.y1.MainActivity
import com.themoon.y1.eventlog.EventLog
import com.themoon.y1.eventlog.EventLogLogic
import com.themoon.y1.handleMainMenuAction
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.BrowserMode
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.testhooks.TestHookLogic.BrowseType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The commands of the test interface. Each command uses the same entry points as the UI
 * (main menu actions, browser list clicks, the key dispatch path), so the back stack stays as after a
 * real walk. Each one calls `done(error)` once, with null when it worked.
 *
 * Before each command, [TestPower] wakes the screen the app's way (not with a key) and waits until the app is
 * awake (at most 5 s, else an error and the command does not run). Then it keeps the screen on until
 * 10 minutes after the last command.
 *
 * - `state` (default): only read the state.
 * - `awake [--ei minutes <n>]`: keep the screen on for n minutes from now (default 10, 0 = stop now and do
 *   not wake). Other commands extend the time to at least 10 minutes.
 * - `open --es screen <name>`: go to a screen from the main menu (see [TestHookLogic.SCREEN_IDS]).
 * - `browse --es type ALBUM|ARTIST|ALBUM_ARTIST|GENRE|SONGS|FAVORITES [--es value <name>] [--es artist <album artist>]`
 * - `play --es path <file> | --es query <text> [--es from album|songs]`
 * - `seek --ei ms <position>`
 * - `bottom --ei state <n>`: Now Playing centre-cycle state.
 * - `key --ei code <keycode> [--ez long true] [--ei hold <ms>] [--ei count <n>] [--ei gap <ms>]`
 * - `theme --es name <theme>`: the live theme change of Settings → Theme, without the Theme list
 *   (builds with the live theme change only).
 * - `log [--ei lines <n>] [--es since <uptimeMs>] [--ez clear true]`: lines of the event log
 *   (see [com.themoon.y1.eventlog.EventLog]) in place of the state.
 * - `cast --es do search|start|stop|status [--es name <part of the speaker name>]`: Play On (see
 *   [com.themoon.y1.cast.CastManager]); `start` searches first when no search ran yet.
 */
internal class TestCommands(private val main: MainActivity, private val handler: Handler) {

    /** Set by commands that reply with their own data in place of the app state. */
    var reply: JSONObject? = null
        private set

    fun run(cmd: String, intent: Intent, done: (String?) -> Unit) {
        TestPower.commandStarted(main)
        val explicit = cmd == "awake"
        @Suppress("DEPRECATION")
        val minutes = if (explicit) {
            TestPowerLogic.parseMinutes(intent.extras?.get("minutes"))
                ?: return done("awake needs --ei minutes <0..${TestPowerLogic.MAX_KEEP_AWAKE_MINUTES}>")
        } else null
        if (explicit && minutes == 0) {
            TestPower.release(main)
            return done(null)
        }
        TestPower.keepAwake(main, minutes, explicit)
        TestPower.wakeThen(main) { error ->
            when {
                error != null -> done(error)
                explicit -> done(null)
                else -> try {
                    runAwake(cmd, intent, done)
                } catch (t: Throwable) {
                    done("$cmd failed: $t")
                }
            }
        }
    }

    /** The command itself, after the wake. */
    private fun runAwake(cmd: String, intent: Intent, done: (String?) -> Unit) {
        when (cmd) {
            "state" -> done(null)
            "open" -> done(open(intent.getStringExtra("screen")))
            "browse" -> done(browse(intent.getStringExtra("type"), intent.getStringExtra("value"), intent.getStringExtra("artist")))
            "play" -> done(play(intent.getStringExtra("path"), intent.getStringExtra("query"), intent.getStringExtra("from")))
            "seek" -> done(if (intent.hasExtra("ms")) seek(intent.getIntExtra("ms", 0)) else "seek needs --ei ms <position>")
            "bottom" -> done(if (intent.hasExtra("state")) bottom(intent.getIntExtra("state", 0)) else "bottom needs --ei state <n>")
            "key" -> {
                if (!intent.hasExtra("code")) {
                    done("key needs --ei code <keycode>")
                    return
                }
                val long = intent.getBooleanExtra("long", false)
                key(
                    code = intent.getIntExtra("code", 0),
                    long = long,
                    holdMs = intent.getIntExtra("hold", if (long) 1000 else 60).toLong().coerceIn(1L, 8000L),
                    count = intent.getIntExtra("count", 1).coerceIn(1, 200),
                    gapMs = intent.getIntExtra("gap", 150).toLong().coerceIn(0L, 5000L),
                    done = done
                )
            }
            "theme" -> done(theme(intent.getStringExtra("name")))
            "log" -> done(log(intent))
            "cast" -> cast(intent.getStringExtra("do") ?: "status", intent.getStringExtra("name"), done)
            else -> done("unknown cmd '$cmd'; use state|open|browse|play|seek|bottom|key|theme|log|cast")
        }
    }

    // --- open ---

    private fun open(name: String?): String? {
        val id = TestHookLogic.screenId(name)
            ?: return "unknown screen '${name.orEmpty()}'; use one of ${TestHookLogic.SCREEN_IDS}"
        if (id == "NowPlaying" && main.currentPlaylist.isEmpty()) return "nothing is loaded in the player"
        main.changeScreen(MainActivity.STATE_MENU) // clears the back stack: each screen opens from the main menu
        when (id) {
            "MainMenu" -> {}
            "Music" -> main.handleMainMenuAction("MUSIC")
            "NowPlaying" -> main.handleMainMenuAction("OPEN_PLAYER")
            "Settings" -> main.handleMainMenuAction("SETTINGS")
            "CoverFlow" -> main.handleMainMenuAction("OPEN_COVER_FLOW")
            "Videos" -> main.handleMainMenuAction("VIDEOS")
            "Podcasts" -> main.handleMainMenuAction("PODCASTS")
            "Audiobooks" -> main.handleMainMenuAction("OPEN_AUDIOBOOKS")
            "Extras" -> main.handleMainMenuAction("EXTRAS")
            "Radio" -> main.handleMainMenuAction("RADIO")
            "Upload" -> main.handleMainMenuAction("WEBSERVER")
            "Bluetooth" -> main.handleMainMenuAction("BLUETOOTH")
            "Wifi" -> main.handleMainMenuAction("WIFI")
            "PlayOn" -> main.handleMainMenuAction("OPEN_PLAY_ON")
            "Themes" -> main.handleMainMenuAction("OPEN_THEME_SETTINGS")
            "Playlists" -> {
                // Music -> Playlists, as the browser row does
                main.handleMainMenuAction("MUSIC")
                main.changeScreen(ScreenState.Playlists)
            }
            "About", "Brightness", "Equalizer" -> {
                // Settings -> <utility screen>, as the settings rows do
                main.handleMainMenuAction("SETTINGS")
                main.backTargetForUtility = MainActivity.STATE_SETTINGS
                main.changeScreen(
                    when (id) {
                        "About" -> ScreenState.Storage
                        "Brightness" -> ScreenState.Brightness
                        else -> ScreenState.Equalizer
                    }
                )
            }
            else -> return "screen '$id' is not implemented"
        }
        return null
    }

    // --- browse ---

    private fun library(): List<SongItem> = synchronized(MainActivity.customLibrary) { ArrayList(MainActivity.customLibrary) }

    private fun openMusicRoot(): String? {
        if (!main.isBrowserControllerInitialized()) return "browser is not ready"
        if (main.isCustomScanning) return "library scan is running"
        main.changeScreen(MainActivity.STATE_MENU)
        main.handleMainMenuAction("MUSIC")
        return null
    }

    private fun browse(typeName: String?, rawValue: String?, rawArtist: String?): String? {
        val type = TestHookLogic.browseType(typeName)
            ?: return "unknown type '${typeName.orEmpty()}'; use ALBUM|ARTIST|ALBUM_ARTIST|GENRE|SONGS|FAVORITES"
        val value = rawValue?.trim().orEmpty()
        val artist = rawArtist?.trim().orEmpty()
        openMusicRoot()?.let { return it }
        val bc = main.browserController
        val songs = library()

        // Same field changes as the Music rows and the list clicks (BrowserController, CategoryListAdapter)
        when (type) {
            BrowseType.ALBUM -> {
                if (value.isEmpty()) {
                    bc.currentBrowserMode = BrowserMode.Albums()
                    bc.virtualQueryValue = ""
                    bc.categoryArtistFilter = ""
                    bc.categoryArtistFilterIsTrackArtist = false
                    bc.buildVirtualCategories("ALBUM")
                    return null
                }
                val candidates = if (artist.isEmpty()) songs else songs.filter { it.albumArtist.equals(artist, ignoreCase = true) || it.artist.equals(artist, ignoreCase = true) }
                val album = TestHookLogic.resolveName(candidates.map { it.album }, value)
                    ?: return "no album '$value'" + (if (artist.isEmpty()) "" else " by '$artist'")
                val albumArtist = if (artist.isEmpty()) "" else candidates.firstOrNull { it.album == album }?.albumArtist ?: artist
                openAlbum(album, albumArtist)
                if (main.virtualSongList.isEmpty()) return "album '$album' has no songs in the Music list"
            }
            BrowseType.ARTIST, BrowseType.ALBUM_ARTIST -> {
                val byTrackArtist = type == BrowseType.ARTIST
                if (value.isEmpty()) {
                    bc.currentBrowserMode = if (byTrackArtist) BrowserMode.TrackArtists else BrowserMode.Artists
                    bc.virtualQueryValue = ""
                    bc.categoryArtistFilter = ""
                    bc.categoryArtistFilterIsTrackArtist = false
                    bc.buildVirtualCategories(if (byTrackArtist) "TRACK_ARTIST" else "ARTIST")
                    return null
                }
                val name = TestHookLogic.resolveName(songs.map { if (byTrackArtist) it.artist else it.albumArtist }, value)
                    ?: return "no ${if (byTrackArtist) "artist" else "album artist"} '$value'"
                bc.categoryArtistFilter = name
                bc.categoryArtistFilterIsTrackArtist = byTrackArtist
                bc.virtualQueryValue = ""
                bc.currentBrowserMode = BrowserMode.Albums(name, byTrackArtist)
                bc.buildVirtualCategories("ALBUM")
            }
            BrowseType.GENRE -> {
                if (value.isEmpty()) {
                    bc.currentBrowserMode = BrowserMode.Genres
                    bc.virtualQueryValue = ""
                    bc.categoryGenreFilter = ""
                    bc.categoryArtistFilter = ""
                    bc.categoryArtistFilterIsTrackArtist = false
                    bc.buildVirtualCategories("GENRE")
                    return null
                }
                val genre = TestHookLogic.resolveName(songs.map { it.genre }, value) ?: return "no genre '$value'"
                bc.categoryGenreFilter = genre
                bc.categoryArtistFilter = ""
                bc.categoryArtistFilterIsTrackArtist = true
                bc.currentBrowserMode = BrowserMode.GenreArtists(genre)
                bc.virtualQueryValue = ""
                bc.buildVirtualCategories("GENRE_ARTIST")
            }
            BrowseType.SONGS -> {
                // No value: Music -> Songs. With a value: the library search result list.
                bc.virtualQueryType = if (value.isEmpty()) "ALL" else "SEARCH"
                bc.virtualQueryValue = value
                bc.currentBrowserMode = BrowserMode.VirtualSongs
                bc.buildVirtualSongs()
                if (main.virtualSongList.isEmpty()) return "no songs" + (if (value.isEmpty()) "" else " match '$value'")
            }
            BrowseType.FAVORITES -> {
                bc.currentBrowserMode = BrowserMode.Favorites
                bc.buildVirtualSongsForFavorites()
            }
        }
        return null
    }

    /** Music -> Albums -> <album>: the album click of CategoryListAdapter. */
    private fun openAlbum(album: String, albumArtist: String) {
        val bc = main.browserController
        bc.currentBrowserMode = BrowserMode.Albums()
        bc.categoryArtistFilter = ""
        bc.categoryArtistFilterIsTrackArtist = false
        bc.virtualQueryType = "ALBUM"
        bc.virtualQueryValue = album
        // Empty album artist = any artist. Builds without the album-grouping fix do not have this field and
        // know albums only by title; there, filter by the artist instead.
        val hasAlbumArtistField = Reflect.setField(bc, "virtualQueryAlbumArtist", albumArtist)
        if (!hasAlbumArtistField && albumArtist.isNotEmpty()) bc.categoryArtistFilter = albumArtist
        bc.currentBrowserMode = BrowserMode.VirtualSongs
        bc.buildVirtualSongs()
    }

    // --- play ---

    private fun play(rawPath: String?, rawQuery: String?, from: String?): String? {
        val songs = library()
        val path = rawPath?.trim().orEmpty()
        val query = rawQuery?.trim().orEmpty()
        val song: SongItem?
        val file: File
        when {
            path.isNotEmpty() -> {
                song = songs.firstOrNull { it.file.absolutePath == path }
                file = song?.file ?: File(path)
                if (song == null && !file.isFile) return "no file '$path'"
            }
            query.isNotEmpty() -> {
                song = TestHookLogic.findSong(songs, query) ?: return "no song matches '$query'"
                file = song.file
            }
            else -> return "play needs --es path <file> or --es query <text>"
        }

        if (song != null) {
            openMusicRoot()?.let { return it }
            if (TestHookLogic.normalize(from) in setOf("songs", "all", "allsongs")) {
                browse("SONGS", null, null)?.let { return it }
            } else {
                openAlbum(song.album, song.albumArtist)
            }
            val index = main.virtualSongList.indexOfFirst { it.absolutePath == file.absolutePath }
            if (index >= 0) {
                // The song row click of SongListAdapter
                main.virtualListSelectedIndex = index
                AudioPlayerManager.getInstance().playTrackList(main.virtualSongList, index)
                main.changeScreen(MainActivity.STATE_PLAYER)
                return null
            }
        }
        // Not in the Music lists: play the file alone
        AudioPlayerManager.getInstance().playTrackList(arrayListOf(file), 0)
        main.changeScreen(MainActivity.STATE_PLAYER)
        return null
    }

    // --- seek, bottom ---

    private fun seek(ms: Int): String? {
        if (main.currentPlaylist.isEmpty()) return "nothing is loaded in the player"
        val am = AudioPlayerManager.getInstance()
        am.seekRelative(ms - am.getCurrentPosition())
        if (NavigationManager.getInstance().currentState is ScreenState.Player) main.updatePlayerUI()
        return null
    }

    private fun bottom(target: Int): String? {
        if (NavigationManager.getInstance().currentState !is ScreenState.Player) return "Now Playing is not open"
        val pc = main.playerController
        // Press the centre the app's way until the state is there (the cycle order differs between builds)
        var presses = 0
        while (pc.nowPlayingBottomState != target && presses < 8) {
            pc.cycleBottomState()
            presses++
        }
        return if (pc.nowPlayingBottomState == target) null else "bottom state $target is not in the cycle (no lyrics?)"
    }

    // --- theme ---

    /** MainActivity.changeTheme(name) (MainActivityTheme.kt): selects the theme and applies it after this event. */
    private fun theme(name: String?): String? {
        if (name.isNullOrBlank()) return "theme needs --es name <theme>"
        val change = try {
            Class.forName("com.themoon.y1.MainActivityThemeKt").getMethod("changeTheme", MainActivity::class.java, String::class.java)
        } catch (_: Throwable) {
            return "this build has no live theme change"
        }
        val ok = change.invoke(null, main, name.trim()) as? Boolean ?: false
        return if (ok) null else "theme '$name' is not installed"
    }

    // --- log ---

    /**
     * The last `lines` lines (default 100) of the event log, or the lines after uptime `since`. All selected
     * lines go to `<external files>/event-log-tail.jsonl`; the reply has them in `events` only when it stays
     * short enough for the broadcast result.
     */
    // --- cast ---

    private fun cast(action: String, name: String?, done: (String?) -> Unit) {
        val cm = com.themoon.y1.cast.CastManager
        fun devices(list: List<com.themoon.y1.cast.CastDevice>) = JSONArray().also { a ->
            for (d in list) a.put(JSONObject().put("name", d.name).put("kind", d.kind.name).put("host", d.host).put("port", d.port))
        }
        fun status() = JSONObject().put("active", cm.isActive).put("device", cm.device?.id ?: JSONObject.NULL)
            .put("known", devices(cm.knownDevices))
        when (action) {
            "search" -> cm.search { found -> reply = status().put("found", devices(found)); done(null) }
            "start" -> {
                val start = { list: List<com.themoon.y1.cast.CastDevice> ->
                    val d = list.firstOrNull { name == null || it.name.contains(name, true) || it.kind.name.equals(name, true) }
                    if (d == null) {
                        done("no speaker matches '${name.orEmpty()}'; found: ${list.joinToString { it.rowLabel }}")
                    } else {
                        cm.start(main, d)
                        reply = status()
                        done(null)
                    }
                }
                if (cm.searchIsRecent()) start(cm.knownDevices) else cm.search { start(it) }
            }
            "stop" -> { cm.stop(); reply = status(); done(null) }
            "status" -> { reply = status(); done(null) }
            else -> done("cast needs --es do search|start|stop|status")
        }
    }

    private fun log(intent: Intent): String? {
        if (!EventLog.enabled) return "the event log is off in this build"
        if (intent.getBooleanExtra("clear", false)) {
            EventLog.clear()
            reply = JSONObject().put("cleared", true).put("uptimeMs", SystemClock.uptimeMillis())
            return null
        }
        // --es, --ei or --el
        @Suppress("DEPRECATION")
        val since = when (val v = intent.extras?.get("since")) {
            null -> null
            is Number -> v.toLong()
            else -> v.toString().trim().toLongOrNull() ?: return "since needs a number: --es since <uptimeMs>"
        }
        val all = EventLog.readAll()
        var lines = if (since != null) EventLogLogic.since(all, since) else all
        if (intent.hasExtra("lines") || since == null) {
            lines = EventLogLogic.tail(lines, intent.getIntExtra("lines", 100).coerceIn(1, 20000))
        }
        val out = JSONObject()
            .put("count", lines.size)
            .put("total", all.size)
            .put("uptimeMs", SystemClock.uptimeMillis())
            .put("logDir", EventLog.dir?.absolutePath ?: JSONObject.NULL)
            .put("logBytes", EventLog.bytes())
        try {
            val dir = main.getExternalFilesDir(null)
            if (dir != null) {
                val tail = File(dir, LOG_TAIL_FILE)
                tail.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n"))
                out.put("tailFile", tail.absolutePath)
            }
        } catch (t: Throwable) {
            out.put("tailFileError", t.toString())
        }
        val chars = lines.sumOf { it.length + 1 }
        if (chars <= TestHookLogic.MAX_BROADCAST_CHARS - 1000) {
            val events = JSONArray()
            for (line in lines) events.put(try { JSONObject(line) } catch (_: Exception) { line })
            out.put("events", events)
        } else {
            out.put("inline", false) // too long for the broadcast result: read tailFile
        }
        reply = out
        return null
    }

    // --- key ---

    /**
     * Sends key events to the focused window (the activity, or a dialog on top) the same way ViewRootImpl does:
     * root view dispatchKeyEvent, then focus search for unhandled D-pad keys. A long press sends the repeats
     * with FLAG_LONG_PRESS at the Android key repeat times, so onKeyLongPress and hold timers run as on the device.
     */
    private fun key(code: Int, long: Boolean, holdMs: Long, count: Int, gapMs: Long, done: (String?) -> Unit) {
        TestHookLogic.keyDurationError(holdMs, count, gapMs)?.let { return done(it) }
        var remaining = count
        lateinit var pressNext: () -> Unit
        pressNext = {
            press(code, long, holdMs) {
                remaining--
                if (remaining > 0) handler.postDelayed({ pressNext() }, gapMs) else done(null)
            }
        }
        pressNext()
    }

    private fun press(code: Int, long: Boolean, holdMs: Long, after: () -> Unit) {
        val downTime = SystemClock.uptimeMillis()
        send(downTime, KeyEvent.ACTION_DOWN, code, 0, 0)
        if (long) {
            TestHookLogic.keyRepeatOffsets(holdMs).forEachIndexed { i, offset ->
                val flags = if (i == 0) KeyEvent.FLAG_LONG_PRESS else 0
                handler.postAtTime({ send(downTime, KeyEvent.ACTION_DOWN, code, i + 1, flags) }, downTime + offset)
            }
        }
        handler.postAtTime({
            send(downTime, KeyEvent.ACTION_UP, code, 0, 0)
            after()
        }, downTime + holdMs)
    }

    private fun send(downTime: Long, action: Int, code: Int, repeat: Int, flags: Int) {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, code, repeat, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags or KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD
        )
        val root = Windows.focusedRoot(main)
        val handled = root.dispatchKeyEvent(event)
        if (!handled && action == KeyEvent.ACTION_DOWN) {
            val direction = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                else -> return
            }
            val focused = root.findFocus() ?: return
            val next = focused.focusSearch(direction)
            if (next != null && next !== focused) next.requestFocus(direction)
        }
    }

    companion object {
        const val LOG_TAIL_FILE = "event-log-tail.jsonl"
    }
}
