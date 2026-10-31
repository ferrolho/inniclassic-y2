package com.themoon.y1.testhooks

import com.themoon.y1.Prefs
import com.themoon.y1.eventlog.EventLogLogic
import com.themoon.y1.models.SongItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Pure parts of the developer test interface (no views, no Activity), so that unit tests can check them.
 * See [TestHooks].
 */
object TestHookLogic {

    /** Longer results go to the file only; the broadcast result then gets a short summary. */
    const val MAX_BROADCAST_CHARS = 12000

    /** Android key repeat timing (ViewConfiguration.getKeyRepeatTimeout / getKeyRepeatDelay). */
    const val KEY_REPEAT_TIMEOUT_MS = 500L
    const val KEY_REPEAT_DELAY_MS = 50L

    enum class BrowseType { ALBUM, ARTIST, ALBUM_ARTIST, GENRE, SONGS, FAVORITES }

    private val SCREENS: Map<String, List<String>> = linkedMapOf(
        "MainMenu" to listOf("mainmenu", "menu", "home", "ipod"),
        "Music" to listOf("music", "browser", "browserroot", "library"),
        "NowPlaying" to listOf("nowplaying", "player"),
        "Settings" to listOf("settings"),
        "CoverFlow" to listOf("coverflow"),
        "About" to listOf("about", "storage"),
        "Videos" to listOf("videos", "video"),
        "Podcasts" to listOf("podcasts", "podcast"),
        "Playlists" to listOf("playlists", "playlist"),
        "Audiobooks" to listOf("audiobooks", "audiobook"),
        "Extras" to listOf("extras", "quiz", "musicquiz"),
        "Radio" to listOf("radio", "fmradio"),
        "Upload" to listOf("upload", "webserver"),
        "Bluetooth" to listOf("bluetooth", "bt"),
        "Wifi" to listOf("wifi"),
        "PlayOn" to listOf("playon", "cast"),
        "Themes" to listOf("themes", "theme"),
        "Equalizer" to listOf("equalizer", "eq"),
        "Brightness" to listOf("brightness")
    )

    /** Screen names that `open` accepts (each also has aliases, see [screenId]). */
    val SCREEN_IDS: List<String> get() = SCREENS.keys.toList()

    fun normalize(name: String?): String =
        name.orEmpty().lowercase(Locale.US).filter { it.isLetterOrDigit() }

    /** Canonical screen id for a name or alias (case, spaces and punctuation are ignored), or null. */
    fun screenId(name: String?): String? {
        val n = normalize(name)
        if (n.isEmpty()) return null
        return SCREENS.entries.firstOrNull { (id, aliases) -> normalize(id) == n || n in aliases }?.key
    }

    fun browseType(name: String?): BrowseType? = when (normalize(name)) {
        "album", "albums" -> BrowseType.ALBUM
        "artist", "artists", "trackartist", "trackartists" -> BrowseType.ARTIST
        "albumartist", "albumartists" -> BrowseType.ALBUM_ARTIST
        "genre", "genres" -> BrowseType.GENRE
        "songs", "song", "allsongs", "all", "search" -> BrowseType.SONGS
        "favorites", "favourites", "favorite", "myfavorites" -> BrowseType.FAVORITES
        else -> null
    }

    /** The candidate equal to [value]: exact match first, then a case-insensitive match; else null. */
    fun resolveName(candidates: Iterable<String>, value: String): String? {
        var caseInsensitive: String? = null
        for (c in candidates) {
            if (c == value) return c
            if (caseInsensitive == null && c.equals(value, ignoreCase = true)) caseInsensitive = c
        }
        return caseInsensitive
    }

    /**
     * First song whose title contains [query] (case-insensitive); if none, the first song whose artist
     * or album contains it. Library order.
     */
    fun findSong(songs: List<SongItem>, query: String): SongItem? {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null
        return songs.firstOrNull { it.title.lowercase(Locale.ROOT).contains(q) }
            ?: songs.firstOrNull {
                it.artist.lowercase(Locale.ROOT).contains(q) || it.album.lowercase(Locale.ROOT).contains(q)
            }
    }

