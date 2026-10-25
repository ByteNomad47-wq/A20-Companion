// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

import org.junit.Assert.*
import org.junit.Test

class FindProtocolTest {
    @Test
    fun exactFindFramesAndStoppedFlags() {
        for ((left, right) in listOf(false to false, true to false, false to true, true to true)) {
            val b = FindProtocol.request(left, right)
            assertEquals(12, b.size)
            assertEquals(0x10, u8(b[5]))
            assertEquals(0x89, u8(b[6]))
            assertEquals(if (left) 1 else 0, u8(b[9]))
            assertEquals(if (right) 1 else 0, u8(b[10]))
            assertEquals(checksum(b.dropLast(1).toByteArray()), u8(b.last()))
        }
        assertArrayEquals(FindProtocol.request(false, false), FindProtocol.stopRequest())
        assertFalse(FindProtocol.DEVICE_TIMEOUT_VERIFIED)
    }

    @Test
    fun onlyExactUnsolicitedStopNotificationMatches() {
        assertTrue(FindProtocol.isStopNotification(Packet(FindProtocol.GET, byteArrayOf(0, 0, 0))))
        assertFalse(FindProtocol.isStopNotification(Packet(FindProtocol.SET, byteArrayOf())))
        assertFalse(FindProtocol.isStopNotification(Packet(FindProtocol.GET, byteArrayOf(1, 0, 0))))
        assertFalse(
            FindProtocol.isStopNotification(Packet(FindProtocol.GET, byteArrayOf(0, 0, 0), false))
        )
    }
}
