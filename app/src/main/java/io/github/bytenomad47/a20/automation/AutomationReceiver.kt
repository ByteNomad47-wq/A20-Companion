// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20.automation

import android.content.*
import io.github.bytenomad47.a20.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.launch

/** Explicit, opt-in fixed action interface; never accepts addresses or raw bytes. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode =
            try {
                intent.getStringExtra(AutomationContract.MODE_EXTRA)
            } catch (_: Exception) {
                return
            }
        val action = AutomationContract.parse(intent.action, mode) ?: return
        val pending = goAsync()
        context.a20.repository.scope.launch {
            try {
                val repo = context.a20.repository
                repo.awaitInitialized()
                if (!repo.state.value.automationEnabled) return@launch
                if (action != DeviceAction.Refresh && !ModeCapability.VERIFIED) {
                    Notifications.result(context, ModeCapability.REASON)
                    return@launch
                }
                ActionService.start(context, action)
            } finally {
                pending.finish()
            }
        }
    }
}
