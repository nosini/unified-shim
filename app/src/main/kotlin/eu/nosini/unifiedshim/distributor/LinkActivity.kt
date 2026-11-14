package eu.nosini.unifiedshim.distributor

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import eu.nosini.unifiedshim.common.Logx

/**
 * Target for `unifiedpush://link`. The connector launches this so the user can
 * pick / confirm this distributor. We have no UI to show here — making sure the
 * service is up is enough — so we start it and finish immediately.
 *
 * Connector 3.x expects a result: RESULT_OK plus a `pi` identifying this package
 * (its creator package), or RESULT_CANCELED when the link cannot be honoured.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.i("link", "unifiedpush://link invoked")
        val data = intent?.data
        val ok = data?.scheme == "unifiedpush" && data.host == "link" && callingPackage != null
        if (ok) {
            try {
                DistributorService.ensureStarted(this)
            } catch (e: Exception) {
                Logx.w("link", "could not start distributor service: ${e.message}")
            }
        }
        val result = if (ok) {
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(UnifiedPush.DUMMY_PACKAGE).setPackage(UnifiedPush.DUMMY_PACKAGE),
                PendingIntent.FLAG_IMMUTABLE,
            )
            Intent().putExtra(UnifiedPush.EXTRA_PI, pi)
        } else {
            null
        }
        setResult(if (ok) RESULT_OK else RESULT_CANCELED, result)
        finish()
    }
}
