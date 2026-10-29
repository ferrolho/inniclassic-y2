package com.themoon.y1.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.themoon.y1.MainActivity
import com.themoon.y1.controllers.settings.ActionScreens
import com.themoon.y1.handleMainMenuAction
import com.themoon.y1.navigation.NavigationManager

/**
 * Main menu → Play On: an iPod-style list of the speakers on the Wi-Fi. "This Y2" plays on the Y2 again. The row
 * that plays has the check mark. The list shows the last search at once and a new search runs (~2.5 s); then the
 * rows change. A push on a row starts it and opens Now Playing.
 *
 * Also a row of the Now Playing menu ([fromNowPlaying]): the list opens over that menu, and a push on a speaker goes
 * back to Now Playing in one step (the menu closes too), so Back from Now Playing goes where it went before.
 */
object PlayOnMenu {

    fun open(main: MainActivity, fromNowPlaying: Boolean = false) {
        if (!wifiConnected(main)) {
            ActionScreens.open(spec(main, CastManager.knownDevices, main.t("The Y2 is not connected to a Wi-Fi network. Main menu → Wi-Fi."), fromNowPlaying))
            return
        }
        val key = ActionScreens.open(spec(main, CastManager.knownDevices, main.t("Searching for speakers on the Wi-Fi…"), fromNowPlaying))
        CastManager.search { found ->
            val info = if (found.isEmpty()) main.t("No speakers found. The Y2 and the speaker must be on the same Wi-Fi.") else null
            ActionScreens.replace(key, spec(main, found, info, fromNowPlaying))
        }
    }

    /** The label of the Play On row of the Now Playing menu: with the speaker name while a speaker plays. */
    fun rowLabel(main: MainActivity): String {
        val d = CastManager.device ?: return main.t("Play On")
        return "${main.t("Play On")}: ${d.name}"
    }

    private fun spec(main: MainActivity, devices: List<CastDevice>, info: String?, fromNowPlaying: Boolean): ActionScreens.Spec {
        val current = CastManager.device
        val rows = ArrayList<ActionScreens.Action>()
        rows.add(ActionScreens.Action(main.t("This Y2"), checked = current == null, closes = !fromNowPlaying) {
            CastManager.stop()
            showPlayer(main, fromNowPlaying)
        })
        // The speaker that plays stays in the list, also when this search did not find it
        val list = if (current != null && devices.none { it.id == current.id }) listOf(current) + devices else devices
        for (d in list) {
            rows.add(ActionScreens.Action(d.rowLabel, checked = current?.id == d.id, closes = !fromNowPlaying) {
                CastManager.start(main, d)
                showPlayer(main, fromNowPlaying)
            })
        }
        rows.add(ActionScreens.Action(main.t("Search Again")) { open(main, fromNowPlaying) })
        return ActionScreens.Spec(main.t("Play On"), info = listOfNotNull(info), actions = rows)
    }

    /** From the main menu: the list closed, Now Playing opens. From the Now Playing menu: Back over the list and the menu. */
    private fun showPlayer(main: MainActivity, fromNowPlaying: Boolean) {
        if (fromNowPlaying) NavigationManager.getInstance().popBackStack(2) else main.handleMainMenuAction("OPEN_PLAYER")
    }

    @Suppress("DEPRECATION")
    private fun wifiConnected(context: Context): Boolean = try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.isWifiEnabled && (wm.connectionInfo?.networkId ?: -1) != -1
    } catch (_: Exception) {
        true
    }
}
