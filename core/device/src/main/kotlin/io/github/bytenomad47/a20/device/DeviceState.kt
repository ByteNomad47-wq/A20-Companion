// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import io.github.bytenomad47.a20.protocol.*

enum class Connection {
    IDLE,
    STANDBY,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

enum class AudioConnection {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

enum class PhoneConnection {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN,
}

enum class ThemeChoice {
    SYSTEM,
    LIGHT,
    DARK,
}

data class BatteryReading(val percent: Int? = null, val receivedAtMs: Long = 0) {
    fun merge(value: Int?, now: Long) = if (value == null) this else BatteryReading(value, now)
}

data class CompanionState(
    val selectedName: String? = null,
    val connection: Connection = Connection.IDLE,
    val audio: AudioConnection = AudioConnection.UNKNOWN,
    val phone: PhoneConnection = PhoneConnection.UNKNOWN,
    val snapshot: DeviceSnapshot? = null,
    val stateReceivedAtMs: Long = 0,
    val left: BatteryReading = BatteryReading(),
    val right: BatteryReading = BatteryReading(),
    val case: BatteryReading = BatteryReading(),
    val busy: Boolean = false,
    val message: String = "Choose your paired A20 to get started.",
    val readyMode: Boolean = false,
    val automationEnabled: Boolean = false,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val associated: Boolean = false,
) {
    fun withSnapshot(value: DeviceSnapshot, now: Long) =
        copy(
            connection = Connection.CONNECTED,
            phone = PhoneConnection.CONNECTED,
            snapshot = value,
            stateReceivedAtMs = now,
            left = left.merge(value.leftBattery, now),
            right = right.merge(value.rightBattery, now),
            case = case.merge(value.caseBattery, now),
        )

    fun batteryIsCurrent(reading: BatteryReading, now: Long) =
        connection == Connection.CONNECTED &&
            reading.receivedAtMs > 0 &&
            now >= reading.receivedAtMs &&
            now - reading.receivedAtMs <= 90_000
}

/** Guaranteed refusal before any find-start bytes were written. */
class FindNotSentException(message: String) : Exception(message)
