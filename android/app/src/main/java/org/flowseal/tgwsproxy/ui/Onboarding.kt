package org.flowseal.tgwsproxy.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.flowseal.tgwsproxy.R

private class Step(val icon: Int, val title: Int, val text: Int, val action: Int? = null)

/** First-launch walkthrough: how to start the proxy, add it to Telegram and fix mobile data. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Onboarding(onBattery: () -> Unit, onOpenWarpSite: () -> Unit, onDone: () -> Unit) {
    val steps = listOf(
        Step(R.drawable.ic_nav_home, R.string.ob_title_1, R.string.ob_text_1),
        Step(R.drawable.ic_power, R.string.ob_title_2, R.string.ob_text_2),
        Step(R.drawable.ic_arrow_up, R.string.ob_title_3, R.string.ob_text_3),
        Step(R.drawable.ic_nav_settings, R.string.ob_title_4, R.string.ob_text_4, R.string.ob_battery),
        Step(R.drawable.ic_nav_network, R.string.ob_title_5, R.string.ob_text_5, R.string.ob_open_warp_site),
    )
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == steps.size - 1

    Box(Modifier.fillMaxSize().background(G.bg.copy(alpha = 0.97f)).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) {
                    Text(
                        stringResource(R.string.ob_skip), color = G.textDim, fontSize = 15.sp,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDone)
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { p ->
                val st = steps[p]
                Column(
                    Modifier.fillMaxSize().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier.size(104.dp).clip(CircleShape)
                            .background(G.tint.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(st.icon), null, tint = G.cyan, modifier = Modifier.size(48.dp))
                    }
                    GapH(26.dp)
                    Text(stringResource(st.title), color = G.text, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    GapH(14.dp)
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(stringResource(st.text), color = G.textDim, fontSize = 16.sp,
                            lineHeight = 23.sp, textAlign = TextAlign.Start)
                    }
                    if (st.action != null) {
                        GapH(14.dp)
                        GlassButton(
                            stringResource(st.action),
                            { if (p == 3) onBattery() else onOpenWarpSite() },
                            Modifier.fillMaxWidth())
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                steps.indices.forEach { i ->
                    val on = i == pager.currentPage
                    Box(
                        Modifier.padding(horizontal = 4.dp)
                            .size(if (on) 22.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(if (on) G.cyan else G.tint.copy(alpha = 0.30f)))
                }
            }
            GlassButton(
                stringResource(if (last) R.string.ob_done else R.string.ob_next),
                { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                Modifier.fillMaxWidth(), primary = true)
        }
    }
}
