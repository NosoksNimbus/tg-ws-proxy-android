package org.flowseal.tgwsproxy

import android.Manifest
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python
import org.json.JSONObject
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        /** True while the activity is on screen (used to decide how to show the install prompt). */
        @Volatile var visible = false
    }

    private enum class UiState { OFF, STARTING, ON, ERROR }

    // views
    private lateinit var power: View
    private lateinit var ring: View
    private lateinit var powerIcon: ImageView
    private lateinit var halo: View
    private lateinit var speedDown: TextView
    private lateinit var speedUp: TextView
    private lateinit var spark: SparkView
    private lateinit var diag: TextView
    private lateinit var poolSize: EditText
    private lateinit var tilPool: TextInputLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusHint: TextView
    private lateinit var port: EditText
    private lateinit var secret: EditText
    private lateinit var tilPort: TextInputLayout
    private lateinit var tilSecret: TextInputLayout
    private lateinit var dcIp: EditText
    private lateinit var cfWorker: EditText
    private lateinit var cfproxy: MaterialSwitch
    private lateinit var keepAwake: MaterialSwitch
    private lateinit var autostart: MaterialSwitch
    private lateinit var verbose: MaterialSwitch
    private lateinit var autoUpdate: MaterialSwitch
    private lateinit var wifiOnly: MaterialSwitch
    private lateinit var updateVersion: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateProgress: ProgressBar
    private lateinit var btnUpdateCheck: MaterialButton
    private lateinit var btnUpdateInstall: MaterialButton
    private lateinit var logBox: View
    private lateinit var logBody: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private var spin: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var startingSince = 0L
    private var shownState: UiState? = null
    @Volatile private var updateBusy = false
    @Volatile private var logOpen = false

    // --- background poller: every Python call stays OFF the UI thread ---
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null
    private var lastDown = -1L
    private var lastUp = -1L
    private var lastT = 0L

    private class Snapshot(val stats: JSONObject?, val log: String?)

    private val pollTask = object : Runnable {
        override fun run() {
            val h = pollHandler ?: return
            val snap = collect()
            ui.post { render(snap) }
            h.postDelayed(this, 1000)
        }
    }

    private fun collect(): Snapshot {
        var stats: JSONObject? = null
        var log: String? = null
        if (Python.isStarted()) {
            try {
                val mod = Python.getInstance().getModule("android_entry")
                if (ProxyService.running) {
                    stats = JSONObject(mod.callAttr("get_stats").toString())
                }
                if (logOpen) {
                    log = mod.callAttr("get_logs").toString()
                        .lines().takeLast(80).joinToString("\n")
                }
            } catch (_: Throwable) {
            }
        }
        return Snapshot(stats, log)
    }

    private fun startPolling() {
        if (pollThread != null) return
        val t = HandlerThread("ui-poll", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        pollThread = t
        pollHandler = Handler(t.looper).also { it.post(pollTask) }
    }

    private fun stopPolling() {
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollThread = null
        pollHandler = null
        lastT = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        power = findViewById(R.id.power)
        ring = findViewById(R.id.ring)
        powerIcon = findViewById(R.id.power_icon)
        halo = findViewById(R.id.halo)
        speedDown = findViewById(R.id.speed_down)
        speedUp = findViewById(R.id.speed_up)
        spark = findViewById(R.id.spark)
        diag = findViewById(R.id.diag)
        poolSize = findViewById(R.id.pool_size)
        tilPool = findViewById(R.id.til_pool)
        statusTitle = findViewById(R.id.status_title)
        statusHint = findViewById(R.id.status_hint)
        port = findViewById(R.id.port)
        secret = findViewById(R.id.secret)
        tilPort = findViewById(R.id.til_port)
        tilSecret = findViewById(R.id.til_secret)
        dcIp = findViewById(R.id.dc_ip)
        cfWorker = findViewById(R.id.cf_worker)
        cfproxy = findViewById(R.id.cfproxy)
        keepAwake = findViewById(R.id.keep_awake)
        autostart = findViewById(R.id.autostart)
        verbose = findViewById(R.id.verbose)
        autoUpdate = findViewById(R.id.auto_update)
        wifiOnly = findViewById(R.id.wifi_only)
        updateVersion = findViewById(R.id.update_version)
        updateStatus = findViewById(R.id.update_status)
        updateProgress = findViewById(R.id.update_progress)
        btnUpdateCheck = findViewById(R.id.btn_update_check)
        btnUpdateInstall = findViewById(R.id.btn_update_install)
        logBox = findViewById(R.id.log_box)
        logBody = findViewById(R.id.log_body)

        findViewById<TextView>(R.id.chip_version).text =
            getString(R.string.chip_core, BuildConfig.CORE_VERSION)

        port.setText(Settings.port(this).toString())
        secret.setText(Settings.secret(this))
        dcIp.setText(Settings.dcIp(this))
        poolSize.setText(Settings.poolSize(this).toString())
        cfWorker.setText(Settings.cfWorker(this))
        cfproxy.isChecked = Settings.cfproxy(this)
        keepAwake.isChecked = Settings.keepAwake(this)
        autostart.isChecked = Settings.autostart(this)
        verbose.isChecked = Settings.verbose(this)
        autoUpdate.isChecked = Settings.autoUpdate(this)
        wifiOnly.isChecked = Settings.updateWifiOnly(this)

        power.setOnClickListener { if (ProxyService.running) stopProxy() else startProxy() }
        tilSecret.setEndIconOnClickListener { secret.setText(Settings.newSecret()) }
        findViewById<View>(R.id.btn_open_tg).setOnClickListener { openInTelegram() }
        findViewById<View>(R.id.btn_copy).setOnClickListener { copyLink() }
        findViewById<View>(R.id.btn_battery).setOnClickListener { requestIgnoreBattery() }
        findViewById<View>(R.id.log_toggle).setOnClickListener {
            val open = logBox.visibility != View.VISIBLE
            logBox.visibility = if (open) View.VISIBLE else View.GONE
            logOpen = open
        }
        findViewById<View>(R.id.btn_log_copy).setOnClickListener { copyLog() }

        // Update-related switches apply immediately.
        autoUpdate.setOnCheckedChangeListener { _, _ -> saveQuiet() }
        wifiOnly.setOnCheckedChangeListener { _, _ -> saveQuiet() }
        btnUpdateCheck.setOnClickListener { checkForUpdates(manual = true) }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        renderUpdate()
        // Auto-check when the app is opened (at most every 6 hours).
        if (Settings.autoUpdate(this) &&
            System.currentTimeMillis() - Settings.lastUpdateCheck(this) > 6 * 3600_000L
        ) checkForUpdates(manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUpdateIntent()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        startPolling()
        handleUpdateIntent()
    }

    override fun onPause() {
        visible = false
        stopPolling()
        stopAnimations()
        shownState = null
        super.onPause()
    }

    // ---- settings form --------------------------------------------------------------

    /** Accepts a bare 32-hex secret as well as a pasted "dd…"/"ee…" proxy secret. */
    private fun normalizeSecret(raw: String): String {
        val t = raw.filter { !it.isWhitespace() }.lowercase()
        return if (t.length > 32 && (t.startsWith("dd") || t.startsWith("ee"))) t.substring(2, 34.coerceAtMost(t.length)) else t
    }

    /**
     * Validates and stores the whole form. Returns false (showing the error) if invalid.
     * If the proxy is running and the connection settings changed, it is restarted so the
     * link given to Telegram always matches what the proxy actually uses.
     */
    private fun saveForm(): Boolean {
        val p = port.text.toString().trim().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            tilPort.error = getString(R.string.invalid_port)
            port.requestFocus()
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return false
        }
        tilPort.error = null
        val s = normalizeSecret(secret.text.toString())
        if (!Regex("[0-9a-f]{32}").matches(s)) {
            tilSecret.error = getString(R.string.invalid_secret)
            secret.requestFocus()
            Toast.makeText(this, R.string.invalid_secret, Toast.LENGTH_SHORT).show()
            return false
        }
        tilSecret.error = null
        if (secret.text.toString() != s) secret.setText(s)
        val pool = poolSize.text.toString().trim().toIntOrNull()
        if (pool == null || pool !in 1..16) {
            tilPool.error = getString(R.string.invalid_pool)
            poolSize.requestFocus()
            Toast.makeText(this, R.string.invalid_pool, Toast.LENGTH_SHORT).show()
            return false
        }
        tilPool.error = null

        val before = Settings.toJson(this)
        Settings.save(
            this, p, s, dcIp.text.toString(), cfWorker.text.toString(), pool,
            cfproxy.isChecked, autostart.isChecked, verbose.isChecked,
            keepAwake.isChecked, autoUpdate.isChecked, wifiOnly.isChecked,
        )
        if (ProxyService.running && Settings.toJson(this) != before) {
            startService(
                Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_RESTART))
            Toast.makeText(this, R.string.restarted_applied, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    /** Saves only if the form is currently valid (used by the instant-apply switches). */
    private fun saveQuiet() {
        saveForm()
    }

    // ---- proxy control --------------------------------------------------------------

    private fun startProxy() {
        if (!saveForm()) return
        ProxyService.lastError = ""
        startingSince = System.currentTimeMillis()
        ContextCompat.startForegroundService(
            this, Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START))
        render(Snapshot(null, null))
    }

    private fun stopProxy() {
        startingSince = 0
        startService(Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
    }

    private fun openInTelegram() {
        if (!saveForm()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Settings.link(this))))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_telegram, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLink() {
        if (!saveForm()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("tg-proxy", Settings.link(this)))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun copyLog() {
        val text = logBody.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, R.string.logs_empty, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("tg-ws-proxy log", text))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        startActivity(
            Intent(
                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    // ---- status rendering -----------------------------------------------------------

    private fun currentState(): UiState {
        val err = ProxyService.lastError
        return when {
            ProxyService.running -> UiState.ON
            err.isNotEmpty() -> UiState.ERROR
            startingSince > 0 && System.currentTimeMillis() - startingSince < 20_000 -> UiState.STARTING
            else -> UiState.OFF
        }
    }

    /** Runs on the UI thread with a snapshot gathered by the poller thread. */
    private fun render(snap: Snapshot) {
        val state = currentState()
        if (state == UiState.ON) startingSince = 0

        when (state) {
            UiState.ON -> {
                statusTitle.setText(R.string.state_on)
                statusHint.text = getString(R.string.state_on_hint, Settings.port(this))
            }
            UiState.STARTING -> {
                statusTitle.setText(R.string.state_starting)
                statusHint.text = ""
            }
            UiState.ERROR -> {
                statusTitle.setText(R.string.state_error)
                statusHint.text = ProxyService.lastError
            }
            UiState.OFF -> {
                statusTitle.setText(R.string.state_off)
                statusHint.setText(R.string.state_off_hint)
            }
        }
        if (state != shownState) {
            shownState = state
            applyStateStyle(state)
        }

        renderTraffic(snap.stats)
        snap.log?.let { logBody.text = it }
    }

    private fun renderTraffic(st: JSONObject?) {
        if (st == null) {
            if (lastT != 0L) spark.clear()
            lastT = 0L
            speedDown.setText(R.string.speed_idle)
            speedUp.setText(R.string.speed_idle)
            diag.setText(R.string.diag_off)
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
                speedDown.text = fmtRate(dRate)
                speedUp.text = fmtRate(uRate)
                spark.push(dRate.toFloat())
            }
        }
        lastT = now
        lastDown = down
        lastUp = up

        val total = st.optLong("pool_total")
        val pool = if (total > 0) "${st.optLong("pool_hits") * 100 / total}%" else "—"
        diag.text = getString(
            R.string.diag_line,
            st.optInt("active"), st.optInt("ws"), st.optInt("cf"), st.optInt("tcp"), pool)
    }

    private fun fmtRate(bps: Double): String = when {
        bps >= 1_048_576 -> String.format("%.1f MB/s", bps / 1_048_576)
        bps >= 1024 -> String.format("%.0f KB/s", bps / 1024)
        else -> String.format("%.0f B/s", bps)
    }

    private fun applyStateStyle(state: UiState) {
        val active = state == UiState.ON || state == UiState.STARTING
        ring.setBackgroundResource(
            when (state) {
                UiState.ON, UiState.STARTING -> R.drawable.bg_power_ring_on
                UiState.ERROR -> R.drawable.bg_power_ring_err
                UiState.OFF -> R.drawable.bg_power_ring_off
            })
        halo.setBackgroundResource(
            if (state == UiState.ERROR) R.drawable.bg_halo_err else R.drawable.bg_halo_on)
        val accent = ContextCompat.getColor(
            this,
            when (state) {
                UiState.ON, UiState.STARTING -> R.color.neon_cyan
                UiState.ERROR -> R.color.neon_pink
                UiState.OFF -> R.color.text_secondary
            })
        statusTitle.setTextColor(accent)
        powerIcon.setColorFilter(accent)

        stopAnimations()
        if (state == UiState.ON || state == UiState.STARTING || state == UiState.ERROR) {
            halo.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            pulse = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = when (state) { UiState.ON -> 2400; UiState.STARTING -> 1100; else -> 1600 }
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val s = 0.72f + 0.28f * t
                    halo.scaleX = s
                    halo.scaleY = s
                    halo.alpha = 1f - t
                }
                start()
            }
        }
        if (active) {
            ring.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            spin = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = if (state == UiState.ON) 7000 else 1800
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { ring.rotation = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun stopAnimations() {
        spin?.cancel(); spin = null
        pulse?.cancel(); pulse = null
        ring.rotation = 0f
        ring.setLayerType(View.LAYER_TYPE_NONE, null)
        halo.alpha = 0f
        halo.scaleX = 1f
        halo.scaleY = 1f
        halo.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    // ---- updates -------------------------------------------------------------------

    private fun currentUpdate(): Updater.Info? =
        Settings.updateInfo(this)?.let { Updater.Info.fromJson(it) }
            ?.takeIf { Updater.isNewer(it.version, BuildConfig.VERSION_NAME) }

    private fun renderUpdate() {
        updateVersion.text = getString(
            R.string.version_line, BuildConfig.VERSION_NAME, BuildConfig.CORE_VERSION)
        val info = currentUpdate()
        val last = Settings.lastUpdateCheck(this)
        val checked = if (last == 0L) getString(R.string.update_checked_never)
        else getString(
            R.string.update_checked_at,
            DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
        if (info == null) {
            updateStatus.text = if (last == 0L) checked
            else getString(R.string.update_up_to_date) + " · " + checked
            btnUpdateInstall.visibility = View.GONE
        } else {
            updateStatus.text = getString(R.string.update_available, info.version)
            btnUpdateInstall.visibility = View.VISIBLE
            btnUpdateInstall.text = getString(
                if (Updater.isDownloaded(this, info)) R.string.update_install
                else R.string.update_download_install, info.version)
            btnUpdateInstall.setOnClickListener { downloadAndInstall(info) }
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (updateBusy) return
        updateBusy = true
        if (manual) {
            btnUpdateCheck.isEnabled = false
            updateStatus.setText(R.string.update_checking)
        }
        bg.execute {
            var failed = false
            try {
                val info = Updater.check(this)
                Settings.setLastUpdateCheck(this, System.currentTimeMillis())
                Settings.setUpdateInfo(this, info?.toJson())
            } catch (_: Exception) {
                failed = true
            }
            runOnUiThread {
                updateBusy = false
                btnUpdateCheck.isEnabled = true
                renderUpdate()
                if (failed && manual) {
                    Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                }
                // Automatic mode: fetch right away when the network allows it.
                val info = currentUpdate()
                if (!failed && info != null && !manual && Settings.autoUpdate(this) &&
                    (!Settings.updateWifiOnly(this) || Updater.isUnmetered(this))
                ) downloadAndInstall(info, prepareOnly = true)
            }
        }
    }

    /** Downloads (if needed) and then installs; with [prepareOnly] stops after the download. */
    private fun downloadAndInstall(info: Updater.Info, prepareOnly: Boolean = false) {
        if (updateBusy) return
        if (!prepareOnly && !Updater.canInstall(this)) {
            Toast.makeText(this, R.string.update_allow_install, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        updateBusy = true
        btnUpdateInstall.isEnabled = false
        btnUpdateCheck.isEnabled = false
        updateProgress.progress = 0
        updateProgress.visibility = View.VISIBLE
        bg.execute {
            var error: String? = null
            var apk: java.io.File? = null
            try {
                apk = Updater.download(this, info) { p ->
                    runOnUiThread {
                        updateProgress.progress = p
                        updateStatus.text = getString(R.string.update_downloading, p)
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            runOnUiThread {
                updateBusy = false
                updateProgress.visibility = View.GONE
                btnUpdateInstall.isEnabled = true
                btnUpdateCheck.isEnabled = true
                renderUpdate()
                if (error != null) {
                    Toast.makeText(
                        this, getString(R.string.update_failed, error), Toast.LENGTH_LONG).show()
                } else if (!prepareOnly && apk != null) {
                    installNow(apk)
                }
            }
        }
    }

    private fun installNow(apk: java.io.File) {
        try {
            Updater.install(this, apk)
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.update_failed, e.message ?: e.toString()),
                Toast.LENGTH_LONG).show()
        }
    }

    /** Opened from the "update ready" notification: go straight to the install prompt. */
    private fun handleUpdateIntent() {
        if (!intent.hasExtra(ProxyService.EXTRA_INSTALL_UPDATE)) return
        val wantInstall = intent.getBooleanExtra(ProxyService.EXTRA_INSTALL_UPDATE, false)
        intent.removeExtra(ProxyService.EXTRA_INSTALL_UPDATE)
        val info = currentUpdate() ?: return
        renderUpdate()
        if (wantInstall && Updater.isDownloaded(this, info)) {
            downloadAndInstall(info)
        }
    }

    override fun onDestroy() {
        bg.shutdown()
        super.onDestroy()
    }
}
