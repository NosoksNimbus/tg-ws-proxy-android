package org.flowseal.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

/** Foreground service that hosts the Python proxy so Android doesn't kill it. */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "org.flowseal.tgwsproxy.START"
        const val ACTION_STOP = "org.flowseal.tgwsproxy.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var lastError = ""

        fun python(ctx: Context): Python {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            return Python.getInstance()
        }
    }

    private val lock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        enterForeground()
        thread(name = "proxy-start") { startProxy() }
        return START_STICKY
    }

    private fun enterForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP), flags)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, Settings.port(this)))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun startProxy() {
        synchronized(lock) {
            try {
                val err = python(this).getModule("android_entry")
                    .callAttr("start", Settings.toJson(this)).toString()
                if (err.isNotEmpty()) {
                    lastError = err
                    running = false
                    shutdown()
                } else {
                    lastError = ""
                    running = true
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.toString()
                running = false
                shutdown()
            }
        }
    }

    private fun shutdown() {
        thread(name = "proxy-stop") {
            synchronized(lock) {
                try {
                    if (Python.isStarted()) {
                        Python.getInstance().getModule("android_entry").callAttr("stop")
                    }
                } catch (_: Throwable) {
                }
                running = false
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
