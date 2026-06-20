package com.tigerlake.unifiedshim.common

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tigerlake.unifiedshim.ui.MainActivity

/** Foreground-service notification plumbing, shared by both roles. */
object Notifications {
    const val CHANNEL_ID = "unifiedshim_fgs"
    const val NOTIF_ID = 1

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Push bridge",
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "Keeps the UnifiedPush bridge running" }
            nm.createNotificationChannel(ch)
        }
    }

    fun build(context: Context, text: String): Notification {
        ensureChannel(context)
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("UnifiedShim")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    fun update(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, build(context, text))
    }
}
