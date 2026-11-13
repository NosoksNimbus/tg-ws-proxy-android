package org.flowseal.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Persistent app settings; serialised to the JSON that android_entry.py expects. */
object Settings {
    const val DEFAULT_PORT = 1443
    const val DEFAULT_POOL = 8
    // Upstream default: DC2 and DC4 use the direct WebSocket endpoint.
    const val DEFAULT_DC_IP = "2:149.154.167.220\n4:149.154.167.220"
    // Previous default (all five DCs on one IP); migrated to the new default on read.
    private const val LEGACY_DC_IP = "1:149.154.167.220\n2:149.154.167.220\n3:149.154.167.220\n4:149.154.167.220\n5:149.154.167.220"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("proxy", Context.MODE_PRIVATE)

    fun port(ctx: Context): Int = prefs(ctx).getInt("port", DEFAULT_PORT)

    fun secret(ctx: Context): String {
        val p = prefs(ctx)
        val cur = p.getString("secret", null)
        if (cur != null && cur.length == 32) return cur
        val fresh = newSecret()
        p.edit().putString("secret", fresh).apply()
        return fresh
    }

    fun newSecret(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun dcIp(ctx: Context): String {
        val saved = prefs(ctx).getString("dc_ip", null) ?: return DEFAULT_DC_IP
        val t = saved.trim()
        return if (t == LEGACY_DC_IP || t == "4:149.154.167.220") DEFAULT_DC_IP else saved
    }
    fun cfproxy(ctx: Context): Boolean = prefs(ctx).getBoolean("cfproxy", true)
    /**
     * Pre-opened upstream sockets per DC. Bursts above this fall back to slower routes.
     * [poolMobile] is the general value; [poolWifi] only applies with [profiles] enabled.
     */
    fun poolMobile(ctx: Context): Int =
        prefs(ctx).getInt("pool_mobile", prefs(ctx).getInt("pool_size", DEFAULT_POOL))
    fun poolWifi(ctx: Context): Int = prefs(ctx).getInt("pool_wifi", poolMobile(ctx))
    fun profiles(ctx: Context): Boolean = prefs(ctx).getBoolean("profiles", false)

    /** The pool size to use right now, following the current network when profiles are on. */
    fun effectivePool(ctx: Context, net: Net = Net.current(ctx)): Int =
        if (profiles(ctx) && net == Net.WIFI) poolWifi(ctx) else poolMobile(ctx)
    fun cfWorker(ctx: Context): String = prefs(ctx).getString("cf_worker", "") ?: ""
    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean("autostart", false)
    fun verbose(ctx: Context): Boolean = prefs(ctx).getBoolean("verbose", false)

    /** Hold a partial wake lock while the proxy runs (keeps the radio path alive in Doze). */
    fun keepAwake(ctx: Context): Boolean = prefs(ctx).getBoolean("keep_awake", true)

    // --- state shared between service, boot receiver and UI ---
    fun wantRunning(ctx: Context): Boolean = prefs(ctx).getBoolean("want_running", false)
    fun setWantRunning(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean("want_running", v).apply()

    // --- updates ---
    fun autoUpdate(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_update", true)
    fun updateWifiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean("update_wifi_only", true)
    fun lastUpdateCheck(ctx: Context): Long = prefs(ctx).getLong("update_last_check", 0L)
    fun setLastUpdateCheck(ctx: Context, t: Long) =
        prefs(ctx).edit().putLong("update_last_check", t).apply()

    /** Last release seen on GitHub that is newer than this build (JSON), or null. */
    fun updateInfo(ctx: Context): String? = prefs(ctx).getString("update_info", null)
    fun setUpdateInfo(ctx: Context, json: String?) =
        prefs(ctx).edit().putString("update_info", json).apply()

    fun save(
        ctx: Context, port: Int, secret: String, dcIp: String, cfWorker: String,
        poolMobile: Int, poolWifi: Int, profiles: Boolean, cfproxy: Boolean, autostart: Boolean, verbose: Boolean,
        keepAwake: Boolean, autoUpdate: Boolean, updateWifiOnly: Boolean,
    ) {
        prefs(ctx).edit()
            .putInt("port", port)
            .putString("secret", secret)
            .putString("dc_ip", dcIp)
            .putString("cf_worker", cfWorker)
            .putInt("pool_mobile", poolMobile)
            .putInt("pool_wifi", poolWifi)
            .putBoolean("profiles", profiles)
            .putBoolean("cfproxy", cfproxy)
            .putBoolean("autostart", autostart)
            .putBoolean("verbose", verbose)
            .putBoolean("keep_awake", keepAwake)
            .putBoolean("auto_update", autoUpdate)
            .putBoolean("update_wifi_only", updateWifiOnly)
            .apply()
    }

    fun link(ctx: Context): String =
        "tg://proxy?server=127.0.0.1&port=${port(ctx)}&secret=dd${secret(ctx)}"

    fun toJson(ctx: Context): String {
        val dc = JSONArray()
        dcIp(ctx).lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { dc.put(it) }
        // Domains may be separated by commas or newlines, as in the desktop UI.
        val workers = JSONArray()
        cfWorker(ctx).split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .forEach { workers.put(it) }
        return JSONObject()
            .put("host", "127.0.0.1")
            .put("port", port(ctx))
            .put("secret", secret(ctx))
            .put("dc_ip", dc)
            .put("cfproxy", cfproxy(ctx))
            .put("cfproxy_worker_domain", workers)
            .put("pool_size", effectivePool(ctx))
            .put("verbose", verbose(ctx))
            .toString()
    }

    // ---- backup ------------------------------------------------------------------

    /** All user settings as pretty JSON (contains the secret!). */
    fun exportJson(ctx: Context): String = JSONObject()
        .put("app", "tg-ws-proxy-android")
        .put("format", 1)
        .put("port", port(ctx))
        .put("secret", secret(ctx))
        .put("dc_ip", dcIp(ctx))
        .put("cf_worker", cfWorker(ctx))
        .put("cfproxy", cfproxy(ctx))
        .put("pool_mobile", poolMobile(ctx))
        .put("pool_wifi", poolWifi(ctx))
        .put("profiles", profiles(ctx))
        .put("keep_awake", keepAwake(ctx))
        .put("autostart", autostart(ctx))
        .put("verbose", verbose(ctx))
        .put("auto_update", autoUpdate(ctx))
        .put("update_wifi_only", updateWifiOnly(ctx))
        .toString(2)

    /** Validates and applies exported settings. Returns null on success or an error text. */
    fun importJson(ctx: Context, text: String): String? {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return "not a JSON file"
        }
        if (o.optString("app") != "tg-ws-proxy-android") return "not a TG WS Proxy settings file"

        val port = o.optInt("port", port(ctx))
        if (port !in 1024..65535) return "bad port"
        val secret = o.optString("secret", secret(ctx)).lowercase()
        if (!Regex("[0-9a-f]{32}").matches(secret)) return "bad secret"
        val pm = o.optInt("pool_mobile", poolMobile(ctx))
        val pw = o.optInt("pool_wifi", poolWifi(ctx))
        if (pm !in 1..16 || pw !in 1..16) return "bad pool size"

        prefs(ctx).edit()
            .putInt("port", port)
            .putString("secret", secret)
            .putString("dc_ip", o.optString("dc_ip", dcIp(ctx)))
            .putString("cf_worker", o.optString("cf_worker", cfWorker(ctx)))
            .putBoolean("cfproxy", o.optBoolean("cfproxy", cfproxy(ctx)))
            .putInt("pool_mobile", pm)
            .putInt("pool_wifi", pw)
            .putBoolean("profiles", o.optBoolean("profiles", profiles(ctx)))
            .putBoolean("keep_awake", o.optBoolean("keep_awake", keepAwake(ctx)))
            .putBoolean("autostart", o.optBoolean("autostart", autostart(ctx)))
            .putBoolean("verbose", o.optBoolean("verbose", verbose(ctx)))
            .putBoolean("auto_update", o.optBoolean("auto_update", autoUpdate(ctx)))
            .putBoolean("update_wifi_only", o.optBoolean("update_wifi_only", updateWifiOnly(ctx)))
            .apply()
        return null
    }
}
