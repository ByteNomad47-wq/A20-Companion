// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("MissingPermission")

package io.github.bytenomad47.a20

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*

class ModeTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observation: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        observation?.cancel()
        observation = scope.launch {
            a20.repository.state.collect { s ->
                val tile = qsTile ?: return@collect
                val enabled = runCatching {
                    getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
                }
                    .getOrDefault(false)
                val model =
                    io.github.bytenomad47.a20.device.presentTile(
                        s,
                        enabled,
                        System.currentTimeMillis(),
                    )
                tile.label = model.label
                tile.subtitle = model.subtitle
                tile.state =
                    when {
                        !model.available -> Tile.STATE_UNAVAILABLE
                        model.active -> Tile.STATE_ACTIVE
                        else -> Tile.STATE_INACTIVE
                    }
                tile.contentDescription = "${tile.label}. ${tile.subtitle}"
                tile.updateTile()
            }
        }
    }

    override fun onStopListening() {
        observation?.cancel()
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (a20.repository.state.value.busy) return
        ActionService.start(
            this,
            if (ModeCapability.VERIFIED) DeviceAction.ToggleMode else DeviceAction.Refresh,
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun requestUpdate(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, ModeTileService::class.java))
            }
        }
    }
}
