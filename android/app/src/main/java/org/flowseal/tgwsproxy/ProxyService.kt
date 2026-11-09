package org.flowseal.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlin.concurrent.thread

/**
 * Foreground service that hosts the Python proxy.
 *
 * Besides starting/stopping the core it keeps it healthy on a phone:
 *  - resets pooled/active connections when the default network changes,
 *  - restarts the core if it dies,
 *  - holds a partial wake lock (optional) so Doze doesn't cut the radio path,
 *  - checks GitHub for app updates in the background.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_START = "org.flowseal.tgwsproxy.START"
        const val ACTION_STOP = "org.flowseal.tgwsproxy.STOP"
        const val CHANNEL_PROXY = "proxy"
        const val CHANNEL_UPDATES = "updates"
        const val NOTIF_ID = 1
        const val NOTIF_UPDATE = 2
        const val EXTRA_INSTALL_UPDATE = "install_update"

        private const val TAG = "TgWsProxy"
        private const val WATCHDOG_MS = 20_000L
        private const val UPDATE_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val UPDATE_MIN_GAP_MS = 6 * 60 * 60 * 1000L
        private const val NET_RESET_GAP_MS = 3_000L

        @Volatile var running = false
        @Volatile var lastError = ""

        fun python(ctx: Context): Python {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
            return Python.getInstance()
        }

        fun ensureChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_PROXY, ctx.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_UPDATES, ctx.getString(R.string.notif_channel_updates),
                NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var wanted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNet: Network? = null
    @Volatile private var netLost = false
    @Volatile private var lastNetReset = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wanted = false
            Settings.setWantRunning(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        wanted = true
        Settings.setWantRunning(this, true)
        enterForeground()
        acquireWakeLock()
        registerNetworkCallback()
        thread(name = "proxy-start") { startProxy() }

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        main.removeCallbacks(updateTask)
        val sinceCheck = System.currentTimeMillis() - Settings.lastUpdateCheck(this)
        main.postDelayed(updateTask, if (sinceCheck > UPDATE_MIN_GAP_MS) 90_000L else UPDATE_EVERY_MS)
        return START_STICKY
    }

    // ---- foreground notification ------------------------------------------------

    private fun enterForeground() {
        ensureChannels(this)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(ACTION_STOP), flags)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_PROXY)
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

    // ---- core lifecycle ---------------------------------------------------------

    private fun startProxy() {
        synchronized(lock) {
            if (!wanted) return
            try {
                val err = python(this).getModule("android_entry")
                    .callAttr("start", Settings.toJson(this)).toString()
                if (err.isNotEmpty()) {
                    fail(err)
                } else {
                    lastError = ""
                    running = true
                }
            } catch (e: Throwable) {
                fail(e.message ?: e.toString())
            }
        }
    }

    private fun fail(message: String) {
        lastError = message
        running = false
        wanted = false
        Settings.setWantRunning(this, false)
        shutdown()
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (wanted) {
                thread(name = "proxy-watchdog") {
                    val alive = try {
                        Python.isStarted() && Python.getInstance()
                            .getModule("android_entry").callAttr("is_running").toBoolean()
                    } catch (_: Throwable) {
                        false
                    }
                    if (wanted && !alive && running) {
                        Log.w(TAG, "Core is not running, restarting")
                        running = false
                        startProxy()
                    }
                }
                main.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    private fun shutdown() {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(updateTask)
        unregisterNetworkCallback()
        releaseWakeLock()
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

    // ---- mobile network handling ------------------------------------------------

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val prev = lastNet
                lastNet = network
                // First callback after registering only reports the current network.
                if ((prev != null && prev != network) || netLost) {
                    netLost = false
                    onNetworkSwitched()
                }
            }

            override fun onLost(network: Network) {
                if (lastNet == network) {
                    lastNet = null
                    netLost = true
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Cannot watch network changes: $e")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
    }

    private fun onNetworkSwitched() {
        val now = System.currentTimeMillis()
        if (now - lastNetReset < NET_RESET_GAP_MS) return
        lastNetReset = now
        thread(name = "proxy-netreset") {
            try {
                if (running) {
                    Python.getInstance().getModule("android_entry").callAttr("network_changed")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "network_changed failed: $e")
            }
        }
    }

    // ---- wake lock ----------------------------------------------------------------

    private fun acquireWakeLock() {
        if (!Settings.keepAwake(this) || wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tgwsproxy:proxy").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    // ---- background update check ----------------------------------------------------

    private val updateTask = object : Runnable {
        override fun run() {
            if (!wanted) return
            thread(name = "update-check") { backgroundUpdateCheck() }
            main.postDelayed(this, UPDATE_EVERY_MS)
        }
    }

    private fun backgroundUpdateCheck() {
        if (!Settings.autoUpdate(this)) return
        try {
            val info = Updater.check(this)
            Settings.setLastUpdateCheck(this, System.currentTimeMillis())
            Settings.setUpdateInfo(this, info?.toJson())
            if (info == null) return

            val mayDownload = !Settings.updateWifiOnly(this) || Updater.isUnmetered(this)
            val ready = if (mayDownload) {
                Updater.download(this, info)
                true
            } else {
                Updater.isDownloaded(this, info)
            }
            notifyUpdate(info, ready)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: $e")
        }
    }

    private fun notifyUpdate(info: Updater.Info, ready: Boolean) {
        val open = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_INSTALL_UPDATE, ready)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            this, 3, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.update_available_title, info.version))
            .setContentText(getString(
                if (ready) R.string.update_tap_install else R.string.update_tap_download))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_UPDATE, n)
    }

    override fun onDestroy() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        releaseWakeLock()
        running = false
        super.onDestroy()
    }
}
