package com.themoon.y1.controllers.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.ClockSettings
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.changeTheme
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.managers.SafeVolumeBypasser
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.AutoThemeManager
import com.themoon.y1.managers.CellRadioManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.WirelessAdbManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.LastFmScrobbler
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.ListEdges
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.ToggleStyle
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Controller for Settings screens:
 * Root settings menu, Date & Time, Theme selector, Language selector, Vibration,
 * Background settings, Volume limit, Legal, Main Menu visibility, and Music Menu visibility.
 */
class SettingsController(
    private val activity: Activity,
    override val rootView: ViewGroup,
    private val eqPresetNames: List<String>,
    private val createSettingRow: (title: String, value: String) -> LinearLayout,
    private val createCategoryHeader: (title: String) -> Unit,
    private val createListButton: (text: String) -> Button,
    private val createTablerListButton: (icon: Array<String>?, text: String, normalColor: Int, focusedColor: Int) -> View,
    private val clickFeedback: () -> Unit,
    private val onNavigateBack: () -> Unit,
    private val onNavigateToScreen: (Int) -> Unit,
    private val onOpenEqualizer: () -> Unit,
    private val onOpenJellyfin: () -> Unit,
    private val onOpenUpdateCheck: () -> Unit,
    private val onClearCacheConfirmed: () -> Unit,
    private val onApplyThemeBackground: () -> Unit,
    private val onClearCustomBackground: () -> Unit,
    private val onPickBackgroundFromStorage: () -> Unit,
    private val resetBacklightTimer: () -> Unit,
    private val applySoundSetting: () -> Unit,
    private val applyAlbumTiltSetting: () -> Unit,
    private val toggleShuffleMode: () -> Unit,
    private val cycleRepeatMode: () -> Unit,
    private val getRepeatModeText: (Int) -> String,
    private val localize: (String) -> String
) : ScreenController {

    private val containerSettingsItems: LinearLayout = rootView.findViewById(R.id.container_settings_items)
    private val prefs = activity.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainActivity: MainActivity? get() = activity as? MainActivity

    var currentSettingsDepth: Int = 0
    var lastSettingsFocusIndex: Int = 0
    var settingsSubMode: Int = 0
    private var isUiActive = false

    // Temporary variables for Date/Time picker
    var dtYear: Int = 2026
    var dtMonth: Int = 1
    var dtDay: Int = 1
    var dtHour: Int = 0
    var dtMinute: Int = 0

    // Reorder state for main menu visibility
    var isMenuReorderMode: Boolean = false
    private var currentReorderRow: View? = null
    private var materialIconFont: Typeface? = null

    companion object {
        val BACKLIGHT_TIMER_LABELS = arrayOf(
            "10 Seconds", "20 Seconds", "30 Seconds", "1 Minute", "2 Minutes", "Always On"
        )
        val TIMEOUT_VALUES = intArrayOf(15000, 30000, 60000, 300000)
        val TIMEOUT_NAMES = arrayOf("15 Sec", "30 Sec", "1 Min", "5 Min")
        val VIBE_STRENGTH_NAMES = arrayOf("Weak", "Normal (Vibe)", "Strong")

        // Common IANA zones covering the main UTC offsets, short enough to scroll with the wheel
        val TIME_ZONE_IDS = arrayOf(
            "Pacific/Honolulu", "America/Anchorage", "America/Los_Angeles", "America/Denver",
            "America/Chicago", "America/Mexico_City", "America/New_York", "America/Bogota",
            "America/Halifax", "America/St_Johns", "America/Sao_Paulo", "America/Argentina/Buenos_Aires",
            "Atlantic/South_Georgia", "Atlantic/Azores", "GMT", "Europe/London", "Europe/Lisbon",
            "Europe/Paris", "Europe/Berlin", "Europe/Madrid", "Europe/Rome", "Europe/Warsaw",
            "Africa/Lagos", "Europe/Athens", "Europe/Helsinki", "Africa/Cairo", "Africa/Johannesburg",
            "Europe/Moscow", "Europe/Istanbul", "Asia/Riyadh", "Asia/Tehran", "Asia/Dubai",
            "Asia/Karachi", "Asia/Kolkata", "Asia/Kathmandu", "Asia/Dhaka", "Asia/Bangkok",
            "Asia/Jakarta", "Asia/Shanghai", "Asia/Hong_Kong", "Asia/Singapore", "Australia/Perth",
            "Asia/Tokyo", "Asia/Seoul", "Australia/Adelaide", "Australia/Brisbane", "Australia/Sydney",
            "Pacific/Auckland"
        )

        fun timeZoneCityName(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

        // e.g. "UTC+01:00 London" (offset at the given time, so it includes summer time)
        fun timeZoneLabel(zoneId: String, nowMs: Long): String {
            val offsetMin = TimeZone.getTimeZone(zoneId).getOffset(nowMs) / 60000
            val sign = if (offsetMin < 0) "-" else "+"
            val absMin = Math.abs(offsetMin)
            return String.format(
                Locale.US, "UTC%s%02d:%02d %s",
                sign, absMin / 60, absMin % 60, timeZoneCityName(zoneId)
            )
        }

        val MUSIC_MENU_ITEMS = arrayOf(
            arrayOf("coverflow", "Cover Flow"),
            arrayOf("recently_played", "Recently Played"),
            arrayOf("playlists", "Playlists"),
            arrayOf("track_artists", "Artists"),
            arrayOf("artists", "Album Artists"),
            arrayOf("albums", "Albums"),
            arrayOf("compilations", "Compilations"),
            arrayOf("songs", "Songs"),
            arrayOf("shuffle_songs", "Shuffle Songs"),
            arrayOf("genres", "Genres"),
            arrayOf("composers", "Composers"),
            arrayOf("years", "Years"),
            arrayOf("recently_added", "Recently Added"),
            arrayOf("favorites", "My Favorites"),
            arrayOf("folders", "Folders"),
            arrayOf("search", "Search")
        )
    }

    init {
        containerSettingsItems.viewTreeObserver?.addOnGlobalFocusChangeListener { _, newFocus ->
            if (isUiActive && currentSettingsDepth == 0 && newFocus != null) {
                var v: View? = newFocus
                while (v != null && v.parent != containerSettingsItems) {
                    v = v.parent as? View
                }
                if (v != null) {
                    val idx = containerSettingsItems.indexOfChild(v)
                    if (idx >= 0) {
                        lastSettingsFocusIndex = idx
                    }
                }
            }
        }
    }

    override fun onEnter(state: ScreenState) {
        buildSettingsUI()
    }

    override fun onExit() {
        if (currentSettingsDepth == 0) {
            val curFocus = containerSettingsItems.findFocus()
            val idx = if (curFocus != null) containerSettingsItems.indexOfChild(curFocus) else -1
            if (idx >= 0) {
                lastSettingsFocusIndex = idx
            }
        }
        isUiActive = false
    }

    override fun onRefreshUI() {
        if (currentSettingsDepth == 0) {
            buildSettingsUI()
        }
    }

    // First row of the theme list when it was made; the list still shows while this row is in the container
    private var themeListFirstRow: View? = null

    override fun onThemeChanged() {
        when {
            currentSettingsDepth == 0 -> buildSettingsUI()
            themeListFirstRow != null && containerSettingsItems.getChildAt(0) === themeListFirstRow -> buildThemeSelectorUI()
            // Other sub-screens: one level up, as Back does
            else -> buildSettingsUI()
        }
    }

    override fun onBackPressed(): Boolean {
        if (isMenuReorderMode) {
            toggleMenuReorderMode()
            return true
        }
        if (currentSettingsDepth == 0) {
            onNavigateBack()
            return true
        } else if (currentSettingsDepth == 1) {
            buildSettingsUI()
            clickFeedback()
            return true
        } else if (currentSettingsDepth == 2) {
            buildDateTimeUI()
            clickFeedback()
            return true
        }
        buildSettingsUI()
        clickFeedback()
        return true
    }

    override fun onWheel(delta: Int, event: KeyEvent): Boolean {
        if (settingsSubMode == 4 && isMenuReorderMode && currentReorderRow != null) {
            val idx = containerSettingsItems.indexOfChild(currentReorderRow)
            if (delta < 0) {
                if (idx > 0) {
                    containerSettingsItems.removeViewAt(idx)
                    containerSettingsItems.addView(currentReorderRow, idx - 1)
                    currentReorderRow?.requestFocus()
                    clickFeedback()
                    val p = containerSettingsItems.parent
                    if (p is ScrollView) {
                        p.requestChildFocus(containerSettingsItems, currentReorderRow)
                    }
                }
            } else {
                if (idx < containerSettingsItems.childCount - 1) {
                    containerSettingsItems.removeViewAt(idx)
                    containerSettingsItems.addView(currentReorderRow, idx + 1)
                    currentReorderRow?.requestFocus()
                    clickFeedback()
                    val p = containerSettingsItems.parent
                    if (p is ScrollView) {
                        p.requestChildFocus(containerSettingsItems, currentReorderRow)
                    }
                }
            }
            return true
        }

        if (containerSettingsItems.childCount == 0) return false
        val isLoopScrollOn = prefs.getBoolean("loop_scroll_on", true)
        val curFocus = containerSettingsItems.findFocus()
        val currentIndex = if (curFocus != null) containerSettingsItems.indexOfChild(curFocus) else -1

        if (delta < 0) {
            if (currentIndex > 0) {
                for (i in currentIndex - 1 downTo 0) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
            if (isLoopScrollOn && currentIndex >= 0) {
                for (i in containerSettingsItems.childCount - 1 downTo currentIndex + 1) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
            if (currentIndex == -1) {
                for (i in 0 until containerSettingsItems.childCount) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
        } else {
            if (currentIndex in 0 until containerSettingsItems.childCount - 1) {
                for (i in currentIndex + 1 until containerSettingsItems.childCount) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
            if (isLoopScrollOn && currentIndex >= 0) {
                for (i in 0 until currentIndex) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
            if (currentIndex == -1) {
                for (i in 0 until containerSettingsItems.childCount) {
                    val child = containerSettingsItems.getChildAt(i)
                    if (child != null && child.visibility == View.VISIBLE && child.isFocusable) {
                        child.requestFocus()
                        if (currentSettingsDepth == 0) lastSettingsFocusIndex = i
                        clickFeedback()
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == 21 || keyCode == 22) {
            return onWheel(if (keyCode == 22) 1 else -1, event)
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isMenuReorderMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0
        ) {
            toggleMenuReorderMode()
            clickFeedback()
            return true
        }
        return false
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsSubMode == 4 && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            if (!isMenuReorderMode) {
                toggleMenuReorderMode()
                clickFeedback()
            }
            return true
        }
        return false
    }

    fun buildSettingsUI() {
        currentSettingsDepth = 0
        settingsSubMode = 0

        val targetFocusIndex = lastSettingsFocusIndex
        containerSettingsItems.removeAllViews()

        val isShuffleMode = prefs.getBoolean("shuffle", false)
        val btnShuffle = createSettingRow(
            localize("Shuffle Mode"),
            onOff(isShuffleMode, localize)
        )
        btnShuffle.setOnClickListener {
            clickFeedback()
            toggleShuffleMode()
            val tvStatus = btnShuffle.getChildAt(1) as? TextView
            val newShuffle = prefs.getBoolean("shuffle", false)
            tvStatus?.text = onOff(newShuffle, localize)
        }
        containerSettingsItems.addView(btnShuffle)

        val repeatMode = prefs.getInt("repeat_mode", 0)
        val btnRepeat = createSettingRow(
            localize("Repeat Mode"),
            localize(getRepeatModeText(repeatMode))
        )
        btnRepeat.setOnClickListener {
            clickFeedback()
            cycleRepeatMode()
            val tvStatus = btnRepeat.getChildAt(1) as? TextView
            val newRepeat = prefs.getInt("repeat_mode", 0)
            tvStatus?.text = localize(getRepeatModeText(newRepeat))
        }
        containerSettingsItems.addView(btnRepeat)

        val currentEqProfile = prefs.getString("eq_profile_id", "preset_0") ?: "preset_0"
        var eqDisplayName = "Normal"
        if (currentEqProfile.startsWith("preset_")) {
            val pIdx = currentEqProfile.replace("preset_", "").toIntOrNull() ?: 0
            if (pIdx < eqPresetNames.size) {
                eqDisplayName = localize(eqPresetNames[pIdx])
            }
        } else {
            eqDisplayName = currentEqProfile.replace("custom_", "")
        }
        val btnEq = createSettingRow(localize("Equalizer & Audio Effects"), "$eqDisplayName 〉")
        btnEq.setOnClickListener {
            clickFeedback()
            onOpenEqualizer()
        }
        containerSettingsItems.addView(btnEq)

        val volumeLimitMax = prefs.getInt("volume_limit_max", -1)
        val volumeLimitDisplay = if (volumeLimitMax < 0) localize("Off") else volumeLimitMax.toString()
        val btnVolumeLimit = createSettingRow(localize("Volume Limit"), "$volumeLimitDisplay 〉")
        btnVolumeLimit.setOnClickListener {
            clickFeedback()
            buildVolumeLimitSettingsUI()
        }
        containerSettingsItems.addView(btnVolumeLimit)

        var isSoundCheckEnabled = prefs.getBoolean("sound_check_enabled", false)
        val btnSoundCheck = createSettingRow(
            localize("Sound Check"),
            onOff(isSoundCheckEnabled, localize)
        )
        btnSoundCheck.setOnClickListener {
            clickFeedback()
            isSoundCheckEnabled = !isSoundCheckEnabled
            prefs.edit().putBoolean("sound_check_enabled", isSoundCheckEnabled).commit()
            mainActivity?.isSoundCheckEnabled = isSoundCheckEnabled
            val tvStatus = btnSoundCheck.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isSoundCheckEnabled, localize)
            AudioEffectManager.getInstance().applySoundCheck()
        }
        containerSettingsItems.addView(btnSoundCheck)

        val videoStretchFill = prefs.getBoolean("video_stretch_fill", false)
        val btnVideoFill = createSettingRow(
            localize("Fill Video Screen"),
            onOff(videoStretchFill, localize)
        )
        btnVideoFill.setOnClickListener {
            clickFeedback()
            val newState = !prefs.getBoolean("video_stretch_fill", false)
            prefs.edit().putBoolean("video_stretch_fill", newState).commit()
            val tvStatus = btnVideoFill.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newState, localize)
        }
        if (com.themoon.y1.FeatureVisibility.videosBuilt) containerSettingsItems.addView(btnVideoFill)

        var isAlbumTiltEnabled = prefs.getBoolean("album_tilt_enabled", true)
        val btnAlbumTilt = createSettingRow(
            localize("Album Cover Tilt"),
            onOff(isAlbumTiltEnabled, localize)
        )
        btnAlbumTilt.setOnClickListener {
            clickFeedback()
            isAlbumTiltEnabled = !isAlbumTiltEnabled
            prefs.edit().putBoolean("album_tilt_enabled", isAlbumTiltEnabled).commit()
            val tvStatus = btnAlbumTilt.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isAlbumTiltEnabled, localize)
            applyAlbumTiltSetting()
        }
        containerSettingsItems.addView(btnAlbumTilt)

        val isIpodLayout = prefs.getBoolean(com.themoon.y1.views.IpodClassicLayout.PREF_KEY, false)
        val btnIpodLayout = createSettingRow(
            localize("iPod Classic Layout"),
            com.themoon.y1.core.extensions.onOff(isIpodLayout, localize)
        )
        btnIpodLayout.setOnClickListener {
            clickFeedback()
            // IpodClassicLayout listens for this key and restyles the static views; the rows are rebuilt here.
            prefs.edit().putBoolean(com.themoon.y1.views.IpodClassicLayout.PREF_KEY, !isIpodLayout).commit()
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnIpodLayout)

        val speedLabels = arrayOf("1.0x (Normal)", "1.2x (Fast)", "1.5x (Faster)", "2.0x (Very Fast)")
        val speedValues = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f)
        val currentSpd = AudioPlayerManager.getInstance().getCurrentSpeed()
        var spdIdx = 0
        for (i in speedValues.indices) {
            if (speedValues[i] == currentSpd) spdIdx = i
        }

        val btnSpeed = createSettingRow(localize("Playback Speed"), localize(speedLabels[spdIdx]))
        btnSpeed.setOnClickListener {
            clickFeedback()
            val current = AudioPlayerManager.getInstance().getCurrentSpeed()
            var nextIdx = 0
            for (i in speedValues.indices) {
                if (speedValues[i] == current) nextIdx = (i + 1) % speedValues.size
            }
            AudioPlayerManager.getInstance().setPlaybackSpeed(speedValues[nextIdx])
            val tvStatus = btnSpeed.getChildAt(1) as? TextView
            tvStatus?.text = localize(speedLabels[nextIdx])
            Toast.makeText(
                activity,
                localize("Speed set to ") + localize(speedLabels[nextIdx]),
                Toast.LENGTH_SHORT
            ).show()
        }
        containerSettingsItems.addView(btnSpeed)

        var isSoundEffectEnabled = prefs.getBoolean("sound", true)
        val btnSound = createSettingRow(
            localize("Button Sound"),
            onOff(isSoundEffectEnabled, localize)
        )
        btnSound.setOnClickListener {
            isSoundEffectEnabled = !isSoundEffectEnabled
            prefs.edit().putBoolean("sound", isSoundEffectEnabled).commit()
            applySoundSetting()
            clickFeedback()
            val tvStatus = btnSound.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isSoundEffectEnabled, localize)
        }
        containerSettingsItems.addView(btnSound)

        val btnVibrateMenu = createSettingRow(localize("Vibration"), "〉 ")
        btnVibrateMenu.setOnClickListener {
            clickFeedback()
            buildVibrationSettingsUI()
        }
        containerSettingsItems.addView(btnVibrateMenu)

        var isScreenOffControlEnabled = prefs.getBoolean("screen_off_control", false)
        val btnScreenOffCtrl = createSettingRow(
            localize("Screen-Off Control"),
            onOff(isScreenOffControlEnabled, localize)
        )
        btnScreenOffCtrl.setOnClickListener {
            clickFeedback()
            isScreenOffControlEnabled = !isScreenOffControlEnabled
            prefs.edit().putBoolean("screen_off_control", isScreenOffControlEnabled).commit()
            mainActivity?.isScreenOffControlEnabled = isScreenOffControlEnabled
            val tvStatus = btnScreenOffCtrl.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isScreenOffControlEnabled, localize)
        }
        containerSettingsItems.addView(btnScreenOffCtrl)

        var isDoubleClickFavoriteEnabled = prefs.getBoolean("double_click_favorite", true)
        val btnDoubleClickFavorite = createSettingRow(
            localize("Double-Click to Favorite"),
            com.themoon.y1.core.extensions.onOff(isDoubleClickFavoriteEnabled, localize)
        )
        btnDoubleClickFavorite.setOnClickListener {
            clickFeedback()
            isDoubleClickFavoriteEnabled = !isDoubleClickFavoriteEnabled
            prefs.edit().putBoolean("double_click_favorite", isDoubleClickFavoriteEnabled).commit()
            val tvStatus = btnDoubleClickFavorite.getChildAt(1) as? TextView
            tvStatus?.text = com.themoon.y1.core.extensions.onOff(isDoubleClickFavoriteEnabled, localize)
        }
        containerSettingsItems.addView(btnDoubleClickFavorite)

        var isUsbPromptEnabled = prefs.getBoolean("usb_storage_prompt", true)
        val btnUsbPrompt = createSettingRow(
            localize("Ask on USB Connect"),
            onOff(isUsbPromptEnabled, localize)
        )
        btnUsbPrompt.setOnClickListener {
            clickFeedback()
            isUsbPromptEnabled = !isUsbPromptEnabled
            prefs.edit().putBoolean("usb_storage_prompt", isUsbPromptEnabled).commit()
            val tvStatus = btnUsbPrompt.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isUsbPromptEnabled, localize)
        }
        containerSettingsItems.addView(btnUsbPrompt)

        var backlightTimerIndex = prefs.getInt("backlight_timer_index", 3)
        if (backlightTimerIndex >= BACKLIGHT_TIMER_LABELS.size) backlightTimerIndex = 0
        val btnBacklightTimer = createSettingRow(
            localize("Backlight Timer"),
            localize(BACKLIGHT_TIMER_LABELS[backlightTimerIndex])
        )
        btnBacklightTimer.setOnClickListener {
            clickFeedback()
            backlightTimerIndex = (backlightTimerIndex + 1) % BACKLIGHT_TIMER_LABELS.size
            prefs.edit().putInt("backlight_timer_index", backlightTimerIndex).commit()
            val tvStatus = btnBacklightTimer.getChildAt(1) as? TextView
            tvStatus?.text = localize(BACKLIGHT_TIMER_LABELS[backlightTimerIndex])
            com.themoon.y1.managers.PowerBacklightManager.getInstance(activity).backlightTimerIndex = backlightTimerIndex
            resetBacklightTimer()
        }
        containerSettingsItems.addView(btnBacklightTimer)

        val btnTheme = createSettingRow(localize("Theme"), AutoThemeManager.settingsRowValue(prefs, localize))
        btnTheme.setOnClickListener {
            clickFeedback()
            buildThemeSelectorUI()
        }
        containerSettingsItems.addView(btnTheme)

        var isSwitchToggleStyle = ToggleStyle.isSwitch(activity)
        val btnToggleStyle = createSettingRow(localize("Toggle Style"), ToggleStyle.label(isSwitchToggleStyle, localize))
        btnToggleStyle.setOnClickListener {
            clickFeedback()
            isSwitchToggleStyle = !isSwitchToggleStyle
            ToggleStyle.setSwitch(activity, isSwitchToggleStyle)
            val tvStatus = btnToggleStyle.getChildAt(1) as? TextView
            tvStatus?.text = ToggleStyle.label(isSwitchToggleStyle, localize)
        }
        containerSettingsItems.addView(btnToggleStyle)

        val btnMenuVisibility = createSettingRow(localize("Main Menu Items"), localize("Edit") + " 〉")
        btnMenuVisibility.setOnClickListener {
            clickFeedback()
            buildMainMenuVisibilitySettingsUI()
        }
        containerSettingsItems.addView(btnMenuVisibility)

        val btnMusicMenuVisibility = createSettingRow(localize("Music Menu Items"), localize("Edit") + " 〉")
        btnMusicMenuVisibility.setOnClickListener {
            clickFeedback()
            buildMusicMenuVisibilitySettingsUI()
        }
        containerSettingsItems.addView(btnMusicMenuVisibility)

        val btnGroupAlbums = createSettingRow(localize("Group Albums"), groupAlbumsLabel())
        btnGroupAlbums.setOnClickListener {
            clickFeedback()
            prefs.edit().putBoolean(AlbumKey.PREF_BY_ARTIST, !AlbumKey.byArtist).commit()
            applyAlbumGrouping()
            (btnGroupAlbums.getChildAt(1) as? TextView)?.text = groupAlbumsLabel()
        }
        containerSettingsItems.addView(btnGroupAlbums)

        var isLoopScrollOn = prefs.getBoolean("loop_scroll_on", true)
        val btnLoopScrollToggle = createSettingRow(
            localize("Wheel Loop Scroll"),
            onOff(isLoopScrollOn, localize)
        )
        btnLoopScrollToggle.setOnClickListener {
            clickFeedback()
            isLoopScrollOn = !isLoopScrollOn
            prefs.edit().putBoolean("loop_scroll_on", isLoopScrollOn).commit()
            val tvStatus = btnLoopScrollToggle.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isLoopScrollOn, localize)
        }
        containerSettingsItems.addView(btnLoopScrollToggle)

        var isListEdgeFade = ListEdges.isFade(activity)
        val btnListEdges = createSettingRow(
            localize("List Edges"),
            if (isListEdgeFade) localize("Fade") else localize("Cut")
        )
        btnListEdges.setOnClickListener {
            clickFeedback()
            isListEdgeFade = !isListEdgeFade
            ListEdges.setFade(activity, isListEdgeFade)
            val tvStatus = btnListEdges.getChildAt(1) as? TextView
            tvStatus?.text = if (isListEdgeFade) localize("Fade") else localize("Cut")
        }
        containerSettingsItems.addView(btnListEdges)

        var currentTimeoutIndex = prefs.getInt("timeout_idx", 1)
        if (currentTimeoutIndex >= TIMEOUT_VALUES.size) currentTimeoutIndex = 0
        val btnTimeout = createSettingRow(
            localize("Screen Timeout"),
            localize(TIMEOUT_NAMES[currentTimeoutIndex])
        )
        btnTimeout.setOnClickListener {
            clickFeedback()
            currentTimeoutIndex = (currentTimeoutIndex + 1) % TIMEOUT_VALUES.size
            val tvStatus = btnTimeout.getChildAt(1) as? TextView
            tvStatus?.text = localize(TIMEOUT_NAMES[currentTimeoutIndex])
            try {
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    TIMEOUT_VALUES[currentTimeoutIndex]
                )
            } catch (_: Exception) {}
            prefs.edit().putInt("timeout_idx", currentTimeoutIndex).commit()
        }
        containerSettingsItems.addView(btnTimeout)

        val btnPowerOff = createSettingRow(localize("Power Off"), "〉 ")
        btnPowerOff.setOnClickListener {
            clickFeedback()
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(localize("Power Off"))
                .setMessage(localize("Do you want to shut down the device?"))
                .setPositiveButton(localize("Shut Down")) { _, _ ->
                    try {
                        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p"))
                        proc.waitFor()
                    } catch (_: Exception) {
                        try {
                            val intent = Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN")
                            intent.putExtra("android.intent.extra.KEY_CONFIRM", false)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            activity.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(
                                activity,
                                localize("System security prevents powering off directly from the app."),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                .setNegativeButton(localize("Cancel"), null)
                .show()
        }
        containerSettingsItems.addView(btnPowerOff)

        val btnServerMenu = createSettingRow(localize("Web Server"), "〉 ")
        btnServerMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_WEBSERVER)
        }
        containerSettingsItems.addView(btnServerMenu)

        // adb over Wi-Fi until the next restart (never persistent: root adb has no key check on this ROM).
        val btnWirelessAdb = createSettingRow(localize("Wireless ADB"), WirelessAdbManager.label(WirelessAdbManager.currentPort(), localize))
        // The address goes on the left of the value (of the switch), as the time left on the Bluetooth Discoverable row.
        val tvAdbAddress = addValueNote(btnWirelessAdb)
        fun showAdbState(port: Int?) {
            (btnWirelessAdb.getChildAt(btnWirelessAdb.childCount - 1) as? TextView)?.text = WirelessAdbManager.label(port, localize)
            val address = WirelessAdbManager.addressLabel(port, WirelessAdbManager.wifiIp(activity))
            tvAdbAddress.text = address ?: ""
            tvAdbAddress.visibility = if (address == null) View.GONE else View.VISIBLE
        }
        showAdbState(WirelessAdbManager.currentPort())
        btnWirelessAdb.setOnClickListener {
            clickFeedback()
            val turnOn = WirelessAdbManager.currentPort() == null
            WirelessAdbManager.setOn(activity, turnOn) { port ->
                showAdbState(port)
                if ((port != null) != turnOn) {
                    Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        containerSettingsItems.addView(btnWirelessAdb)

        val btnWifiMenu = createSettingRow(localize("Wi-Fi"), "〉 ")
        btnWifiMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_WIFI)
        }
        containerSettingsItems.addView(btnWifiMenu)

        val btnCellRadio = createSettingRow(
            localize("Cell Radio"),
            com.themoon.y1.core.extensions.onOff(CellRadioManager.isCellRadioOn(activity), localize)
        )
        btnCellRadio.setOnClickListener {
            clickFeedback()
            val turnOn = !CellRadioManager.isCellRadioOn(activity)
            CellRadioManager.setCellRadioOn(activity, turnOn) { isOn ->
                val tvStatus = btnCellRadio.getChildAt(1) as? TextView
                tvStatus?.text = com.themoon.y1.core.extensions.onOff(isOn, localize)
                if (isOn != turnOn) {
                    Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
                }
            }
        }
        containerSettingsItems.addView(btnCellRadio)

        val btnJellyfin = createSettingRow(
            localize("Jellyfin Sync"),
            JellyfinSyncProvider.getInstance(activity).getStatusSummary()
        )
        btnJellyfin.setOnClickListener {
            clickFeedback()
            onOpenJellyfin()
        }
        containerSettingsItems.addView(btnJellyfin)

        var isAutoFetchEnabled = prefs.getBoolean("auto_fetch", false)
        val btnAutoFetch = createSettingRow(
            localize("Auto Fetch Album Art"),
            onOff(isAutoFetchEnabled, localize)
        )
        btnAutoFetch.setOnClickListener {
            clickFeedback()
            isAutoFetchEnabled = !isAutoFetchEnabled
            prefs.edit().putBoolean("auto_fetch", isAutoFetchEnabled).commit()
            val tvStatus = btnAutoFetch.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isAutoFetchEnabled, localize)
        }
        containerSettingsItems.addView(btnAutoFetch)

        val btnClearCache = createSettingRow(localize("Clear Album Art & Info"), "〉 ")
        btnClearCache.setOnClickListener {
            clickFeedback()
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(localize("Clear Cache & Track Info"))
                .setMessage(localize("Delete all downloaded album covers and saved track information (Title/Artist)?"))
                .setPositiveButton(localize("Clear")) { _, _ ->
                    onClearCacheConfirmed()
                }
                .setNegativeButton(localize("Cancel"), null)
                .show()
        }
        containerSettingsItems.addView(btnClearCache)

        val btnBtMenu = createSettingRow(localize("Bluetooth"), "〉 ")
        btnBtMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_BLUETOOTH)
        }
        containerSettingsItems.addView(btnBtMenu)

        val scrobbler = LastFmScrobbler.getInstance(activity)
        val btnScrobbleToggle = createSettingRow(
            localize("Sync Scrobbles to Last.fm"),
            onOff(scrobbler.isEnabled(), localize)
        )
        btnScrobbleToggle.setOnClickListener {
            clickFeedback()
            val newState = !scrobbler.isEnabled()
            scrobbler.setEnabled(newState)
            val tvStatus = btnScrobbleToggle.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newState, localize)
        }
        containerSettingsItems.addView(btnScrobbleToggle)

        val btnLastFmAccount = createSettingRow(
            localize("Last.fm Account"),
            if (scrobbler.isLoggedIn()) "@" + scrobbler.getUsername() else localize("Not Logged In")
        )
        btnLastFmAccount.setOnClickListener {
            clickFeedback()
            if (scrobbler.isLoggedIn()) {
                AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(localize("Last.fm Account"))
                    .setMessage(localize("Log out of ") + "@" + scrobbler.getUsername() + "?")
                    .setPositiveButton(localize("Log Out")) { _, _ ->
                        scrobbler.logout()
                        buildSettingsUI()
                    }
                    .setNegativeButton(localize("Cancel"), null)
                    .show()
            } else {
                onNavigateToScreen(MainActivity.STATE_WIFI_KEYBOARD)
            }
        }
        containerSettingsItems.addView(btnLastFmAccount)

        val btnBrightMenu = createSettingRow(localize("Display Brightness"), "〉 ")
        btnBrightMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_BRIGHTNESS)
        }
        containerSettingsItems.addView(btnBrightMenu)

        val isShowPct = mainActivity?.isShowBatteryPercentage ?: prefs.getBoolean("show_battery_percentage", false)
        val btnBatteryPct = createSettingRow(
            localize("Battery Percentage"),
            onOff(isShowPct, localize)
        )
        btnBatteryPct.setOnClickListener {
            clickFeedback()
            val newPct = !(mainActivity?.isShowBatteryPercentage ?: prefs.getBoolean("show_battery_percentage", false))
            mainActivity?.isShowBatteryPercentage = newPct
            prefs.edit().putBoolean("show_battery_percentage", newPct).commit()
            val tvStatus = btnBatteryPct.getChildAt(1) as? TextView
            tvStatus?.text = onOff(newPct, localize)
            mainActivity?.batteryIconView?.setShowPercentage(newPct)
        }
        containerSettingsItems.addView(btnBatteryPct)

        val btnStorageMenu = createSettingRow(localize("About"), "〉 ")
        btnStorageMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(MainActivity.STATE_STORAGE)
        }
        containerSettingsItems.addView(btnStorageMenu)

        val btnBgMenu = createSettingRow(localize("Background"), "〉 ")
        btnBgMenu.setOnClickListener {
            clickFeedback()
            buildBackgroundSettingsUI()
        }
        containerSettingsItems.addView(btnBgMenu)

        val btnTime = createSettingRow(localize("Date & Time"), "〉")
        btnTime.setOnClickListener {
            clickFeedback()
            val c = Calendar.getInstance()
            dtYear = c.get(Calendar.YEAR)
            dtMonth = c.get(Calendar.MONTH) + 1
            dtDay = c.get(Calendar.DAY_OF_MONTH)
            dtHour = c.get(Calendar.HOUR_OF_DAY)
            dtMinute = c.get(Calendar.MINUTE)
            buildDateTimeUI()
        }
        containerSettingsItems.addView(btnTime)

        var myVersionName = "1.0"
        try {
            myVersionName = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {}

        val displayLang = LanguageManager.getInstance(activity).currentLangFileName.replace(".json", "")
        val btnLangMenu = createSettingRow(localize("Language"), displayLang)
        btnLangMenu.setOnClickListener {
            clickFeedback()
            buildLanguageSelectorUI()
        }
        containerSettingsItems.addView(btnLangMenu)

        val btnUpdateCheck = createSettingRow(localize("System Update"), "v$myVersionName")
        btnUpdateCheck.setOnClickListener {
            clickFeedback()
            buildUpdateSettingsUI()
        }
        containerSettingsItems.addView(btnUpdateCheck)

        val btnLegal = createSettingRow(localize("Legal"), "〉 ")
        btnLegal.setOnClickListener {
            clickFeedback()
            buildLegalSettingsUI()
        }
        containerSettingsItems.addView(btnLegal)

        SettingsBackupUi.addRows(activity, containerSettingsItems, createSettingRow, clickFeedback, localize)

        val btnResetAll = createSettingRow(localize("Reset All Settings"), "〉 ")
        btnResetAll.setOnClickListener {
            clickFeedback()
            AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(localize("Reset All Settings"))
                .setMessage(localize("Reset all settings to their default values? This won't delete any music or playlists."))
                .setPositiveButton(localize("Reset")) { _, _ ->
                    resetAllAppSettings()
                }
                .setNegativeButton(localize("Cancel"), null)
                .show()
        }
        containerSettingsItems.addView(btnResetAll)

        val sv = containerSettingsItems.parent as? ScrollView
        sv?.post {
            if (targetFocusIndex in 0 until containerSettingsItems.childCount) {
                val target = containerSettingsItems.getChildAt(targetFocusIndex)
                if (sv != null && target != null) {
                    val offset = (sv.height / 2) - (target.height / 2)
                    val targetY = (target.top - offset).coerceAtLeast(0)
                    sv.scrollTo(0, targetY)
                }
                target?.requestFocus()
                lastSettingsFocusIndex = targetFocusIndex
            } else if (containerSettingsItems.childCount > 0) {
                val first = containerSettingsItems.getChildAt(0)
                first?.requestFocus()
                sv?.scrollTo(0, 0)
                lastSettingsFocusIndex = 0
            }
            isUiActive = true
        }
    }

    private fun buildLanguageSelectorUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val langMgr = LanguageManager.getInstance(activity)
        langMgr.loadAvailableLanguages()

        val isEngSelected = langMgr.currentLangFileName == "English (Default)"
        val btnEng = createTablerListButton(
            if (isEngSelected) TablerIcons.CHECK else null,
            "English (Default)",
            ThemeManager.getTextColorPrimary(),
            ThemeManager.getListButtonFocusedTextColor()
        )
        btnEng.setOnClickListener {
            clickFeedback()
            prefs.edit().putString("app_language", "English (Default)").commit()
            langMgr.applyLanguage("English (Default)")
            activity.recreate()
        }
        containerSettingsItems.addView(btnEng)

        for (f in langMgr.availableLangFiles) {
            val isSelected = langMgr.currentLangFileName == f.name
            val btnLang = createTablerListButton(
                if (isSelected) TablerIcons.CHECK else null,
                f.name.replace(".json", ""),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btnLang.setOnClickListener {
                clickFeedback()
                prefs.edit().putString("app_language", f.name).commit()
                langMgr.applyLanguage(f.name)
                activity.recreate()
            }
            containerSettingsItems.addView(btnLang)
        }

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildThemeSelectorUI() {
        currentSettingsDepth = 1
        val themeFolder = File(activity.filesDir, "themes")
        ThemeManager.loadThemesFromStorage(themeFolder)
        containerSettingsItems.removeAllViews()
        containerSettingsItems.addView(
            AutoThemeManager.createSelectorRow(activity, prefs, createTablerListButton, clickFeedback, localize)
        )

        for (i in 0 until ThemeManager.availableThemes.size) {
            val theme = ThemeManager.availableThemes[i]
            val isSelected = ThemeManager.getCurrentThemeIndex() == i && !AutoThemeManager.isEnabled(prefs)
            val icon = if (isSelected) TablerIcons.CHECK else null
            val btn = createTablerListButton(
                icon,
                localize(theme.name),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btn.setOnClickListener {
                clickFeedback()
                AutoThemeManager.turnOff(activity, prefs)
                // Live: the theme list is made again in the new theme, with the focus on this row
                mainActivity?.changeTheme(theme.name)
            }
            containerSettingsItems.addView(btn)
        }

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
        themeListFirstRow = containerSettingsItems.getChildAt(0)
    }

    private fun buildVibrationSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var isVibrationEnabled = prefs.getBoolean("vibrate", true)
        val btnToggle = createSettingRow(
            localize("Vibration Power"),
            onOff(isVibrationEnabled, localize)
        )
        btnToggle.setOnClickListener {
            isVibrationEnabled = !isVibrationEnabled
            mainActivity?.isVibrationEnabled = isVibrationEnabled
            clickFeedback()
            val tv = btnToggle.getChildAt(1) as? TextView
            tv?.text = onOff(isVibrationEnabled, localize)
            try {
                prefs.edit().putBoolean("vibrate", isVibrationEnabled).commit()
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    if (isVibrationEnabled) 1 else 0
                )
            } catch (_: Exception) {}
        }
        containerSettingsItems.addView(btnToggle)

        var vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1)
        if (vibrationStrengthLevel >= VIBE_STRENGTH_NAMES.size) vibrationStrengthLevel = 1
        val btnStrength = createSettingRow(
            localize("Vibration Strength"),
            localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
        )
        btnStrength.setOnClickListener {
            vibrationStrengthLevel = (vibrationStrengthLevel + 1) % 3
            mainActivity?.vibrationStrengthLevel = vibrationStrengthLevel
            clickFeedback()
            val tv = btnStrength.getChildAt(1) as? TextView
            tv?.text = localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
            try {
                prefs.edit().putInt("vibrate_strength", vibrationStrengthLevel).commit()
            } catch (_: Exception) {}
        }
        containerSettingsItems.addView(btnStrength)

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildBackgroundSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val btnSelectBg = createSettingRow(localize("Select New Background"), "〉 ")
        btnSelectBg.setOnClickListener {
            clickFeedback()
            onPickBackgroundFromStorage()
        }
        containerSettingsItems.addView(btnSelectBg)

        val btnThemeBg = createSettingRow(localize("Apply Theme Background"), "〉 ")
        btnThemeBg.setOnClickListener {
            clickFeedback()
            onApplyThemeBackground()
        }
        containerSettingsItems.addView(btnThemeBg)

        val btnClearBg = createSettingRow(localize("Clear Custom Background"), "〉 ")
        btnClearBg.setOnClickListener {
            clickFeedback()
            onClearCustomBackground()
        }
        containerSettingsItems.addView(btnClearBg)

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildDateTimeUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var is24HourFormat = prefs.getBoolean(ClockSettings.KEY_24_HOUR_FORMAT, ClockSettings.DEFAULT_24_HOUR_FORMAT)
        val formatRightText = if (is24HourFormat) localize("24 Hour") else localize("12 Hour")
        val rowFormat = createSettingRow(localize("Time Format"), formatRightText)
        rowFormat.setOnClickListener {
            clickFeedback()
            is24HourFormat = !is24HourFormat
            mainActivity?.is24HourFormat = is24HourFormat
            prefs.edit().putBoolean(ClockSettings.KEY_24_HOUR_FORMAT, is24HourFormat).commit()
            mainActivity?.onClockSettingsChanged()
            buildDateTimeUI()
        }
        containerSettingsItems.addView(rowFormat)

        var isTimeInTitleBar = prefs.getBoolean(ClockSettings.KEY_TIME_IN_TITLE, ClockSettings.DEFAULT_TIME_IN_TITLE)
        val rowTitleClock = createSettingRow(
            localize("Time in Title"),
            onOff(isTimeInTitleBar, localize)
        )
        rowTitleClock.setOnClickListener {
            clickFeedback()
            isTimeInTitleBar = !isTimeInTitleBar
            mainActivity?.isTimeInTitleBar = isTimeInTitleBar
            prefs.edit().putBoolean(ClockSettings.KEY_TIME_IN_TITLE, isTimeInTitleBar).commit()
            mainActivity?.onClockSettingsChanged()
            val tvStatus = rowTitleClock.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isTimeInTitleBar, localize)
        }
        containerSettingsItems.addView(rowTitleClock)

        val rowTimeZone = createSettingRow(localize("Time Zone"), timeZoneCityName(TimeZone.getDefault().id))
        rowTimeZone.setOnClickListener {
            clickFeedback()
            buildTimeZoneSelectorUI()
        }
        containerSettingsItems.addView(rowTimeZone)

        val rowYear = createSettingRow(localize("Year"), dtYear.toString())
        rowYear.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Year", 2020, 2035, dtYear)
        }
        containerSettingsItems.addView(rowYear)

        val rowMonth = createSettingRow(localize("Month"), String.format(Locale.US, "%02d", dtMonth))
        rowMonth.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Month", 1, 12, dtMonth)
        }
        containerSettingsItems.addView(rowMonth)

        val rowDay = createSettingRow(localize("Day"), String.format(Locale.US, "%02d", dtDay))
        rowDay.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Day", 1, 31, dtDay)
        }
        containerSettingsItems.addView(rowDay)

        val rowHour = createSettingRow(localize("Hour (24H)"), String.format(Locale.US, "%02d", dtHour))
        rowHour.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Hour", 0, 23, dtHour)
        }
        containerSettingsItems.addView(rowHour)

        val rowMinute = createSettingRow(localize("Minute"), String.format(Locale.US, "%02d", dtMinute))
        rowMinute.setOnClickListener {
            clickFeedback()
            buildDateTimeSelectorUI("Minute", 0, 59, dtMinute)
        }
        containerSettingsItems.addView(rowMinute)

        createCategoryHeader("━━━━━━━━━━━━━━")

        val btnApply = createListButton(localize("APPLY DATE & TIME"))
        btnApply.setTextColor(0xFFFFFFFF.toInt())
        btnApply.typeface = ThemeManager.getCustomFontBold()
        btnApply.setOnClickListener {
            clickFeedback()
            applySystemDateTime()
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnApply)

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    private fun applySystemDateTime() {
        try {
            val cmd = "settings put global auto_time 0; settings put system auto_time 0; "
            val targetYMD = String.format(Locale.US, "%04d%02d%02d", dtYear, dtMonth, dtDay)
            val dateToolbox = String.format(
                Locale.US, "%04d%02d%02d.%02d%02d%02d",
                dtYear, dtMonth, dtDay, dtHour, dtMinute, 0
            )
            val datePosix = String.format(
                Locale.US, "%02d%02d%02d%02d%04d.00",
                dtMonth, dtDay, dtHour, dtMinute, dtYear
            )
            val dateString = String.format(
                Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                dtYear, dtMonth, dtDay, dtHour, dtMinute, 0
            )
            val executeCmd = cmd +
                "date -s $dateToolbox; " +
                "if [ \"$(date +%Y%m%d)\" != \"$targetYMD\" ]; then " +
                "  date $datePosix; " +
                "  if [ \"$(date +%Y%m%d)\" != \"$targetYMD\" ]; then " +
                "    date -s \"$dateString\"; " +
                "  fi; " +
                "fi; " +
                "hwclock -w; sync"

            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", executeCmd))
            proc.waitFor()
            activity.sendBroadcast(Intent(Intent.ACTION_TIME_CHANGED))
            Toast.makeText(activity, localize("Time applied successfully!"), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySystemTimeZone(zoneId: String) {
        try {
            // AlarmManager.setTimeZone needs SET_TIME_ZONE (signature|system), so call it through su.
            // Transaction 3 is IAlarmManager.setTimeZone on API 19: it saves persist.sys.timezone,
            // updates the kernel zone and broadcasts ACTION_TIMEZONE_CHANGED to refresh the clocks.
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call alarm 3 s16 $zoneId"))
            proc.waitFor()
            TimeZone.setDefault(null)
            if (TimeZone.getDefault().id != zoneId) {
                Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(activity, localize("Failed: Root access required."), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildTimeZoneSelectorUI() {
        currentSettingsDepth = 2
        containerSettingsItems.removeAllViews()

        val currentId = TimeZone.getDefault().id
        val now = System.currentTimeMillis()
        val ids = if (currentId in TIME_ZONE_IDS) TIME_ZONE_IDS else arrayOf(currentId) + TIME_ZONE_IDS
        var focusBtn: View? = null
        for (id in ids.sortedBy { TimeZone.getTimeZone(it).getOffset(now) }) {
            val isSelected = id == currentId
            val btn = createTablerListButton(
                if (isSelected) TablerIcons.CHECK else null,
                timeZoneLabel(id, now),
                ThemeManager.getTextColorPrimary(),
                ThemeManager.getListButtonFocusedTextColor()
            )
            btn.setOnClickListener {
                clickFeedback()
                applySystemTimeZone(id)
                // The clock itself is unchanged, but its local time moved, so reload the picker values
                val c = Calendar.getInstance()
                dtYear = c.get(Calendar.YEAR)
                dtMonth = c.get(Calendar.MONTH) + 1
                dtDay = c.get(Calendar.DAY_OF_MONTH)
                dtHour = c.get(Calendar.HOUR_OF_DAY)
                dtMinute = c.get(Calendar.MINUTE)
                buildDateTimeUI()
            }
            containerSettingsItems.addView(btn)
            if (isSelected) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildDateTimeSelectorUI(type: String, min: Int, max: Int, currentValue: Int) {
        currentSettingsDepth = 2
        containerSettingsItems.removeAllViews()

        var focusBtn: Button? = null
        for (i in min..max) {
            val valNum = i
            val displayVal = if (type == "Minute" || type == "Hour" || type == "Month" || type == "Day") {
                String.format(Locale.US, "%02d", valNum)
            } else {
                valNum.toString()
            }
            val btn = createListButton(displayVal)
            btn.gravity = Gravity.CENTER
            btn.setOnClickListener {
                clickFeedback()
                when (type) {
                    "Year" -> dtYear = valNum
                    "Month" -> dtMonth = valNum
                    "Day" -> dtDay = valNum
                    "Hour" -> dtHour = valNum
                    "Minute" -> dtMinute = valNum
                }
                buildDateTimeUI()
            }
            containerSettingsItems.addView(btn)
            if (valNum == currentValue) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    /** Volume Limit: the loudest volume step (1..30, see VolumeSteps), or Off. */
    private fun buildVolumeLimitSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val volumeLimitMax = prefs.getInt(com.themoon.y1.managers.VolumeSteps.PREF_LIMIT, -1)
        var focusBtn: Button? = null

        val btnOff = createListButton(localize("Off"))
        btnOff.gravity = Gravity.CENTER
        btnOff.setOnClickListener {
            clickFeedback()
            setVolumeLimit(-1)
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnOff)
        if (volumeLimitMax < 0) focusBtn = btnOff

        for (i in 1..com.themoon.y1.managers.VolumeSteps.STEPS) {
            val level = i
            val btn = createListButton(level.toString())
            btn.gravity = Gravity.CENTER
            btn.setOnClickListener {
                clickFeedback()
                setVolumeLimit(level)
                buildSettingsUI()
            }
            containerSettingsItems.addView(btn)
            if (level == volumeLimitMax) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun setVolumeLimit(limitStep: Int) {
        prefs.edit().putInt(com.themoon.y1.managers.VolumeSteps.PREF_LIMIT, limitStep).commit()
        mainActivity?.setVolumeLimit(limitStep)
    }

    private fun buildLegalSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val tvLegal = TextView(activity).apply {
            text = "JJ Launcher Classic Version\n\n" +
                "A fan-made, non-commercial fork of JJ Launcher (github.com/ismileblue/y1_launcher), " +
                "built to bring an iPod Classic-style experience to the Innioasis Y1.\n\n" +
                "This software is not affiliated with or endorsed by Apple Inc.\n\n" +
                "Open source components used:\n" +
                "- ExoPlayer (Apache License 2.0)\n" +
                "- OkHttp (Apache License 2.0)\n" +
                "- Conscrypt (Apache License 2.0)\n" +
                "- Nimbus Sans (URW++, GUST Font License)\n\n" +
                "See the project repository for full license texts and source code."
            setTextColor(ThemeManager.getTextColorPrimary())
            textSize = 14f
            setLineSpacing(6f, 1.2f)
            val d = resources.displayMetrics.density
            setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
        }
        containerSettingsItems.addView(tvLegal)
    }

    private fun buildUpdateSettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var myVersionName = "1.0"
        try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            myVersionName = pInfo.versionName ?: "1.0"
        } catch (_: Exception) {}

        val rowCurrent = createSettingRow(
            localize("Current Version"),
            if (myVersionName.startsWith("v")) myVersionName else "v$myVersionName"
        )
        containerSettingsItems.addView(rowCurrent)

        createCategoryHeader("━━━━━━━━━━━━━━")

        val btnCheckUpdates = createTablerListButton(
            TablerIcons.REFRESH,
            localize("Check for Updates"),
            ThemeManager.getTextColorPrimary(),
            ThemeManager.getListButtonFocusedTextColor()
        )
        btnCheckUpdates.setOnClickListener {
            clickFeedback()
            onOpenUpdateCheck()
        }
        containerSettingsItems.addView(btnCheckUpdates)

        btnCheckUpdates.post { btnCheckUpdates.requestFocus() }
    }

    private fun buildMainMenuVisibilitySettingsUI() {
        currentSettingsDepth = 1
        settingsSubMode = 4
        containerSettingsItems.removeAllViews()

        val buttons = ArrayList<ThemeManager.MenuElement>()
        for (el in ThemeManager.getCurrentTheme().menuElements) {
            if (el.type == "button" && com.themoon.y1.FeatureVisibility.isBuilt(el.action)) buttons.add(el)
        }
        sortMenuElements(buttons)

        if (materialIconFont == null) {
            try {
                materialIconFont = Typeface.createFromAsset(activity.assets, "fonts/MaterialIcons-Regular.ttf")
            } catch (_: Exception) {}
        }

        for (el in buttons) {
            val isHidden = prefs.getBoolean("hide_btn_" + el.id, false)
            val btnName = if (!el.textNormal.isNullOrBlank()) el.textNormal else el.id
            val stateIcon = if (isHidden) "\uE5CD" else "\uE8F4"

            val row = createSettingRow(btnName, stateIcon)
            row.tag = el.id
            row.isFocusable = true

            val tvRight = row.getChildAt(1) as? TextView
            if (materialIconFont != null) {
                tvRight?.typeface = materialIconFont
                tvRight?.textSize = 22f
                tvRight?.let { centreIconInRow(it) }
            }

            // Keep the row highlight of createSettingRow: this listener replaces its listener.
            val rowHighlight = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, hasFocus ->
                if (isMenuReorderMode) return@setOnFocusChangeListener
                rowHighlight?.onFocusChange(v, hasFocus)
                val currHidden = prefs.getBoolean("hide_btn_" + el.id, false)
                if (hasFocus) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (currHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }

            row.setOnLongClickListener {
                clickFeedback()
                if (!isMenuReorderMode) toggleMenuReorderMode()
                true
            }

            row.setOnClickListener {
                if (isMenuReorderMode) {
                    clickFeedback()
                    toggleMenuReorderMode()
                    return@setOnClickListener
                }
                clickFeedback()
                val newState = !prefs.getBoolean("hide_btn_" + el.id, false)
                prefs.edit().putBoolean("hide_btn_" + el.id, newState).commit()
                com.themoon.y1.FeatureVisibility.onMenuButtonToggled(activity, el.action, newState)
                tvRight?.text = if (newState) "\uE5CD" else "\uE8F4"
                if (row.hasFocus()) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (newState) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }
            containerSettingsItems.addView(row)
        }
        containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildMusicMenuVisibilitySettingsUI() {
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        if (materialIconFont == null) {
            try {
                materialIconFont = Typeface.createFromAsset(activity.assets, "fonts/MaterialIcons-Regular.ttf")
            } catch (_: Exception) {}
        }

        for (item in MUSIC_MENU_ITEMS) {
            val id = item[0]
            val isHidden = com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
            val stateIcon = if (isHidden) "\uE5CD" else "\uE8F4"

            val row = createSettingRow(localize(item[1]), stateIcon)
            val tvRight = row.getChildAt(1) as? TextView
            if (materialIconFont != null) {
                tvRight?.typeface = materialIconFont
                tvRight?.textSize = 22f
                tvRight?.let { centreIconInRow(it) }
            }
            tvRight?.setTextColor(
                if (isHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
            )

            // Keep the row highlight of createSettingRow: this listener replaces its listener.
            val rowHighlight = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, hasFocus ->
                rowHighlight?.onFocusChange(v, hasFocus)
                val currHidden = com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
                if (hasFocus) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (currHidden) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }

            row.setOnClickListener {
                clickFeedback()
                val newState = !com.themoon.y1.controllers.browser.MusicMenuItems.isHidden(prefs, id)
                prefs.edit().putBoolean("hide_music_btn_$id", newState).commit()
                tvRight?.text = if (newState) "\uE5CD" else "\uE8F4"
                if (row.hasFocus()) {
                    tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                } else {
                    tvRight?.setTextColor(
                        if (newState) ThemeManager.getTextColorSecondary() else ThemeManager.getTextColorPrimary()
                    )
                }
            }
            containerSettingsItems.addView(row)
        }
        containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun sortMenuElements(buttons: ArrayList<ThemeManager.MenuElement>) {
        com.themoon.y1.controllers.menu.MenuOrder.sort(buttons, prefs)
    }

    /**
     * The icon glyphs fill the em box of the icon font (no descent), so a box of one line without font
     * padding, centred in the row, centres the icons. A list style can have set a top padding for text.
     */
    private fun centreIconInRow(tv: TextView) {
        tv.includeFontPadding = false
        tv.gravity = Gravity.CENTER_VERTICAL
        tv.setPadding(tv.paddingLeft, 0, tv.paddingRight, 0)
        (tv.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.gravity = Gravity.CENTER_VERTICAL
            tv.layoutParams = it
        }
    }

    fun toggleMenuReorderMode() {
        // Material icon "swap_vert", from the same font as the eye icon. The font has no "↕" (and no space),
        // so a fallback glyph sat on the icon baseline, below the row centre.
        val reorderMark = "\uE8D5"
        if (!isMenuReorderMode) {
            val focused = activity.currentFocus
            if (focused != null && focused.parent == containerSettingsItems) {
                isMenuReorderMode = true
                currentReorderRow = focused
                currentReorderRow?.setBackgroundColor(0xFFD32F2F.toInt())
                val tvRight = (currentReorderRow as? LinearLayout)?.getChildAt(1) as? TextView
                val currentIcon = tvRight?.text?.toString()?.replace(reorderMark, "") ?: ""
                tvRight?.text = currentIcon + reorderMark
                tvRight?.setTextColor(0xFFFFFFFF.toInt())
            }
        } else {
            isMenuReorderMode = false
            if (currentReorderRow != null) {
                // Replace the red reorder background with the normal row highlight.
                currentReorderRow?.let { it.onFocusChangeListener?.onFocusChange(it, it.hasFocus()) }
                val tvRight = (currentReorderRow as? LinearLayout)?.getChildAt(1) as? TextView
                val currentIcon = tvRight?.text?.toString()?.replace(reorderMark, "") ?: ""
                tvRight?.text = currentIcon
                tvRight?.setTextColor(ThemeManager.getListButtonFocusedTextColor())
                currentReorderRow = null
            }
            val sb = StringBuilder()
            for (i in 0 until containerSettingsItems.childCount) {
                val child = containerSettingsItems.getChildAt(i)
                val tagStr = child.tag as? String
                if (!tagStr.isNullOrEmpty()) {
                    sb.append(tagStr).append(",")
                }
            }
            com.themoon.y1.controllers.menu.MenuOrder.save(prefs, sb.toString())
            Toast.makeText(activity, localize("Menu order saved!"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun groupAlbumsLabel(): String = localize(if (AlbumKey.byArtist) "By Title and Artist" else "By Title")

    /** Reads Group Albums and rebuilds the album lists, so the next Albums or Cover Flow screen uses it. */
    private fun applyAlbumGrouping() {
        AlbumKey.byArtist = prefs.getBoolean(AlbumKey.PREF_BY_ARTIST, false)
        mainActivity?.rebuildUniqueAlbumList()
    }

    private fun resetAllAppSettings() {
        AutoThemeManager.reset(activity, prefs)
        try {
            prefs.edit()
                .remove(ClockSettings.KEY_24_HOUR_FORMAT)
                .remove(ClockSettings.KEY_TIME_IN_TITLE)
                .remove("show_battery_percentage")
                .remove("volume_limit_max")
                .remove(ToggleStyle.PREF_KEY)
                .remove("sound_check_enabled")
                .remove("video_stretch_fill")
                .remove(com.themoon.y1.views.IpodClassicLayout.PREF_KEY)
                .remove("album_tilt_enabled")
                .remove("show_total_time")
                .remove("backlight_timer_index")
                .remove("screen_off_control")
                .remove("double_click_favorite")
                .remove("repeat_mode")
                .remove("repeat")
                .remove("loop_scroll_on")
                .remove(ListEdges.PREF_KEY)
                .remove("eq_preset")
                .remove("eq_profile_id")
                .remove(AlbumKey.PREF_BY_ARTIST)
                .remove("auto_fetch")
                .commit()
        } catch (_: Exception) {}

        mainActivity?.isSoundCheckEnabled = false
        AudioEffectManager.getInstance().applySoundCheck()
        ToggleStyle.reload(activity)
        AudioEffectManager.getInstance().applyEqProfile()
        applyAlbumTiltSetting()
        ListEdges.reload(activity)
        com.themoon.y1.managers.PowerBacklightManager.getInstance(activity).backlightTimerIndex = 3
        resetBacklightTimer()
        applyAlbumGrouping()

        mainActivity?.isScreenOffControlEnabled = false
        mainActivity?.isShowBatteryPercentage = false
        mainActivity?.isTimeInTitleBar = ClockSettings.DEFAULT_TIME_IN_TITLE
        mainActivity?.is24HourFormat = ClockSettings.DEFAULT_24_HOUR_FORMAT
        mainActivity?.onClockSettingsChanged()
        mainActivity?.batteryIconView?.setShowPercentage(false)
        mainActivity?.setVolumeLimit(-1)

        Toast.makeText(activity, localize("All settings have been reset."), Toast.LENGTH_SHORT).show()
        buildSettingsUI()
    }

    /**
     * A text on the left of the value of a setting row [row], in the value's text style, with its digits on the
     * centre line of the row (as the row title and the switch). Hidden until the caller gives it a text.
     */
    private fun addValueNote(row: LinearLayout): TextView {
        val value = row.getChildAt(row.childCount - 1) as? TextView
        val note = TextView(activity).apply {
            if (value != null) {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, value.textSize)
                typeface = value.typeface
                setTextColor(value.currentTextColor)
            }
            includeFontPadding = false
            val padRight = (8 * activity.resources.displayMetrics.density).toInt()
            val rowHeight = row.layoutParams?.height ?: 0
            if (rowHeight > 0) {
                // Full row height, gravity TOP, and a top padding that puts the centre of the cap height ("H")
                // on the centre line of the row, in all themes.
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
                gravity = Gravity.TOP or Gravity.END
                val path = Path()
                paint.getTextPath("H", 0, 1, 0f, 0f, path)
                val bounds = RectF()
                path.computeBounds(bounds, true)
                val top = Math.round(rowHeight / 2f + paint.fontMetricsInt.ascent - bounds.top / 2f).coerceAtLeast(0)
                setPadding(0, top, padRight, 0)
            } else {
                setPadding(0, 0, padRight, 0)
            }
            visibility = View.GONE
        }
        row.addView(note, row.childCount - 1)
        // The row changes the value colour when it gets or loses the focus: give the same colour to the note.
        val rowFocusListener = row.onFocusChangeListener
        row.setOnFocusChangeListener { v, hasFocus ->
            rowFocusListener?.onFocusChange(v, hasFocus)
            if (value != null) note.setTextColor(value.currentTextColor)
        }
        return note
    }
}
