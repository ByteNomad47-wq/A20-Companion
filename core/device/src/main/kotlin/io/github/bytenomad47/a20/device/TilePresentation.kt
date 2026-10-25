// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.device

import io.github.bytenomad47.a20.protocol.*

data class TilePresentation(
    val label: String,
    val subtitle: String,
    val available: Boolean,
    val active: Boolean,
)

fun presentTile(
    state: CompanionState,
    bluetoothEnabled: Boolean,
    now: Long,
    modeVerified: Boolean = ModeCapability.VERIFIED,
): TilePresentation {
    val fresh = state.stateReceivedAtMs > 0 && now - state.stateReceivedAtMs in 0..90_000
    val label = if (modeVerified) "A20 Sleep mode" else "A20 status"
    val subtitle =
        when {
            !bluetoothEnabled -> "Bluetooth off"
            state.busy -> if (modeVerified) "Working…" else "Reading…"
            state.selectedName == null -> "Open app to choose A20"
            !modeVerified -> "Tap to read · preview"
            !fresh -> "Tap to check"
            state.snapshot?.mode == Mode.SLEEP -> "Sleep"
            state.snapshot?.mode == Mode.BLUETOOTH -> "Bluetooth"
            else -> "Tap to check"
        }
    return TilePresentation(
        label,
        subtitle,
        bluetoothEnabled,
        bluetoothEnabled && modeVerified && fresh && state.snapshot?.mode == Mode.SLEEP,
    )
}
