package eu.nosini.unifiedshim.distributor

import android.app.Activity
import android.os.Bundle
import eu.nosini.unifiedshim.common.Logx

/**
 * Target for `unifiedpush://link`. The connector launches this so the user can
 * pick / confirm this distributor. We have no UI to show here — making sure the
 * service is up is enough — so we start it and finish immediately.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.i("link", "unifiedpush://link invoked")
        DistributorService.ensureStarted(this)
        finish()
    }
}
