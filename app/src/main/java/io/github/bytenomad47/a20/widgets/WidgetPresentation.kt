// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.widgets

import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*

internal data class WidgetBattery(
    val label: String,
    val percent: Int?,
    val receivedAt: Long,
    val available: Boolean,
)

internal data class WidgetPresentation(
    val batteries: List<WidgetBattery>,
    val lastMode: Mode?,
    val modeReceivedAt: Long,
    val status: String,
    val busy: Boolean,
)

/** A widget always presents a dated report, never claims its cached mode is live. */
internal fun presentWidget(state: CompanionState): WidgetPresentation {
    fun active(ear: Ear) =
        state.phone == PhoneConnection.CONNECTED &&
            state.snapshot?.availability(ear) == Availability.AVAILABLE
    fun battery(label: String, reading: BatteryReading, available: Boolean) =
        WidgetBattery(
            label,
            reading.percent?.takeIf { it in 0..100 },
            reading.receivedAtMs,
            available,
        )
    return WidgetPresentation(
        listOf(
            battery("Left", state.left, active(Ear.LEFT)),
            battery("Right", state.right, active(Ear.RIGHT)),
            battery("Case", state.case, state.phone == PhoneConnection.CONNECTED),
        ),
        state.snapshot?.mode,
        state.stateReceivedAtMs,
        when {
            state.busy -> "Updating…"
            state.selectedName == null -> "Tap to choose your earbuds"
            state.connection == Connection.ERROR -> "Couldn't update · open app"
            state.phone == PhoneConnection.DISCONNECTED -> "Not connected · saved readings"
            state.snapshot?.mode == Mode.SLEEP -> "Last mode: Sleep"
            state.snapshot?.mode == Mode.BLUETOOTH -> "Last mode: Bluetooth"
            else -> "Saved readings · tap refresh"
        },
        state.busy,
    )
}
