// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

import org.junit.Assert.*
import org.junit.Test

class SleepHistoryProtocolTest {
    private fun packet(
        kind: SleepRecordKind,
        source: Int = 0,
        index: Int = 0,
        total: Int = 1,
        count: Int = 1,
        seed: Int = 0,
    ): Packet =
        Packet(
            kind.command,
            byteArrayOf(source.toByte(), index.toByte(), total.toByte(), count.toByte()) +
                ByteArray(count * kind.recordBytes) { (it + seed).toByte() },
        )

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("Expected protocol refusal")
        } catch (_: ProtocolException) {}
    }

    @Test
    fun boundedReadRequestsNeverEncodeClearOrTrackingSetters() {
        for ((kind, id) in listOf(SleepRecordKind.EVENTS to 1, SleepRecordKind.SUMMARIES to 0x88)) {
            val bytes = SleepHistoryProtocol.request(kind)
            assertEquals(10, bytes.size)
            assertEquals(0x15, u8(bytes[5]))
            assertEquals(id, u8(bytes[6]))
            assertEquals(checksum(bytes.dropLast(1).toByteArray()), u8(bytes.last()))
        }
    }

    @Test
    fun chunkBoundariesAndBothSourcesPreserveEveryRecordByte() {
        for (kind in SleepRecordKind.entries) for (count in
            listOf(0, 1, kind.perPacket, kind.perPacket + 1, kind.maximum)) {
            val collector = SleepRecordCollector(kind, 2)
            val total = maxOf(1, (count + kind.perPacket - 1) / kind.perPacket)
            for (source in 0..1) for (index in (0 until total).reversed()) {
                val n = minOf(kind.perPacket, count - index * kind.perPacket)
                collector.accept(packet(kind, source, index, total, n, index))
            }
            val result = collector.finish()
            assertEquals(listOf(0, 1), result.map { it.source })
            for (source in result) {
                assertEquals(count, source.records.size)
                source.records.forEach { assertEquals(kind.recordBytes * 2, it.hex.length) }
            }
        }
    }

    @Test
    fun duplicatesMustAgreeAndAChangedTransferCannotOverwriteHistory() {
        val k = SleepRecordKind.EVENTS
        val c = SleepRecordCollector(k, 1)
        assertTrue(c.accept(packet(k)))
        assertTrue(c.accept(packet(k)))
        assertEquals(1, c.finish().single().records.size)
        refused { c.accept(packet(k, seed = 2)) }
        val missing = SleepRecordCollector(k, 1)
        missing.accept(packet(k, total = 2, count = k.perPacket))
        refused { missing.finish() }
        refused { missing.accept(packet(k, total = 1)) }
        refused { SleepRecordCollector(k, 1).finish() }
    }

    @Test
    fun rejectsMalformedPacketsAndAcknowledgementsInsteadOfClaimingEmptyHistory() {
        for (k in SleepRecordKind.entries) {
            for (bad in
                listOf(
                    Packet(k.command, byteArrayOf()),
                    Packet(k.command, byteArrayOf(0, 0, 1, 0), false),
                    packet(k, source = 2),
                    packet(k, total = 0),
                    packet(k, total = 10),
                    packet(k, index = 1),
                    packet(k, total = 2, count = 1),
                    packet(k, count = k.perPacket + 1),
                    Packet(k.command, byteArrayOf(0, 0, 1, 1)),
                    Packet(Command(0x15, 0x83), byteArrayOf(0, 0, 1, 0)),
                )) {
                refused { SleepRecordCollector(k, 1).accept(bad) }
            }
            val empty = SleepRecordCollector(k, 1)
            assertTrue(empty.accept(packet(k, count = 0)))
            assertTrue(empty.finish().single().records.isEmpty())
        }
    }

    @Test
    fun missingPeerIsDistinguishedFromAnIncompletePeer() {
        val k = SleepRecordKind.SUMMARIES
        val c = SleepRecordCollector(k, 2)
        assertFalse(c.accept(packet(k)))
        assertEquals(1, c.finish().size)
        c.accept(packet(k, source = 1, total = 2, count = k.perPacket))
        refused { c.finish() }
    }
}
