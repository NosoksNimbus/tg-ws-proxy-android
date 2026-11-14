package org.flowseal.tgwsproxy

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Editable settings form. Every field is Compose state so the UI recomposes on change. */
class FormState {
    var port by mutableStateOf("")
    var secret by mutableStateOf("")
    var dcIp by mutableStateOf("")
    var cfWorker by mutableStateOf("")
    var poolMobile by mutableStateOf("")
    var poolWifi by mutableStateOf("")
    var profiles by mutableStateOf(false)
    var cfproxy by mutableStateOf(true)
    var keepAwake by mutableStateOf(true)
    var autostart by mutableStateOf(false)
    var verbose by mutableStateOf(false)
    var autoUpdate by mutableStateOf(true)
    var wifiOnly by mutableStateOf(true)

    var portError by mutableStateOf<String?>(null)
    var secretError by mutableStateOf<String?>(null)
    var poolMobileError by mutableStateOf<String?>(null)
    var poolWifiError by mutableStateOf<String?>(null)
}

enum class RunState { OFF, STARTING, ON, ERROR }

class CheckLine(val name: String, val ok: Boolean, val detail: String)

class TrafficTotals(
    val todayMobile: String, val todayWifi: String,
    val monthMobile: String, val monthWifi: String,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Application get() = getApplication()

    val form = FormState()

    // ---- live state shown on the home tab ------------------------------------------
    var runState by mutableStateOf(RunState.OFF); private set
    var statusHint by mutableStateOf(""); private set
    var downRate by mutableStateOf<String?>(null); private set
    var upRate by mutableStateOf<String?>(null); private set
    val history = mutableStateListOf<Float>()
    var diagLine by mutableStateOf(""); private set
    var logText by mutableStateOf(""); private set
    var totals by mutableStateOf(TrafficTotals("", "", "", "")); private set

    /** True while the "More" tab is on screen, so the poller only fetches logs then. */
    @Volatile var logVisible = false

    // ---- connectivity check -----------------------------------------------------------
    var checkRunning by mutableStateOf(false); private set
    var checkLines by mutableStateOf<List<CheckLine>>(emptyList()); private set
    var checkVerdict by mutableStateOf<String?>(null); private set

    // ---- updates -------------------------------------------------------------------------
    var updVersionLine by mutableStateOf(""); private set
    var updStatus by mutableStateOf(""); private set
    var updInfo by mutableStateOf<Updater.Info?>(null); private set
    var updInstallLabel by mutableStateOf(""); private set
    var updBusy by mutableStateOf(false); private set
    var updProgress by mutableStateOf(-1); private set

    private var pollJob: Job? = null
    private var startingSince = 0L
    private var lastDown = -1L
    private var lastUp = -1L
    private var lastT = 0L

    init {
        loadForm()
        renderUpdate()
        refreshTotals()
    }

    private fun str(id: Int, vararg a: Any): String = ctx.getString(id, *a)
    private fun toast(id: Int, vararg a: Any) =
        Toast.makeText(ctx, str(id, *a), Toast.LENGTH_SHORT).show()

    // ================================ form =================================================

    fun loadForm() {
        form.port = Settings.port(ctx).toString()
        form.secret = Settings.secret(ctx)
        form.dcIp = Settings.dcIp(ctx)
        form.cfWorker = Settings.cfWorker(ctx)
        form.poolMobile = Settings.poolMobile(ctx).toString()
        form.poolWifi = Settings.poolWifi(ctx).toString()
        form.profiles = Settings.profiles(ctx)
        form.cfproxy = Settings.cfproxy(ctx)
        form.keepAwake = Settings.keepAwake(ctx)
        form.autostart = Settings.autostart(ctx)
        form.verbose = Settings.verbose(ctx)
        form.autoUpdate = Settings.autoUpdate(ctx)
        form.wifiOnly = Settings.updateWifiOnly(ctx)
    }

    fun newSecret() {
        form.secret = Settings.newSecret()
        form.secretError = null
    }

    /** Accepts a bare 32-hex secret as well as a pasted "dd…"/"ee…" proxy secret. */
    private fun normalizeSecret(raw: String): String {
        val t = raw.filter { !it.isWhitespace() }.lowercase()
        return if (t.length > 32 && (t.startsWith("dd") || t.startsWith("ee"))) t.substring(2, 34) else t
    }

    /**
     * Validates and stores the form. Returns false (with field errors set) if invalid.
     * A running proxy is restarted when the connection settings changed, so the link given
     * to Telegram always matches what the proxy really uses.
     */
    fun saveForm(toastErrors: Boolean = true): Boolean {
        val p = form.port.trim().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            form.portError = str(R.string.invalid_port)
            if (toastErrors) toast(R.string.invalid_port)
            return false
        }
        form.portError = null
        val s = normalizeSecret(form.secret)
        if (!Regex("[0-9a-f]{32}").matches(s)) {
            form.secretError = str(R.string.invalid_secret)
            if (toastErrors) toast(R.string.invalid_secret)
            return false
        }
        form.secretError = null
        if (form.secret != s) form.secret = s
        val pm = form.poolMobile.trim().toIntOrNull()
        if (pm == null || pm !in 1..16) {
            form.poolMobileError = str(R.string.invalid_pool)
            if (toastErrors) toast(R.string.invalid_pool)
            return false
        }
        form.poolMobileError = null
        val pw = form.poolWifi.trim().toIntOrNull() ?: pm
        if (pw !in 1..16) {
            form.poolWifiError = str(R.string.invalid_pool)
            if (toastErrors) toast(R.string.invalid_pool)
            return false
        }
        form.poolWifiError = null

        val before = Settings.toJson(ctx)
        Settings.save(
            ctx, p, s, form.dcIp, form.cfWorker, pm, pw, form.profiles, form.cfproxy,
            form.autostart, form.verbose, form.keepAwake, form.autoUpdate, form.wifiOnly,
        )
        if (ProxyService.running && Settings.toJson(ctx) != before) {
            ctx.startService(
                Intent(ctx, ProxyService::class.java).setAction(ProxyService.ACTION_RESTART))
            toast(R.string.restarted_applied)
        }
        return true
    }

    fun saveQuiet() {
        saveForm(toastErrors = false)
    }

    // ============================ proxy control ===========================================

    fun toggle() {
        if (ProxyService.running) stop() else start()
    }

    fun start() {
        if (!saveForm()) return
        ProxyService.lastError = ""
        startingSince = System.currentTimeMillis()
        ContextCompat.startForegroundService(
            ctx, Intent(ctx, ProxyService::class.java).setAction(ProxyService.ACTION_START))
        refreshState()
    }

    fun stop() {
        startingSince = 0
        ctx.startService(Intent(ctx, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
    }

    fun openInTelegram() {
        if (!saveForm()) return
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(Settings.link(ctx)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_telegram)
        }
    }

    fun copyLink() {
        if (!saveForm()) return
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("tg-proxy", Settings.link(ctx)))
        toast(R.string.link_copied)
    }

    fun copyLog() {
        if (logText.isBlank()) return toast(R.string.logs_empty)
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("tg-ws-proxy log", logText))
        toast(R.string.link_copied)
    }

    fun shareLog() {
        if (logText.isBlank()) return toast(R.string.logs_empty)
        val head = buildString {
            append("TG WS Proxy ${BuildConfig.VERSION_NAME} (core ${BuildConfig.CORE_VERSION})\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ")
            append("${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Network: ${Net.current(ctx)}, pool ${Settings.effectivePool(ctx)}\n\n")
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, str(R.string.share_title))
            .putExtra(Intent.EXTRA_TEXT, head + logText)
        ctx.startActivity(
            Intent.createChooser(send, str(R.string.share_title))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun requestIgnoreBattery() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        ctx.startActivity(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // =============================== backup ================================================

    fun exportTo(uri: Uri) {
        if (!saveForm()) return
        viewModelScope.launch(Dispatchers.IO) {
            val err = try {
                ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(Settings.exportJson(ctx).toByteArray())
                }
                null
            } catch (e: Exception) {
                e.message ?: "I/O"
            }
            withContext(Dispatchers.Main) {
                if (err == null) toast(R.string.export_done) else toast(R.string.import_failed, err)
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: ""
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(R.string.import_failed, e.message ?: "I/O") }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val before = Settings.toJson(ctx)
                val err = Settings.importJson(ctx, text)
                if (err != null) {
                    toast(R.string.import_failed, err)
                    return@withContext
                }
                loadForm()
                if (ProxyService.running && Settings.toJson(ctx) != before) {
                    ctx.startService(
                        Intent(ctx, ProxyService::class.java).setAction(ProxyService.ACTION_RESTART))
                }
                toast(R.string.import_done)
            }
        }
    }

    // ============================= connectivity check =====================================

    fun runCheck() {
        if (checkRunning) return
        if (!saveForm()) return
        checkRunning = true
        checkLines = emptyList()
        checkVerdict = null
        val cfg = Settings.toJson(ctx)
        viewModelScope.launch(Dispatchers.IO) {
            var out: JSONObject? = null
            var error: String? = null
            try {
                val mod = ProxyService.python(ctx).getModule("android_entry")
                out = JSONObject(mod.callAttr("diagnose", cfg).toString())
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            withContext(Dispatchers.Main) {
                checkRunning = false
                if (out == null) checkVerdict = str(R.string.check_failed, error ?: "?")
                else showCheck(out)
            }
        }
    }

    private fun showCheck(o: JSONObject) {
        val lines = ArrayList<CheckLine>()
        val arr = o.optJSONArray("results")
        var ws = false; var cf = false; var tcp = false; var net = false
        if (arr != null) for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val name = r.optString("name")
            val ok = r.optBoolean("ok")
            if (ok) {
                if (name.startsWith("WS")) ws = true
                if (name.startsWith("CF") || name.startsWith("Worker")) cf = true
                if (name.startsWith("TCP")) tcp = true
                if (name.startsWith("NET")) net = true
            }
            lines += CheckLine(
                name, ok, if (ok) str(R.string.check_ms, r.optInt("ms")) else r.optString("error"))
        }
        checkLines = lines
        val extra = if (o.optBoolean("cf_needs_proxy")) "\n" + str(R.string.check_cf_needs_proxy) else ""
        checkVerdict = str(
            when {
                ws -> R.string.verdict_ws
                cf -> R.string.verdict_cf
                tcp -> R.string.verdict_tcp
                net -> R.string.verdict_filtered
                else -> R.string.verdict_none
            }) + extra
    }

    // ================================ traffic ================================================

    fun refreshTotals() {
        val t = TrafficStore.today(ctx)
        val m = TrafficStore.month(ctx)
        fun line(d: Long, u: Long) = "↓ ${TrafficStore.format(d)}  ↑ ${TrafficStore.format(u)}"
        totals = TrafficTotals(
            line(t.mobDown, t.mobUp), line(t.wifiDown, t.wifiUp),
            line(m.mobDown, m.mobUp), line(m.wifiDown, m.wifiUp))
    }

    fun resetTraffic() {
        TrafficStore.reset(ctx)
        refreshTotals()
        toast(R.string.traffic_reset_done)
    }

    // ================================ polling ================================================

    /** Called from onResume/onPause. All Python calls stay off the UI thread. */
    fun setActive(active: Boolean) {
        if (!active) {
            pollJob?.cancel()
            pollJob = null
            lastT = 0L
            return
        }
        if (pollJob != null) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                var stats: JSONObject? = null
                var log: String? = null
                if (Python.isStarted()) {
                    try {
                        val mod = Python.getInstance().getModule("android_entry")
                        if (ProxyService.running) stats = JSONObject(mod.callAttr("get_stats").toString())
                        if (logVisible) {
                            log = mod.callAttr("get_logs").toString()
                                .lines().takeLast(80).joinToString("\n")
                        }
                    } catch (_: Throwable) {
                    }
                }
                withContext(Dispatchers.Main) {
                    refreshState()
                    applyStats(stats)
                    log?.let { logText = it }
                }
                delay(1000)
            }
        }
    }

    private fun refreshState() {
        val err = ProxyService.lastError
        val state = when {
            ProxyService.running -> RunState.ON
            err.isNotEmpty() -> RunState.ERROR
            startingSince > 0 && System.currentTimeMillis() - startingSince < 20_000 -> RunState.STARTING
            else -> RunState.OFF
        }
        if (state == RunState.ON) startingSince = 0
        runState = state
        statusHint = when (state) {
            RunState.ON -> str(R.string.state_on_hint, Settings.port(ctx))
            RunState.STARTING -> ""
            RunState.ERROR -> err
            RunState.OFF -> str(R.string.state_off_hint)
        }
    }

    private fun applyStats(st: JSONObject?) {
        if (st == null) {
            if (lastT != 0L) history.clear()
            lastT = 0L
            downRate = null
            upRate = null
            diagLine = str(R.string.diag_off)
            return
        }
        val now = System.nanoTime()
        val down = st.optLong("down")
        val up = st.optLong("up")
        if (lastT != 0L) {
            val dt = (now - lastT) / 1e9
            if (dt > 0.2) {
                val dRate = maxOf(0L, down - lastDown) / dt
                val uRate = maxOf(0L, up - lastUp) / dt
                downRate = fmtRate(dRate)
                upRate = fmtRate(uRate)
                history.add(dRate.toFloat())
                while (history.size > 60) history.removeAt(0)
            }
        }
        lastT = now
        lastDown = down
        lastUp = up
        val total = st.optLong("pool_total")
        val pool = if (total > 0) "${st.optLong("pool_hits") * 100 / total}%" else "—"
        diagLine = str(
            R.string.diag_line,
            st.optInt("active"), st.optInt("ws"), st.optInt("cf"), st.optInt("tcp"), pool)
    }

    private fun fmtRate(bps: Double): String = when {
        bps >= 1_048_576 -> String.format("%.1f MB/s", bps / 1_048_576)
        bps >= 1024 -> String.format("%.0f KB/s", bps / 1024)
        else -> String.format("%.0f B/s", bps)
    }

    // ================================ updates ================================================

    private fun currentUpdate(): Updater.Info? =
        Settings.updateInfo(ctx)?.let { Updater.Info.fromJson(it) }
            ?.takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }

    fun renderUpdate() {
        updVersionLine = str(R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.CORE_VERSION)
        val info = currentUpdate()
        updInfo = info
        val last = Settings.lastUpdateCheck(ctx)
        val checked = if (last == 0L) str(R.string.update_checked_never)
        else str(
            R.string.update_checked_at,
            DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        if (info == null) {
            updStatus = if (last == 0L) checked else str(R.string.update_up_to_date) + " · " + checked
            updInstallLabel = ""
        } else {
            updStatus = str(R.string.update_available, info.version)
            updInstallLabel = str(
                if (Updater.isDownloaded(ctx, info)) R.string.update_install
                else R.string.update_download_install, info.version)
        }
    }

    /** Checks at most every 6 hours when the app opens, if automatic updates are on. */
    fun autoCheckOnOpen() {
        if (Settings.autoUpdate(ctx) &&
            System.currentTimeMillis() - Settings.lastUpdateCheck(ctx) > 6 * 3600_000L
        ) checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (updBusy) return
        updBusy = true
        if (manual) updStatus = str(R.string.update_checking)
        viewModelScope.launch(Dispatchers.IO) {
            var failed = false
            try {
                val info = Updater.check(ctx)
                Settings.setLastUpdateCheck(ctx, System.currentTimeMillis())
                Settings.setUpdateInfo(ctx, info?.toJson())
            } catch (_: Exception) {
                failed = true
            }
            withContext(Dispatchers.Main) {
                updBusy = false
                renderUpdate()
                if (failed && manual) toast(R.string.update_check_failed)
                val info = currentUpdate()
                if (!failed && info != null && !manual && Settings.autoUpdate(ctx) &&
                    (!Settings.updateWifiOnly(ctx) || Updater.isUnmetered(ctx))
                ) downloadAndInstall(info, prepareOnly = true)
            }
        }
    }

    /** Downloads (if needed) and then installs; with [prepareOnly] stops after the download. */
    fun downloadAndInstall(info: Updater.Info, prepareOnly: Boolean = false) {
        if (updBusy) return
        if (!prepareOnly && !Updater.canInstall(ctx)) {
            Toast.makeText(ctx, str(R.string.update_allow_install), Toast.LENGTH_LONG).show()
            ctx.startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        updBusy = true
        updProgress = 0
        viewModelScope.launch(Dispatchers.IO) {
            var error: String? = null
            var apk: java.io.File? = null
            try {
                apk = Updater.download(ctx, info) { p ->
                    updProgress = p
                    updStatus = str(R.string.update_downloading, p)
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            withContext(Dispatchers.Main) {
                updBusy = false
                updProgress = -1
                renderUpdate()
                if (error != null) {
                    Toast.makeText(ctx, str(R.string.update_failed, error), Toast.LENGTH_LONG).show()
                } else if (!prepareOnly && apk != null) {
                    try {
                        Updater.install(ctx, apk)
                    } catch (e: Exception) {
                        Toast.makeText(
                            ctx, str(R.string.update_failed, e.message ?: e.toString()),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    fun installPending() {
        val info = currentUpdate() ?: return
        renderUpdate()
        if (Updater.isDownloaded(ctx, info)) downloadAndInstall(info)
    }
}
