package org.flowseal.tgwsproxy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: looks at the latest GitHub release of [BuildConfig.UPDATE_REPO],
 * downloads its .apk and hands it to the system PackageInstaller.
 *
 * Android always asks the user to confirm an install, and the new APK must be
 * signed with the same key as the installed one, otherwise the system refuses.
 */
object Updater {

    data class Info(val version: String, val url: String, val size: Long, val notes: String) {
        fun toJson(): String = JSONObject()
            .put("version", version).put("url", url)
            .put("size", size).put("notes", notes).toString()

        companion object {
            fun fromJson(s: String): Info? = try {
                val o = JSONObject(s)
                Info(o.getString("version"), o.getString("url"), o.optLong("size"), o.optString("notes"))
            } catch (_: Exception) {
                null
            }
        }
    }

    const val ACTION_INSTALL_RESULT = "org.flowseal.tgwsproxy.INSTALL_RESULT"
    private const val UA = "TgWsProxy-Android"

    /** Newer release or null. Throws IOException on network/API problems. */
    fun check(ctx: Context): Info? {
        val api = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest"
        val body = httpGet(api, "application/vnd.github+json")
        val rel = JSONObject(body)
        val tag = rel.optString("tag_name").removePrefix("v")
        if (tag.isEmpty() || !isNewer(tag, BuildConfig.VERSION_NAME)) return null
        val assets = rel.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name.endsWith(".apk") && url.startsWith("https://")) {
                return Info(tag, url, a.optLong("size"), rel.optString("body"))
            }
        }
        return null
    }

    /** Segment-wise numeric compare: "1.11.4.1" > "1.11.4", "1.12" > "1.11.9". */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.map { it.toInt() }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun apkFile(ctx: Context, info: Info): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "TgWsProxy-${info.version}.apk")
    }

    fun isDownloaded(ctx: Context, info: Info): Boolean {
        val f = apkFile(ctx, info)
        return f.exists() && (info.size <= 0 || f.length() == info.size)
    }

    /** Downloads the APK (resumable only by restart); [onProgress] gets 0..100. */
    fun download(ctx: Context, info: Info, onProgress: (Int) -> Unit = {}): File {
        val target = apkFile(ctx, info)
        if (isDownloaded(ctx, info)) return target
        // Drop older downloads.
        target.parentFile?.listFiles()?.forEach { if (it != target) it.delete() }

        val part = File(target.path + ".part")
        val conn = open(info.url, null)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var done = 0L
            var last = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            if (info.size > 0 && part.length() != info.size) {
                part.delete()
                throw IOException("Incomplete download")
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(target)) throw IOException("Cannot save APK")
        return target
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Streams the APK into a PackageInstaller session; result goes to [InstallReceiver]. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            FileInputStream(apk).use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            // Must be mutable: the system fills in the status extras.
            val pi = PendingIntent.getBroadcast(
                ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
    }

    fun isUnmetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return !cm.isActiveNetworkMetered
    }

    private fun open(url: String, accept: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        if (accept != null) c.setRequestProperty("Accept", accept)
        val code = c.responseCode
        if (code !in 200..299) {
            c.disconnect()
            throw IOException("HTTP $code")
        }
        return c
    }

    private fun httpGet(url: String, accept: String): String {
        val c = open(url, accept)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
