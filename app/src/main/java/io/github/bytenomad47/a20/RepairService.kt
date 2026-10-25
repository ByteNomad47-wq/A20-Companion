// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

/** Private service; consumes only a UI-staged in-memory request, never an intent command. */
class RepairService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var foregroundAllowed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(
                22,
                NotificationCompat.Builder(this, Notifications.READY)
                    .setSmallIcon(R.drawable.ic_moon)
                    .setContentTitle("A20 charging repair")
                    .setContentText("One earbud at a time · open Companion to see progress")
                    .setContentIntent(
                        android.app.PendingIntent.getActivity(
                            this,
                            22,
                            Intent(this, MainActivity::class.java).putExtra("open_repair", true),
                            android.app.PendingIntent.FLAG_IMMUTABLE or
                                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                        )
                    )
                    .setOngoing(true)
                    .setSilent(true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            foregroundAllowed = true
        } catch (_: SecurityException) {
            a20.repair.startFailed()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundAllowed) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true)
            job = scope.launch {
                try {
                    a20.repair.consume()
                } finally {
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        a20.repair.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal fun start(
            context: Context,
            reset: Boolean,
            token: String? = null,
            acknowledgeUnknown: Boolean = false,
        ) {
            val controller = context.a20.repair
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                controller.startFailed()
                return
            }
            if (!controller.enqueue(reset, token, acknowledgeUnknown)) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RepairService::class.java),
                )
            } catch (_: Exception) {
                controller.startFailed()
            }
        }
    }
}
