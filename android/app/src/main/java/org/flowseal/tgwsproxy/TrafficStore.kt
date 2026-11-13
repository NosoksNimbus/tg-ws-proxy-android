package org.flowseal.tgwsproxy

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-day traffic counters split by network kind. Keys look like "20261110.m.d"
 * (day, m=mobile/other | w=wifi, d=down | u=up). Anything that is not Wi-Fi counts as mobile.
 */
object TrafficStore {

    data class Totals(val mobDown: Long, val mobUp: Long, val wifiDown: Long, val wifiUp: Long)

    private const val KEEP_DAYS = 120

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("traffic", Context.MODE_PRIVATE)
    private fun day(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    @Synchronized
    fun add(ctx: Context, net: Net, down: Long, up: Long) {
        if (down <= 0 && up <= 0) return
        val p = prefs(ctx)
        val kind = if (net == Net.WIFI) "w" else "m"
        val d = day()
        val kd = "$d.$kind.d"
        val ku = "$d.$kind.u"
        p.edit()
            .putLong(kd, p.getLong(kd, 0) + maxOf(0, down))
            .putLong(ku, p.getLong(ku, 0) + maxOf(0, up))
            .apply()
    }

    fun today(ctx: Context): Totals = sum(ctx, day())

    fun month(ctx: Context): Totals = sum(ctx, day().substring(0, 6))

    private fun sum(ctx: Context, prefix: String): Totals {
        var md = 0L; var mu = 0L; var wd = 0L; var wu = 0L
        for ((k, v) in prefs(ctx).all) {
            if (!k.startsWith(prefix) || v !is Long) continue
            when (k.substring(k.indexOf('.') + 1)) {
                "m.d" -> md += v
                "m.u" -> mu += v
                "w.d" -> wd += v
                "w.u" -> wu += v
            }
        }
        return Totals(md, mu, wd, wu)
    }

    @Synchronized
    fun prune(ctx: Context) {
        val cutoff = SimpleDateFormat("yyyyMMdd", Locale.US)
            .format(Date(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L))
        val p = prefs(ctx)
        val e = p.edit()
        for (k in p.all.keys) if (k.substringBefore('.') < cutoff) e.remove(k)
        e.apply()
    }

    @Synchronized
    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    fun format(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
