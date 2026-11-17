package org.flowseal.tgwsproxy.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.WarpManager

/** Settings card: automatic WARP on mobile data. Applies immediately, no "Save" needed. */
@Composable
fun WarpCard() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(WarpManager.enabled(ctx)) }
    var wifi by remember { mutableStateOf(WarpManager.wifiEnabled(ctx)) }
    var hasConfig by remember { mutableStateOf(WarpManager.hasConfig(ctx)) }
    var pasteError by remember { mutableStateOf<String?>(null) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        WarpManager.refresh(ctx)
    }

    // System file picker: the user selects the downloaded .conf file.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = readFile(ctx, uri)
            pasteError = when {
                text == null -> "cannot read the file"
                text.isBlank() -> "the file is empty"
                else -> WarpManager.saveConfig(ctx, text)
            }
            hasConfig = WarpManager.hasConfig(ctx)
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.sec_warp))
        GlassSwitchRow(
            stringResource(R.string.warp_auto), enabled,
            { v ->
                enabled = v
                WarpManager.setEnabled(ctx, v)
                if (v) WarpManager.vpnPermissionIntent(ctx)?.let { consent.launch(it) }
            },
            stringResource(R.string.warp_auto_hint),
        )
        GlassSwitchRow(
            stringResource(R.string.warp_wifi), wifi,
            { v ->
                wifi = v
                WarpManager.setWifiEnabled(ctx, v)
                if (v) WarpManager.vpnPermissionIntent(ctx)?.let { consent.launch(it) }
            },
            stringResource(R.string.warp_wifi_hint),
        )
        GapH(8.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(
                stringResource(R.string.warp_pick),
                { picker.launch(arrayOf("*/*")) },
                Modifier.weight(1f), primary = !hasConfig,
            )
            if (hasConfig) {
                GlassButton(
                    stringResource(R.string.warp_remove),
                    { WarpManager.clearConfig(ctx); hasConfig = false; pasteError = null },
                    Modifier.weight(1f),
                )
            }
        }
        val statusText = when (WarpManager.status) {
            WarpManager.Status.OFF -> stringResource(R.string.warp_st_off)
            WarpManager.Status.NO_CONFIG -> stringResource(R.string.warp_st_noconfig)
            WarpManager.Status.NEED_PERMISSION -> stringResource(R.string.warp_st_permission)
            WarpManager.Status.WAITING -> stringResource(R.string.warp_st_waiting)
            WarpManager.Status.PAUSED_VPN -> stringResource(R.string.warp_st_paused)
            WarpManager.Status.CONNECTING -> stringResource(R.string.warp_st_connecting)
            WarpManager.Status.UP -> stringResource(R.string.warp_st_up)
            WarpManager.Status.ERROR -> stringResource(R.string.warp_st_error, WarpManager.lastError ?: "")
        }
        Hint(statusText, Modifier.padding(top = 8.dp))
        pasteError?.let { Hint(stringResource(R.string.warp_bad_config, it), Modifier.padding(top = 4.dp)) }
        WarpManager.lastNote?.let { Hint(stringResource(R.string.warp_note, it), Modifier.padding(top = 4.dp)) }
    }
}

/** Reads a small text file chosen in the system picker (configs are well under 64 KB). */
private fun readFile(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        val bytes = input.readBytes()
        if (bytes.size > 64 * 1024) null else String(bytes, Charsets.UTF_8)
    }
} catch (_: Exception) {
    null
}
