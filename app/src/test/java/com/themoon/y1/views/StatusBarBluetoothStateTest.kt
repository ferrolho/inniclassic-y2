package com.themoon.y1.views

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarBluetoothStateTest {
    private val connected = StatusBarBluetoothState.STATE_CONNECTED
    private val disconnected = 0
    private val connecting = 1
    private val disconnecting = 3

    @Test
    fun startsWithTheRuneOnly() {
        assertFalse(StatusBarBluetoothState().isConnected)
    }

    @Test
    fun connectedDeviceAddsTheDotsAndDisconnectRemovesThem() {
        val s = StatusBarBluetoothState()
        assertFalse(s.onAdapterState(true))
        assertFalse(s.onA2dpState("AA", connecting))
        assertFalse(s.isConnected)
        assertTrue(s.onA2dpState("AA", connected))
        assertTrue(s.isConnected)
        // The same broadcast again: no change, no new drawing.
        assertFalse(s.onA2dpState("AA", connected))
        assertTrue(s.onA2dpState("AA", disconnecting))
        assertFalse(s.isConnected)
        assertFalse(s.onA2dpState("AA", disconnected))
    }

    @Test
    fun staysConnectedUntilTheLastDeviceDisconnects() {
        val s = StatusBarBluetoothState()
        s.onAdapterState(true)
        s.onA2dpState("AA", connected)
        assertFalse(s.onA2dpState("BB", connected))
        assertFalse(s.onA2dpState("AA", disconnected))
        assertTrue(s.isConnected)
        assertTrue(s.onA2dpState("BB", disconnected))
        assertFalse(s.isConnected)
    }

    @Test
    fun adapterOffForgetsTheDevices() {
        val s = StatusBarBluetoothState()
        s.onAdapterState(true)
        s.onA2dpState("AA", connected)
        assertTrue(s.onAdapterState(false))
        assertFalse(s.isConnected)
        // On again: no dots until the A2DP query or a broadcast says a device is connected.
        assertFalse(s.onAdapterState(true))
        assertFalse(s.isConnected)
        assertTrue(s.onConnectedDevices(listOf("AA")))
        assertTrue(s.isConnected)
    }

    @Test
    fun queryAtStartReplacesTheList() {
        val s = StatusBarBluetoothState()
        assertFalse(s.onConnectedDevices(emptyList()))
        assertTrue(s.onConnectedDevices(listOf("AA", "BB")))
        assertTrue(s.adapterOn)
        assertFalse(s.onConnectedDevices(listOf("BB")))
        assertTrue(s.onConnectedDevices(emptyList()))
        assertFalse(s.isConnected)
    }

    @Test
    fun broadcastWithoutADeviceStillCounts() {
        val s = StatusBarBluetoothState()
        assertTrue(s.onA2dpState(null, connected))
        assertTrue(s.onA2dpState(null, disconnected))
    }
}
