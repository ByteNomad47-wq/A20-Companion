// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

/** Pinned A6611/01.57 dispatch and setter emulation. Encoding only: not a playback approval. */
object FindProtocol {
    val SET = Command(0x10, 0x89)
    val GET = Command(0x10, 0x09)
    const val DEVICE_TIMEOUT_VERIFIED = false // Whole-device qualification still pending.
    const val WIRE_MAPPING_VERIFIED = true

    fun isStopNotification(packet: Packet): Boolean =
        packet.command == GET &&
            packet.responseSuccess &&
            packet.body.contentEquals(byteArrayOf(0, 0, 0))

    const val QUALIFICATION_REASON = "Device-side stopping and physical playback remain unverified."

    fun request(left: Boolean, right: Boolean): ByteArray =
        Packet(SET, byteArrayOf(if (left) 1 else 0, if (right) 1 else 0)).encodeOutbound()

    fun stopRequest(): ByteArray = request(false, false)
    // Do not infer playing/stopped state from GET: examined firmware's stub returns zeros.
    // SET's success acknowledgement can also be returned when the master gate ignored it.
}
