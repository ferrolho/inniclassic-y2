package com.themoon.y1.managers

import android.bluetooth.BluetoothClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAccessPolicyTest {

    @Test
    fun constantsMatchAndroid() {
        // BluetoothDevice.REQUEST_TYPE_* and CONNECTION_ACCESS_NO in Android 4.4.
        assertEquals(1, BluetoothAccessPolicy.REQUEST_TYPE_PROFILE_CONNECTION)
        assertEquals(2, BluetoothAccessPolicy.REQUEST_TYPE_PHONEBOOK_ACCESS)
        assertEquals(3, BluetoothAccessPolicy.REQUEST_TYPE_MESSAGE_ACCESS)
        assertEquals(2, BluetoothAccessPolicy.CONNECTION_ACCESS_NO)
    }

    @Test
    fun phoneBookAndMessagesAreRejected() {
        assertTrue(BluetoothAccessPolicy.rejects(2))
        assertTrue(BluetoothAccessPolicy.rejects(3))
        assertFalse(BluetoothAccessPolicy.rejects(1))
        assertFalse(BluetoothAccessPolicy.rejects(0))
    }

    @Test
    fun handsFreeOffForCarsPhonesAndComputers() {
        assertTrue(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertTrue(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
        assertTrue(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.PHONE_SMART))
        assertTrue(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.COMPUTER_LAPTOP))
    }

    @Test
    fun handsFreeStaysForHeadsetsAndSpeakers() {
        assertFalse(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertFalse(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertFalse(BluetoothAccessPolicy.turnOffHandsFree(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertFalse(BluetoothAccessPolicy.turnOffHandsFree(null))
    }

    @Test
    fun prefsXmlRejectsEachAddressInTheSettingsFormat() {
        val xml = BluetoothAccessPolicy.rejectedPrefsXml(listOf("CC:88:26:54:B6:62", "48:73:CB:52:59:1C", "48:73:CB:52:59:1C", "bad"))
        assertEquals(
            "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" +
                "    <int name=\"48:73:CB:52:59:1C\" value=\"2\" />\n" +
                "    <int name=\"CC:88:26:54:B6:62\" value=\"2\" />\n</map>\n",
            xml
        )
        assertTrue(BluetoothAccessPolicy.prefsRejectAll(xml, listOf("48:73:CB:52:59:1C", "CC:88:26:54:B6:62")))
    }

    @Test
    fun prefsWithUnknownOrAllowedAddressNeedAnUpdate() {
        val empty = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map />\n"
        assertFalse(BluetoothAccessPolicy.prefsRejectAll(empty, listOf("48:73:CB:52:59:1C")))
        assertFalse(BluetoothAccessPolicy.prefsRejectAll("<int name=\"48:73:CB:52:59:1C\" value=\"1\" />", listOf("48:73:CB:52:59:1C")))
        assertFalse(BluetoothAccessPolicy.prefsRejectAll(null, listOf("48:73:CB:52:59:1C")))
        assertEquals(empty, BluetoothAccessPolicy.rejectedPrefsXml(emptyList()))
    }
}
