package org.flowseal.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restores the proxy after a reboot or after the app was updated in place. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val start = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Settings.autostart(context)
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                Settings.autostart(context) || Settings.wantRunning(context)
            else -> false
        }
        if (start) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START),
            )
        }
    }
}
