// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import io.github.bytenomad47.a20.protocol.*
import org.junit.Assert.*
import org.junit.Test

class TilePresentationTest {
    private fun state(mode: Mode?) =
        CompanionState(
            selectedName = "A20",
            snapshot = DeviceSnapshot(Ear.LEFT, true, 90, 90, 90, mode, "01.57", "01.57"),
            stateReceivedAtMs = 1000,
        )

    @Test
    fun previewNeverPretendsToBeAToggle() {
        val tile = presentTile(state(Mode.SLEEP), true, 2000, modeVerified = false)
        assertEquals("A20 status", tile.label)
        assertFalse(tile.active)
        assertTrue(tile.available)
    }

    @Test
    fun unknownAndStaleStayTappableWithoutActiveClaim() {
        for (s in
            listOf(state(null), state(Mode.SLEEP).copy(stateReceivedAtMs = 0), state(Mode.SLEEP))) {
            val tile = presentTile(s, true, 200000, true)
            assertTrue(tile.available)
            assertFalse(tile.active)
            assertEquals("Tap to check", tile.subtitle)
        }
    }

    @Test
    fun confirmedModeStatesAreDifferent() {
        assertTrue(presentTile(state(Mode.SLEEP), true, 2000, true).active)
        val bluetooth = presentTile(state(Mode.BLUETOOTH), true, 2000, true)
        assertFalse(bluetooth.active)
        assertEquals("Bluetooth", bluetooth.subtitle)
    }

    @Test
    fun bluetoothOffDisablesTileAndClockChangeDoesNotMakeFresh() {
        val off = presentTile(state(Mode.SLEEP), false, 2000, true)
        assertFalse(off.available)
        assertFalse(off.active)
        assertFalse(presentTile(state(Mode.SLEEP), true, 500, true).active)
    }

    @Test
    fun selectingDeviceAndBusyAreVisible() {
        assertEquals("Open app to choose A20", presentTile(CompanionState(), true, 2000).subtitle)
        assertEquals("Working…", presentTile(state(null).copy(busy = true), true, 2000).subtitle)
    }
}
