// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.content.Context
import android.content.Intent
import io.github.bytenomad47.a20.protocol.DeviceAction
import io.github.bytenomad47.a20.protocol.Mode

/** User-facing launcher entries. Never accept a device address or a raw command. */
internal object LauncherShortcuts {
    const val BLUETOOTH = "io.github.bytenomad47.a20.shortcut.BLUETOOTH"
    const val SLEEP = "io.github.bytenomad47.a20.shortcut.SLEEP"

    fun intent(context: Context, action: DeviceAction.SetMode): Intent =
        Intent(context, ModeShortcutActivity::class.java)
            .setAction(if (action.mode == Mode.SLEEP) SLEEP else BLUETOOTH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun consume(intent: Intent): DeviceAction.SetMode? {
        val mode =
            when (intent.action) {
                BLUETOOTH -> Mode.BLUETOOTH
                SLEEP -> Mode.SLEEP
                else -> return null
            }
        // A launcher action is a single tap, never an instruction to replay on recreation.
        intent.action = Intent.ACTION_MAIN
        return DeviceAction.SetMode(mode)
    }
}
