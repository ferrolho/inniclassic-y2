package com.themoon.y1.navigation

import android.content.Context
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.themoon.y1.MainActivity
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.managers.AudioPlayerManager

/**
 * Handles global key intercepts (wake logic, volume overlay, UMS blocking)
 * and delegates wheel/button events to active ScreenController or NavigationManager.
 */
class InputRouter private constructor() {

    @Volatile
    var isBackSuppressed: Boolean = false
        private set

    @Volatile
    var backDebounceUntil: Long = 0L
        private set

    // Set when Next/Previous auto-repeats, so the release does not skip the track
    @Volatile
    var isSkipKeyHeld: Boolean = false
        private set

    // Hold time of the last seek step and its size, to pace and speed up the seek while the key is held
    private var lastSeekHeldMs: Long = -SEEK_INTERVAL_MS
    private var lastSeekStepMs: Int = 0

    fun suppressBackUntilRelease(debounceMs: Long = 350L) {
        isBackSuppressed = true
        backDebounceUntil = System.currentTimeMillis() + debounceMs
    }

    fun resetBackSuppression() {
        isBackSuppressed = false
        backDebounceUntil = 0L
    }

    fun interface VolumeAdjuster {
        fun adjustVolume(isUp: Boolean)
    }

    fun dispatchKeyEvent(
        event: KeyEvent,
        context: Context,
        isFakeScreenOff: Boolean,
        isScreenOffControlEnabled: Boolean = false,
        onWakeFromFakeScreenOff: Runnable,
        onResetBacklightTimer: Runnable,
        isUsbStorageActive: Boolean,
        onStopUsbStorage: Runnable,
        onAdjustVolume: VolumeAdjuster,
        activeController: ScreenController?
    ): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isScreenOn = if (android.os.Build.VERSION.SDK_INT >= 20) {
            pm?.isInteractive ?: true
        } else {
            pm?.isScreenOn ?: true
        }

        if (isScreenOn && !isFakeScreenOff) {
            onResetBacklightTimer.run()
        }

