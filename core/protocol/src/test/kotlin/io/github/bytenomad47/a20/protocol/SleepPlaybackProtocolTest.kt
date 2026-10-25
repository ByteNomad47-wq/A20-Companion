// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

import org.junit.Assert.*
import org.junit.Test

class SleepPlaybackProtocolTest {
    private fun packet(playing: Int = 1) =
        Packet(
            SleepPlaybackProtocol.STATE,
            ByteArray(11).apply {
                this[0] = 2
                this[9] = playing.toByte()
            },
        )

    @Test
    fun onlyExactSuccessfulPlaybackNotificationsAreAccepted() {
        assertEquals(SleepPlaybackStatus(2, true), SleepPlaybackProtocol.notification(packet()))
        assertEquals(SleepPlaybackStatus(2, false), SleepPlaybackProtocol.notification(packet(0)))
        for (bad in
            listOf(
                Packet(A20Protocol.SLEEP_PLAYBACK_COMMAND, packet().body),
                Packet(SleepPlaybackProtocol.STATE, byteArrayOf()),
                Packet(SleepPlaybackProtocol.STATE, ByteArray(12)),
                Packet(SleepPlaybackProtocol.STATE, packet().body, false),
                packet(2),
                Packet(SleepPlaybackProtocol.STATE, packet().body.apply { this[0] = 4 }),
            )) assertNull(SleepPlaybackProtocol.notification(bad))
    }

    @Test
    fun updateRequiresKnownSleepSlotAndDoesNotInventOtherReadings() {
        val snapshot =
            DeviceSnapshot(
                Ear.LEFT,
                false,
                90,
                80,
                100,
                Mode.SLEEP,
                "01.57",
                null,
                30,
                2,
                true,
                setOf(2),
            )
        val status = SleepPlaybackStatus(2, false)
        val changed = SleepPlaybackProtocol.apply(snapshot, status)!!
        assertEquals(snapshot.copy(sleepPlaying = false), changed)
        assertNull(SleepPlaybackProtocol.apply(snapshot.copy(mode = Mode.BLUETOOTH), status))
        assertNull(SleepPlaybackProtocol.apply(snapshot.copy(leftFirmware = "01.58"), status))
        assertNull(SleepPlaybackProtocol.apply(snapshot.copy(sleepSlots = emptySet()), status))
    }
}
