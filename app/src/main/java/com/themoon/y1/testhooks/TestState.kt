package com.themoon.y1.testhooks

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.TextView
import com.themoon.y1.BuildConfig
import com.themoon.y1.MainActivity
import com.themoon.y1.ThemeManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.MediaLibraryManager
import com.themoon.y1.managers.VirtualVolumeManager
import com.themoon.y1.models.SongItem
import com.themoon.y1.navigation.NavigationManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the app state as JSON. Call on the main thread only. Each section is read on its own, so that an
 * error in one section (for example a view that this build does not have) shows as `"<section>Error"`
 * and does not stop the others.
 */
internal class TestState(private val main: MainActivity, private val options: Options = Options()) {

    data class Options(val items: Boolean = false, val allPrefs: Boolean = false)

    fun build(): JSONObject {
        val out = JSONObject()
        section(out, "app") { app() }
        section(out, "screen") { screen() }
        section(out, "browser") { browser() }
        section(out, "focus") { focus() }
        section(out, "nowPlaying") { nowPlaying() }
        section(out, "player") { player() }
        section(out, "audio") { audio() }
        section(out, "power") { power() }
        section(out, "theme") { theme() }
        section(out, "settings") { settings() }
        section(out, "library") { library() }
        return out
    }

    private fun section(out: JSONObject, name: String, read: () -> Any?) {
        try {
            out.put(name, read() ?: JSONObject.NULL)
        } catch (t: Throwable) {
            out.put(name + "Error", t.toString())
        }
    }

    private fun app() = JSONObject()
        .put("package", main.packageName)
        .put("versionName", BuildConfig.VERSION_NAME)
        .put("versionCode", BuildConfig.VERSION_CODE)
        .put("buildType", BuildConfig.BUILD_TYPE)
        // Same activity id and start time before and after a change = no activity restart
        .put("activityId", System.identityHashCode(main))
        .put("activityCreatedUptimeMs", TestHooks.activityCreatedUptimeMs)
        .put("activityStarts", TestHooks.activityStarts)
        .put("uptimeMs", SystemClock.uptimeMillis())

    /** Theme state. generation, lastApplyMs and applyCount only in builds with the live theme change. */
    private fun theme(): JSONObject {
        val out = JSONObject()
        out.put("name", ThemeManager.getCurrentTheme().name)
        out.put("index", ThemeManager.getCurrentThemeIndex())
        Reflect.getStaticField(ThemeManager::class.java, "themeGeneration")?.let { out.put("generation", it) }
        val live = try { Class.forName("com.themoon.y1.LiveTheme") } catch (_: Throwable) { null }
        out.put("liveChange", live != null)
        if (live != null) {
            Reflect.getStaticField(live, "lastApplyMs")?.let { out.put("lastApplyMs", it) }
            Reflect.getStaticField(live, "applyCount")?.let { out.put("applyCount", it) }
        }
        val auto = try { Class.forName("com.themoon.y1.managers.AutoThemeManager") } catch (_: Throwable) { null }
        if (auto != null) {
            out.put("auto", main.prefs.getBoolean("auto_theme_enabled", false))
            Reflect.getStaticField(auto, "nextSwitchMs")?.let { out.put("autoNextSwitchMs", it) }
        }
        return out
    }

    private fun screen(): JSONObject {
        val nav = NavigationManager.getInstance()
        val out = JSONObject()
        out.put("name", TestHookLogic.typeName(nav.currentState) ?: JSONObject.NULL)
        out.put("state", TestHookLogic.describe(nav.currentState) ?: JSONObject.NULL)
        out.put("stateInt", main.currentScreenState)
        out.put("controller", TestHookLogic.typeName(nav.activeController) ?: JSONObject.NULL)
        out.put("title", main.tvStatusBarTitle?.text?.toString() ?: JSONObject.NULL)
        val backStack = Reflect.getField(nav, "backStack") as? Collection<*>
        if (backStack != null) out.put("backStack", TestHookLogic.jsonArray(backStack.map { it?.toString() }))
        return out
    }

    private fun browser(): JSONObject? {
        if (!main.isBrowserControllerInitialized()) return null
        val bc = main.browserController
        val out = JSONObject()
        out.put("mode", TestHookLogic.describe(bc.currentBrowserMode))
        out.put("title", bc.getCurrentBrowserTitle())
        out.put("queryType", bc.virtualQueryType)
        out.put("queryValue", bc.virtualQueryValue)
        Reflect.getField(bc, "virtualQueryAlbumArtist")?.let { out.put("queryAlbumArtist", it) }
        out.put("artistFilter", bc.categoryArtistFilter)
        out.put("artistFilterIsTrackArtist", bc.categoryArtistFilterIsTrackArtist)
        out.put("genreFilter", bc.categoryGenreFilter)
        out.put("audiobookMode", bc.isAudiobookLibraryMode)
        out.put("songListSize", main.virtualSongList.size)
        return out
    }

