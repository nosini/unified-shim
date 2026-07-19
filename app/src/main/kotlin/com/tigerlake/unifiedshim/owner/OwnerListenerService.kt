package com.tigerlake.unifiedshim.owner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.tigerlake.unifiedshim.common.Notifications

/**
 * Optional owner-side foreground service (auto-provision). OFF by default — it is
 * the only persistent component in the owner profile, so it exists solely to keep
 * the nudge listener up while the owner app is *closed*, letting a distributor
 * provision the event-driven owner the instant a registration changes.
 *
 * Thin FGS shell around [NudgeListener]: it just holds the listener alive under the
 * "fgs" tag. The owner Activity holds it under "activity" while its screen is open,
 * so both share one listener with no double-bind.
 */
class OwnerListenerService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.NOTIF_ID_OWNER,
            Notifications.build(this, "Auto-provision listener active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        running = true
        NudgeListener.acquire(applicationContext, "fgs")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        NudgeListener.release("fgs")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Best-effort: whether the listener FGS is currently up in this process. */
        @Volatile var running = false
            private set

        fun ensureStarted(context: Context) {
            context.startForegroundService(Intent(context, OwnerListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OwnerListenerService::class.java))
        }
    }
}
