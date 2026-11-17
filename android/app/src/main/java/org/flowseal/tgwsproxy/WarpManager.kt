package org.flowseal.tgwsproxy

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.app.PendingIntent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.amnezia.awg.backend.GoBackend
import org.amnezia.awg.backend.Tunnel
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Built-in WARP (AmneziaWG) tunnel that switches itself on while the phone is on mobile data
 * and off again on Wi-Fi.
 *
 * - The tunnel config is a normal AmneziaWG/WireGuard ".conf" that the user pastes in Settings.
 * - Only Telegram clients and this app are routed into the tunnel (IncludedApplications), so the
 *   rest of the phone keeps its normal connection.
 * - Network watching runs while [ProxyService] runs (see start/stop).
 *
 * The tunnel backend is the AmneziaWG Android library (com.zaneschepke:amneziawg-android).
 */
object WarpManager {
    private const val TAG = "WarpManager"
    private const val DEBOUNCE_MS = 1500L

    enum class Status { OFF, NO_CONFIG, NEED_PERMISSION, WAITING, PAUSED_VPN, CONNECTING, UP, ERROR }

    var status by mutableStateOf(Status.OFF)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Shown after a successful import when some options had to be skipped. */
    var lastNote by mutableStateOf<String?>(null)
        private set

    /** Telegram clients routed through the tunnel (only those that are installed are used). */
    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "com.exteragram.messenger",
        "tw.nekomimi.nekogram",
        "app.nicegram",
        "org.forkgram.messenger",
    )
    val queriedPackages: List<String> get() = TELEGRAM_PACKAGES

    private const val NOTIF_WARP = 5

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val nets = HashMap<Network, Boolean>() // physical network -> is Wi-Fi/Ethernet (false = cellular)
    private val vpnNets = HashSet<Network>()       // every VPN network currently present (ours or not)

    private var appCtx: Context? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var vpnCb: ConnectivityManager.NetworkCallback? = null
    private var backend: GoBackend? = null
    @Volatile private var isUp = false
    @Volatile private var connecting = false
    @Volatile private var stopping = false
    private var consentNotified = false

    private val refreshTask = Runnable { appCtx?.let { refresh(it) } }

    private val tunnel = object : Tunnel {
        override fun getName(): String = "warp"
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "tunnel state: $newState")
            // DOWN that we did not ask for = another VPN app took over the single VPN slot.
            if (newState == Tunnel.State.DOWN && isUp && !stopping) {
                isUp = false
                main.post { schedule() }
            }
        }
        // WARP endpoint is dual-stack; the config endpoint is a literal IP, so no DNS
        // resolution preference is needed.
        override fun isIpv4ResolutionPreferred(): Boolean = false
        // The tunnel only runs on mobile data, which is a metered network; GoBackend
        // passes this to VpnService.Builder.setMetered().
        override fun isMetered(): Boolean = true
    }

    // ---- settings ---------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("warp", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", false)
    fun hasConfig(ctx: Context): Boolean = !prefs(ctx).getString("conf", null).isNullOrBlank()

    /** Also keep WARP on while connected to Wi-Fi (off by default). */
    fun wifiEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("wifi_enabled", false)

    fun setWifiEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("wifi_enabled", v).apply()
        refresh(ctx)
    }

    private fun anyEnabled(ctx: Context): Boolean = enabled(ctx) || wifiEnabled(ctx)

    fun setEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", v).apply()
        refresh(ctx)
    }

    /**
     * Validates and stores a config file. Returns null on success or a readable error text.
     * Client-only AmneziaWG 2.0 signature options (I1..I5) are skipped when the bundled
     * library does not know them; [lastNote] then lists what was skipped.
     */
    fun saveConfig(ctx: Context, text: String): String? {
        lastNote = null
        var t = normalize(text)
        if (!t.contains("[Interface]", ignoreCase = true) || !t.contains("[Peer]", ignoreCase = true)) {
            return "not a WireGuard/AmneziaWG config"
        }
        val skipped = ArrayList<String>()
        repeat(8) {
            try {
                Config.parse(ByteArrayInputStream(withApps(ctx, t).toByteArray()))
                prefs(ctx).edit().putString("conf", t).apply()
                if (skipped.isNotEmpty()) lastNote = skipped.joinToString(", ")
                refresh(ctx)
                return null
            } catch (e: Exception) {
                val key = unknownKey(e)
                if (key == null || !Regex("(?i)^I[1-5]$").matches(key)) return describe(e)
                skipped += key
                t = t.lines()
                    .filterNot { it.trim().substringBefore('=').trim().equals(key, ignoreCase = true) }
                    .joinToString("\n")
            }
        }
        return "too many unsupported options"
    }

    private fun normalize(raw: String): String =
        raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').trim()

    /** Calls a no-arg getter by name, so this code does not depend on the library's exact API. */
    private fun getter(e: Exception, name: String): Any? = try {
        e.javaClass.getMethod(name).invoke(e)
    } catch (_: Exception) {
        null
    }

    /** The option name the parser did not recognise, or null for any other kind of error. */
    private fun unknownKey(e: Exception): String? {
        val reason = getter(e, "getReason")?.toString() ?: return null
        if (!reason.contains("UNKNOWN", ignoreCase = true)) return null
        val txt = getter(e, "getText")?.toString() ?: return null
        return txt.substringBefore('=').trim().ifEmpty { null }
    }

    /** Human-readable parser error (BadConfigException has no message of its own). */
    private fun describe(e: Exception): String = buildString {
        append(e.javaClass.simpleName)
        for (n in listOf("getSection", "getLocation", "getReason")) {
            getter(e, n)?.let { append(' ').append(n.removePrefix("get").lowercase()).append('=').append(it) }
        }
        // The offending text can contain secrets (keys), so only show its option name.
        getter(e, "getText")?.toString()?.substringBefore('=')?.trim()?.takeIf { it.isNotEmpty() && it.length < 40 }
            ?.let { append(" key=").append(it) }
        e.cause?.let { append(" cause=").append(it.javaClass.simpleName) }
    }

    fun clearConfig(ctx: Context) {
        prefs(ctx).edit().remove("conf").apply()
        lastNote = null
        refresh(ctx)
    }

    /** Non-null when the system VPN consent dialog has to be shown first. */
    fun vpnPermissionIntent(ctx: Context): Intent? = VpnService.prepare(ctx)

    // ---- lifecycle (called by ProxyService) --------------------------------------

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val cm = app.getSystemService(ConnectivityManager::class.java)
        if (netCb == null) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    synchronized(nets) {
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> nets[network] = true
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> nets[network] = false
                            else -> nets.remove(network)
                        }
                    }
                    schedule()
                }

                override fun onLost(network: Network) {
                    synchronized(nets) { nets.remove(network) }
                    schedule()
                }
            }
            try {
                cm.registerNetworkCallback(req, cb)
                netCb = cb
            } catch (e: Exception) {
                Log.w(TAG, "cannot watch networks: $e")
            }
        }
        if (vpnCb == null) {
            // Default requests only match non-VPN networks, so NOT_VPN has to be removed explicitly.
            val vreq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val vcb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(vpnNets) { vpnNets.add(network) }
                    schedule()
                }

                override fun onLost(network: Network) {
                    synchronized(vpnNets) { vpnNets.remove(network) }
                    schedule()
                }
            }
            try {
                cm.registerNetworkCallback(vreq, vcb)
                vpnCb = vcb
            } catch (e: Exception) {
                Log.w(TAG, "cannot watch VPN networks: $e")
            }
        }
        refresh(app)
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        for (cb in listOf(netCb, vpnCb)) {
            if (cb != null) {
                try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
            }
        }
        netCb = null
        vpnCb = null
        main.removeCallbacks(refreshTask)
        synchronized(nets) { nets.clear() }
        synchronized(vpnNets) { vpnNets.clear() }
        down(app)
        cancelConsentNotification(app)
        status = if (!anyEnabled(app)) Status.OFF else Status.WAITING
    }

    // ---- decision logic -------------------------------------------------------------

    private fun schedule() {
        main.removeCallbacks(refreshTask)
        main.postDelayed(refreshTask, DEBOUNCE_MS)
    }

    /**
     * Re-evaluates what the tunnel should be doing right now:
     *  - WARP on only while mobile data is the only physical network;
     *  - never while another VPN app is active (Android allows one VPN at a time), and it comes
     *    back by itself once that VPN is gone. Android drops the VPN consent when another VPN app
     *    was approved, so after such a pause one tap on a notification re-approves it.
     */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        if (!anyEnabled(app)) {
            down(app)
            cancelConsentNotification(app)
            status = Status.OFF
            return
        }
        if (!hasConfig(app)) {
            down(app)
            status = Status.NO_CONFIG
            return
        }
        val onMobileOnly: Boolean = synchronized(nets) { nets.isNotEmpty() && nets.values.none { it } }
        val onWifi: Boolean = synchronized(nets) { nets.values.any { it } }
        val want = (enabled(app) && onMobileOnly) || (wifiEnabled(app) && onWifi)
        if (!want) {
            down(app)
            cancelConsentNotification(app)
            status = Status.WAITING
            return
        }
        // Any VPN network that is present while ours is not up belongs to another app.
        val otherVpn: Boolean = !isUp && !connecting && synchronized(vpnNets) { vpnNets.isNotEmpty() }
        if (otherVpn) {
            status = Status.PAUSED_VPN
            return
        }
        if (isUp) {
            status = Status.UP
            return
        }
        if (VpnService.prepare(app) != null) {
            status = Status.NEED_PERMISSION
            if (!consentNotified) {
                consentNotified = true
                postConsentNotification(app)
            }
            return
        }
        cancelConsentNotification(app)
        up(app)
    }

    private fun postConsentNotification(app: Context) {
        try {
            ProxyService.ensureChannels(app)
            val pi = PendingIntent.getActivity(
                app, 5, Intent(app, WarpConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(app, ProxyService.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(app.getString(R.string.warp_notif_title))
                .setContentText(app.getString(R.string.warp_notif_text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(app).notify(NOTIF_WARP, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not allowed: $e")
        } catch (e: Exception) {
            Log.w(TAG, "notification failed: $e")
        }
    }

    private fun cancelConsentNotification(app: Context) {
        consentNotified = false
        try { NotificationManagerCompat.from(app).cancel(NOTIF_WARP) } catch (_: Exception) { }
    }

    // ---- tunnel control -----------------------------------------------------------

    private fun backend(ctx: Context): GoBackend =
        backend ?: makeBackend(ctx.applicationContext).also { backend = it }

    /**
     * The library's GoBackend runs PreUp/PostUp/PreDown/PostDown hooks through a
     * TunnelActionHandler; with the plain one-argument constructor it is null and starting a
     * tunnel crashes with a NullPointerException. We give it a handler that does nothing
     * (WARP configs have no hook scripts). Done by reflection so this does not depend on the
     * handler's exact method list or on how the library accepts it (constructor or setter).
     */
    private fun makeBackend(app: Context): GoBackend {
        try {
            val hCls = Class.forName("org.amnezia.awg.backend.TunnelActionHandler")
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                hCls.classLoader, arrayOf(hCls),
                java.lang.reflect.InvocationHandler { _, m, _ ->
                    when (m.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                },
            )
            GoBackend::class.java.constructors.firstOrNull {
                it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == hCls
            }?.let { return it.newInstance(app, handler) as GoBackend }

            // Older builds: one-arg constructor plus a setter.
            val b = GoBackend::class.java.constructors
                .first { it.parameterTypes.size == 1 && it.parameterTypes[0] == Context::class.java }
                .newInstance(app) as GoBackend
            GoBackend::class.java.methods.firstOrNull {
                it.name.contains("TunnelActionHandler", ignoreCase = true) &&
                    it.parameterTypes.size == 1 && it.parameterTypes[0] == hCls
            }?.invoke(b, handler)
            return b
        } catch (e: Exception) {
            Log.w(TAG, "backend setup fallback: $e")
            // Last resort: in 2.3.7 the (Context, TunnelActionHandler) constructor is
            // the only one, so a null handler must never be passed.
            val hCls = Class.forName("org.amnezia.awg.backend.TunnelActionHandler")
            val ctor = GoBackend::class.java.constructors.first {
                it.parameterTypes.size == 2 && it.parameterTypes[1] == hCls
            }
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                hCls.classLoader, arrayOf(hCls),
                java.lang.reflect.InvocationHandler { _, m, _ ->
                    when (m.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                },
            )
            return ctor.newInstance(app, handler) as GoBackend
        }
    }

    private fun up(ctx: Context) {
        if (isUp || connecting) return
        connecting = true
        status = Status.CONNECTING
        exec.execute {
            try {
                val raw = prefs(ctx).getString("conf", null) ?: return@execute
                val cfg = Config.parse(ByteArrayInputStream(withApps(ctx, raw).toByteArray()))
                backend(ctx).setState(tunnel, Tunnel.State.UP, cfg)
                isUp = true
                lastError = null
                main.post { status = Status.UP }
            } catch (e: Exception) {
                Log.w(TAG, "tunnel up failed: $e")
                isUp = false
                lastError = e.message ?: describe(e)
                main.post { status = Status.ERROR }
            } finally {
                connecting = false
            }
        }
    }

    private fun down(ctx: Context) {
        if (!isUp) return
        exec.execute {
            stopping = true
            try {
                backend(ctx).setState(tunnel, Tunnel.State.DOWN, null)
            } catch (e: Exception) {
                Log.w(TAG, "tunnel down failed: $e")
            } finally {
                isUp = false
                stopping = false
            }
        }
    }

    // ---- config post-processing ------------------------------------------------------

    /** Adds "IncludedApplications" so only Telegram clients and this app use the tunnel. */
    private fun withApps(ctx: Context, raw: String): String {
        if (Regex("(?im)^\\s*(Included|Excluded)Applications\\s*=").containsMatchIn(raw)) return raw
        val pm = ctx.packageManager
        val pkgs = TELEGRAM_PACKAGES.filter {
            try { pm.getApplicationInfo(it, 0); true } catch (_: Exception) { false }
        } + ctx.packageName
        return Regex("(?im)^\\s*\\[Interface\\]\\s*$")
            .replaceFirst(raw, "[Interface]\nIncludedApplications = " + pkgs.joinToString(", "))
    }
}
