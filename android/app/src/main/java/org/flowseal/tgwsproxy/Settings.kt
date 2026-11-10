package org.flowseal.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Persistent app settings; serialised to the JSON that android_entry.py expects. */
object Settings {
    const val DEFAULT_PORT = 1443
    const val DEFAULT_POOL = 6
    const val DEFAULT_DC_IP = "2:149.154.167.220\n4:149.154.167.220"

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

    fun dcIp(ctx: Context): String = prefs(ctx).getString("dc_ip", DEFAULT_DC_IP) ?: DEFAULT_DC_IP
    fun cfproxy(ctx: Context): Boolean = prefs(ctx).getBoolean("cfproxy", true)
    /** Pre-opened upstream sockets per DC. Bursts above this fall back to slower routes. */
    fun poolSize(ctx: Context): Int = prefs(ctx).getInt("pool_size", DEFAULT_POOL)
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
        poolSize: Int, cfproxy: Boolean, autostart: Boolean, verbose: Boolean,
        keepAwake: Boolean, autoUpdate: Boolean, updateWifiOnly: Boolean,
    ) {
        prefs(ctx).edit()
            .putInt("port", port)
            .putString("secret", secret)
            .putString("dc_ip", dcIp)
            .putString("cf_worker", cfWorker)
            .putInt("pool_size", poolSize)
            .putBoolean("cfproxy", cfproxy)
            .putBoolean("autostart", autostart)
            .putBoolean("verbose", verbose)
            .putBoolean("keep_awake", keepAwake)
            .putBoolean("auto_update", autoUpdate)
            .putBoolean("update_wifi_only", updateWifiOnly)
            .apply()
    }

    // The key handed to Telegram must be exactly the key shown in the form and used by
    // the core. No "dd"/"ee" prefix: those are transport hints, and the user compares the
    // key character by character ("dd" made every link look wrong).
    fun link(ctx: Context): String =
        "tg://proxy?server=127.0.0.1&port=${port(ctx)}&secret=${secret(ctx)}"

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
            .put("pool_size", poolSize(ctx))
            .put("verbose", verbose(ctx))
            .toString()
    }
}
