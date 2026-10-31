package org.flowseal.tgwsproxy

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** Persistent app settings; serialised to the JSON that android_entry.py expects. */
object Settings {
    const val DEFAULT_PORT = 1443
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
    fun cfWorker(ctx: Context): String = prefs(ctx).getString("cf_worker", "") ?: ""
    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean("autostart", false)
    fun verbose(ctx: Context): Boolean = prefs(ctx).getBoolean("verbose", false)

    fun save(
        ctx: Context, port: Int, secret: String, dcIp: String, cfWorker: String,
        cfproxy: Boolean, autostart: Boolean, verbose: Boolean,
    ) {
        prefs(ctx).edit()
            .putInt("port", port)
            .putString("secret", secret)
            .putString("dc_ip", dcIp)
            .putString("cf_worker", cfWorker)
            .putBoolean("cfproxy", cfproxy)
            .putBoolean("autostart", autostart)
            .putBoolean("verbose", verbose)
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
            .put("verbose", verbose(ctx))
            .toString()
    }
}
