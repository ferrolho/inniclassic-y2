package com.themoon.y1.managers

import com.themoon.y1.core.extensions.onOffState
import com.themoon.y1.core.extensions.withoutOnOffMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessAdbManagerTest {

    @Test
    fun commandSetsTheRuntimePortAndRestartsAdbd() {
        assertEquals("setprop service.adb.tcp.port 5555; stop adbd; start adbd", WirelessAdbManager.command(true))
        assertEquals("setprop service.adb.tcp.port -1; stop adbd; start adbd", WirelessAdbManager.command(false))
    }

    @Test
    fun commandIsNeverPersistent() {
        // A restart must go back to USB adb: root adb has no key check on this ROM.
        assertFalse(WirelessAdbManager.command(true).contains("persist"))
        assertFalse(WirelessAdbManager.command(false).contains("persist"))
    }

    @Test
    fun tcpPortFollowsAdbdRules() {
        assertEquals(5555, WirelessAdbManager.tcpPort("5555", null))
        assertEquals(5556, WirelessAdbManager.tcpPort("5556", ""))
        assertNull(WirelessAdbManager.tcpPort("-1", ""))
        assertNull(WirelessAdbManager.tcpPort("0", ""))
        assertNull(WirelessAdbManager.tcpPort("", ""))
        assertNull(WirelessAdbManager.tcpPort(null, null))
        assertNull(WirelessAdbManager.tcpPort("abc", ""))
        // adbd reads persist.adb.tcp.port only when service.adb.tcp.port is empty.
        assertEquals(5555, WirelessAdbManager.tcpPort("", "5555"))
        assertNull(WirelessAdbManager.tcpPort("-1", "5555"))
    }

    @Test
    fun ipTextIsLowByteFirst() {
        // 192.168.1.23 as WifiManager gives it: 0x1701A8C0
        assertEquals("192.168.1.23", WirelessAdbManager.ipText(0x1701A8C0))
        assertEquals("10.0.0.255", WirelessAdbManager.ipText(0xFF00000A.toInt()))
        assertNull(WirelessAdbManager.ipText(0))
    }

    @Test
    fun labelIsOnOffWithTheMark() {
        val id: (String) -> String = { it }
        assertEquals("On", withoutOnOffMark(WirelessAdbManager.label(5555, id)).toString())
        assertEquals("Off", withoutOnOffMark(WirelessAdbManager.label(null, id)).toString())
        assertEquals("Wył.", withoutOnOffMark(WirelessAdbManager.label(null) { if (it == "Off") "Wył." else it }).toString())
        assertEquals(true, onOffState(WirelessAdbManager.label(5555, id)))
        assertEquals(false, onOffState(WirelessAdbManager.label(null, id)))
    }

    @Test
    fun addressShowsOnlyWhenOn() {
        assertEquals("192.168.1.23:5555", WirelessAdbManager.addressLabel(5555, "192.168.1.23"))
        assertEquals("port 5555", WirelessAdbManager.addressLabel(5555, null))
        assertNull(WirelessAdbManager.addressLabel(null, "192.168.1.23"))
    }
}
