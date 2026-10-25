// SPDX-License-Identifier: GPL-3.0-only
@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package io.github.bytenomad47.a20

import android.companion.*
import android.os.Build
import io.github.bytenomad47.a20.protocol.DeviceAction
import kotlinx.coroutines.launch

/** Presence can cold-start the process: load the saved selection before filtering events. */
class A20CompanionService : CompanionDeviceService() {
    private fun appeared() {
        a20.repository.associationChanged()
        if (a20.repository.settings.value.ready && a20.repository.state.value.associated)
            ActionService.start(this, DeviceAction.Refresh)
    }

    override fun onDeviceAppeared(address: String) {
        if (Build.VERSION.SDK_INT >= 36) return
        a20.repository.scope.launch {
            a20.repository.awaitInitialized()
            if (address.equals(a20.repository.settings.value.address, true)) appeared()
        }
    }

    override fun onDeviceDisappeared(address: String) {
        if (Build.VERSION.SDK_INT >= 36) return
        a20.repository.scope.launch {
            a20.repository.awaitInitialized()
            if (address.equals(a20.repository.settings.value.address, true))
                a20.repository.disconnected()
        }
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        a20.repository.scope.launch {
            val repo = a20.repository
            repo.awaitInitialized()
            if (event.event == DevicePresenceEvent.EVENT_ASSOCIATION_REMOVED) {
                repo.associationChanged()
                if (!repo.state.value.associated) repo.setReady(false)
                return@launch
            }
            val manager = getSystemService(CompanionDeviceManager::class.java)
            val selected = repo.settings.value.address
            val same = runCatching {
                manager.myAssociations.any {
                    it.id == event.associationId &&
                        it.deviceMacAddress?.toString().equals(selected, true)
                }
            }
                .getOrDefault(false)
            if (!same) return@launch
            when (event.event) {
                DevicePresenceEvent.EVENT_BT_CONNECTED,
                DevicePresenceEvent.EVENT_BLE_APPEARED -> appeared()
                DevicePresenceEvent.EVENT_BT_DISCONNECTED,
                DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> repo.disconnected()
            }
        }
    }
}
