package com.themoon.y1.managers

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
    fun labelShowsAddressWhenOn() {
        val id: (String) -> String = { it }
        assertEquals("On · 192.168.1.23:5555", WirelessAdbManager.label(5555, "192.168.1.23", id))
        assertEquals("On · port 5555", WirelessAdbManager.label(5555, null, id))
        assertEquals("Off", WirelessAdbManager.label(null, "192.168.1.23", id))
        assertEquals("Wył.", WirelessAdbManager.label(null, null) { if (it == "Off") "Wył." else it })
    }
}
