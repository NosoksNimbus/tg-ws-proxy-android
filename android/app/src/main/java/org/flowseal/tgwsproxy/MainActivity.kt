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
    private lateinit var header: View
    private lateinit var power: View
    private lateinit var powerBg: View
    private lateinit var powerIcon: ImageView
    private lateinit var halo: View
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
    private var pulse: ValueAnimator? = null
    private var startingSince = 0L
    private var shownState: UiState? = null
    @Volatile private var updateBusy = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        header = findViewById(R.id.header)
        power = findViewById(R.id.power)
        powerBg = findViewById(R.id.power_bg)
        powerIcon = findViewById(R.id.power_icon)
        halo = findViewById(R.id.halo)
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
            logBox.visibility = if (logBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
        ui.post(tick)
        handleUpdateIntent()
    }

    override fun onPause() {
        visible = false
        ui.removeCallbacks(tick)
        pulse?.cancel()
        pulse = null
        shownState = null
        super.onPause()
    }

    // ---- settings form --------------------------------------------------------------

    /** Validates and stores the whole form. Returns false (showing the error) if invalid. */
    private fun saveForm(): Boolean {
        val p = port.text.toString().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            tilPort.error = getString(R.string.invalid_port)
            return false
        }
        tilPort.error = null
        val s = secret.text.toString().trim().lowercase()
        if (!Regex("[0-9a-f]{32}").matches(s)) {
            tilSecret.error = getString(R.string.invalid_secret)
            return false
        }
        tilSecret.error = null
        Settings.save(
            this, p, s, dcIp.text.toString(), cfWorker.text.toString(),
            cfproxy.isChecked, autostart.isChecked, verbose.isChecked,
            keepAwake.isChecked, autoUpdate.isChecked, wifiOnly.isChecked,
        )
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
        refresh()
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

    private fun refresh() {
        val running = ProxyService.running
        val err = ProxyService.lastError
        val state = when {
            running -> UiState.ON
            err.isNotEmpty() -> UiState.ERROR
            System.currentTimeMillis() - startingSince < 20_000 && startingSince > 0 -> UiState.STARTING
            else -> UiState.OFF
        }
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
                statusHint.text = err
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
        refreshLog()
    }

    private fun applyStateStyle(state: UiState) {
        val on = state == UiState.ON
        header.setBackgroundResource(
            when (state) {
                UiState.ON, UiState.STARTING -> R.drawable.bg_header_on
                UiState.ERROR -> R.drawable.bg_header_err
                UiState.OFF -> R.drawable.bg_header_off
            })
        window.statusBarColor = ContextCompat.getColor(
            this,
            when (state) {
                UiState.ON, UiState.STARTING -> R.color.hdr_on_start
                UiState.ERROR -> R.color.hdr_err_start
                UiState.OFF -> R.color.hdr_off_start
            })
        powerBg.setBackgroundResource(if (on) R.drawable.bg_power_on else R.drawable.bg_power_off)
        powerIcon.setColorFilter(
            if (on) ContextCompat.getColor(this, R.color.brand_dark) else 0xFFFFFFFF.toInt())

        pulse?.cancel()
        pulse = null
        halo.alpha = 0f
        halo.scaleX = 1f
        halo.scaleY = 1f
        if (on || state == UiState.STARTING) {
            pulse = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (on) 2200 else 1000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val s = 0.78f + 0.22f * t
                    halo.scaleX = s
                    halo.scaleY = s
                    halo.alpha = 1f - t
                }
                start()
            }
        }
    }

    private fun refreshLog() {
        if (logBox.visibility != View.VISIBLE || !Python.isStarted()) return
        try {
            logBody.text = Python.getInstance().getModule("android_entry")
                .callAttr("get_logs").toString().lines().takeLast(80).joinToString("\n")
        } catch (_: Throwable) {
        }
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
