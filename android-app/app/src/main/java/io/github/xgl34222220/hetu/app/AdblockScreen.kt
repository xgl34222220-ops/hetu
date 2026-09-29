package io.github.xgl34222220.hetu

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.RulesSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun AdblockScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    val c = Hx.colors
    var revision by remember { mutableIntStateOf(0) }
    var rules by remember { mutableStateOf<RulesSnapshot?>(null) }
    var stats by remember { mutableStateOf(AdblockRuntimeStats()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxyAdblockChain", true)) }
    var fallback by remember { mutableStateOf(prefs.getBoolean("proxyAdblockFallbackEnabled", false)) }
    var cname by remember { mutableStateOf(prefs.getBoolean("cnameProtection", true)) }
    var addDomain by remember { mutableStateOf<Boolean?>(null) } // true = allow list, false = block list
    var whitelistCandidate by remember { mutableStateOf<String?>(null) }
    val running = vm.state.running
    var injected by remember { mutableStateOf<Boolean?>(null) }
    var loadedInCore by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(revision, running) {
        try {
            rules = vm.filters.rulesSnapshot()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            vm.toast(error.message ?: "规则读取失败")
        }
        if (running) {
            stats = try { vm.inspector.adblockRuntimeStats() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { stats }
            injected = try {
                AdblockRuleInspection.isInjected(withContext(Dispatchers.IO) { RootProxyManager(context).startupConfig() })
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            loadedInCore = try {
                vm.controller.rules().any {
                    it.payload.contains(ProxyAdblockRules.PROVIDER_NAME, true) || it.type.contains(ProxyAdblockRules.PROVIDER_NAME, true)
                }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        } else {
            injected = null
            loadedInCore = null
        }
    }

    fun perform(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                val message = block()
                if (message.isNotBlank()) vm.toast(message)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "操作失败")
            } finally {
                busy = null
                revision++
                vm.bumpSettings()
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.filters.startVpn()
            vm.toast("独立 DNS 过滤已开启")
        } else {
            prefs.edit().putBoolean("proxyAdblockFallbackEnabled", false).apply()
            fallback = false
            vm.toast("未获得 VPN 授权")
        }
    }

    fun setFallback(on: Boolean) {
        prefs.edit()
            .putBoolean("proxyAdblockFallbackEnabled", on)
            .putString("proxyAdblockFallbackMode", "vpn")
            .putBoolean("autoStartVpn", on)
            .apply()
        fallback = on
        if (running) {
            vm.toast(if (on) "已保存：代理停止后自动启用独立 DNS 过滤" else "已关闭独立 DNS 过滤")
            return
        }
        if (!on) {
            vm.filters.stopVpn()
            return
        }
        val prepare = vm.filters.prepareVpn()
        if (prepare != null) vpnPermission.launch(prepare) else vm.filters.startVpn()
    }

    val lastError = if (revision >= 0) prefs.getString("proxyAdblockLastError", "").orEmpty() else ""
    val effective = running && enabled && prefs.getBoolean("proxyAdblockLastEffective", false) && lastError.isBlank()
    val actualMode = vm.state.trafficMode.lowercase()
    val snapshot = rules

    HxPage(
        title = "广告过滤",
        subtitle = "在 Mihomo 内按域名拦截广告与追踪",
        onBack = { nav.pop() },
        actions = { HxBarAction(Icons.Rounded.Refresh, "刷新", onClick = { revision++ }) },
    ) {
        item(key = "master") {
            HxSection {
                HxCard {
                    val tint by animateColorAsState(if (effective) c.good else if (enabled) c.warn else c.textFaint, tween(HxMotion.Medium), label = "shield")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Shield, null, tint = tint, modifier = Modifier.size(26.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    !enabled -> "已关闭"
                                    !running -> "已开启 · 等待代理启动"
                                    lastError.isNotBlank() -> "未完整加载"
                                    effective && actualMode != "rule" && actualMode.isNotBlank() -> "当前为${if (actualMode == "global") "全局" else "直连"}模式"
                                    effective -> "保护中"
                                    else -> "正在验证"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = c.text,
                            )
                            Text(
                                if (actualMode == "global" || actualMode == "direct") "广告规则只在「规则」模式下生效" else "广告域名直接 REJECT，其余流量照常分流",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted,
                            )
                        }
                        if (busy == "toggle") HxSpinner(20.dp)
                        else HxSwitch(enabled, { on ->
                            enabled = on
                            perform("toggle") {
                                withContext(Dispatchers.IO) { ProxyAdblockRuntimeBridge.setEnabled(context, on) }
                            }
                        })
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        HxMetric("有效规则", HxFormat.count((snapshot?.count ?: 0).toLong()), Modifier.weight(1f))
                        HxMetric("本次拦截", if (running) HxFormat.count(stats.count) else "—", Modifier.weight(1f), valueColor = if (stats.count > 0) c.good else c.text)
                        HxMetric("白名单", (snapshot?.allow?.size ?: 0).toString(), Modifier.weight(.8f))
                    }
                    if (lastError.isNotBlank()) {
                        HxBanner(lastError, tone = HxTone.Warn, modifier = Modifier.padding(top = 14.dp), actionLabel = if (running) "重载" else null, onAction = {
                            perform("reload") { withContext(Dispatchers.IO) { ProxyAdblockRuntimeBridge.hotReload(context) } }
                        })
                    } else if (running && enabled && (actualMode == "global" || actualMode == "direct")) {
                        HxBanner("当前模式不会经过规则，广告过滤不会生效", tone = HxTone.Warn, modifier = Modifier.padding(top = 14.dp), actionLabel = "切到规则", onAction = {
                            vm.setTrafficMode("rule")
                        })
                    }
                }
            }
        }

        item(key = "verify") {
            HxSection("运行链验证") {
                HxGroup {
                    VerifyRow("本地规则库", (snapshot?.count ?: 0) > 0, if ((snapshot?.count ?: 0) > 0) "${snapshot?.count} 条有效规则" else "当前没有启用的拦截规则")
                    HxDivider(44.dp)
                    VerifyRow("启动配置注入", injected, when (injected) { true -> "hetu-adblock 已写入运行副本"; false -> "当前运行副本没有广告规则"; null -> "代理启动后检测" })
                    HxDivider(44.dp)
                    VerifyRow("Mihomo 规则链", loadedInCore, when (loadedInCore) { true -> "核心已加载 REJECT 规则"; false -> "核心未看到广告规则"; null -> "代理启动后检测" })
                    HxDivider(44.dp)
                    VerifyRow("实际拦截", if (running) stats.count > 0 else null, if (running) "${stats.count} 次" else "代理启动后统计")
                }
                Text(
                    "执行顺序：应用流量 → 直连应用 / 明确白名单 → 广告规则 REJECT → 配置分流 → 兜底。代理运行时独立 DNS 过滤自动暂停。",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }

        if (running && stats.recentDomains.isNotEmpty()) {
            item(key = "recent") {
                HxSection("最近拦截") {
                    HxGroup {
                        stats.recentDomains.forEachIndexed { index, domain ->
                            if (index > 0) HxDivider(16.dp)
                            HxRow(domain, subtitle = "点按可加入白名单", onClick = { whitelistCandidate = domain }) {
                                Icon(Icons.Rounded.Block, null, tint = c.bad, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }

        item(key = "profile") {
            val current = if (snapshot == null) "" else when {
                snapshot.profile.startsWith("轻量") -> "lite"
                snapshot.profile.startsWith("均衡") -> "balanced"
                snapshot.profile.startsWith("加强") -> "enhanced"
                else -> "custom"
            }
            HxSection("拦截强度") {
                HxSegmented(
                    options = listOf("lite" to "轻量", "balanced" to "均衡", "enhanced" to "加强"),
                    selected = current,
                    enabled = busy == null,
                    onSelect = { id -> perform("profile") { vm.filters.setRuleProfile(id).ifBlank { "已切换拦截强度" } } },
                )
                Text(
                    when (current) {
                        "lite" -> "只使用中文广告规则，误拦最少。"
                        "balanced" -> "中文广告 + AdGuard DNS，推荐日常使用。"
                        "enhanced" -> "再叠加 HaGeZi 与隐私追踪规则，拦得更多，偶有误拦。"
                        "custom" -> "当前为自定义组合。"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            }
        }

        item(key = "sources") {
            HxSection(
                "规则源",
                trailing = {
                    HxButton("立即更新", onClick = {
                        perform("update") { vm.filters.updateRules() }
                    }, icon = Icons.Rounded.Sync, busy = busy == "update", enabled = busy == null || busy == "update")
                },
            ) {
                HxGroup {
                    val sources = snapshot?.sources.orEmpty()
                    if (sources.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner() }
                    }
                    sources.forEachIndexed { index, source ->
                        if (index > 0) HxDivider()
                        HxSwitchRow(
                            source.name,
                            source.enabled,
                            { on -> perform("source") { vm.filters.setRuleSource(source.id, on).ifBlank { "${source.name} 已${if (on) "开启" else "关闭"}" } } },
                            subtitle = buildString {
                                append("${HxFormat.count(source.count.toLong())} 条 · ")
                                append(if (source.lastSuccess > 0) "更新于 ${HxFormat.ago(source.lastSuccess)}" else "使用内置快照")
                                if (source.lastError.isNotBlank()) append("\n上次更新失败：${source.lastError}")
                            },
                            icon = Icons.Rounded.FilterAlt,
                            iconTint = if (source.lastError.isNotBlank()) c.warn else c.accent,
                            enabled = busy == null,
                        )
                    }
                }
            }
        }

        item(key = "allow") {
            DomainList(
                title = "白名单（永不拦截）",
                domains = snapshot?.allow.orEmpty(),
                tone = HxTone.Good,
                onAdd = { addDomain = true },
                onRemove = { d -> perform("domain") { vm.filters.changeDomain(d, true, false).ifBlank { "已移除 $d" } } },
            )
        }
        item(key = "block") {
            DomainList(
                title = "黑名单（额外拦截）",
                domains = snapshot?.block.orEmpty(),
                tone = HxTone.Bad,
                onAdd = { addDomain = false },
                onRemove = { d -> perform("domain") { vm.filters.changeDomain(d, false, false).ifBlank { "已移除 $d" } } },
            )
        }

        item(key = "fallback") {
            HxSection("代理关闭时") {
                HxGroup {
                    HxSwitchRow(
                        "独立 DNS 过滤",
                        fallback,
                        ::setFallback,
                        subtitle = "代理未运行时用本地 VPN 继续过滤广告；代理启动时自动让位",
                        icon = Icons.Rounded.Dns,
                        iconTint = c.textMuted,
                    )
                    HxDivider()
                    HxSwitchRow(
                        "CNAME 追踪防护",
                        cname,
                        { on ->
                            cname = on
                            prefs.edit().putBoolean("cnameProtection", on).apply()
                            vm.filters.applyVpnBypass()
                        },
                        subtitle = "独立 DNS 过滤时，拦截伪装成正常域名的追踪 CNAME",
                        icon = Icons.Rounded.FilterAlt,
                        iconTint = c.textMuted,
                    )
                }
            }
        }
    }

    addDomain?.let { allow ->
        HxFormDialog(
            title = if (allow) "添加白名单" else "添加黑名单",
            message = "输入域名，匹配它及其所有子域名，例如 example.com",
            fields = listOf(HxField("域名", placeholder = "example.com")),
            confirmLabel = "添加",
            validate = { v -> if (v[0].isBlank() || !v[0].contains('.')) "请输入有效域名" else null },
            onConfirm = { v ->
                addDomain = null
                perform("domain") { vm.filters.changeDomain(v[0], allow, true).ifBlank { "已添加 ${v[0]}" } }
            },
            onDismiss = { addDomain = null },
        )
    }

    whitelistCandidate?.let { domain ->
        HxConfirmDialog(
            title = "加入白名单？",
            message = "$domain 及其子域名将不再被拦截。",
            confirmLabel = "加入",
            onConfirm = {
                whitelistCandidate = null
                perform("domain") { vm.filters.changeDomain(domain, true, true).ifBlank { "已加入白名单" } }
            },
            onDismiss = { whitelistCandidate = null },
        )
    }
}

@Composable
private fun DomainList(title: String, domains: List<String>, tone: HxTone, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    val c = Hx.colors
    HxSection(
        title,
        trailing = {
            Row(
                Modifier.clip(Hx.chipShape).clickable(onClick = onAdd).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("添加", style = MaterialTheme.typography.labelLarge, color = c.accent)
            }
        },
    ) {
        HxGroup {
            if (domains.isEmpty()) {
                Text("暂无", style = MaterialTheme.typography.bodyMedium, color = c.textFaint, modifier = Modifier.padding(16.dp))
            }
            domains.take(200).forEachIndexed { index, domain ->
                if (index > 0) HxDivider(16.dp)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (tone == HxTone.Good) Icons.Rounded.CheckCircle else Icons.Rounded.Block, null, tint = tone.fg(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(domain, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(domain) }) { Icon(Icons.Rounded.Close, "移除", tint = c.textFaint, modifier = Modifier.size(18.dp)) }
                }
            }
            if (domains.size > 200) {
                Text("仅显示前 200 条，共 ${domains.size} 条", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(16.dp))
            }
        }
    }
}


@Composable
private fun VerifyRow(label: String, ok: Boolean?, detail: String) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        HxDot(when (ok) { true -> c.good; false -> c.warn; null -> c.textFaint }, 10.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}