    // --- focus and lists ---

    private fun focus(): JSONObject {
        val root = Windows.focusedRoot(main)
        val isActivity = root === main.window.decorView
        val out = JSONObject()
        out.put("window", if (isActivity) "activity" else "dialog")
        out.put("activityHasWindowFocus", main.hasWindowFocus())
        if (!isActivity) out.put("dialogTexts", TestHookLogic.jsonArray(texts(root)))
        val focused = root.findFocus()
        if (focused == null) {
            out.put("view", JSONObject.NULL)
            return out
        }
        val view = JSONObject()
        view.put("class", focused.javaClass.simpleName)
        idName(focused)?.let { view.put("id", it) }
        view.put("text", textOf(focused) ?: JSONObject.NULL)
        out.put("view", view)
        listInfo(focused)?.let { out.put("list", it) }
        return out
    }

    private fun idName(v: View): String? =
        if (v.id == View.NO_ID) null else try { main.resources.getResourceEntryName(v.id) } catch (_: Exception) { null }

    private fun texts(v: View): List<String> {
        val out = ArrayList<String>()
        collectTexts(v, out)
        return out
    }

    private fun collectTexts(v: View, out: MutableList<String>) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) {
            val t = v.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && !TestHookLogic.isIconGlyph(t)) out.add(t)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collectTexts(v.getChildAt(i), out)
    }

    private fun textOf(v: View?): String? {
        if (v == null) return null
        val parts = texts(v)
        return if (parts.isEmpty()) v.contentDescription?.toString() else parts.joinToString(" | ")
    }

    /** The list that holds the focused view: a ListView, or a LinearLayout with more than one focusable row. */
    private fun listInfo(focused: View): JSONObject? {
        var row: View = focused
        while (true) {
            val parent = row.parent as? ViewGroup ?: return null
            if (parent is AbsListView) return adapterList(parent, row)
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL && focusableRows(parent) > 1) {
                return linearList(parent, row)
            }
            row = parent
        }
    }

    private fun focusableRows(parent: ViewGroup): Int =
        (0 until parent.childCount).count { parent.getChildAt(it)?.let { c -> c.visibility == View.VISIBLE && c.isFocusable } == true }

    private fun adapterList(list: AbsListView, row: View): JSONObject {
        val out = JSONObject()
        out.put("kind", list.javaClass.simpleName)
        val index = try { list.getPositionForView(row) } catch (_: Exception) { -1 }
        out.put("index", index)
        out.put("count", list.count)
        out.put("firstVisible", list.firstVisiblePosition)
        out.put("lastVisible", list.lastVisiblePosition)
        val visible = JSONArray()
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            visible.put(JSONObject()
                .put("i", list.firstVisiblePosition + i)
                .put("text", textOf(child) ?: JSONObject.NULL)
                .put("focused", child.hasFocus()))
        }
        out.put("visible", visible)
        if (options.items) {
            val adapter = list.adapter
            val items = JSONArray()
            if (adapter != null) {
                for (i in 0 until minOf(adapter.count, MAX_ITEMS)) items.put(itemLabel(adapter.getItem(i)))
            }
            out.put("items", items)
        }
        return out
    }

    private fun itemLabel(item: Any?): Any = when (item) {
        null -> JSONObject.NULL
        is String -> item
        is SongItem -> item.title
        else -> item.toString()
    }

    private fun linearList(parent: ViewGroup, row: View): JSONObject {
        val out = JSONObject()
        out.put("kind", parent.javaClass.simpleName)
        idName(parent)?.let { out.put("id", it) }
        val rows = (0 until parent.childCount).mapNotNull { parent.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.isFocusable }
        out.put("index", rows.indexOf(row))
        out.put("count", rows.size)
        val visible = JSONArray()
        rows.forEachIndexed { i, child ->
            visible.put(JSONObject().put("i", i).put("text", textOf(child) ?: JSONObject.NULL).put("focused", child.hasFocus()))
        }
        out.put("visible", visible)
        return out
    }

    // --- Now Playing and player ---

    private fun nowPlaying(): JSONObject {
        val pc = main.playerController
        val out = JSONObject()
        out.put("bottomState", pc.nowPlayingBottomState)
        out.put("pendingSeek", if (pc.pendingSeekChanged) pc.pendingSeekPosition else JSONObject.NULL)
        out.put("spectrum", main.isVisualizerShowing)
        out.put("albumTilt", main.isAlbumTiltEnabled)
        out.put("display", JSONObject()
            .put("title", main.tvPlayerTitle.text.toString())
            .put("artist", main.tvPlayerArtist.text.toString())
            .put("album", main.tvPlayerAlbum.text.toString())
            .put("trackCount", main.tvPlayerTrackCount.text.toString())
            .put("timeCurrent", main.tvPlayerTimeCurrent.text.toString())
            .put("timeTotal", main.tvPlayerTimeTotal.text.toString()))
        out.put("lyrics", lyrics(pc) ?: JSONObject.NULL)
        return out
    }

    private fun lyrics(pc: Any): JSONObject? {
        // Do not create the lazy overlay only to read it
        val lazy = Reflect.getField(pc, "lyricsOverlayController\$delegate") as? Lazy<*>
        if (lazy != null && !lazy.isInitialized()) return JSONObject().put("loaded", false)
        val lc = main.playerController.lyricsOverlayController
        val out = JSONObject()
        out.put("loaded", true)
        out.put("has", lc.hasLyrics)
        out.put("showing", lc.isShowing())
        val synced = Reflect.getField(lc, "currentLyrics") as? Map<*, *>
        val timestamps = Reflect.getField(lc, "lyricTimestamps") as? List<*>
        out.put("synced", synced?.isNotEmpty() ?: JSONObject.NULL)
        out.put("lineCount", synced?.size ?: JSONObject.NULL)
        out.put("plain", Reflect.getField(lc, "plainLyrics") != null)
        val index = Reflect.getField(lc, "lastLyricIndex") as? Int
        out.put("lineIndex", index ?: JSONObject.NULL)
        if (index != null && timestamps != null && index in timestamps.indices) {
            out.put("lineTimeMs", timestamps[index])
            out.put("line", synced?.get(timestamps[index])?.toString() ?: JSONObject.NULL)
        }
        Reflect.getField(lc, "manualLyricIndex")?.let { out.put("manualLineIndex", it) }
        return out
    }

    private fun player(): JSONObject {
        val am = AudioPlayerManager.getInstance()
        val out = JSONObject()
        val file = main.currentPlaylist.getOrNull(main.currentIndex)
        val path = file?.absolutePath
        out.put("path", path ?: JSONObject.NULL)
        val song = if (path == null) null else synchronized(MainActivity.customLibrary) {
            MainActivity.customLibrary.firstOrNull { it.file.absolutePath == path }
        }
        out.put("inLibrary", song != null)
        if (song != null) {
            out.put("title", song.title).put("artist", song.artist).put("album", song.album)
                .put("albumArtist", song.albumArtist).put("genre", song.genre).put("year", song.year)
        }
        val track = if (path == null) null else synchronized(MainActivity.trackNumberMap) { MainActivity.trackNumberMap[path] }
        out.put("trackNumber", track ?: JSONObject.NULL)
        out.put("positionMs", am.getCurrentPosition())
        out.put("durationMs", am.getDuration())
        out.put("playing", am.isPlaying())
        out.put("pausedByHand", main.isPausedByHand)
        out.put("queueIndex", main.currentIndex)
        out.put("queueSize", main.currentPlaylist.size)
        out.put("shuffle", main.isShuffleMode)
        out.put("repeat", main.repeatMode)
        out.put("speed", am.getCurrentSpeed().toDouble())
        out.put("legacyPlayer", am.isUsingLegacyPlayer)
        am.exoPlayer?.let { exo ->
            try {
                out.put("engine", JSONObject()
                    .put("shuffle", exo.shuffleModeEnabled)
                    .put("repeat", exo.repeatMode)
                    .put("playWhenReady", exo.playWhenReady)
                    .put("playbackState", exo.playbackState))
            } catch (_: Throwable) {}
        }
        // Stored shuffle/repeat: AppPrefs (Settings, AudioPlayerManager) and y1_settings (older Now Playing code)
        val stored = JSONObject()
        for (name in listOf("AppPrefs", "y1_settings")) {
            val p = main.getSharedPreferences(name, Context.MODE_PRIVATE)
            val s = JSONObject()
            if (p.contains("shuffle")) s.put("shuffle", p.getBoolean("shuffle", false))
            if (p.contains("repeat_mode")) s.put("repeat_mode", p.getInt("repeat_mode", 0))
            stored.put(name, s)
        }
        out.put("stored", stored)
        return out
    }

    // --- audio, power ---

    private fun audio(): JSONObject {
        val am = main.audioManager
        val out = JSONObject()
        out.put("musicVolume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
        out.put("musicVolumeMax", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        out.put("appVolume", main.currentVirtualVolume)
        out.put("appVolumeMax", MainActivity.VIRTUAL_VOLUME_STEPS)
        out.put("appVolumeGain", VirtualVolumeManager.getInstance().getMasterGain().toDouble())
        out.put("volumeLimitMax", main.volumeLimitMax)
        out.put("systemVolume", am.getStreamVolume(AudioManager.STREAM_SYSTEM))
        val muted = Reflect.call(am, "isStreamMute", AudioManager.STREAM_SYSTEM)
        out.put("systemStreamMuted", muted ?: JSONObject.NULL)
        // Only in builds with the sound-setting fix: whether this process holds the STREAM_SYSTEM mute
        Reflect.getStaticField(MainActivity::class.java, "isSystemStreamMuted")?.let { out.put("appHoldsSystemMute", it) }
        val cr = main.contentResolver
        out.put("soundEffectsSetting", Settings.System.getInt(cr, Settings.System.SOUND_EFFECTS_ENABLED, -1))
        out.put("hapticSetting", Settings.System.getInt(cr, Settings.System.HAPTIC_FEEDBACK_ENABLED, -1))
        out.put("clickSound", main.isSoundEffectEnabled)
        out.put("vibrate", main.isVibrationEnabled)
        out.put("vibrateStrength", main.vibrationStrengthLevel)
        @Suppress("DEPRECATION")
        out.put("wiredHeadset", am.isWiredHeadsetOn)
        @Suppress("DEPRECATION")
        out.put("bluetoothA2dp", am.isBluetoothA2dpOn)
        return out
    }

    private fun power(): JSONObject {
        val pm = main.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val screenOn = pm.isScreenOn
        return JSONObject()
            .put("screenOn", screenOn)
            .put("screenSleeping", main.isScreenSleeping)
            .put("fakeScreenOff", main.isFakeScreenOff)
            .put("screenOffControl", main.isScreenOffControlEnabled)
    }

    // --- settings, library ---

    private fun settings(): JSONObject {
        val all = main.prefs.all
        val out = JSONObject()
        out.put("theme", try { ThemeManager.getCurrentTheme().name } catch (_: Throwable) { JSONObject.NULL })
        out.put("prefs", TestHookLogic.keySettings(all))
        if (options.allPrefs) out.put("allPrefs", TestHookLogic.allSettings(all))
        out.put("effective", JSONObject()
            .put("sound", main.isSoundEffectEnabled)
            .put("vibrate", main.isVibrationEnabled)
            .put("vibrateStrength", main.vibrationStrengthLevel)
            .put("screenOffControl", main.isScreenOffControlEnabled)
            .put("is24h", main.is24HourFormat)
            .put("timeInTitleBar", main.isTimeInTitleBar)
            .put("albumTilt", main.isAlbumTiltEnabled))
        return out
    }

    private fun library(): JSONObject {
        val out = JSONObject()
        out.put("songs", synchronized(MainActivity.customLibrary) { MainActivity.customLibrary.size })
        out.put("albums", main.uniqueAlbumList.size)
        out.put("audiobooks", synchronized(MainActivity.audiobookLibrary) { MainActivity.audiobookLibrary.size })
        out.put("favorites", main.favoritePaths.size)
        out.put("scanning", main.isCustomScanning || MediaLibraryManager.getInstance().isScanning)
        // Only in builds that load the library cache off the main thread
        Reflect.getField(main, "isLibraryCacheLoading")?.let { out.put("cacheLoading", it) }
        return out
    }

    companion object {
        const val MAX_ITEMS = 5000
    }
}

/** Reflection for fields that only some of our build's branches have, or that are private. Null when absent. */
internal object Reflect {
    fun getField(obj: Any, name: String): Any? = findField(obj.javaClass, name)?.let {
        try { it.get(obj) } catch (_: Throwable) { null }
    }

    fun getStaticField(cls: Class<*>, name: String): Any? = findField(cls, name)?.let {
        try { it.get(null) } catch (_: Throwable) { null }
    }

    fun setField(obj: Any, name: String, value: Any?): Boolean {
        val f = findField(obj.javaClass, name) ?: return false
        return try {
            f.set(obj, value)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun call(obj: Any, name: String, arg: Int): Any? = try {
        obj.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(obj, arg)
    } catch (_: Throwable) {
        null
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }
}

/** The window that has focus: the activity, or a dialog on top of it (WindowManagerGlobal, hidden API). */
internal object Windows {
    fun focusedRoot(main: MainActivity): View {
        val decor = main.window.decorView
        if (decor.hasWindowFocus()) return decor
        return roots().lastOrNull { it.hasWindowFocus() } ?: decor
    }

    private fun roots(): List<View> = try {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val global = cls.getMethod("getInstance").invoke(null)
        val field = cls.getDeclaredField("mViews").apply { isAccessible = true }
        when (val views = field.get(global)) {
            is List<*> -> views.filterIsInstance<View>()
            is Array<*> -> views.filterIsInstance<View>()
            else -> emptyList()
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
