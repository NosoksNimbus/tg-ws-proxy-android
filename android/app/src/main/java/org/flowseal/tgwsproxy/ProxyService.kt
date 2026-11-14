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
import org.json.JSONObject
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
        const val ACTION_RESTART = "org.flowseal.tgwsproxy.RESTART"
        const val CHANNEL_PROXY = "proxy"
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_ALERTS = "alerts"
        const val NOTIF_ID = 1
        const val NOTIF_UPDATE = 2
        const val NOTIF_ALERT = 4
        const val EXTRA_INSTALL_UPDATE = "install_update"

        private const val TAG = "TgWsProxy"
        private const val WATCHDOG_MS = 20_000L
        private const val UPDATE_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val UPDATE_MIN_GAP_MS = 6 * 60 * 60 * 1000L
        private const val NET_RESET_GAP_MS = 3_000L
        private const val TRAFFIC_EVERY_MS = 10_000L
        private const val CRASH_WINDOW_MS = 10 * 60_000L
        private const val CRASH_ALERT_COUNT = 3

        @Volatile var running = false
        @Volatile var lastError = ""

        @Synchronized
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
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ALERTS, ctx.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var wanted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNet: Network? = null
    @Volatile private var lostNet: Network? = null
    @Volatile private var netLost = false
    @Volatile private var lastNetReset = 0L
    @Volatile private var appliedPool = -1
    private var lastDown = -1L
    private var lastUp = -1L
    private val crashTimes = ArrayDeque<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            wanted = false
            Settings.setWantRunning(this, false)
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            thread(name = "proxy-restart") { restartProxy() }
            return START_STICKY
        }
        wanted = true
        Settings.setWantRunning(this, true)
        enterForeground()
        acquireWakeLock()
        registerNetworkCallback()
        thread(name = "proxy-start") { startProxy() }

        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        lastDown = -1L
        lastUp = -1L
        main.removeCallbacks(trafficTask)
        main.postDelayed(trafficTask, TRAFFIC_EVERY_MS)
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
                val err = Engine.get(this).start(Settings.toJson(this))
                if (err.isNotEmpty()) {
                    fail(err)
                } else {
                    lastError = ""
                    running = true
                    appliedPool = Settings.effectivePool(this)
                    getSystemService(NotificationManager::class.java).cancel(NOTIF_ALERT)
                    ProxyTileService.refresh(this)
                }
            } catch (e: Throwable) {
                fail(e.message ?: e.toString())
            }
        }
    }

    /** Re-reads the settings: stops the core and starts it again with the new config. */
    private fun restartProxy() {
        synchronized(lock) {
            try {
                Engine.peek()?.stop()
            } catch (_: Throwable) {
            }
            running = false
        }
        enterForeground() // refresh the notification (port may have changed)
        startProxy()
    }

    private fun fail(message: String) {
        notifyProblem(getString(R.string.alert_start_failed, message))
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
                        Engine.peek()?.isRunning() == true
                    } catch (_: Throwable) {
                        false
                    }
                    if (wanted && !alive && running) {
                        Log.w(TAG, "Core is not running, restarting")
                        running = false
                        noteCrash()
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
        main.removeCallbacks(trafficTask)
        unregisterNetworkCallback()
        releaseWakeLock()
        thread(name = "proxy-stop") {
            synchronized(lock) {
                pollTraffic()
                try {
                    Engine.peek()?.stop()
                } catch (_: Throwable) {
                }
                running = false
            }
            ProxyTileService.refresh(this)
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
                // The first callback after registering only reports the current network.
                if (prev != null && prev != network) {
                    netLost = false
                    onNetworkSwitched(hard = true)
                } else if (netLost) {
                    netLost = false
                    // Same network back after a blip: keep live sessions, refresh the pools.
                    onNetworkSwitched(hard = lostNet != network)
                }
            }

            override fun onLost(network: Network) {
                if (lastNet == network) {
                    lostNet = network
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

    private fun onNetworkSwitched(hard: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastNetReset < NET_RESET_GAP_MS) return
        lastNetReset = now
        thread(name = "proxy-netreset") {
            try {
                if (running) {
                    val mod = Engine.get(this)
                    mod.networkChanged(hard)
                    // Per-network profile: switch the pool size on the fly, no restart.
                    val want = Settings.effectivePool(this)
                    if (want != appliedPool) {
                        mod.setTuning(want)
                        appliedPool = want
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "network_changed failed: $e")
            }
        }
    }

    // ---- traffic counters ----------------------------------------------------------

    private val trafficTask = object : Runnable {
        override fun run() {
            if (!wanted) return
            thread(name = "traffic-poll") { pollTraffic() }
            main.postDelayed(this, TRAFFIC_EVERY_MS)
        }
    }

    /** Adds the bytes moved since the last sample to today's counters for the current network. */
    private fun pollTraffic() {
        try {
            val eng = Engine.peek()
            if (!running || eng == null) return
            val st = JSONObject(eng.stats())
            val d = st.optLong("down")
            val u = st.optLong("up")
            if (lastDown >= 0) {
                TrafficStore.add(this, Net.current(this), maxOf(0L, d - lastDown), maxOf(0L, u - lastUp))
            }
            lastDown = d
            lastUp = u
        } catch (e: Throwable) {
            Log.w(TAG, "Traffic poll failed: $e")
        }
    }

    // ---- problem alerts ---------------------------------------------------------------

    private fun noteCrash() {
        val now = System.currentTimeMillis()
        crashTimes.addLast(now)
        while (crashTimes.isNotEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) {
            crashTimes.removeFirst()
        }
        if (crashTimes.size >= CRASH_ALERT_COUNT) {
            notifyProblem(getString(R.string.alert_crashing, crashTimes.size))
        }
    }

    private fun notifyProblem(text: String) {
        try {
            ensureChannels(this)
            val pi = PendingIntent.getActivity(
                this, 4, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.alert_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT, n)
        } catch (_: Exception) {
            // Notifications may be disabled; nothing else to do.
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
