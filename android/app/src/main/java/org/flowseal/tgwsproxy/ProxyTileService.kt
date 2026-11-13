package org.flowseal.tgwsproxy

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/** Quick Settings tile: toggle the proxy from the notification shade. */
class ProxyTileService : TileService() {

    companion object {
        /** Ask the system to re-read the tile state (call after the proxy starts/stops). */
        fun refresh(ctx: Context) {
            try {
                TileService.requestListeningState(
                    ctx, ComponentName(ctx, ProxyTileService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    override fun onStartListening() = update()

    override fun onClick() {
        if (ProxyService.running || Settings.wantRunning(this)) {
            startService(Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
        } else {
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START))
            } catch (_: Exception) {
                // Background service start refused: let the app do it.
                openApp()
            }
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = ProxyService.running
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(if (on) R.string.state_on else R.string.state_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 5, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
