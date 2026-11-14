package org.flowseal.tgwsproxy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.flowseal.tgwsproxy.ui.G
import org.flowseal.tgwsproxy.ui.GlassBackground
import org.flowseal.tgwsproxy.ui.GlassNav
import org.flowseal.tgwsproxy.ui.HomeScreen
import org.flowseal.tgwsproxy.ui.MoreScreen
import org.flowseal.tgwsproxy.ui.NavItem
import org.flowseal.tgwsproxy.ui.NetworkScreen
import org.flowseal.tgwsproxy.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    companion object {
        /** Read by [InstallReceiver] to decide whether to post a notification. */
        @Volatile var visible = false
        const val EXTRA_AUTO_START = "auto_start"
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        vm.autoCheckOnOpen()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = G.cyan, background = G.bg, surface = G.bg, onSurface = G.text)
            ) { App() }
        }
    }

    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(tab) { vm.logVisible = tab == 3 }

        val export = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null && vm.saveForm()) vm.exportTo(uri)
        }
        val import = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.importFrom(uri)
        }

        val items = listOf(
            NavItem(stringResource(R.string.tab_home), R.drawable.ic_nav_home),
            NavItem(stringResource(R.string.tab_network), R.drawable.ic_nav_network),
            NavItem(stringResource(R.string.tab_settings), R.drawable.ic_nav_settings),
            NavItem(stringResource(R.string.tab_more), R.drawable.ic_nav_more),
        )

        Box(Modifier.fillMaxSize()) {
            GlassBackground()
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                when (tab) {
                    0 -> HomeScreen(vm)
                    1 -> NetworkScreen(vm)
                    2 -> SettingsScreen(vm)
                    else -> MoreScreen(
                        vm,
                        onExport = { export.launch("tg-ws-proxy-settings.json") },
                        onImport = { import.launch(arrayOf("*/*")) })
                }
            }
            GlassNav(
                items, tab, { tab = it },
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        vm.setActive(true)
        handleIntent()
    }

    override fun onPause() {
        visible = false
        vm.setActive(false)
        super.onPause()
    }

    private fun handleIntent() {
        if (intent.hasExtra(ProxyService.EXTRA_INSTALL_UPDATE)) {
            val wantInstall = intent.getBooleanExtra(ProxyService.EXTRA_INSTALL_UPDATE, false)
            intent.removeExtra(ProxyService.EXTRA_INSTALL_UPDATE)
            if (wantInstall) vm.installPending() else vm.renderUpdate()
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.removeExtra(EXTRA_AUTO_START)
            if (!ProxyService.running) vm.start()
        }
    }
}
