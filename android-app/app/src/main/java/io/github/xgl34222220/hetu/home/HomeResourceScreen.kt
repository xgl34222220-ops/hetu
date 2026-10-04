package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 资源占用 (full page, pushed from the 资源占用 card).
 *
 * Running: two trend cards plus a detail list; missed samples break the curve and the CPU card
 * says so. Not running: both charts show “等待连续运行采样”, live fields show “—”, static fields
 * (core version, mode, config) are still filled in.
 */
@Composable
internal fun HomeResourceScreen(state: HomeUiState, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val running = state.status is HomeStatus.Running || state.status is HomeStatus.PendingRestart
    val res = state.resource
    Column(modifier.fillMaxSize().background(c.bg)) {
        HomeTopBar(title = "资源占用", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            TrendCard(
                title = "CPU",
                value = res.cpuPercent?.takeIf { running && !it.isNaN() }?.let(HomeFormat::percent),
                history = res.cpuHistory.takeIf { running }.orEmpty(),
                color = c.accent,
                max = maxOf(8f, (res.cpuHistory.filterNotNull().maxOrNull() ?: 0f) * 1.6f),
                footnote = if (res.cpuHistory.any { it == null }) "曲线断开处为缺测" else null,
            )
            TrendCard(
                title = "内存",
                value = res.memoryBytes?.takeIf { running }?.let(HomeFormat::bytes),
                history = res.memoryHistoryMb.takeIf { running }.orEmpty(),
                color = c.good,
                max = maxOf(16f, (res.memoryHistoryMb.filterNotNull().maxOrNull() ?: 0f) * 1.25f),
                footnote = null,
            )
            HomeCard(Modifier.fillMaxWidth()) {
                val rows = listOf(
                    "运行时长" to state.status.uptimeSeconds?.let(HomeFormat::uptime),
                    "进程 PID" to res.pid?.takeIf { running && it > 0 }?.toString(),
                    "核心版本" to res.coreVersion.ifBlank { null },
                    "CPU 核心分配" to res.cpuAffinity?.takeIf { running },
                    "当前 CPU" to res.currentCpu?.takeIf { running }?.let { "CPU $it" },
                    "模式" to "${state.runMode} · ${state.proxyMode.id}",
                    "启动配置" to state.config.ifBlank { null },
                    "活动连接" to res.connections?.takeIf { running }?.toString(),
                )
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HomeDivider()
                    HomeKeyValueRow(label, value ?: HomeFormat.Dash, unknown = value == null)
                }
            }
        }
    }
}

@Composable
private fun TrendCard(title: String, value: String?, history: List<Float?>, color: Color, max: Float, footnote: String?) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(HomeDims.cardPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = c.t2, style = HomeType.section)
                Text("本次查看", color = c.t3, style = HomeType.caption)
            }
            Text(value ?: HomeFormat.Dash, Modifier.padding(top = 2.dp, bottom = 12.dp), color = if (value == null) c.t3 else c.t1, style = HomeType.metricLarge)
            if (value != null && history.count { it != null } >= 2) {
                HomeSparkline(history, color, max)
                if (footnote != null) Text(footnote, Modifier.fillMaxWidth().padding(top = 8.dp), color = c.t3, style = HomeType.caption, textAlign = TextAlign.Center)
            } else {
                Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                    Text("等待连续运行采样", color = c.t3, style = HomeType.rowSub)
                }
            }
        }
    }
}
