package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRollingText
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.HomeVerticalDivider
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 网络测试 › 多平台连通性. Runs once on open; 重新测试 in the bar starts a new round and cancels the
 * old one. Results arrive one by one on the main thread (one small map write each); all network
 * work stays on IO.
 */
@Composable
internal fun ConnectivityScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    val targets = PlatformCatalog.targets
    val outcomes = remember { mutableStateMapOf<String, PlatformOutcome>() }
    var running by remember { mutableStateOf(false) }
    var viaCore by remember { mutableStateOf<Boolean?>(null) }
    var finishedAt by remember { mutableLongStateOf(0L) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun start() {
        job?.cancel()
        targets.forEach { outcomes[it.id] = PlatformOutcome.Waiting }
        running = true
        val round = scope.launch {
            try {
                val proxy = withContext(Dispatchers.IO) { PlatformRoute.proxy(context) }
                viaCore = proxy != null
                PlatformProbe.run(targets, proxy).collect { outcomes[it.target.id] = it.outcome }
                finishedAt = System.currentTimeMillis()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "网络测试失败")
            }
        }
        job = round
        round.invokeOnCompletion { if (job === round) running = false }
    }

    LaunchedEffect(Unit) { start() }

    val summary = PlatformProbe.summarize(targets.map { outcomes[it.id] ?: PlatformOutcome.Waiting })

    HxPage(
        title = ht("网络测试"),
        subtitle = ht("多平台连通性"),
        onBack = onBack,
        largeTitle = false,
        actions = { HomeIconButton(HomeIcons.RefreshCw, "重新测试", ::start, enabled = !running, spinning = running) },
    ) {
        item(key = "summary") {
            SettingsSection {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ConnectivityMetric("可用", summary.reachable, c.good, Modifier.weight(1f))
                        HomeVerticalDivider(Modifier.height(34.dp))
                        ConnectivityMetric("受限", summary.restricted, c.warn, Modifier.weight(1f))
                        HomeVerticalDivider(Modifier.height(34.dp))
                        ConnectivityMetric("失败", summary.failed, c.bad, Modifier.weight(1f))
                    }
                }
                val route = viaCore
                if (route != null) {
                    HxBanner(
                        if (route) ht("经代理核心的策略监听测试，结果与当前分流和节点一致")
                        else ht("代理未运行：以下为不经代理的直连结果"),
                        tone = if (route) HxTone.Neutral else HxTone.Warn,
                        modifier = Modifier.padding(top = HomeDims.gap),
                    )
                }
            }
        }
        for (group in PlatformGroup.entries) {
            val members = targets.filter { it.group == group }
            item(key = "group-${group.name}") {
                SettingsSection {
                    SettingsGroup(title = ht(group.title)) {
                        members.forEachIndexed { index, target ->
                            if (index > 0) SettingsDivider()
                            PlatformRow(target, outcomes[target.id] ?: PlatformOutcome.Waiting)
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                ht("耗时为建立连接、TLS 握手到收到响应头的时间；每个平台单独建连，最多同时 6 个，单项超时 6 秒。403/451 表示服务可达但拒绝访问，多为地区限制。") +
                    if (finishedAt > 0L && summary.done) "\n" + ht("最近完成：") + HxFormat.ago(finishedAt) else "",
                Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap),
                color = c.t2,
                style = HomeType.note,
            )
        }
    }
}

@Composable
private fun ConnectivityMetric(label: String, value: Int, color: Color, modifier: Modifier) {
    val c = LocalHomeColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ht(label), color = c.t2, style = HomeType.note.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        HomeRollingText(value.toString(), color, HomeType.metric.copy(fontSize = HomeType.sheetTitle.fontSize), alignment = Alignment.Center)
    }
}

@Composable
private fun PlatformRow(target: PlatformTarget, outcome: PlatformOutcome) {
    val c = LocalHomeColors.current
    val (icon, tint) = when (outcome) {
        is PlatformOutcome.Reachable -> HomeIcons.CircleCheck to c.good
        is PlatformOutcome.Restricted -> HomeIcons.TriangleAlert to c.warn
        is PlatformOutcome.Failed -> HomeIcons.CircleAlert to c.bad
        else -> ToolsFeatureIcons.CircleDashed to c.t3
    }
    val detail = when (outcome) {
        is PlatformOutcome.Reachable -> "${target.host} · HTTP ${outcome.code}"
        is PlatformOutcome.Restricted -> "${target.host} · HTTP ${outcome.code} · " + if (outcome.code == 429) ht("请求过多") else ht("拒绝访问，可能地区限制")
        is PlatformOutcome.Failed -> "${target.host} · ${outcome.reason}"
        PlatformOutcome.Running -> "${target.host} · " + ht("测试中")
        PlatformOutcome.Waiting -> target.host
    }
    SettingsRow(target.name, subtitle = detail, icon = icon, iconTint = tint, compact = true) {
        when (outcome) {
            PlatformOutcome.Running -> HxSpinner(18.dp)
            is PlatformOutcome.Reachable -> LatencyText(outcome.latencyMs, if (outcome.latencyMs <= 800L) c.good else c.warn)
            is PlatformOutcome.Restricted -> LatencyText(outcome.latencyMs, c.warn)
            is PlatformOutcome.Failed -> Text(ht("失败"), color = c.bad, style = HomeType.value)
            PlatformOutcome.Waiting -> Text("—", color = c.t3, style = HomeType.value)
        }
    }
}

@Composable
private fun LatencyText(ms: Long, color: Color) {
    Text("$ms ms", color = color, style = HomeType.value, maxLines = 1)
}
