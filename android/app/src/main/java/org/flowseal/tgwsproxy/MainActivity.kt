package org.flowseal.tgwsproxy

import android.Manifest
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
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.chaquo.python.Python

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var port: EditText
    private lateinit var secret: EditText
    private lateinit var dcIp: EditText
    private lateinit var cfWorker: EditText
    private lateinit var cfproxy: CheckBox
    private lateinit var autostart: CheckBox
    private lateinit var verbose: CheckBox
    private lateinit var toggle: Button
    private lateinit var logView: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        port = findViewById(R.id.port)
        secret = findViewById(R.id.secret)
        dcIp = findViewById(R.id.dc_ip)
        cfWorker = findViewById(R.id.cf_worker)
        cfproxy = findViewById(R.id.cfproxy)
        autostart = findViewById(R.id.autostart)
        verbose = findViewById(R.id.verbose)
        toggle = findViewById(R.id.toggle)
        logView = findViewById(R.id.log)

        port.setText(Settings.port(this).toString())
        secret.setText(Settings.secret(this))
        dcIp.setText(Settings.dcIp(this))
        cfWorker.setText(Settings.cfWorker(this))
        cfproxy.isChecked = Settings.cfproxy(this)
        autostart.isChecked = Settings.autostart(this)
        verbose.isChecked = Settings.verbose(this)

        toggle.setOnClickListener { if (ProxyService.running) stopProxy() else startProxy() }
        findViewById<Button>(R.id.regen).setOnClickListener {
            secret.setText(Settings.newSecret())
        }
        findViewById<Button>(R.id.open_tg).setOnClickListener { openInTelegram() }
        findViewById<Button>(R.id.copy_link).setOnClickListener { copyLink() }
        findViewById<Button>(R.id.battery).setOnClickListener { requestIgnoreBattery() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    /** Validates and stores the form. Returns false (with a toast) if invalid. */
    private fun saveForm(): Boolean {
        val p = port.text.toString().toIntOrNull()
        if (p == null || p !in 1024..65535) {
            Toast.makeText(this, R.string.invalid_port, Toast.LENGTH_SHORT).show()
            return false
        }
        val s = secret.text.toString().trim().lowercase()
        if (!Regex("[0-9a-f]{32}").matches(s)) {
            secret.error = getString(R.string.secret)
            return false
        }
        Settings.save(
            this, p, s, dcIp.text.toString(), cfWorker.text.toString(),
            cfproxy.isChecked, autostart.isChecked, verbose.isChecked,
        )
        return true
    }

    private fun startProxy() {
        if (!saveForm()) return
        ProxyService.lastError = ""
        ContextCompat.startForegroundService(
            this, Intent(this, ProxyService::class.java).setAction(ProxyService.ACTION_START))
    }

    private fun stopProxy() {
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

    private fun refresh() {
        val running = ProxyService.running
        status.text = when {
            running -> getString(R.string.status_running, Settings.port(this))
            ProxyService.lastError.isNotEmpty() -> ProxyService.lastError
            else -> getString(R.string.status_stopped)
        }
        toggle.setText(if (running) R.string.stop else R.string.start)
        if (Python.isStarted()) {
            try {
                logView.text = Python.getInstance().getModule("android_entry")
                    .callAttr("get_logs").toString().lines().takeLast(60).joinToString("\n")
            } catch (_: Throwable) {
            }
        }
    }
}
