package com.themoon.y1

/**
 * Build switch for the Videos feature (Gradle property `videos`, default true).
 * With `-Pvideos=false` the app hides the Videos menu item and settings rows, does not scan for videos
 * and does not sync Jellyfin movie and TV libraries. The video code stays in the app, but is not used.
 */
object VideoFeature {
    val enabled: Boolean get() = BuildConfig.VIDEOS

    /** False for a main menu button that opens Videos while the feature is off. */
    fun showsMenuAction(action: String?, enabled: Boolean = this.enabled): Boolean =
        enabled || !(action.equals("OPEN_VIDEOS", ignoreCase = true) || action.equals("VIDEOS", ignoreCase = true))

    /** False for a Jellyfin movie or TV library while the feature is off. */
    fun syncsLibrary(collectionType: String?, enabled: Boolean = this.enabled): Boolean =
        enabled || !(collectionType.equals("movies", ignoreCase = true) || collectionType.equals("tvshows", ignoreCase = true))
}
