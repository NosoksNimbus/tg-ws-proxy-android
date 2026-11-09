package org.flowseal.tgwsproxy

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat

/** Receives the result of a PackageInstaller session started by [Updater.install]. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (MainActivity.visible) {
                    context.startActivity(confirm)
                } else {
                    // Background activity starts are blocked: ask via notification.
                    val pi = PendingIntent.getActivity(
                        context, 2, confirm,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    notify(context, context.getString(R.string.update_ready_title),
                        context.getString(R.string.update_tap_install), pi)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the process is replaced by the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Toast.makeText(
                    context, context.getString(R.string.update_failed, msg), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun notify(ctx: Context, title: String, text: String, pi: PendingIntent) {
        ProxyService.ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, ProxyService.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ProxyService.NOTIF_UPDATE, n)
    }
}
