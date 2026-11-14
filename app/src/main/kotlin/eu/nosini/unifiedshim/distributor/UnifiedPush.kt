package eu.nosini.unifiedshim.distributor

import android.app.PendingIntent
import android.content.Intent
import android.os.Build

/** UnifiedPush distributor action + extra constants. */
object UnifiedPush {
    // app -> distributor
    const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
    const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
    const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"

    // distributor -> app
    const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
    const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
    const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
    const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"

    const val EXTRA_APPLICATION = "application"
    /** Connector 3.x app identity: an immutable PendingIntent created by the app. */
    const val EXTRA_PI = "pi"
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_MESSAGE_ID = "id"
    const val EXTRA_REASON = "reason"

    /** Dummy target used by the link handshake PendingIntents (sender identity only). */
    const val DUMMY_PACKAGE = "org.unifiedpush.dummy_app"

    /**
     * Resolve the requesting app's package: connector 3.x sends [EXTRA_PI] (we read
     * its creator package), older connectors fall back to [EXTRA_APPLICATION].
     */
    @Suppress("DEPRECATION")
    fun applicationOf(intent: Intent): String? {
        val pi = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_PI, PendingIntent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PI)
        }
        return pi?.creatorPackage ?: intent.getStringExtra(EXTRA_APPLICATION)
    }
}
