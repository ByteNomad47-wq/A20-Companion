// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

/** Stock 01.57 internal 0x39 getter/notification, valid selected sound: 11-byte body. */
data class SleepPlaybackStatus(val slot: Int, val playing: Boolean) {
    init {
        require(slot in 0..3)
    }
}

object SleepPlaybackProtocol {
    val STATE = Command(0x13, 3)

    fun notification(packet: Packet): SleepPlaybackStatus? {
        val b = packet.body
        if (packet.command != STATE || !packet.responseSuccess || b.size != 11) return null
        val slot = u8(b[0]).takeIf { it in 0..3 } ?: return null
        val playing =
            when (u8(b[9])) {
                0 -> false
                1 -> true
                else -> return null
            }
        // Sound-ID bytes and adaptive volume are not used as playback/battery readings.
        return SleepPlaybackStatus(slot, playing)
    }

    fun apply(snapshot: DeviceSnapshot, status: SleepPlaybackStatus): DeviceSnapshot? {
        if (
            snapshot.mode != Mode.SLEEP ||
                !ModeCapability.supportsModeControl(snapshot) ||
                snapshot.sleepSlots?.contains(status.slot) != true
        )
            return null
        return snapshot.copy(sleepSoundSlot = status.slot, sleepPlaying = status.playing)
    }
}
