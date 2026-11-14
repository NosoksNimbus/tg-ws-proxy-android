package org.flowseal.tgwsproxy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.flowseal.tgwsproxy.BuildConfig
import org.flowseal.tgwsproxy.MainViewModel
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.RunState

@Composable
private fun TabColumn(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        content()
        GapH(120.dp)
    }
}

// ================================= Home =====================================================

@Composable
fun HomeScreen(vm: MainViewModel) {
    TabColumn {
        ScreenTitle(stringResource(R.string.app_name))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerOrb(vm.runState, onClick = { vm.toggle() })
            GapH(8.dp)
            Text(
                stringResource(when (vm.runState) {
                    RunState.ON -> R.string.state_on
                    RunState.STARTING -> R.string.state_starting
                    RunState.ERROR -> R.string.state_error
                    RunState.OFF -> R.string.state_off
                }),
                color = G.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Hint(vm.statusHint, Modifier.padding(top = 4.dp))
            GapH(6.dp)
            Text(
                stringResource(R.string.chip_core, BuildConfig.CORE_VERSION),
                color = G.cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        GapH(20.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Rate(R.drawable.ic_arrow_down, vm.downRate, G.cyan)
                Rate(R.drawable.ic_arrow_up, vm.upRate, G.pink)
            }
            GapH(10.dp)
            Spark(vm.history, Modifier.fillMaxWidth().height(64.dp))
            GapH(8.dp)
            Hint(vm.diagLine)
        }
        GapH(16.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(
                stringResource(R.string.open_in_telegram), { vm.openInTelegram() },
                Modifier.weight(1f), primary = true)
            GlassButton(stringResource(R.string.copy_link), { vm.copyLink() }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Rate(icon: Int, value: String?, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            value ?: stringResource(R.string.speed_idle),
            color = G.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp))
    }
}

// ================================= Network ==================================================

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val f = vm.form
    TabColumn {
        ScreenTitle(stringResource(R.string.tab_network))

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_check))
            Hint(stringResource(R.string.check_hint))
            GapH(12.dp)
            GlassButton(
                stringResource(if (vm.checkRunning) R.string.check_running else R.string.check_run),
                { vm.runCheck() }, Modifier.fillMaxWidth(), primary = true, enabled = !vm.checkRunning)
            vm.checkLines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (l.ok) G.mint else G.danger)
                    GapW(10.dp)
                    Text(l.name, color = G.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(l.detail, color = G.textDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            vm.checkVerdict?.let {
                Text(it, color = G.cyan, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            }
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_traffic))
            val t = vm.totals
            TrafficRow(stringResource(R.string.traffic_today), stringResource(R.string.traffic_mobile), t.todayMobile)
            TrafficRow("", stringResource(R.string.traffic_wifi), t.todayWifi)
            TrafficRow(stringResource(R.string.traffic_month), stringResource(R.string.traffic_mobile), t.monthMobile)
            TrafficRow("", stringResource(R.string.traffic_wifi), t.monthWifi)
            GapH(10.dp)
            GlassButton(stringResource(R.string.traffic_reset), { vm.resetTraffic() }, Modifier.fillMaxWidth())
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_profiles))
            GlassField(
                f.poolMobile, { f.poolMobile = it; f.poolMobileError = null },
                stringResource(R.string.pool_mobile), number = true, error = f.poolMobileError)
            GlassSwitchRow(stringResource(R.string.profile_enable), f.profiles, { f.profiles = it })
            if (f.profiles) {
                GlassField(
                    f.poolWifi, { f.poolWifi = it; f.poolWifiError = null },
                    stringResource(R.string.pool_wifi), number = true, error = f.poolWifiError)
            }
            Hint(stringResource(R.string.pool_size_hint), Modifier.padding(top = 4.dp))
            Hint(stringResource(R.string.profile_hint), Modifier.padding(top = 4.dp))
            GapH(10.dp)
            GlassButton(stringResource(R.string.save), { vm.saveForm() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TrafficRow(period: String, kind: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(period, color = G.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(kind, color = G.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = G.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

// ================================= Settings =================================================

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val f = vm.form
    TabColumn {
        ScreenTitle(stringResource(R.string.tab_settings))

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_connection))
            GlassField(f.port, { f.port = it; f.portError = null },
                stringResource(R.string.port), number = true, error = f.portError)
            GlassField(f.secret, { f.secret = it; f.secretError = null },
                stringResource(R.string.secret), error = f.secretError, mono = true)
            GlassButton(stringResource(R.string.secret_new), { vm.newSecret() },
                Modifier.padding(top = 6.dp))
            GapH(8.dp)
            GlassField(f.dcIp, { f.dcIp = it }, stringResource(R.string.dc_ip),
                singleLine = false, minLines = 3, mono = true)
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_fallback))
            GlassSwitchRow(stringResource(R.string.cfproxy), f.cfproxy, { f.cfproxy = it })
            GlassField(f.cfWorker, { f.cfWorker = it }, stringResource(R.string.cf_worker),
                singleLine = false, minLines = 2, mono = true)
            Hint(stringResource(R.string.cf_worker_hint), Modifier.padding(top = 4.dp))
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_system))
            GlassSwitchRow(stringResource(R.string.keep_awake), f.keepAwake, { f.keepAwake = it },
                stringResource(R.string.keep_awake_hint))
            GlassSwitchRow(stringResource(R.string.autostart), f.autostart, { f.autostart = it })
            GlassSwitchRow(stringResource(R.string.verbose), f.verbose, { f.verbose = it })
            GapH(8.dp)
            GlassButton(stringResource(R.string.battery), { vm.requestIgnoreBattery() },
                Modifier.fillMaxWidth())
        }

        GapH(16.dp)
        GlassButton(stringResource(R.string.save), { vm.saveForm() },
            Modifier.fillMaxWidth(), primary = true)
    }
}

