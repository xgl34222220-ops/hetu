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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.launch

/**
 * 去广告核查: the run chain as the core reports it, hit statistics, and a live probe of known ad
 * domains through the core. All reads run on IO; the page only renders their results.
 */
@Composable
internal fun AdblockVerifyScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var audit by remember { mutableStateOf<AdblockAudit?>(null) }
    var probing by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf<List<AdProbeResult>?>(null) }
    var probeNote by remember { mutableStateOf<String?>(null) }
    val running = vm.state.running

    LaunchedEffect(revision, running) {
        loading = true
        try {
            audit = AdblockAuditBridge.audit(context, running)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            vm.toast(error.message ?: "核查失败")
        } finally {
            loading = false
        }
    }

    fun probe() {
        if (probing || !running) return
        probing = true
        probeNote = null
        scope.launch {
            try {
                val domains = AdblockAuditBridge.probeDomains(context)
                if (domains.isEmpty()) probeNote = "当前拦截库里没有内置的测试广告域名（可能已加入白名单），仅检查对照域名"
                probes = AdblockProbe.run(context, domains)
                revision++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                probeNote = error.message ?: "实测失败"
            } finally {
                probing = false
            }
        }
    }

    val current = audit
    HxPage(
        title = ht("去广告核查"),
        subtitle = ht("运行链、核心规则与拦截实测"),
        onBack = onBack,
        largeTitle = false,
        actions = { HomeIconButton(HomeIcons.RefreshCw, "重新核查", { revision++ }, enabled = !loading, spinning = loading) },
    ) {
        item(key = "verdict") {
            SettingsSection {
                if (current == null) {
                    HxBanner(ht(if (loading) "正在读取核心状态…" else "无法读取广告过滤状态"), tone = HxTone.Neutral)
                } else {
                    HxBanner(
                        current.headline,
                        tone = when (current.verdict) {
                            AdVerdict.Effective -> HxTone.Good
                            AdVerdict.NotEffective -> HxTone.Bad
                            AdVerdict.Off, AdVerdict.Waiting -> HxTone.Neutral
                            AdVerdict.Unknown -> HxTone.Warn
                        },
                    )
                }
            }
        }
        item(key = "stats") {
            SettingsSection {
                SettingsGroup(title = ht("拦截统计")) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        VerifyMetric("核心规则集", current?.coreRuleCount?.let { "%,d".format(it) } ?: "—", Modifier.weight(1f))
                        HomeVerticalDivider(Modifier.height(34.dp))
                        VerifyMetric("规则命中", current?.ruleHits?.let { "%,d".format(it) } ?: "—", Modifier.weight(1f))
                        HomeVerticalDivider(Modifier.height(34.dp))
                        VerifyMetric("本次拦截", "%,d".format(vm.prefs.getLong("proxyAdblockSessionHits", 0L).takeIf { revision >= 0 } ?: 0L), Modifier.weight(1f))
                    }
                }
            }
        }
        item(key = "checks") {
            SettingsSection {
                SettingsGroup(title = ht("运行链核查")) {
                    val checks = current?.checks.orEmpty()
                    if (checks.isEmpty()) SettingsRow(ht("读取中"), icon = ToolsFeatureIcons.CircleDashed, iconTint = c.t3, compact = true) { HxSpinner(18.dp) }
                    checks.forEachIndexed { index, check ->
                        if (index > 0) SettingsDivider()
                        val (icon, tint) = checkLook(check.state)
                        SettingsRow(ht(check.title), subtitle = check.detail, icon = icon, iconTint = tint, compact = true)
                    }
                }
            }
        }
        item(key = "probe") {
            SettingsSection {
                SettingsGroup(title = ht("拦截实测")) {
                    SettingsRow(
                        ht("开始实测"),
                        subtitle = ht(if (running) "经核心请求几个已知广告域名与一个对照域名，并读取核心日志里的判定" else "代理运行时可用"),
                        icon = ToolsFeatureIcons.ShieldCheck,
                        enabled = running && !probing,
                        onClick = ::probe,
                    ) { if (probing) HxSpinner(18.dp) else HxChevron() }
                    probeNote?.let { note ->
                        HxBanner(note, tone = HxTone.Warn, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp))
                    }
                    probes.orEmpty().forEach { result ->
                        SettingsDivider()
                        val ok = AdblockProbe.asExpected(result)
                        val icon = when {
                            result.verdict == AdProbeVerdict.Unknown -> ToolsFeatureIcons.CircleDashed
                            ok -> HomeIcons.CircleCheck
                            else -> HomeIcons.CircleAlert
                        }
                        val tint = when {
                            result.verdict == AdProbeVerdict.Unknown -> c.warn
                            ok -> c.good
                            else -> c.bad
                        }
                        val label = if (result.expectBlocked) result.domain else result.domain + ht("（对照）")
                        SettingsRow(label, subtitle = result.detail, icon = icon, iconTint = tint, compact = true) {
                            Text(
                                ht(when (result.verdict) {
                                    AdProbeVerdict.Blocked, AdProbeVerdict.BlockedByOther -> "已拦截"
                                    AdProbeVerdict.Allowed -> "已放行"
                                    AdProbeVerdict.Unknown -> "未确认"
                                }),
                                color = tint, style = HomeType.value, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        item(key = "note") {
            Text(
                ht("说明：Root 代理内的去广告由 Mihomo 规则执行——广告域名的连接被 REJECT，DNS 查询本身照常应答（fake-ip 下得到虚拟地址）。只在「规则」模式下生效；不进入核心的应用（应用名单外或直连放行）与 HTTPS 页面内嵌的同域广告不受影响。实测请求会计入一次拦截次数。"),
                Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap),
                color = c.t2,
                style = HomeType.note,
            )
        }
    }
}

@Composable
private fun checkLook(state: AdCheckState): Pair<ImageVector, Color> {
    val c = LocalHomeColors.current
    return when (state) {
        AdCheckState.Pass -> HomeIcons.CircleCheck to c.good
        AdCheckState.Warn -> HomeIcons.TriangleAlert to c.warn
        AdCheckState.Fail -> HomeIcons.CircleAlert to c.bad
        AdCheckState.Pending -> ToolsFeatureIcons.CircleDashed to c.t3
    }
}

@Composable
private fun VerifyMetric(label: String, value: String, modifier: Modifier) {
    val c = LocalHomeColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ht(label), color = c.t2, style = HomeType.note.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        HomeRollingText(value, c.accent, HomeType.metric.copy(fontSize = HomeType.sheetTitle.fontSize), alignment = Alignment.Center)
    }
}
