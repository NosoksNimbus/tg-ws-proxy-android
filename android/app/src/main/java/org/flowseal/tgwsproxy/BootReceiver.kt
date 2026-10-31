package org.flowseal.tgwsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings.autostart(context)) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_START),
            )
        }
    }
}