// ================================= More =====================================================

@Composable
fun MoreScreen(vm: MainViewModel, onExport: () -> Unit, onImport: () -> Unit) {
    val f = vm.form
    TabColumn {
        ScreenTitle(stringResource(R.string.tab_more))

        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_updates))
            Text(vm.updVersionLine, color = G.text, fontSize = 14.sp)
            Text(vm.updStatus, color = G.textDim, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            GapH(10.dp)
            val info = vm.updInfo
            if (info != null && vm.updInstallLabel.isNotEmpty()) {
                GlassButton(vm.updInstallLabel, { vm.downloadAndInstall(info) },
                    Modifier.fillMaxWidth(), primary = true, enabled = !vm.updBusy)
                GapH(8.dp)
            }
            GlassButton(stringResource(R.string.update_check), { vm.checkForUpdates(manual = true) },
                Modifier.fillMaxWidth(), enabled = !vm.updBusy)
            GlassSwitchRow(stringResource(R.string.update_auto), f.autoUpdate,
                { f.autoUpdate = it; vm.saveQuiet() })
            GlassSwitchRow(stringResource(R.string.update_wifi_only), f.wifiOnly,
                { f.wifiOnly = it; vm.saveQuiet() })
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.sec_backup))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(stringResource(R.string.export_settings), onExport, Modifier.weight(1f))
                GlassButton(stringResource(R.string.import_settings), onImport, Modifier.weight(1f))
            }
            Hint(stringResource(R.string.backup_hint), Modifier.padding(top = 8.dp))
        }

        GapH(16.dp)
        GlassCard(Modifier.fillMaxWidth()) {
            SectionTitle(stringResource(R.string.logs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(stringResource(R.string.logs_copy), { vm.copyLog() }, Modifier.weight(1f))
                GlassButton(stringResource(R.string.logs_share), { vm.shareLog() }, Modifier.weight(1f))
            }
            GapH(10.dp)
            Text(
                vm.logText.ifEmpty { stringResource(R.string.logs_empty) },
                color = G.textDim, fontSize = 11.sp, lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}
