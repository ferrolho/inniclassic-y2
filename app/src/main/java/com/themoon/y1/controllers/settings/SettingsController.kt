package com.themoon.y1.controllers.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.themoon.y1.MainActivity
import com.themoon.y1.R
import com.themoon.y1.StoragePaths
import com.themoon.y1.ThemeManager
import com.themoon.y1.controllers.ScreenController
import com.themoon.y1.core.extensions.onOff
import com.themoon.y1.managers.SafeVolumeBypasser
import com.themoon.y1.managers.AudioEffectManager
import com.themoon.y1.managers.AudioPlayerManager
import com.themoon.y1.managers.FmRadioManager
import com.themoon.y1.managers.LanguageManager
import com.themoon.y1.managers.LastFmScrobbler
import com.themoon.y1.models.AlbumKey
import com.themoon.y1.navigation.NavigationManager
import com.themoon.y1.managers.PowerMenu
import com.themoon.y1.navigation.ScreenState
import com.themoon.y1.navigation.SettingsSubMode
import com.themoon.y1.sync.jellyfin.JellyfinSyncProvider
import com.themoon.y1.views.TablerIcons
import com.themoon.y1.views.ToggleStyle
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Controller for Settings screens:
 * Root settings menu, Date & Time, Theme selector, Language selector,
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
        set(value) {
            val old = field
            field = value
            // Title: the group name on a group list, the row name on a row's sub-screen (SettingsGroups.kt)
            groupPages.onDepthChanged(old, value, containerSettingsItems, mainActivity?.tvStatusBarTitle, localize)
        }
    var settingsSubMode: Int = 0
    private var isUiActive = false
    /** The Power screen (Restart, Power Off) is open: Back goes to the screen before it. */
    private var isPowerScreen = false

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
        /** The values of each Date & Time row's screen (Settings → Date & Time → Year...). */
        val DATE_TIME_RANGES = mapOf("Year" to 2020..2035, "Month" to 1..12, "Day" to 1..31, "Hour" to 0..23, "Minute" to 0..59)

        /** Backlight Timer is the one screen timer: it also sets Android's SCREEN_OFF_TIMEOUT (see BacklightTimer). */
        val BACKLIGHT_TIMER_LABELS = com.themoon.y1.managers.BacklightTimer.LABELS
        val VIBE_STRENGTH_NAMES = arrayOf("Weak", "Normal (Vibe)", "Strong")

        val MUSIC_MENU_ITEMS = arrayOf(
            arrayOf("coverflow", "Cover Flow"),
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

    // Groups on the top level (SettingsGroups.kt). The rows stay in buildSettingsUI; the groups only move them.
    private val groupPages = SettingsGroupPages()

    /** The list that shows, as its route key: the top level, a group, or the screen of a row (a back-stack entry). */
    private var page: SettingsSubMode = SettingsSubMode.Root
    /** currentSettingsDepth of [page]; a deeper depth is a sub-screen that a row opened in place. */
    private var pageDepth = 0

    /** Builds the list of [state]: when it opens going down, and when Back comes back to it (NavigationManager). */
    override fun onEnter(state: ScreenState) {
        page = (state as? ScreenState.Settings)?.subMode ?: SettingsSubMode.Root
        when (val shown = page) {
            is SettingsSubMode.Page -> buildPage(shown)
            is SettingsSubMode.Power -> buildPowerUI()
            else -> {
                val title = (shown as? SettingsSubMode.Group)?.title
                groupPages.show(SettingsGroups.GROUPS.indexOfFirst { it.title == title })
                buildSettingsUI()
            }
        }
        pageDepth = currentSettingsDepth
    }

    private fun buildPage(shown: SettingsSubMode.Page) {
        when (shown.id) {
            "Language" -> buildLanguageSelectorUI()
            "Theme" -> buildThemeSelectorUI()
            "Background" -> buildBackgroundSettingsUI()
            "DateTime" -> {
                val range = DATE_TIME_RANGES[shown.arg]
                if (range == null) buildDateTimeUI() else buildDateTimeSelectorUI(shown.arg, range.first, range.last, dateTimeValue(shown.arg))
            }
            "VolumeLimit" -> buildVolumeLimitSettingsUI()
            "Legal" -> buildLegalSettingsUI()
            "Update" -> buildUpdateSettingsUI()
            "MainMenuItems" -> buildMainMenuVisibilitySettingsUI()
            "MusicMenuItems" -> buildMusicMenuVisibilitySettingsUI()
        }
    }

    /**
     * True when the row screen [id] is the list that shows: the caller builds it now. Else this opens it and
     * returns false: Back to it when it is the list under this one (a value was chosen), or one level down.
     */
    private fun showsPage(id: String, arg: String = ""): Boolean {
        val target = SettingsSubMode.Page(id, arg)
        if (page == target) return true
        val state = ScreenState.Settings(target)
        val nav = NavigationManager.getInstance()
        if (nav.previousState() == state) nav.popBackStack() else mainActivity?.changeScreen(state)
        return false
    }

    private fun dateTimeValue(type: String): Int = when (type) {
        "Year" -> dtYear
        "Month" -> dtMonth
        "Day" -> dtDay
        "Hour" -> dtHour
        else -> dtMinute
    }

    override fun onExit() {
        isUiActive = false
    }

    override fun onRefreshUI() {
        if (isPowerScreen) {
            buildPowerUI()
        } else if (currentSettingsDepth == 0) {
            buildSettingsUI()
        }
    }

    /** Back: the list of the top back-stack entry (NavigationManager builds it again and selects its row). */
    override fun onBackPressed(): Boolean {
        if (isPowerScreen) {
            onNavigateBack()
            return true
        }
        if (isMenuReorderMode) {
            toggleMenuReorderMode()
            return true
        }
        clickFeedback()
        // A sub-screen that a row opened in place (not a back-stack entry): back to the list of this entry
        if (currentSettingsDepth > pageDepth) onEnter(ScreenState.Settings(page)) else onNavigateBack()
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
        if (isMenuReorderMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
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
        isPowerScreen = false
        // A row's screen asks for its list (a value was chosen): Back to that list
        if (page is SettingsSubMode.Page) {
            onNavigateBack()
            return
        }
        // A new build of the list that shows (a row changed a value) keeps the focused row; a list that opens starts at its first row
        var focused: View? = if (isUiActive && currentSettingsDepth == 0) containerSettingsItems.findFocus() else null
        while (focused != null && focused.parent !== containerSettingsItems) focused = focused.parent as? View
        val targetFocusIndex = if (focused != null) containerSettingsItems.indexOfChild(focused) else -1
        currentSettingsDepth = 0
        settingsSubMode = 0
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
        containerSettingsItems.addView(btnVideoFill)

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

        // Settings → Controls: Button Vibration and Vibration Strength (no Vibration sub-screen).
        // Vibration Strength is grey and the wheel skips it while Button Vibration is off.
        var isVibrationEnabled = prefs.getBoolean("vibrate", true)
        var vibrationStrengthLevel = prefs.getInt("vibrate_strength", 1)
        if (vibrationStrengthLevel !in VIBE_STRENGTH_NAMES.indices) vibrationStrengthLevel = 1
        val btnVibration = createSettingRow(
            localize("Button Vibration"),
            onOff(isVibrationEnabled, localize)
        )
        val btnVibrationStrength = createSettingRow(
            localize("Vibration Strength"),
            localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
        )
        btnVibration.setOnClickListener {
            isVibrationEnabled = !isVibrationEnabled
            mainActivity?.isVibrationEnabled = isVibrationEnabled
            clickFeedback()
            val tvStatus = btnVibration.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isVibrationEnabled, localize)
            StatusBarSettings.setRowEnabled(btnVibrationStrength, isVibrationEnabled)
            try {
                prefs.edit().putBoolean("vibrate", isVibrationEnabled).commit()
                Settings.System.putInt(
                    activity.contentResolver,
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    if (isVibrationEnabled) 1 else 0
                )
            } catch (_: Exception) {}
        }
        btnVibrationStrength.setOnClickListener {
            vibrationStrengthLevel = (vibrationStrengthLevel + 1) % VIBE_STRENGTH_NAMES.size
            mainActivity?.vibrationStrengthLevel = vibrationStrengthLevel
            // The click vibration uses the new strength: a preview
            clickFeedback()
            val tvStatus = btnVibrationStrength.getChildAt(1) as? TextView
            tvStatus?.text = localize(VIBE_STRENGTH_NAMES[vibrationStrengthLevel])
            try {
                prefs.edit().putInt("vibrate_strength", vibrationStrengthLevel).commit()
            } catch (_: Exception) {}
        }
        containerSettingsItems.addView(btnVibration)
        containerSettingsItems.addView(btnVibrationStrength)
        StatusBarSettings.setRowEnabled(btnVibrationStrength, isVibrationEnabled)

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

        val currentKb = prefs.getString("keyboard_layout", "qwerty") ?: "qwerty"
        val btnKeyboard = createSettingRow(
            localize("Keyboard Layout"),
            if (currentKb == "original") localize("Original") else localize("QWERTY")
        )
        btnKeyboard.setOnClickListener {
            clickFeedback()
            val cur = prefs.getString("keyboard_layout", "qwerty") ?: "qwerty"
            val next = if (cur == "qwerty") "original" else "qwerty"
            prefs.edit().putString("keyboard_layout", next).commit()
            val tvStatus = btnKeyboard.getChildAt(1) as? TextView
            tvStatus?.text = if (next == "original") localize("Original") else localize("QWERTY")
        }
        containerSettingsItems.addView(btnKeyboard)

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

        val btnTheme = createSettingRow(localize("Theme"), ThemeManager.getCurrentTheme().name)
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

        val btnPowerOff = createSettingRow(localize("Power Off"), "〉 ")
        btnPowerOff.setOnClickListener {
            clickFeedback()
            mainActivity?.showPowerMenu()
        }
        containerSettingsItems.addView(btnPowerOff)

        val btnServerMenu = createSettingRow(localize("Web Server"), "〉 ")
        btnServerMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(8) // STATE_WEBSERVER
        }
        containerSettingsItems.addView(btnServerMenu)

        val btnWifiMenu = createSettingRow(localize("Wi-Fi"), "〉 ")
        btnWifiMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(6) // STATE_WIFI
        }
        containerSettingsItems.addView(btnWifiMenu)

        val btnJellyfin = createSettingRow(
            localize("Jellyfin Sync"),
            JellyfinSyncProvider.getInstance(activity).getStatusSummary()
        )
        btnJellyfin.setOnClickListener {
            clickFeedback()
            onOpenJellyfin()
        }
        containerSettingsItems.addView(btnJellyfin)

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
            onNavigateToScreen(5) // STATE_BLUETOOTH
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
                onNavigateToScreen(7) // STATE_WIFI_KEYBOARD
            }
        }
        containerSettingsItems.addView(btnLastFmAccount)

        val btnBrightMenu = createSettingRow(localize("Display Brightness"), "〉 ")
        btnBrightMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(3) // STATE_BRIGHTNESS
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

        // Settings → Status Bar (SettingsGroups.kt): Clock, Battery, Battery Percentage
        addStatusBarRows(btnBatteryPct)

        val btnStorageMenu = createSettingRow(localize("About"), "〉 ")
        btnStorageMenu.setOnClickListener {
            clickFeedback()
            onNavigateToScreen(4) // STATE_STORAGE
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

        groupPages.arrange(containerSettingsItems, createSettingRow, clickFeedback, localize, mainActivity?.tvStatusBarTitle) { group ->
            mainActivity?.changeScreen(ScreenState.Settings(SettingsSubMode.Group(SettingsGroups.GROUPS[group].title)))
        }

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
            } else if (containerSettingsItems.childCount > 0) {
                val first = containerSettingsItems.getChildAt(0)
                first?.requestFocus()
                sv?.scrollTo(0, 0)
            }
            isUiActive = true
        }
    }

    /** The Power screen: Restart and Power Off. The centre button does the action at once; Back closes the screen. */
    private fun buildPowerUI() {
        isPowerScreen = true
        settingsSubMode = 0
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()
        for (action in PowerMenu.Action.values()) {
            val row = createSettingRow(localize(action.label), "")
            row.setOnClickListener {
                clickFeedback()
                PowerMenu.run(activity, action) {
                    Toast.makeText(
                        activity,
                        localize("System security prevents powering off directly from the app."),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            containerSettingsItems.addView(row)
        }
        groupPages.showTitle(mainActivity?.tvStatusBarTitle, containerSettingsItems, localize("Power"))
        (containerSettingsItems.parent as? ScrollView)?.scrollTo(0, 0)
        containerSettingsItems.getChildAt(0)?.let { first -> first.post { first.requestFocus() } }
    }

    private fun buildLanguageSelectorUI() {
        if (!showsPage("Language")) return
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val langMgr = LanguageManager.getInstance(activity)
        langMgr.loadAvailableLanguages()

        val isEngSelected = langMgr.currentLangFileName == "English (Default)"
        val btnEng = createTablerListButton(
            if (isEngSelected) TablerIcons.CHECK else null,
            "English (Default)",
            if (isEngSelected) 0xFF00FF00.toInt() else ThemeManager.getTextColorPrimary(),
            if (isEngSelected) 0xFF00FF00.toInt() else ThemeManager.getListButtonFocusedTextColor()
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
                if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getTextColorPrimary(),
                if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getListButtonFocusedTextColor()
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
        if (!showsPage("Theme")) return
        currentSettingsDepth = 1
        val themeFolder = File(activity.filesDir, "themes")
        ThemeManager.loadThemesFromStorage(themeFolder)
        containerSettingsItems.removeAllViews()

        for (i in 0 until ThemeManager.availableThemes.size) {
            val index = i
            val theme = ThemeManager.availableThemes[i]
            val isSelected = ThemeManager.getCurrentThemeIndex() == i
            val icon = if (isSelected) TablerIcons.CHECK else null
            val btn = createTablerListButton(
                icon,
                localize(theme.name),
                if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getTextColorPrimary(),
                if (isSelected) 0xFF00FF00.toInt() else ThemeManager.getListButtonFocusedTextColor()
            )
            btn.setOnClickListener {
                clickFeedback()
                ThemeManager.setThemeIndex(index)
                try {
                    val editor = prefs.edit()
                    editor.putInt("app_theme_index", index)
                    editor.putString("app_theme_name", ThemeManager.availableThemes[index].name)
                    editor.putString("current_theme_name", ThemeManager.availableThemes[index].name)
                    editor.putBoolean("reboot_to_theme", true)

                    val selectedTheme = ThemeManager.availableThemes[index]
                    var hasThemeBg = false
                    if (!selectedTheme.bgImage.isNullOrEmpty() && File(selectedTheme.folderPath, selectedTheme.bgImage).exists()) {
                        hasThemeBg = true
                    }
                    if (!hasThemeBg && File(selectedTheme.folderPath, "background.png").exists()) hasThemeBg = true
                    if (!hasThemeBg && File(selectedTheme.folderPath, "bg.png").exists()) hasThemeBg = true

                    if (hasThemeBg) {
                        editor.putString("bg_path", "THEME_DEFAULT")
                    }

                    var hasClock = false
                    var hasAnalog = false
                    var hasBattery = false
                    var hasCircular = false
                    var hasAlbum = false
                    var hasFocusImage = false

                    for (el in theme.menuElements) {
                        if ("widget_clock" == el.type) hasClock = true
                        if ("widget_analog_clock" == el.type) hasAnalog = true
                        if ("widget_battery" == el.type) hasBattery = true
                        if ("widget_circular_battery" == el.type) hasCircular = true
                        if ("widget_album" == el.type) hasAlbum = true
                        if ("widget_focus_image" == el.type) hasFocusImage = true
                    }

                    editor.putBoolean("widget_clock", hasClock)
                    editor.putBoolean("widget_analog_clock", hasAnalog)
                    editor.putBoolean("widget_battery", hasBattery)
                    editor.putBoolean("widget_circular_battery", hasCircular)
                    editor.putBoolean("widget_album", hasAlbum)
                    editor.putBoolean("widget_focus_image", hasFocusImage)
                    editor.commit()
                } catch (_: Exception) {}

                activity.recreate()
            }
            containerSettingsItems.addView(btn)
        }

        if (containerSettingsItems.childCount > 0) {
            containerSettingsItems.getChildAt(0)?.requestFocus()
        }
    }

    fun buildBackgroundSettingsUI() {
        if (!showsPage("Background")) return
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
        if (!showsPage("DateTime")) return
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        var is24HourFormat = prefs.getBoolean("is_24h_format", true)
        val formatRightText = if (is24HourFormat) localize("24 Hour") else localize("12 Hour")
        val rowFormat = createSettingRow(localize("Time Format"), formatRightText)
        rowFormat.setOnClickListener {
            clickFeedback()
            is24HourFormat = !is24HourFormat
            mainActivity?.is24HourFormat = is24HourFormat
            prefs.edit().putBoolean("is_24h_format", is24HourFormat).commit()
            buildDateTimeUI()
        }
        containerSettingsItems.addView(rowFormat)
        addDateTimeValueRows()
    }

    /**
     * Settings → Status Bar: the Battery row (show or hide the battery icon) and the Clock row. Battery Percentage
     * ([batteryPercentRow], made in buildSettingsUI) is grey and the wheel skips it while the battery icon is hidden.
     */
    private fun addStatusBarRows(batteryPercentRow: LinearLayout) {
        val showBattery = StatusBarSettings.isBatteryShown(prefs)
        val btnBattery = createSettingRow(localize("Battery"), onOff(showBattery, localize))
        btnBattery.setOnClickListener {
            clickFeedback()
            StatusBarSettings.setBatteryShown(mainActivity, prefs, !StatusBarSettings.isBatteryShown(prefs))
            // Again, for the state of Battery Percentage (the focus stays on this row)
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnBattery)
        StatusBarSettings.setRowEnabled(batteryPercentRow, showBattery)
        addClockRow()
    }

    /**
     * The clock in the status bar ("Time in Title"), shown as Settings → Status Bar → Clock.
     * Before the Settings groups, it was a row of Date & Time.
     */
    private fun addClockRow() {
        var isTimeInTitleBar = prefs.getBoolean("time_in_title_bar", true)
        val rowTitleClock = createSettingRow(
            localize("Time in Title"),
            onOff(isTimeInTitleBar, localize)
        )
        rowTitleClock.setOnClickListener {
            clickFeedback()
            isTimeInTitleBar = !isTimeInTitleBar
            mainActivity?.isTimeInTitleBar = isTimeInTitleBar
            prefs.edit().putBoolean("time_in_title_bar", isTimeInTitleBar).commit()
            val tvStatus = rowTitleClock.getChildAt(1) as? TextView
            tvStatus?.text = onOff(isTimeInTitleBar, localize)
        }
        containerSettingsItems.addView(rowTitleClock)
    }

    /** The rows of Date & Time after Time Format. */
    private fun addDateTimeValueRows() {
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

    private fun buildDateTimeSelectorUI(type: String, min: Int, max: Int, currentValue: Int) {
        if (!showsPage("DateTime", type)) return
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

    private fun buildVolumeLimitSettingsUI() {
        if (!showsPage("VolumeLimit")) return
        currentSettingsDepth = 1
        containerSettingsItems.removeAllViews()

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var volumeLimitMax = prefs.getInt("volume_limit_max", -1)
        var focusBtn: Button? = null

        val btnOff = createListButton(localize("Off"))
        btnOff.gravity = Gravity.CENTER
        btnOff.setOnClickListener {
            clickFeedback()
            volumeLimitMax = -1
            prefs.edit().putInt("volume_limit_max", -1).commit()
            buildSettingsUI()
        }
        containerSettingsItems.addView(btnOff)
        if (volumeLimitMax < 0) focusBtn = btnOff

        for (i in 1..maxVol) {
            val level = i
            val btn = createListButton(level.toString())
            btn.gravity = Gravity.CENTER
            btn.setOnClickListener {
                clickFeedback()
                volumeLimitMax = level
                prefs.edit().putInt("volume_limit_max", level).commit()
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (cur > level) {
                    SafeVolumeBypasser.setStreamVolumeWithBypass(
                        activity,
                        audioManager,
                        AudioManager.STREAM_MUSIC,
                        level,
                        0
                    )
                }
                buildSettingsUI()
            }
            containerSettingsItems.addView(btn)
            if (level == volumeLimitMax) focusBtn = btn
        }

        focusBtn?.requestFocus() ?: containerSettingsItems.getChildAt(0)?.requestFocus()
    }

    private fun buildLegalSettingsUI() {
        if (!showsPage("Legal")) return
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
        if (!showsPage("Update")) return
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
        if (!showsPage("MainMenuItems")) return
        currentSettingsDepth = 1
        settingsSubMode = 4
        containerSettingsItems.removeAllViews()

        val buttons = ArrayList<ThemeManager.MenuElement>()
        for (el in ThemeManager.getCurrentTheme().menuElements) {
            if (el.type == "button") buttons.add(el)
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
    }

    private fun buildMusicMenuVisibilitySettingsUI() {
        if (!showsPage("MusicMenuItems")) return
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
        try {
            prefs.edit()
                .remove("is_24h_format")
                .remove("time_in_title_bar")
                .remove("show_battery_percentage")
                .remove(StatusBarSettings.KEY_SHOW_BATTERY)
                .remove("volume_limit_max")
                .remove(ToggleStyle.PREF_KEY)
                .remove("sound_check_enabled")
                .remove("video_stretch_fill")
                .remove("album_tilt_enabled")
                .remove("backlight_timer_index")
                .remove("screen_off_control")
                .remove("repeat_mode")
                .remove("repeat")
                .remove("loop_scroll_on")
                .remove("eq_preset")
                .remove("eq_profile_id")
                .remove(AlbumKey.PREF_BY_ARTIST)
                .commit()
        } catch (_: Exception) {}

        AudioEffectManager.getInstance().applySoundCheck()
        ToggleStyle.reload(activity)
        AudioEffectManager.getInstance().applyEqProfile()
        applyAlbumTiltSetting()
        com.themoon.y1.managers.PowerBacklightManager.getInstance(activity).backlightTimerIndex = 3
        resetBacklightTimer()
        applyAlbumGrouping()

        mainActivity?.isScreenOffControlEnabled = false
        mainActivity?.isShowBatteryPercentage = false
        mainActivity?.isTimeInTitleBar = false
        mainActivity?.is24HourFormat = false
        mainActivity?.batteryIconView?.setShowPercentage(false)
        mainActivity?.let { StatusBarSettings.applyBattery(it) }

        Toast.makeText(activity, localize("All settings have been reset."), Toast.LENGTH_SHORT).show()
        buildSettingsUI()
    }
}
