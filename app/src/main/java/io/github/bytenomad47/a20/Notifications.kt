// SPDX-License-Identifier: GPL-3.0-only
package io.github.bytenomad47.a20

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val READY = "a20_ready"
    const val RESULT = "a20_results"
    const val READY_ID = 20

    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(READY, "A20 ready mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Local Bluetooth status and active actions"
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(RESULT, "A20 action results", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) }
        )
    }

    fun open(context: Context) =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun ongoing(context: Context, text: String) =
        NotificationCompat.Builder(context, READY)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("A20 Companion")
            .setContentText(text)
            .setContentIntent(open(context))
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                0,
                "Stop ready mode",
                PendingIntent.getService(
                    context,
                    2,
                    Intent(context, ActionService::class.java).setAction(ActionService.STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    fun result(context: Context, message: String) {
        try {
            NotificationManagerCompat.from(context)
                .notify(
                    21,
                    NotificationCompat.Builder(context, RESULT)
                        .setSmallIcon(R.drawable.ic_moon)
                        .setContentTitle("A20 Companion")
                        .setContentText(message)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                        .setContentIntent(open(context))
                        .setAutoCancel(true)
                        .setSilent(true)
                        .build(),
                )
        } catch (_: SecurityException) {}
    }
}
