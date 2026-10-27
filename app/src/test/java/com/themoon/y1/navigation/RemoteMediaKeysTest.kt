package com.themoon.y1.navigation

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMediaKeysTest {

    private val mediaKeys = intArrayOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_HEADSETHOOK
    )

    @Test
    fun bluetoothAndWiredMediaKeysActWithScreenOffControlOff() {
        for (device in listOf("AVRCP", "ACCDET", "Virtual", null)) {
            for (key in mediaKeys) {
                assertTrue("$device $key", RemoteMediaKeys.actsWithScreenOff(key, device, isScreenOffControlEnabled = false))
            }
        }
    }

    @Test
    fun ownButtonsActWithScreenOffOnlyWithScreenOffControl() {
        for (device in listOf("mtk-tpd-kpd", "mtk-kpd")) {
            for (key in mediaKeys + intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) {
                assertFalse("$device $key", RemoteMediaKeys.actsWithScreenOff(key, device, isScreenOffControlEnabled = false))
                assertTrue("$device $key", RemoteMediaKeys.actsWithScreenOff(key, device, isScreenOffControlEnabled = true))
            }
        }
    }

    @Test
    fun otherRemoteKeysNeedScreenOffControl() {
        for (key in intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK)) {
            assertFalse(RemoteMediaKeys.actsWithScreenOff(key, "AVRCP", isScreenOffControlEnabled = false))
        }
    }
}
