package com.themoon.y1.managers

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.themoon.y1.MainActivity

/**
 * One audio source at a time, as on the iPod nano with FM radio: music (a song, a podcast or a Music Quiz clip)
 * that starts turns the radio off, a video also pauses the music, and the radio pauses the music when it starts.
 * (iPod nano user guide: the radio is cleared when "you exit Radio to play other media content".)
 */
class AudioSources internal constructor(
    private val radioOn: () -> Boolean,
    private val stopRadio: () -> Unit,
    private val musicPlaying: () -> Boolean,
    private val pauseMusic: () -> Unit
) {
    /** Music starts, or is about to start: the radio goes off. */
    fun musicStarts() {
        if (!radioOn()) return
        Log.w(TAG, "Music starts: FM radio off")
        stopRadio()
    }

    /** A video starts: the radio goes off and the music pauses. */
    fun videoStarts() {
        musicStarts()
        if (musicPlaying()) {
            Log.w(TAG, "Video starts: music paused")
            pauseMusic()
        }
    }

    /** The radio is about to start: the music pauses. */
    fun radioStarts() {
        if (musicPlaying()) {
            Log.w(TAG, "FM radio starts: music paused")
            pauseMusic()
        }
    }

    companion object {
        private const val TAG = "AudioSources"

        /** The rule for the app's players. Call it on the main thread (the music player lives there). */
        @JvmField
        val app = AudioSources(
            radioOn = { FmRadioManager.getInstance()?.isPowerUp == true },
            stopRadio = {
                FmRadioManager.getInstance()?.powerDown()
                // The Radio screen shows "stopped"
                MainActivity.instance?.let { main ->
                    if (main.currentScreenState == MainActivity.STATE_FM_RADIO) main.getActiveScreenController()?.onRefreshUI()
                }
            },
            musicPlaying = { AudioPlayerManager.getInstance().isPlaying() },
            pauseMusic = { onMain { AudioPlayerManager.getInstance().let { if (it.isPlaying()) it.playOrPauseMusic() } } }
        )

        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
        }
    }
}
