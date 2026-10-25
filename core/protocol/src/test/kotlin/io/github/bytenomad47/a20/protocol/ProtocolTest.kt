// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun body() =
        ByteArray(A20StateParser.KNOWN_BODY_SIZE) { 0xff.toByte() }
            .apply {
                this[0] = 1
                this[1] = 1
                this[2] = 7
                this[3] = 8
                "01.57".toByteArray().copyInto(this, 4)
                "01.57".toByteArray().copyInto(this, 9)
                "0000000000000000".toByteArray().copyInto(this, 14)
                this[65] = 0
                this[126] = 9
            }

    private fun inbound(
        body: ByteArray = body(),
        command: Command = A20Protocol.STATE_COMMAND,
    ): ByteArray {
        val n = body.size + 10
        val a =
            byteArrayOf(
                9,
                0xff.toByte(),
                0,
                0,
                1,
                command.group.toByte(),
                command.id.toByte(),
                n.toByte(),
                (n ushr 8).toByte(),
            ) + body
        return a + checksum(a).toByte()
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            fail("Expected a protocol refusal")
        } catch (_: ProtocolException) {}
    }

    @Test
    fun stateQueryAndQualifiedModeGate() {
        val request = A20Protocol.stateRequest()
        assertArrayEquals(byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 1, 1, 10, 0, 2), request)
        assertTrue(ModeCapability.VERIFIED)
    }

    @Test
    fun exact157ModePacketsAndAcknowledgementStatus() {
        assertArrayEquals(
            byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 1, 0xa9.toByte(), 11, 0, 0, 0xab.toByte()),
            A20Protocol.modeRequest(Mode.BLUETOOTH),
        )
        assertArrayEquals(
            byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 1, 0xa9.toByte(), 11, 0, 1, 0xac.toByte()),
            A20Protocol.modeRequest(Mode.SLEEP),
        )
        val success = inbound(byteArrayOf(), A20Protocol.MODE_COMMAND)
        assertTrue(FrameDecoder().feed(success).single().responseSuccess)
        val failure =
            success.copyOf().apply {
                this[4] = 0
                this[lastIndex] = checksum(copyOfRange(0, lastIndex)).toByte()
            }
        assertFalse(FrameDecoder().feed(failure).single().responseSuccess)
        val unknown =
            failure.copyOf().apply {
                this[4] = 2
                this[lastIndex] = checksum(copyOfRange(0, lastIndex)).toByte()
            }
        rejects { FrameDecoder().feed(unknown) }
        rejects {
            A20StateParser.parse(Packet(A20Protocol.STATE_COMMAND, body(), responseSuccess = false))
        }
    }

    @Test
    fun sleepVolumeFramesRangeAndUnknownStateValues() {
        assertArrayEquals(
            byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 0x13, 5, 11, 0, 65, 0x5a),
            A20Protocol.sleepVolumeRequest(65),
        )
        assertEquals(0x19, A20Protocol.sleepVolumeRequest(0).last().toInt())
        for (bad in listOf(-1, 66, 255)) {
            try {
                A20Protocol.sleepVolumeRequest(bad)
                fail("Out of range volume")
            } catch (_: IllegalArgumentException) {}
            try {
                DeviceAction.SetSleepVolume(bad)
                fail("Out of range action")
            } catch (_: IllegalArgumentException) {}
        }
        for (raw in 0..255) {
            val state =
                A20StateParser.parse(
                    Packet(A20Protocol.STATE_COMMAND, body().apply { this[78] = raw.toByte() })
                )
            assertEquals(raw.takeIf { it <= 65 }, state.sleepVolume)
        }
    }

    @Test
    fun sleepPlaybackPayloadUsesExistingSlotAndStrictStateFields() {
        assertArrayEquals(
            byteArrayOf(8, 0xee.toByte(), 0, 0, 0, 0x13, 2, 12, 0, 3, 1, 0x1b),
            A20Protocol.sleepPlaybackRequest(3, true),
        )
        assertArrayEquals(
            byteArrayOf(3, 0),
            A20Protocol.sleepPlaybackRequest(3, false).copyOfRange(9, 11),
        )
        for (bad in listOf(-1, 4, 255)) {
            try {
                A20Protocol.sleepPlaybackRequest(bad, true)
                fail("Bad slot")
            } catch (_: IllegalArgumentException) {}
        }
        val b =
            body().apply {
                this[79] = 2
                this[80] = 1
                this[81] = 2
                this[82] = 0
                this[92] = 2
            }
        fun parse() = A20StateParser.parse(Packet(A20Protocol.STATE_COMMAND, b))
        assertEquals(2, parse().sleepSoundSlot)
        assertEquals(true, parse().sleepPlaying)
        assertEquals(setOf(0, 2), parse().sleepSlots)
        b[80] = 0
        assertEquals(false, parse().sleepPlaying)
        b[80] = 2
        assertNull(parse().sleepPlaying)
        b[79] = 4
        assertNull(parse().sleepSoundSlot)
        b[92] = 0
        assertNull(parse().sleepSlots) // Duplicate slot records are unsupported.
        b[81] = 5
        assertNull(parse().sleepSlots)
        b[81] = 0
        assertEquals(emptySet<Int>(), parse().sleepSlots)
    }

    @Test
    fun preservesBoundariesAtEveryPossibleFragmentation() {
        val wire = inbound()
        for (split in 0..wire.size) {
            val decoder = FrameDecoder()
            val result =
                decoder.feed(wire.copyOfRange(0, split)) +
                    decoder.feed(wire.copyOfRange(split, wire.size))
            assertEquals(1, result.size)
            assertArrayEquals(body(), result.single().body)
            decoder.endOfStream()
        }
        val d = FrameDecoder()
        assertEquals(1, wire.flatMap { d.feed(byteArrayOf(it)) }.size)
    }

    @Test
    fun handlesCombinedPacketsAndPartialNextPacket() {
        val wire = inbound()
        val d = FrameDecoder()
        assertEquals(2, d.feed(wire + wire + wire.copyOfRange(0, 6)).size)
        assertEquals(1, d.feed(wire.copyOfRange(6, wire.size)).size)
        d.endOfStream()
    }

    @Test
    fun rejectsEverySingleByteCorruption() {
        val wire = inbound()
        for (index in wire.indices) {
            val changed = wire.copyOf()
            changed[index] = (changed[index].toInt() xor 1).toByte()
            rejects {
                val d = FrameDecoder()
                d.feed(changed)
                d.endOfStream()
            }
        }
    }

    @Test
    fun rejectsLengthUnderflowOverflowAndNoise() {
        for (size in listOf(0, 1, 9, 4097, 65535)) {
            val b =
                inbound().apply {
                    this[7] = size.toByte()
                    this[8] = (size ushr 8).toByte()
                }
            rejects { FrameDecoder().feed(b) }
        }
        rejects { FrameDecoder().feed(ByteArray(12289)) }
        rejects { FrameDecoder().feed(ByteArray(20)) }
        rejects {
            val d = FrameDecoder()
            d.feed(inbound().dropLast(1).toByteArray())
            d.endOfStream()
        }
    }

    @Test
    fun readsOnlyKnownA20LayoutWithoutReturningSerials() {
        val s = A20StateParser.parse(Packet(A20Protocol.STATE_COMMAND, body()))
        assertEquals(Ear.RIGHT, s.primary)
        assertEquals(true, s.peerLinked)
        assertEquals(80, s.leftBattery)
        assertEquals(90, s.rightBattery)
        assertEquals(100, s.caseBattery)
        assertEquals(Mode.BLUETOOTH, s.mode)
        assertEquals("01.57", s.leftFirmware)
        assertFalse(s.toString().contains("0000000000000000"))
        for (size in listOf(0, 4, 65, 126, 130, 132, 4000)) rejects {
            A20StateParser.parse(Packet(A20Protocol.STATE_COMMAND, ByteArray(size)))
        }
        rejects { A20StateParser.parse(Packet(Command(1, 3), body())) }
    }

    @Test
    fun allBatteryCodesAndMissingDataStayExplicit() {
        for (v in 0..255) {
            val b =
                body().apply {
                    this[2] = v.toByte()
                    this[3] = v.toByte()
                    this[126] = v.toByte()
                }
            val s = A20StateParser.parse(Packet(A20Protocol.STATE_COMMAND, b))
            val expected = if (v < 10) (v + 1) * 10 else null
            assertEquals(expected, s.leftBattery)
            assertEquals(expected, s.rightBattery)
            assertEquals(expected, s.caseBattery)
        }
    }

    @Test
    fun modeAndPeerAreNotInferredFromBattery() {
        for (v in 0..255) {
            val s =
                A20StateParser.parse(
                    Packet(
                        A20Protocol.STATE_COMMAND,
                        body().apply {
                            this[0] = v.toByte()
                            this[1] = v.toByte()
                            this[65] = v.toByte()
                        },
                    )
                )
            assertEquals(
                when (v) {
                    0 -> Mode.BLUETOOTH
                    1 -> Mode.SLEEP
                    else -> null
                },
                s.mode,
            )
            assertEquals(
                when (v) {
                    0 -> Ear.LEFT
                    1 -> Ear.RIGHT
                    else -> null
                },
                s.primary,
            )
            assertEquals(
                when (v) {
                    0 -> false
                    1 -> true
                    else -> null
                },
                s.peerLinked,
            )
        }
        val s =
            A20StateParser.parse(
                Packet(
                    A20Protocol.STATE_COMMAND,
                    body().apply {
                        this[0] = 0
                        this[1] = 0
                        this[3] = 9
                    },
                )
            )
        assertEquals(Availability.AVAILABLE, s.availability(Ear.LEFT))
        assertEquals(Availability.NOT_REPORTED, s.availability(Ear.RIGHT))
        assertEquals(100, s.rightBattery) // Battery alone never establishes peer availability.
    }

    @Test
    fun automationIsAnExactAllowlist() {
        assertEquals(
            DeviceAction.SetMode(Mode.SLEEP),
            AutomationContract.parse(AutomationContract.SLEEP, null),
        )
        assertEquals(
            DeviceAction.SetMode(Mode.BLUETOOTH),
            AutomationContract.parse(AutomationContract.BLUETOOTH, "sleep"),
        )
        assertEquals(
            DeviceAction.Refresh,
            AutomationContract.parse(AutomationContract.REFRESH_STATUS, null),
        )
        assertEquals(
            DeviceAction.ToggleMode,
            AutomationContract.parse(AutomationContract.TOGGLE_MODE, null),
        )
        assertEquals(
            DeviceAction.SetMode(Mode.SLEEP),
            AutomationContract.parse(AutomationContract.SET_MODE, "sleep"),
        )
        assertEquals(
            DeviceAction.SetMode(Mode.BLUETOOTH),
            AutomationContract.parse(AutomationContract.SET_MODE, "bluetooth"),
        )
        for (v in listOf(null, "", "SLEEP", "reset", "1", "sleep;reset")) assertNull(
            AutomationContract.parse(AutomationContract.SET_MODE, v)
        )
        for (v in
            listOf(
                null,
                "reset",
                "SET_MODE",
                AutomationContract.PACKAGE + ".action.RESET",
            )) assertNull(AutomationContract.parse(v, "sleep"))
    }

    @Test
    fun packetLogRepresentationExcludesPayload() {
        val p = Packet(A20Protocol.STATE_COMMAND, "PRIVATE_SERIAL".toByteArray())
        assertFalse(p.toString().contains("PRIVATE"))
    }
}
