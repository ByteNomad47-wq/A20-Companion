// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

enum class Ear {
    LEFT,
    RIGHT,
}

enum class Mode(val wireName: String) {
    BLUETOOTH("bluetooth"),
    SLEEP("sleep"),
}

enum class Availability {
    AVAILABLE,
    NOT_REPORTED,
    UNKNOWN,
}

data class DeviceSnapshot(
    val primary: Ear?,
    val peerLinked: Boolean?,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val mode: Mode?,
    val leftFirmware: String?,
    val rightFirmware: String?,
    val sleepVolume: Int? = null,
    val sleepSoundSlot: Int? = null,
    val sleepPlaying: Boolean? = null,
    val sleepSlots: Set<Int>? = null,
) {
    fun availability(ear: Ear): Availability =
        when {
            primary == ear -> Availability.AVAILABLE
            primary == null || peerLinked == null -> Availability.UNKNOWN
            peerLinked -> Availability.AVAILABLE
            else -> Availability.NOT_REPORTED
        }
}

/** 131-byte layout observed on the owned A6611/01.57; unknown layouts stay unsupported. */
object A20StateParser {
    // The full known layout has 131 body bytes. Shorter responses cannot supply mode/case.
    const val MODE_OFFSET = 65
    const val CASE_OFFSET = 126
    const val KNOWN_BODY_SIZE = 131

    fun parse(packet: Packet): DeviceSnapshot {
        if (!packet.responseSuccess) throw ProtocolException("A20 rejected the state request")
        if (packet.command != A20Protocol.STATE_COMMAND)
            throw ProtocolException("Not an A20 state response")
        val b = packet.body
        if (b.size != KNOWN_BODY_SIZE)
            throw ProtocolException("Unsupported or truncated A20 state layout")
        fun percent(at: Int): Int? = u8(b[at]).takeIf { it in 0..9 }?.let { (it + 1) * 10 }
        fun firmware(at: Int): String? =
            b.copyOfRange(at, at + 5).toString(Charsets.US_ASCII).takeIf {
                it.matches(Regex("[0-9]{2}\\.[0-9]{2}"))
            }
        // Common Soundcore host convention: 0=left, 1=right. Unknown values stay unknown.
        val primary =
            when (u8(b[0])) {
                0 -> Ear.LEFT
                1 -> Ear.RIGHT
                else -> null
            }
        val peer =
            when (u8(b[1])) {
                0 -> false
                1 -> true
                else -> null
            }
        return DeviceSnapshot(
            primary,
            peer,
            percent(2),
            percent(3),
            percent(CASE_OFFSET),
            when (u8(b[MODE_OFFSET])) {
                0 -> Mode.BLUETOOTH
                1 -> Mode.SLEEP
                else -> null
            },
            firmware(4),
            firmware(9),
            u8(b[78]).takeIf { it in 0..65 },
            u8(b[79]).takeIf { it in 0..3 },
            when (u8(b[80])) {
                0 -> false
                1 -> true
                else -> null
            },
            u8(b[81])
                .takeIf { it in 0..4 }
                ?.let { count ->
                    (0 until count)
                        .map { u8(b[82 + it * 10]) }
                        .takeIf { slots ->
                            slots.all { it in 0..3 } && slots.distinct().size == count
                        }
                        ?.toSet()
                },
        )
    }
}

/** Protocol qualification is deliberately independent of the application's feature plumbing. */
object ModeCapability {
    const val VERIFIED = true
    const val QUALIFIED_FIRMWARE = "01.57"
    const val ENCODER_IDENTIFIED = true

    fun supportsModeControl(snapshot: DeviceSnapshot): Boolean {
        if (snapshot.mode == null || snapshot.primary == null || snapshot.peerLinked == null)
            return false
        val activeFirmware =
            when (snapshot.primary) {
                Ear.LEFT -> snapshot.leftFirmware
                Ear.RIGHT -> snapshot.rightFirmware
            }
        if (activeFirmware != QUALIFIED_FIRMWARE) return false
        // An absent peer cannot prevent a normal mode command to the active bud.
        // Linked peers must both use the identified firmware; unknown layouts stay rejected.
        return snapshot.peerLinked == false ||
            (snapshot.leftFirmware == QUALIFIED_FIRMWARE &&
                snapshot.rightFirmware == QUALIFIED_FIRMWARE)
    }

    const val REASON = "Mode control is unavailable in this build."
}
