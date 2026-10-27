package com.themoon.y1.managers

import android.media.MediaMetadataRetriever
import android.media.RemoteControlClient

/**
 * Pure rules for the track data that the Y2 gives to Bluetooth AVRCP (car displays) through
 * RemoteControlClient on Android 4.4.
 */
object AvrcpMetadataLogic {

    /** Largest side of the artwork bitmap given to RemoteControlClient. */
    const val MAX_ART_SIDE = 256

    data class Track(val title: String, val artist: String, val album: String, val durationMs: Long)

    /** Text values for MetadataEditor.putString, by METADATA_KEY_*. */
    fun strings(track: Track): Map<Int, String> = linkedMapOf(
        MediaMetadataRetriever.METADATA_KEY_TITLE to track.title,
        MediaMetadataRetriever.METADATA_KEY_ARTIST to track.artist,
        // The MediaTek AVRCP of the Y2 ROM sends key 13 (album artist) as the artist; set both.
        MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to track.artist,
        MediaMetadataRetriever.METADATA_KEY_ALBUM to track.album
    )

    /** Duration for MetadataEditor.putLong(METADATA_KEY_DURATION), or null when not known yet. */
    fun duration(track: Track): Long? = track.durationMs.takeIf { it > 0 }

    fun playState(hasTrack: Boolean, isPlaying: Boolean): Int = when {
        !hasTrack -> RemoteControlClient.PLAYSTATE_STOPPED
        isPlaying -> RemoteControlClient.PLAYSTATE_PLAYING
        else -> RemoteControlClient.PLAYSTATE_PAUSED
    }

    /** Size of the artwork copy: same aspect ratio, largest side at most [max]. */
    fun artSize(width: Int, height: Int, max: Int = MAX_ART_SIDE): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        if (width <= max && height <= max) return width to height
        return if (width >= height) {
            max to maxOf(1, Math.round(height.toFloat() * max / width))
        } else {
            maxOf(1, Math.round(width.toFloat() * max / height)) to max
        }
    }
}
