// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.Manifest
import android.app.Service
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.content.ContextCompat
import io.github.bytenomad47.a20.device.*
import io.github.bytenomad47.a20.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Started only by a user action or the associated companion presence exemption. */
class ActionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var actionJob: Job? = null
    private var watchJob: Job? = null
    private var foregroundAllowed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(
                Notifications.READY_ID,
                Notifications.ongoing(this, "Reading A20…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundAllowed = true
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundAllowed) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            scope.launch {
                a20.repository.setReady(false)
                a20.repository.release()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (actionJob?.isActive == true) {
            Notifications.result(this, "An A20 action is already running.")
            return START_NOT_STICKY
        }
        val action =
            when (intent?.getStringExtra(OPERATION)) {
                "refresh" -> DeviceAction.Refresh
                "sleep" -> DeviceAction.SetMode(Mode.SLEEP)
                "bluetooth" -> DeviceAction.SetMode(Mode.BLUETOOTH)
                "toggle" -> DeviceAction.ToggleMode
                "sleep_playback_toggle" -> DeviceAction.ToggleSleepPlayback
                "sleep_volume" -> {
                    val level = intent.getIntExtra("level", -1)
                    if (level !in 0..65) {
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    DeviceAction.SetSleepVolume(level)
                }
                else -> {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        actionJob = scope.launch {
            val result = a20.repository.execute(action)
            if (!result.successful) Notifications.result(this@ActionService, result.message)
            val s = a20.repository.state.value
            if (
                s.readyMode &&
                    s.associated &&
                    s.snapshot?.mode == Mode.BLUETOOTH &&
                    s.connection == Connection.CONNECTED
            ) {
                startForeground(
                    Notifications.READY_ID,
                    Notifications.ongoing(this@ActionService, "A20 ready · Bluetooth mode"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
                if (watchJob == null)
                    watchJob = scope.launch {
                        a20.repository.state.collectLatest { now ->
                            if (
                                !now.busy &&
                                    (!now.readyMode ||
                                        now.connection != Connection.CONNECTED ||
                                        now.snapshot?.mode != Mode.BLUETOOTH)
                            )
                                stopSelf()
                        }
                    }
            } else stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (!a20.repository.visible) a20.repository.release()
        super.onDestroy()
    }

    companion object {
        private const val OPERATION = "operation"
        const val STOP = "io.github.bytenomad47.a20.internal.STOP_READY"

        internal fun actionIntent(context: Context, action: DeviceAction): Intent {
            val operation =
                when (action) {
                    DeviceAction.Refresh -> "refresh"
                    DeviceAction.ToggleMode -> "toggle"
                    DeviceAction.ToggleSleepPlayback -> "sleep_playback_toggle"
                    is DeviceAction.SetMode -> action.mode.wireName
                    is DeviceAction.SetSleepVolume -> "sleep_volume"
                }
            return Intent(context, ActionService::class.java).putExtra(OPERATION, operation).apply {
                if (action is DeviceAction.SetSleepVolume) putExtra("level", action.level)
            }
        }

        fun start(context: Context, action: DeviceAction): Boolean {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                Notifications.result(context, "Open A20 Companion and grant Nearby devices access.")
                return false
            }
            return try {
                ContextCompat.startForegroundService(
                    context,
                    actionIntent(context, action),
                )
                true
            } catch (_: SecurityException) {
                Notifications.result(context, "Grant Nearby devices permission in A20 Companion.")
                false
            } catch (_: IllegalStateException) {
                Notifications.result(
                    context,
                    "Android blocked background execution. Open A20 Companion or enable associated ready mode.",
                )
                false
            }
        }
    }
}