    /** Times (ms after the key down) of the repeated key-down events of a key held for [holdMs]. */
    fun keyRepeatOffsets(holdMs: Long): List<Long> {
        val out = ArrayList<Long>()
        var t = KEY_REPEAT_TIMEOUT_MS
        while (t < holdMs) {
            out.add(t)
            t += KEY_REPEAT_DELAY_MS
        }
        return out
    }

    /**
     * Longest time of one `key` command (200 presses with the default hold and gap fit). With the wake (5 s)
     * and the settle wait the reply comes before Android's 60 s timeout of a background ordered broadcast.
     */
    const val MAX_KEY_COMMAND_MS = 45_000L

    /** Time of a `key` command: [count] presses of [holdMs] with [gapMs] between them. */
    fun keyDurationMs(holdMs: Long, count: Int, gapMs: Long): Long =
        count * holdMs + (count - 1).coerceAtLeast(0) * gapMs

    /** The error for a `key` command that takes longer than [MAX_KEY_COMMAND_MS], else null. */
    fun keyDurationError(holdMs: Long, count: Int, gapMs: Long): String? {
        val ms = keyDurationMs(holdMs, count, gapMs)
        if (ms <= MAX_KEY_COMMAND_MS) return null
        return "key would take $ms ms (count*hold + (count-1)*gap); the limit is $MAX_KEY_COMMAND_MS ms: send it in parts"
    }

    /** True for text that is only icon-font glyphs (Unicode private use area) and spaces. */
    fun isIconGlyph(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && t.all { it.isWhitespace() || it in ''..'' }
    }

    /** "Name" of a ScreenState, BrowserMode or other model object: its class name. */
    fun typeName(value: Any?): String? = value?.javaClass?.simpleName

    /** {"name": class name, "detail": toString()} of a ScreenState or BrowserMode (data classes show their params). */
    fun describe(value: Any?): JSONObject? {
        if (value == null) return null
        return JSONObject().put("name", typeName(value)).put("detail", value.toString())
    }

    /** The settings from AppPrefs (the setting keys of [Prefs], only keys that are set). */
    fun keySettings(all: Map<String, *>): JSONObject {
        val out = JSONObject()
        for (key in all.keys.sorted()) {
            if (Prefs.isSetting(key)) putScalar(out, key, all[key])
        }
        return out
    }

    /** All scalar AppPrefs entries, without secrets and without per-song keys (ratings, positions, cached tags). */
    fun allSettings(all: Map<String, *>): JSONObject {
        val out = JSONObject()
        for (key in all.keys.sorted()) {
            if (EventLogLogic.isSecretKey(key) || Prefs.isPerSong(key)) continue
            putScalar(out, key, all[key])
        }
        return out
    }

    private fun putScalar(out: JSONObject, key: String, value: Any?) {
        when (value) {
            is Boolean, is Int, is Long, is String -> out.put(key, value)
            is Float -> out.put(key, value.toDouble())
            is Set<*> -> out.put(key, JSONObject().put("setSize", value.size))
        }
    }

    /** The reply of every command: {"ok":…, "error":…, "cmd":…, "state":{…}}. */
    fun result(ok: Boolean, error: String?, cmd: String, state: JSONObject?): JSONObject {
        val out = JSONObject()
        out.put("ok", ok)
        out.put("error", error ?: JSONObject.NULL)
        out.put("cmd", cmd)
        out.put("state", state ?: JSONObject.NULL)
        return out
    }

    /**
     * The text for the ordered-broadcast result data: the full one-line JSON if it is short enough,
     * else a short JSON (with [power]) that names the file with the full result.
     */
    fun broadcastData(
        json: String, ok: Boolean, error: String?, cmd: String, file: String?,
        max: Int = MAX_BROADCAST_CHARS, power: JSONObject? = null
    ): String {
        if (json.length <= max) return json
        val out = JSONObject()
        out.put("ok", ok)
        out.put("error", error ?: JSONObject.NULL)
        out.put("cmd", cmd)
        if (power != null) out.put("power", power)
        out.put("truncated", true)
        out.put("chars", json.length)
        out.put("file", file ?: JSONObject.NULL)
        return out.toString()
    }

    fun jsonArray(values: Iterable<Any?>): JSONArray {
        val arr = JSONArray()
        for (v in values) arr.put(v ?: JSONObject.NULL)
        return arr
    }
}
