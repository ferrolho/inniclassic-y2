package com.themoon.y1

import android.content.Context
import android.util.Log

/**
 * A feature that the user hides in Settings -> Main Menu Items also stops its background work.
 * The check reads the preference at the time of the work, so no restart is necessary.
 *
 * Background work that stops while the feature is hidden:
 * - Videos: video folder scan and video thumbnails, Jellyfin movie and TV sync. Unhide = one new scan.
 * - Audiobooks: audiobook folder scan, Jellyfin book sync. The next library scan finds new books.
 * - Web Server: a hide stops a running server (it holds a wake lock, a Wi-Fi lock and keeps the screen on).
 *
 * Build switches (Gradle properties, default true): `-Pvideos=false` and `-PmusicQuiz=false` leave a feature
 * out of the APK. A feature that is not built is always hidden, and the app also removes its main menu item,
 * its row in Settings -> Main Menu Items and the settings rows that only belong to it.
 * The code of the feature stays in the app, but is not used.
 */
object FeatureVisibility {
    const val VIDEOS = "OPEN_VIDEOS"
    const val AUDIOBOOKS = "OPEN_AUDIOBOOKS"
    const val WEB_SERVER = "OPEN_WEBSERVER"
    const val MUSIC_QUIZ = "OPEN_MUSIC_QUIZ"

    private const val TAG = "FeatureVisibility"

    /** Other main menu actions that open the same feature (older theme files use them). */
    private val ACTION_ALIASES = mapOf("VIDEOS" to VIDEOS, "EXTRAS" to MUSIC_QUIZ)

    /** The features that this build leaves out. */
    private val NOT_BUILT: Set<String> = notBuilt(BuildConfig.FEATURE_VIDEOS, BuildConfig.FEATURE_MUSIC_QUIZ)

    internal fun notBuilt(videos: Boolean, musicQuiz: Boolean): Set<String> = buildSet {
        if (!videos) add(VIDEOS)
        if (!musicQuiz) add(MUSIC_QUIZ)
    }

    /** The feature that a main menu action opens: its `OPEN_*` name. */
    internal fun featureForAction(action: String): String {
        val upper = action.uppercase(java.util.Locale.US)
        return ACTION_ALIASES[upper] ?: upper
    }

    /** False when this build leaves out the feature that [action] opens. Unknown actions are built. */
    fun isBuilt(action: String?): Boolean = isBuilt(action, NOT_BUILT)

    internal fun isBuilt(action: String?, notBuilt: Set<String>): Boolean =
        action == null || featureForAction(action) !in notBuilt

    val videosBuilt: Boolean get() = isBuilt(VIDEOS)

    /** True when every main menu button of the current theme that opens [action] is hidden, or the feature is not built. */
    fun isHidden(context: Context?, action: String): Boolean {
        if (!isBuilt(action)) return true
        if (context == null) return false
        return try {
            val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val ids = ThemeManager.getCurrentTheme().menuElements
                .filter { it.type == "button" && it.action.equals(action, ignoreCase = true) }
                .map { it.id }
            isHidden(ids) { prefs.getBoolean("hide_btn_$it", false) }
        } catch (e: Exception) {
            false
        }
    }

    /** A feature without a menu button stays as it is: hiding is a user choice in the menu settings. */
    internal fun isHidden(buttonIds: List<String>, isButtonHidden: (String) -> Boolean): Boolean =
        buttonIds.isNotEmpty() && buttonIds.all(isButtonHidden)

    /** The feature that a Jellyfin library type belongs to, or null for music and other libraries. */
    internal fun featureForLibrary(collectionType: String?): String? = when (collectionType?.lowercase(java.util.Locale.US)) {
        "movies", "tvshows" -> VIDEOS
        "books", "audiobooks" -> AUDIOBOOKS
        else -> null
    }

    /** True when the sync skips a Jellyfin library because its feature is hidden. */
    fun skipsJellyfinLibrary(context: Context?, collectionType: String?): Boolean {
        val feature = featureForLibrary(collectionType) ?: return false
        return isHidden(context, feature)
    }

    /** False for a Jellyfin library (in the Jellyfin settings list) whose feature is not built. */
    fun showsJellyfinLibrary(collectionType: String?): Boolean = isBuilt(featureForLibrary(collectionType))

    /** Settings -> Main Menu Items calls this after it hides or shows a button. */
    fun onMenuButtonToggled(activity: android.app.Activity, action: String?, hidden: Boolean) {
        val main = activity as? MainActivity ?: return
        when {
            action.equals(VIDEOS, ignoreCase = true) && !hidden && !isHidden(main, VIDEOS) -> {
                Thread({
                    try {
                        main.scanVideos()
                    } catch (e: Exception) {
                        Log.e(TAG, "Video scan failed", e)
                    }
                }, "y1-video-scan").start()
            }
            action.equals(WEB_SERVER, ignoreCase = true) && hidden && isHidden(main, WEB_SERVER) -> {
                if (main.webServerController.isServerRunning) main.webServerController.toggleServer()
            }
        }
    }
}
