package io.github.xgl34222220.bichen

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.bichen.ui.BichenComposeController
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import io.github.xgl34222220.bichen.ui.RuleSourceItem
import io.github.xgl34222220.bichen.ui.RulesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyAdblockChainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyAdblockChainPage(onBack = { finish() }) } }
    }
}

private data class ChainSnapshot(
    val rules: RulesSnapshot = RulesSnapshot(),
    val running: Boolean = false,
    val hitCount: Long = 0L,
    val hitCountSource: String = "",
    val recentBlockedDomains: List<String> = emptyList(),
    val startupInjected: Boolean = false,
    val controllerLoaded: Boolean = false,
    val effective: Boolean = false,
    val lastError: String = "",
    val vpnFallbackRunning: Boolean = false,
    val hostsFallbackRunning: Boolean = false,
    val message: String = "",
)

@Composable
private fun ProxyAdblockChainPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val adController = remember { BichenComposeController(context) }
    val proxyController = remember { ProxyComposeController(context) }
    val rootManager = remember { RootProxyManager(context) }
    val runtimeInspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val updateView = androidx.compose.ui.platform.LocalView.current
    val updateSpinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "adblockUpdateSpin")
    val updateSpin by updateSpinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "adblockUpdateSpinValue",
    )
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var updatingRules by remember { mutableStateOf(false) }
    var updateSuccess by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var chainEnabled by remember(revision) { mutableStateOf(prefs.getBoolean("proxyAdblockChain", true)) }
    var fallbackEnabled by remember(revision) {
        mutableStateOf(prefs.getBoolean("proxyAdblockFallbackEnabled", prefs.getBoolean("vpnWanted", false)))
    }
    var fallbackMode by remember(revision) {
        mutableStateOf((prefs.getString("proxyAdblockFallbackMode", adController.protectionMode()) ?: "vpn").let { if (it == "module") "module" else "vpn" })
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && prefs.getBoolean("proxyAdblockFallbackEnabled", false)) {
            adController.startVpn()
            notice = "独立 DNS 去广告已启用"
            revision++
        } else if (result.resultCode != Activity.RESULT_OK) {
            prefs.edit().putBoolean("proxyAdblockFallbackEnabled", false).apply()
            notice = "未获得 VPN 授权，独立去广告保持关闭"
            revision++
        }
    }

    val cachedRules = remember { RulesSnapshot(count = prefs.getInt("proxyAdblockUiRuleCount", 0), profile = prefs.getString("proxyAdblockUiProfile", "加载中") ?: "加载中") }
    val snapshot by produceState(initialValue = ChainSnapshot(rules = cachedRules), revision) {
        val rulesResult = runCatching { adController.rulesSnapshot() }
        val rules = rulesResult.getOrDefault(cachedRules)
        if (rules.count > 0 || rules.sources.isNotEmpty()) {
            prefs.edit().putInt("proxyAdblockUiRuleCount", rules.count).putString("proxyAdblockUiProfile", rules.profile).apply()
        }
        val state = runCatching { proxyController.state() }.getOrNull()
        val independent = runCatching { adController.homeSnapshot() }.getOrNull()
        val startup = runCatching { rootManager.startupConfig() }.getOrDefault("")
        val startupInjected = startup.contains("${ProxyAdblockRules.PROVIDER_NAME}:") &&
            startup.contains("RULE-SET,${ProxyAdblockRules.PROVIDER_NAME},REJECT")
        val liveRules = if (state?.running == true) runCatching { proxyController.rules() }.getOrDefault(emptyList()) else emptyList()
        val adRule = liveRules.firstOrNull {
            it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true) ||
                it.type.contains(ProxyAdblockRules.PROVIDER_NAME, true)
        }
        val controllerLoaded = adRule != null
        val apiHits = adRule?.hitCount ?: 0L
        val logStats = if (state?.running == true) runCatching { runtimeInspector.adblockRuntimeStats() }.getOrDefault(AdblockRuntimeStats()) else AdblockRuntimeStats()
        val hits = maxOf(apiHits, logStats.count)
        val hitSource = when {
            logStats.count > 0L -> "Mihomo 运行日志"
            apiHits > 0L -> "Controller 规则计数"
            logStats.logAvailable -> "运行日志已检查"
            else -> ""
        }
        val lastError = prefs.getString("proxyAdblockLastError", "").orEmpty()
        val effective = chainEnabled && state?.running == true && rules.count > 0 && startupInjected && controllerLoaded
        value = ChainSnapshot(
            rules = rules,
            running = state?.running == true,
            hitCount = hits,
            hitCountSource = hitSource,
            recentBlockedDomains = logStats.recentDomains,
            startupInjected = startupInjected,
            controllerLoaded = controllerLoaded,
            effective = effective,
            lastError = lastError,
            vpnFallbackRunning = independent?.vpnRunning == true,
            hostsFallbackRunning = independent?.moduleEnabled == true,
            message = listOfNotNull(state?.message?.takeIf { it.isNotBlank() }, rulesResult.exceptionOrNull()?.message).joinToString("；"),
        )
    }

    fun applyFallback(enabled: Boolean, mode: String) {
        if (busy) return
        prefs.edit()
            .putBoolean("proxyAdblockFallbackEnabled", enabled)
            .putString("proxyAdblockFallbackMode", mode)
            .putBoolean("autoStartVpn", enabled && mode == "vpn")
            .apply()
        fallbackEnabled = enabled
        fallbackMode = mode
        if (snapshot.running) {
            notice = if (enabled) "已保存；Root 代理停止后自动恢复${if (mode == "vpn") "独立 DNS 去广告" else "Root hosts 去广告"}" else "已关闭代理停止后的独立去广告"
            revision++
            return
        }
        scope.launch {
            busy = true
            try {
                if (!enabled) {
                    if (snapshot.vpnFallbackRunning || prefs.getBoolean("vpnWanted", false)) adController.stopVpn()
                    if (snapshot.hostsFallbackRunning) adController.toggleModuleProtection(true)
                    notice = "独立去广告已关闭"
                } else if (mode == "vpn") {
                    if (snapshot.hostsFallbackRunning) adController.toggleModuleProtection(true)
                    val prepare = adController.prepareVpn()
                    if (prepare != null) {
                        busy = false
                        vpnPermissionLauncher.launch(prepare)
                        return@launch
                    }
                    adController.startVpn()
                    notice = "独立 DNS 去广告已启用；代理启动时会自动暂停"
                } else {
                    if (snapshot.vpnFallbackRunning || prefs.getBoolean("vpnWanted", false)) adController.stopVpn()
                    if (!snapshot.hostsFallbackRunning) adController.toggleModuleProtection(false)
                    notice = "Root hosts 去广告已启用；代理启动时会自动暂停"
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                notice = error.message ?: "独立去广告切换失败"
                prefs.edit().putBoolean("proxyAdblockFallbackEnabled", false).apply()
                fallbackEnabled = false
            } finally {
                busy = false
                revision++
            }
        }
    }

    fun toggleSource(item: RuleSourceItem) {
        if (busy) return
        scope.launch {
            busy = true
            runCatching { adController.setRuleSource(item.id, !item.enabled) }
                .onSuccess { notice = "${item.name} 已${if (item.enabled) "关闭" else "开启"}；代理运行中时请重启代理应用新快照"; revision++ }
                .onFailure { notice = it.message ?: "规则源修改失败" }
            busy = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("广告过滤", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("代理串联 · 明确白名单优先，广告后缀规则早于普通分流", color = t.textSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = { revision++ }, enabled = !busy, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item("master") {
            Surface(shape = RoundedCornerShape(24.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("随代理串联过滤", color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                when {
                                    snapshot.effective -> "已生效 · Mihomo 当前已加载辟尘广告规则链"
                                    chainEnabled && snapshot.running -> "已开启 · 正在验证当前运行链"
                                    chainEnabled -> "已开启 · 启动 Root 代理后自动验证"
                                    else -> "代理仅负责转发，不执行辟尘广告规则"
                                },
                                color = if (snapshot.effective) Color(0xFF059669) else t.textSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = chainEnabled,
                            onCheckedChange = {
                                chainEnabled = it
                                prefs.edit().putBoolean("proxyAdblockChain", it).apply()
                                notice = if (snapshot.running) "设置已保存，重启 Root 代理后生效" else "设置已保存"
                                revision++
                            },
                        )
                    }
                    HorizontalDivider(color = if (dark) t.outline else Color(0xFFF1F5F9))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChainMetric("有效规则", snapshot.rules.count.toString(), Modifier.weight(1f))
                        ChainMetric("规则源", snapshot.rules.sources.count { it.enabled }.toString(), Modifier.weight(1f))
                        ChainMetric("本次拦截", if (!snapshot.running) "—" else if (snapshot.hitCount > 0L) snapshot.hitCount.toString() else "0", Modifier.weight(1f))
                    }
                }
            }
        }


        item("runtime-verify") {
            val statusText = when {
                snapshot.effective && snapshot.hitCount > 0L -> "已生效 · 已记录 ${snapshot.hitCount} 次实际拦截"
                snapshot.effective -> "已生效 · 当前运行日志暂未记录到广告拦截"
                !chainEnabled -> "广告串联已关闭"
                !snapshot.running -> "等待代理启动"
                snapshot.lastError.isNotBlank() -> "本次运行已降级"
                else -> "运行链未完整加载"
            }
            val statusColor = when {
                snapshot.effective -> Color(0xFF059669)
                snapshot.lastError.isNotBlank() -> Color(0xFFF59E0B)
                else -> Color(0xFF64748B)
            }
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (dark) t.elevatedCardBackground else Color.White,
                shadowElevation = if (dark) 0.dp else 2.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(38.dp).background(statusColor.copy(alpha = .10f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (snapshot.effective) Icons.Rounded.VerifiedUser else Icons.Rounded.FactCheck,
                                null,
                                tint = statusColor,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("运行链验证", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(onClick = { if (!busy) revision++ }) { Text("重新检测", fontSize = 11.sp) }
                    }
                    HorizontalDivider(color = if (dark) t.outline.copy(alpha = .35f) else Color(0xFFF1F5F9))
                    ChainVerifyRow("本地规则库", snapshot.rules.count > 0, if (snapshot.rules.count > 0) "${snapshot.rules.count} 条有效规则" else "没有可用规则")
                    ChainVerifyRow("启动配置注入", snapshot.startupInjected, if (snapshot.startupInjected) "bichen-adblock 已写入运行副本" else "当前启动副本没有广告 provider")
                    ChainVerifyRow("Mihomo 规则链", snapshot.controllerLoaded, if (snapshot.controllerLoaded) "Controller 已看到 REJECT 规则" else "当前 Controller 未看到广告规则")
                    if (snapshot.running) {
                        ChainVerifyRow(
                            "实际拦截",
                            snapshot.hitCount > 0L,
                            if (snapshot.hitCount > 0L) "${snapshot.hitCount} 次 · ${snapshot.hitCountSource.ifBlank { "已记录" }}"
                            else "0 次 · 已加载规则，但当前日志/API 尚未记录命中",
                            allowNeutral = true,
                        )
                    }
                    if (snapshot.recentBlockedDomains.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(12.dp), color = if (dark) Color.White.copy(alpha = .04f) else Color(0xFFF8FAFC)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("最近拦截", color = t.textPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                snapshot.recentBlockedDomains.take(6).forEach { domain ->
                                    Text(domain, color = t.textSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                    if (snapshot.lastError.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFF59E0B).copy(alpha = .10f)) {
                            Text(
                                "最近一次降级原因：${snapshot.lastError}",
                                Modifier.fillMaxWidth().padding(10.dp),
                                color = Color(0xFFB45309),
                                fontSize = 10.sp,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }
            }
        }

        item("profile") {
            Surface(shape = RoundedCornerShape(20.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("保护强度", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(snapshot.rules.profile, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("lite" to "轻量", "balanced" to "均衡", "enhanced" to "加强").forEach { (id, label) ->
                            FilterChip(
                                selected = snapshot.rules.profile == label,
                                onClick = {
                                    if (!busy) scope.launch {
                                        busy = true
                                        runCatching { adController.setRuleProfile(id) }
                                            .onSuccess { notice = "已切换到${label}保护；Root 代理运行中请重启以载入新快照"; revision++ }
                                            .onFailure { notice = it.message ?: "保护强度切换失败" }
                                        busy = false
                                    }
                                },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                        }
                    }
                    Text("轻量：国内纯广告；均衡：AdAway + 国内规则 + HaGeZi Light；加强：再加入隐私/追踪增强。旧均衡只有约 7k 规则，重新点“均衡”即可升级。用户黑白名单始终保留。", color = t.textSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }

        item("flow") {
            Surface(shape = RoundedCornerShape(18.dp), color = t.selectionBackground) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("执行顺序", color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("应用流量 → Root DIRECT 应用 / 明确域名白名单 → 广告后缀 RULE-SET → 普通地区/规则集分流 → 最终兜底", color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                    Text("串联模式使用 Mihomo +. 域名后缀匹配，可覆盖多级子域；独立 DNS 去广告在代理运行时暂停。", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        item("fallback") {
            Surface(shape = RoundedCornerShape(22.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondary.copy(alpha = .10f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Dns, null, tint = MaterialTheme.colorScheme.secondary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("代理关闭后的独立去广告", color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            val fallbackState = when {
                                snapshot.running && fallbackEnabled -> "代理运行中 · 独立模式已暂停"
                                !fallbackEnabled -> "关闭"
                                fallbackMode == "vpn" && snapshot.vpnFallbackRunning -> "DNS VPN 正在运行"
                                fallbackMode == "module" && snapshot.hostsFallbackRunning -> "Root hosts 正在运行"
                                else -> "已启用 · 等待恢复"
                            }
                            Text(fallbackState, color = t.textSecondary, fontSize = 11.sp)
                        }
                        Switch(checked = fallbackEnabled, onCheckedChange = { applyFallback(it, fallbackMode) }, enabled = !busy)
                    }
                    if (fallbackEnabled) {
                        HorizontalDivider(color = if (dark) t.outline else Color(0xFFF1F5F9))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = fallbackMode == "vpn",
                                onClick = { if (fallbackMode != "vpn") applyFallback(true, "vpn") },
                                label = { Text("DNS VPN") },
                                leadingIcon = { Icon(Icons.Rounded.Dns, null, Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                            FilterChip(
                                selected = fallbackMode == "module",
                                onClick = { if (fallbackMode != "module") applyFallback(true, "module") },
                                label = { Text("Root hosts") },
                                leadingIcon = { Icon(Icons.Rounded.AdminPanelSettings, null, Modifier.size(16.dp)) },
                                modifier = Modifier.weight(1f),
                                enabled = !busy,
                            )
                        }
                        Text("只在代理停止时运行；启动 Root 代理会自动暂停，停止代理后按这里的选择恢复。", color = t.textSecondary, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }
        }

        item("source-title") { ChainSectionLabel("规则源") }
        snapshot.rules.sources.forEach { source ->
            item("source-${source.id}") {
                Surface(shape = RoundedCornerShape(18.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.FilterAlt, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(source.name, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (source.count >= 0) "${source.count} 条 · ${source.id}" else source.id, color = t.textSecondary, fontSize = 10.sp)
                        }
                        Switch(checked = source.enabled, onCheckedChange = { toggleSource(source) }, enabled = !busy)
                    }
                }
            }
        }

        item("user-rules") {
            Surface(shape = RoundedCornerShape(18.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("用户黑白名单", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("白名单 ${snapshot.rules.allow.size} · 黑名单 ${snapshot.rules.block.size} · 与独立去广告共用同一规则库", color = t.textSecondary, fontSize = 11.sp)
                    }
                }
            }
        }

        item("update") {
            val container = if (updateSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
            Button(
                onClick = {
                    if (busy) return@Button
                    scope.launch {
                        busy = true
                        updatingRules = true
                        updateSuccess = false
                        val startedAt = android.os.SystemClock.elapsedRealtime()
                        val result = runCatching { adController.updateRules() }
                        val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                        if (elapsed < 650L) kotlinx.coroutines.delay(650L - elapsed)
                        updatingRules = false
                        result
                            .onSuccess {
                                notice = "$it；代理运行中时请重启代理应用新快照"
                                revision++
                                updateSuccess = true
                                updateView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                                kotlinx.coroutines.delay(1100)
                                updateSuccess = false
                            }
                            .onFailure { notice = it.message ?: "规则更新失败" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(17.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = Color.White,
                    disabledContainerColor = container,
                    disabledContentColor = Color.White,
                ),
            ) {
                Icon(
                    if (updateSuccess) Icons.Rounded.Check else Icons.Rounded.Sync,
                    if (updatingRules) "正在更新" else null,
                    modifier = Modifier.size(19.dp).graphicsLayer { rotationZ = if (updatingRules) updateSpin else 0f },
                    tint = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        updatingRules -> "正在更新…"
                        updateSuccess -> "已是最新"
                        else -> "更新广告规则"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (notice.isNotBlank() || snapshot.message.isNotBlank()) {
            item("notice") {
                Surface(shape = RoundedCornerShape(16.dp), color = t.controlBackground) {
                    Text((notice.ifBlank { snapshot.message }), Modifier.fillMaxWidth().padding(13.dp), color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun ChainVerifyRow(label: String, ok: Boolean, detail: String, allowNeutral: Boolean = false) {
    val t = LocalBichenTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).background(
                when {
                    ok -> Color(0xFF10B981).copy(alpha = .12f)
                    allowNeutral -> Color(0xFF94A3B8).copy(alpha = .12f)
                    else -> Color(0xFFF59E0B).copy(alpha = .12f)
                },
                CircleShape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    ok -> Icons.Rounded.Check
                    allowNeutral -> Icons.Rounded.Remove
                    else -> Icons.Rounded.PriorityHigh
                },
                null,
                tint = when {
                    ok -> Color(0xFF059669)
                    allowNeutral -> Color(0xFF64748B)
                    else -> Color(0xFFD97706)
                },
                modifier = Modifier.size(13.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = t.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(88.dp))
        Text(detail, color = t.textSecondary, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ChainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val accent = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)
    Surface(
        modifier = modifier.height(70.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.controlBackground.copy(alpha = .72f) else Color.White,
        tonalElevation = 0.dp,
        shadowElevation = if (dark) 0.dp else 3.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(value, color = accent, fontSize = 20.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color(0xFF94A3B8), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ChainSectionLabel(text: String) {
    Text(text, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = .6.sp, modifier = Modifier.padding(start = 12.dp, top = 2.dp))
}
