package eu.nosini.unifiedshim.distributor

/** UnifiedPush connector (v2 broadcast) action + extra constants. */
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
    const val EXTRA_TOKEN = "token"
    const val EXTRA_ENDPOINT = "endpoint"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_BYTES_MESSAGE = "bytesMessage"
    const val EXTRA_MESSAGE_ID = "id"
    const val EXTRA_REASON = "reason"
}
