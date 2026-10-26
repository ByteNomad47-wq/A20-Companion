// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

/** Stock A6611/01.57 read-only history. Field meanings beyond framing remain unqualified. */
enum class SleepRecordKind(
    val command: Command,
    val recordBytes: Int,
    val perPacket: Int,
    val maximum: Int,
) {
    EVENTS(Command(0x15, 0x01), 6, 25, 100),
    SUMMARIES(Command(0x15, 0x88), 14, 10, 50),
}

data class SleepRecord(val kind: SleepRecordKind, val source: Int, val hex: String) {
    init {
        require(source in 0..1 && hex.matches(Regex("[0-9a-f]{${kind.recordBytes * 2}}")))
    }
}

data class SleepSourceRecords(val source: Int, val records: List<SleepRecord>)

/** Bounded assembly: a timeout never turns an incomplete source into a successful sync. */
class SleepRecordCollector(private val kind: SleepRecordKind, private val expectedSources: Int) {
    private data class Source(
        val total: Int,
        val packets: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val sources = mutableMapOf<Int, Source>()

    init {
        require(expectedSources in 1..2)
    }

    fun accept(packet: Packet): Boolean {
        if (packet.command != kind.command) throw ProtocolException("Unexpected sleep command")
        if (!packet.responseSuccess) throw ProtocolException("Sleep read rejected")
        val b = packet.body
        if (b.size < 4)
            throw ProtocolException("Sleep records unavailable; no history was returned")
        val source = u8(b[0])
        val index = u8(b[1])
        val total = u8(b[2])
        val count = u8(b[3])
        val maxPackets = (kind.maximum + kind.perPacket - 1) / kind.perPacket
        if (
            source !in 0..1 ||
                total !in 1..maxPackets ||
                index >= total ||
                count > kind.perPacket ||
                b.size != 4 + count * kind.recordBytes ||
                (index < total - 1 && count != kind.perPacket) ||
                (count == 0 && (total != 1 || index != 0))
        )
            throw ProtocolException("Unsupported sleep packet layout")
        val previous = sources.getOrPut(source) { Source(total) }
        if (previous.total != total) throw ProtocolException("Sleep history changed during sync")
        val duplicate = previous.packets[index]
        if (duplicate != null && !duplicate.contentEquals(b))
            throw ProtocolException("Conflicting sleep packets")
        previous.packets[index] = b.copyOf()
        return sources.size >= expectedSources && sources.values.all { it.packets.size == it.total }
    }

    fun finish(): List<SleepSourceRecords> {
        if (sources.isEmpty() || sources.values.any { it.packets.size != it.total })
            throw ProtocolException("Incomplete sleep history; previous readings retained")
        return sources.toSortedMap().map { (source, data) ->
            val records =
                (0 until data.total).flatMap { index ->
                    data.packets.getValue(index).drop(4).chunked(kind.recordBytes).map { bytes ->
                        SleepRecord(kind, source, bytes.joinToString("") { "%02x".format(u8(it)) })
                    }
                }
            if (records.size > kind.maximum) throw ProtocolException("Sleep history exceeds limit")
            SleepSourceRecords(source, records)
        }
    }
}

object SleepHistoryProtocol {
    fun request(kind: SleepRecordKind): ByteArray =
        Packet(kind.command, byteArrayOf()).encodeOutbound()
}