        // Intercept while USB mass storage modal is active
        if (isUsbStorageActive) {
            if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onStopUsbStorage.run()
                }
                return true
            }
        }

        // Global volume button interception
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0 || (event.repeatCount % 2 == 0)) {
                    onAdjustVolume.adjustVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                }
            }
            return true
        }

        // Screen is off: block or allow only media controls based on isScreenOffControlEnabled
        if (!isScreenOn) {
            if (!isScreenOffControlEnabled) {
                return true
            }
            val isMediaKey = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
                    keyCode == 85 ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                    keyCode == 87 ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                    keyCode == 88

            if (isMediaKey) {
                if (isSkipKey(keyCode)) {
                    // Same press/hold behaviour as with the screen on
                    if (action == KeyEvent.ACTION_DOWN) {
                        onSkipKeyDown(keyCode, event.repeatCount, event.eventTime - event.downTime)
                    } else if (action == KeyEvent.ACTION_UP) {
                        onSkipKeyUp(keyCode, event.eventTime - event.downTime)
                    }
                } else if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    com.themoon.y1.managers.AudioPlayerManager.getInstance().playOrPauseMusic()
                }
                return true
            }
            return true
        }

        // Wake from fake screen off
        if (isFakeScreenOff) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                onWakeFromFakeScreenOff.run()
            }
            return true
        }

        // Allow active controller to intercept raw dispatchKeyEvent if needed (when screen is on)
        if (isScreenOn && activeController?.dispatchKeyEvent(event) == true) {
            return true
        }

        // Suppress back key repeat / debounce after modal or long-press dismissal
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            if (isBackSuppressed || System.currentTimeMillis() < backDebounceUntil) {
                if (action == KeyEvent.ACTION_UP) {
                    isBackSuppressed = false
                }
                return true
            }
        }

        return false
    }

    fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            val delta = if (keyCode == 22) 1 else -1
            if (activeController?.onWheel(delta, event) == true) {
                return true
            }

            val focused = activeController?.rootView?.findFocus()
            val parent = focused?.parent as? ViewGroup
            if (parent is LinearLayout) {
                val idx = parent.indexOfChild(focused)
                if (delta < 0) {
                    for (i in idx - 1 downTo 0) {
                        val child = parent.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                            return true
                        }
                    }
                } else {
                    for (i in idx + 1 until parent.childCount) {
                        val child = parent.getChildAt(i)
                        if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                            child.requestFocus()
                            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
                            return true
                        }
                    }
                }
            }
        }
        if (activeController?.onKeyDown(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            val now = System.currentTimeMillis()
            if (isBackSuppressed || now < backDebounceUntil || event.repeatCount > 0) {
                return true
            }
            if (activeController?.onBackPressed() == true) {
                return true
            }
            if (NavigationManager.getInstance().navigateBack()) {
                return true
            }
            (activeController?.rootView?.context as? MainActivity)?.clickFeedback()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || keyCode == 85) {
            AudioPlayerManager.getInstance().playOrPauseMusic()
            return true
        }
        if (isSkipKey(keyCode)) {
            onSkipKeyDown(keyCode, event.repeatCount, event.eventTime - event.downTime)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (event.repeatCount == 0) {
                main?.isLongPressConsumed = false
                event.startTracking()
            }
        }
        return false
    }

    fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        if (activeController?.onKeyUp(keyCode, event) == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == 19 || keyCode == 4) {
            isBackSuppressed = false
            return true
        }
        if (isSkipKey(keyCode)) {
            onSkipKeyUp(keyCode, event.eventTime - event.downTime)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23 || keyCode == KeyEvent.KEYCODE_ENTER) {
            val main = (activeController?.rootView?.context as? MainActivity) ?: MainActivity.instance
            if (main?.isLongPressConsumed == true) {
                main.isLongPressConsumed = false
                return true
            }
            if ((event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                val focused = activeController?.rootView?.findFocus()
                if (focused != null && focused !is android.widget.Button && focused.isClickable) {
                    focused.performClick()
                    return true
                }
            }
        }
        return false
    }

    fun onKeyLongPress(
        keyCode: Int,
        event: KeyEvent,
        activeController: ScreenController?
    ): Boolean {
        return activeController?.onKeyLongPress(keyCode, event) == true
    }

    private fun isSkipKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87 ||
                keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS || keyCode == 88
    }

    /**
     * Next/Previous key down. Holding the button seeks in steps that grow the longer it is held
     * (see [seekStepMs]); a short press changes the track on release (see [onSkipKeyUp]).
     * Returns the offset of the seek in ms, or 0 if it did not seek.
     */
    internal fun onSkipKeyDown(keyCode: Int, repeatCount: Int, heldMs: Long): Int {
        if (repeatCount == 0 || heldMs < lastSeekHeldMs) {
            isSkipKeyHeld = false
            lastSeekHeldMs = -SEEK_INTERVAL_MS
            lastSeekStepMs = 0
            if (repeatCount == 0) return 0
        }
        isSkipKeyHeld = true
        if (heldMs - lastSeekHeldMs < SEEK_INTERVAL_MS) {
            return 0
        }
        lastSeekHeldMs = heldMs
        val stepMs = seekStepMs(heldMs)
        val player = AudioPlayerManager.getInstance()
        val isNext = keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87
        val offsetMs = if (isNext) {
            forwardSeekMs(player.getCurrentPosition(), player.getDuration(), stepMs)
        } else {
            -stepMs
        }
        if (offsetMs != 0) {
            player.seekRelative(offsetMs)
        }
        // Click on the first step and when the seek speeds up, not on every step
        if (stepMs != lastSeekStepMs) {
            lastSeekStepMs = stepMs
            MainActivity.instance?.clickFeedback()
        }
        return offsetMs
    }

    /**
     * Next/Previous key up. Changes the track only after a short press. A long press counts as a hold
     * even if a controller (e.g. FM radio station seek) consumed the key repeats. Returns true if it skipped.
     */
    internal fun onSkipKeyUp(keyCode: Int, heldMs: Long): Boolean {
        if (isSkipKeyHeld || heldMs >= SKIP_KEY_HOLD_MS) {
            return false
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT || keyCode == 87) {
            AudioPlayerManager.getInstance().nextTrack()
        } else {
            AudioPlayerManager.getInstance().prevTrack()
        }
        MainActivity.instance?.clickFeedback()
        return true
    }

    companion object {
        // Android's default long-press / key-repeat timeout
        private const val SKIP_KEY_HOLD_MS = 500L

        // Hold-to-seek speeds up like the iPod's fast-forward: one step every 200 ms, of 2 s until the
        // button has been held for 2 s (10x), then 5 s until 5 s (25x), then 15 s until 8 s (75x), then
        // 30 s (150x). Short holds stay precise; a long hold crosses a song in ~7 s and an hour in ~30 s.
        private const val SEEK_INTERVAL_MS = 200L
        private val SEEK_STEPS_BY_HOLD_MS = listOf(2_000L to 2_000, 5_000L to 5_000, 8_000L to 15_000)
        private const val SEEK_MAX_STEP_MS = 30_000

        // Forward seeks stop this far before the end, so seeking alone never skips to the next track
        private const val SEEK_END_MARGIN_MS = 1_000

        /** Seek step size for a key held for [heldMs]. */
        internal fun seekStepMs(heldMs: Long): Int {
            return SEEK_STEPS_BY_HOLD_MS.firstOrNull { heldMs < it.first }?.second ?: SEEK_MAX_STEP_MS
        }

        /** Forward seek offset of up to [stepMs], clamped to stop [SEEK_END_MARGIN_MS] before the end. */
        internal fun forwardSeekMs(positionMs: Int, durationMs: Int, stepMs: Int): Int {
            if (durationMs <= 0) return stepMs
            return (durationMs - SEEK_END_MARGIN_MS - positionMs).coerceIn(0, stepMs)
        }

        @Volatile
        private var instance: InputRouter? = null

        fun getInstance(): InputRouter {
            return instance ?: synchronized(this) {
                instance ?: InputRouter().also { instance = it }
            }
        }
    }
}
