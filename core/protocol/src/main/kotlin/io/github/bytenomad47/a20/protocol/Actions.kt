// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.protocol

sealed interface DeviceAction {
    data object Refresh : DeviceAction

    data object ToggleMode : DeviceAction

    data object ToggleSleepPlayback : DeviceAction

    data class SetMode(val mode: Mode) : DeviceAction

    data class SetSleepVolume(val level: Int) : DeviceAction {
        init {
            require(level in 0..65)
        }
    }
}

object AutomationContract {
    const val PACKAGE = "io.github.bytenomad47.a20"
    const val RECEIVER = "$PACKAGE.automation.AutomationReceiver"
    const val SLEEP = "$PACKAGE.action.SLEEP"
    const val BLUETOOTH = "$PACKAGE.action.BLUETOOTH"
    const val SET_MODE = "$PACKAGE.action.SET_MODE"
    const val TOGGLE_MODE = "$PACKAGE.action.TOGGLE_MODE"
    const val REFRESH_STATUS = "$PACKAGE.action.REFRESH_STATUS"
    const val MODE_EXTRA = "mode"

    fun parse(action: String?, mode: String?): DeviceAction? =
        when (action) {
            SLEEP -> DeviceAction.SetMode(Mode.SLEEP)
            BLUETOOTH -> DeviceAction.SetMode(Mode.BLUETOOTH)
            SET_MODE ->
                when (mode) {
                    "sleep" -> DeviceAction.SetMode(Mode.SLEEP)
                    "bluetooth" -> DeviceAction.SetMode(Mode.BLUETOOTH)
                    else -> null
                }
            TOGGLE_MODE -> DeviceAction.ToggleMode
            REFRESH_STATUS -> DeviceAction.Refresh
            else -> null
        }
}
