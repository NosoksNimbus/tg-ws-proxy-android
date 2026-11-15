package org.flowseal.tgwsproxy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One finished connectivity check. Latencies are in ms, null = route unavailable. */
data class CheckRun(val time: Long, val net: String, val vpn: Boolean, val ws: Int?, val cf: Int?, val tcp: Int?) {
    val reachable: Int get() = listOfNotNull(ws, cf, tcp).size
}

/** Last connectivity checks, kept in SharedPreferences (max 30). */
object CheckStore {
    private const val MAX = 30
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("checks", Context.MODE_PRIVATE)

    fun add(ctx: Context, result: JSONObject, net: Net, vpn: Boolean) {
        var ws: Int? = null; var cf: Int? = null; var tcp: Int? = null
        val arr = result.optJSONArray("results")
        if (arr != null) for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (!r.optBoolean("ok")) continue
            val name = r.optString("name")
            val ms = r.optInt("ms")
            fun best(cur: Int?) = if (cur == null || ms < cur) ms else cur
            when {
                name.startsWith("WS") -> ws = best(ws)
                name.startsWith("CF") || name.startsWith("Worker") -> cf = best(cf)
                name.startsWith("TCP") -> tcp = best(tcp)
            }
        }
        val list = JSONArray(prefs(ctx).getString("runs", "[]"))
        val o = JSONObject().put("t", System.currentTimeMillis()).put("net", net.name).put("vpn", vpn)
        ws?.let { o.put("ws", it) }; cf?.let { o.put("cf", it) }; tcp?.let { o.put("tcp", it) }
        val out = JSONArray()
        val start = maxOf(0, list.length() - (MAX - 1))
        for (i in start until list.length()) out.put(list.get(i))
        out.put(o)
        prefs(ctx).edit().putString("runs", out.toString()).apply()
    }

    /** Oldest first. */
    fun list(ctx: Context): List<CheckRun> = try {
        val a = JSONArray(prefs(ctx).getString("runs", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CheckRun(
                o.optLong("t"), o.optString("net", "OTHER"), o.optBoolean("vpn"),
                if (o.has("ws")) o.getInt("ws") else null,
                if (o.has("cf")) o.getInt("cf") else null,
                if (o.has("tcp")) o.getInt("tcp") else null)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun clear(ctx: Context) = prefs(ctx).edit().remove("runs").apply()
}
