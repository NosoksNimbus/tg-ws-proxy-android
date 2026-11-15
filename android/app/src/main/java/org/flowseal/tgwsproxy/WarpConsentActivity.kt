package org.flowseal.tgwsproxy

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible screen that shows Android's "allow VPN connection" dialog and then lets
 * [WarpManager] bring the tunnel back. Opened from the "WARP is paused" notification.
 */
class WarpConsentActivity : ComponentActivity() {
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        WarpManager.refresh(this)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent: Intent? = VpnService.prepare(this)
        if (intent == null) {
            WarpManager.refresh(this)
            finish()
        } else {
            consent.launch(intent)
        }
    }
}
