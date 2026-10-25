// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import io.github.bytenomad47.a20.protocol.DeviceAction
import kotlinx.coroutines.launch

/** Transparent launcher entry: hand one fixed action to the private service, then leave. */
class ModeShortcutActivity : ComponentActivity() {
    private var requested: DeviceAction.SetMode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = LauncherShortcuts.consume(intent)
        if (savedInstanceState != null || action == null) {
            finish()
            return
        }
        requested = action
    }

    override fun onResume() {
        super.onResume()
        val action = requested ?: return
        requested = null
        // Starting while resumed supplies Android's user-interaction service exemption.
        // The service owns read/write/readback after this invisible activity finishes.
        lifecycleScope.launch {
            try {
                a20.repository.awaitInitialized()
                if (a20.repository.settings.value.address == null) {
                    Toast.makeText(
                            this@ModeShortcutActivity,
                            "Choose your A20 in Companion first, then try the shortcut again.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                } else if (!ActionService.start(this@ModeShortcutActivity, action)) {
                    Toast.makeText(
                            this@ModeShortcutActivity,
                            "Open Companion to check Nearby devices access and try again.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }
            } finally {
                finish()
            }
        }
    }
}
