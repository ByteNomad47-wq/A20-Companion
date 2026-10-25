// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

/** Normal vendor control channel only. Never the diagnostic/repair service. */
object A20Protocol {
    const val SERVICE_UUID = "0cf12d31-fac3-4553-bd80-d6832e7b6611"
    const val MAX_FRAME = 4096
    val STATE_COMMAND = Command(1, 1)
    // Exact 01.57 dispatch table: internal 0x18 -> group 01, command A9.
    val MODE_COMMAND = Command(1, 0xa9)
    // Exact 01.57 dispatch maps [13,05] to internal 0x3B / 0x1412A374.
    val SLEEP_VOLUME_COMMAND = Command(0x13, 5)
    // Stock 01.57 internal 0x38 / 0x1412B298: saved slot, desired playing flag.
    val SLEEP_PLAYBACK_COMMAND = Command(0x13, 2)

    fun sleepPlaybackRequest(slot: Int, playing: Boolean): ByteArray {
        require(slot in 0..3)
        return Packet(SLEEP_PLAYBACK_COMMAND, byteArrayOf(slot.toByte(), if (playing) 1 else 0))
            .encodeOutbound()
    }

    fun sleepVolumeRequest(level: Int): ByteArray {
        require(level in 0..65)
        return Packet(SLEEP_VOLUME_COMMAND, byteArrayOf(level.toByte())).encodeOutbound()
    }

    fun stateRequest(): ByteArray = Packet(STATE_COMMAND, byteArrayOf()).encodeOutbound()

    fun modeRequest(mode: Mode): ByteArray =
        Packet(MODE_COMMAND, byteArrayOf(if (mode == Mode.SLEEP) 1 else 0)).encodeOutbound()
}

data class Command(val group: Int, val id: Int) {
    init {
        require(group in 0..255 && id in 0..255)
    }
}

class ProtocolException(message: String) : Exception(message)

class ModeRejectedException : Exception()

class Packet(val command: Command, body: ByteArray, val responseSuccess: Boolean = true) {
    val body = body.copyOf()

    fun encodeOutbound(): ByteArray {
        require(body.size <= A20Protocol.MAX_FRAME - 10)
        val size = body.size + 10
        val result =
            byteArrayOf(
                8,
                0xee.toByte(),
                0,
                0,
                0,
                command.group.toByte(),
                command.id.toByte(),
                size.toByte(),
                (size ushr 8).toByte(),
            ) + body
        return result + checksum(result).toByte()
    }

    override fun equals(other: Any?) =
        other is Packet &&
            command == other.command &&
            responseSuccess == other.responseSuccess &&
            body.contentEquals(other.body)

    override fun hashCode() =
        31 * (31 * command.hashCode() + body.contentHashCode()) + responseSuccess.hashCode()

    override fun toString() =
        "Packet(command=$command, bodyBytes=${body.size})" // Never logs serial-bearing body.
}

fun checksum(bytes: ByteArray): Int = bytes.fold(0) { a, b -> (a + (b.toInt() and 255)) and 255 }

/** Streaming decoder: strict direction, total length, checksum and bounded buffer. */
class FrameDecoder {
    private var buffered = byteArrayOf()

    fun feed(bytes: ByteArray): List<Packet> {
        if (
            bytes.size > A20Protocol.MAX_FRAME * 2 ||
                buffered.size + bytes.size > A20Protocol.MAX_FRAME * 3
        )
            fail("Input exceeds framing limit")
        buffered += bytes
        val packets = mutableListOf<Packet>()
        while (buffered.size >= 9) {
            val prefix = byteArrayOf(9, 0xff.toByte(), 0, 0)
            if (!buffered.copyOfRange(0, 4).contentEquals(prefix) || u8(buffered[4]) !in 0..1)
                fail("Unexpected control-channel header")
            val size = u8(buffered[7]) or (u8(buffered[8]) shl 8)
            if (size !in 10..A20Protocol.MAX_FRAME) fail("Invalid frame length")
            if (buffered.size < size) break
            val frame = buffered.copyOfRange(0, size)
            if (checksum(frame.copyOfRange(0, size - 1)) != u8(frame.last()))
                fail("Invalid frame checksum")
            packets +=
                Packet(
                    Command(u8(frame[5]), u8(frame[6])),
                    frame.copyOfRange(9, size - 1),
                    frame[4].toInt() == 1,
                )
            buffered = buffered.copyOfRange(size, buffered.size)
        }
        return packets
    }

    fun endOfStream() {
        if (buffered.isNotEmpty()) fail("Truncated control-channel frame")
    }

    private fun fail(message: String): Nothing {
        buffered = byteArrayOf()
        throw ProtocolException(message)
    }
}

internal fun u8(b: Byte) = b.toInt() and 255
