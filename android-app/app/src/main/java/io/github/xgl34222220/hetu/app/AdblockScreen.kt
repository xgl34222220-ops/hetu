package io.github.xgl34222220.hetu

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
    var showHelp by remember { mutableStateOf(false) }

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
        largeTitle = false, compactTitleFontSizeSp = 20f,
        onBack = { nav.pop() },
        actions = {
            HxBarAction(Icons.Rounded.HelpOutline, "说明", onClick = { showHelp = true })
            HxBarAction(Icons.Rounded.Refresh, "刷新", onClick = { revision++ })
        },
    ) {
        item(key = "adblock-subtitle") {
            Text("在 Mihomo 内按域名拦截广告与追踪", Modifier.fillMaxWidth().padding(bottom = 12.dp),
                color = c.textMuted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        item(key = "master") {
            HxSection {
                HxCard {
                    val tint by animateColorAsState(if (running && enabled && actualMode in listOf("global", "direct")) c.warn else if (effective) c.good else if (enabled) c.warn else c.textFaint, tween(HxMotion.Medium), label = "shield")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // While actively protecting, a soft ring breathes around the shield.
                        val breathing = rememberInfiniteTransition(label = "shieldBreath")
                        val breath by breathing.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "shieldBreathValue")
                        val haloStrength by animateFloatAsState(if (effective) 1f else 0f, tween(HxMotion.Long), label = "shieldHalo")
                        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(76.dp)
                                    .graphicsLayer {
                                        val s = 1f + .12f * breath * haloStrength
                                        scaleX = s
                                        scaleY = s
                                        alpha = haloStrength * (.55f - .35f * breath)
                                    }
                                    .clip(CircleShape)
                                    .background(tint.copy(alpha = .18f)),
                            )
                            Box(Modifier.size(76.dp).clip(CircleShape).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Shield, null, tint = tint, modifier = Modifier.size(44.dp))
                                if (effective) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(23.dp))
                                else if (running && enabled && (actualMode in listOf("global", "direct") || lastError.isNotBlank()))
                                    Text("!", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = when {
                                    !enabled -> "已关闭"
                                    !running -> "已开启 · 等待代理启动"
                                    lastError.isNotBlank() -> "未完整加载"
                                    enabled && actualMode in listOf("global", "direct") -> "当前为${if (actualMode == "global") "全局" else "直连"}模式"
                                    effective -> "保护中"
                                    else -> "正在验证"
                                },
                                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                                label = "adblockTitle",
                            ) { title ->
                                Text(title, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = tint)
                            }
                            Text(
                                if (actualMode == "global" || actualMode == "direct") "广告规则只在「规则」模式下生效" else "广告域名直接 REJECT，\n其余流量照常分流",
                                fontSize = 14.sp, lineHeight = 18.sp,
                                color = c.textMuted,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (busy == "toggle") HxSpinner(20.dp)
                        else HxSwitch(enabled, { on ->
                            enabled = on
                            perform("toggle") {
                                withContext(Dispatchers.IO) { ProxyAdblockRuntimeBridge.setEnabled(context, on) }
                            }
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    if (lastError.isNotBlank()) {
                        HxBanner(lastError, tone = HxTone.Warn, modifier = Modifier.padding(bottom = 12.dp), actionLabel = if (running) "重载" else null, onAction = {
                            perform("reload") { withContext(Dispatchers.IO) { ProxyAdblockRuntimeBridge.hotReload(context) } }
                        })
                    } else if (running && enabled && (actualMode == "global" || actualMode == "direct")) {
                        HxBanner("当前模式不会经过规则，广告过滤不会生效", tone = HxTone.Warn, modifier = Modifier.padding(bottom = 12.dp), actionLabel = "切到规则", onAction = {
                            vm.setTrafficMode("rule")
                        })
                    }
                    HorizontalDivider(thickness = .5.dp, color = c.line)
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        AdblockMetric("有效规则", snapshot?.let { adblockCount(it.count.toLong()) } ?: "—", Modifier.weight(1f))
                        Box(Modifier.width(.5.dp).height(36.dp).background(c.line))
                        AdblockMetric("本次拦截", if (running) adblockCount(stats.count) else "—", Modifier.weight(1f))
                        Box(Modifier.width(.5.dp).height(36.dp).background(c.line))
                        AdblockMetric("白名单", snapshot?.allow?.size?.toString() ?: "—", Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "verify") {
            HxSection() {
                HxGroup {
                    AdblockHeading("运行链验证")
                    VerifyRow("本地规则库", (snapshot?.count ?: 0) > 0, if ((snapshot?.count ?: 0) > 0) "${snapshot?.count} 条有效规则" else "当前没有启用的拦截规则")
                    HxDivider(44.dp)
                    VerifyRow("启动配置注入", injected, when (injected) { true -> "hetu-adblock 已写入运行副本"; false -> "当前运行副本没有广告规则"; null -> "代理启动后检测" })
                    HxDivider(44.dp)
                    VerifyRow("Mihomo 规则链", loadedInCore, when (loadedInCore) { true -> "核心已加载 REJECT 规则"; false -> "核心未看到广告规则"; null -> "代理启动后检测" })
                    HxDivider(44.dp)
                    VerifyRow("实际拦截", if (running) stats.count > 0 else null, if (running) "${stats.count} 次" else "代理启动后统计", neutralFalse = true)
                }
            }
        }

        if (running && stats.recentDomains.isNotEmpty()) {
            item(key = "recent") {
                HxSection() {
                    HxGroup {
                        AdblockHeading("最近拦截")
                        stats.recentDomains.forEachIndexed { index, domain ->
                            if (index > 0) HxDivider(16.dp)
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { whitelistCandidate = domain }
                                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(c.badSoft), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Block, null, tint = c.bad, modifier = Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(domain, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("点按可加入白名单", fontSize = 14.sp, lineHeight = 18.sp, color = c.textMuted)
                                }
                                HxChevron()
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
            HxSection {
                HxCard {
                    Text("拦截强度", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = c.text)
                    Spacer(Modifier.height(8.dp))
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
        }

        item(key = "sources") {
            HxSection {
                HxGroup {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("规则源", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                        Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(c.accent)
                            .clickable(enabled = busy == null) { perform("update") { vm.filters.updateRules() } }
                            .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (busy == "update") { HxSpinner(13.dp, c.onAccent); Spacer(Modifier.width(5.dp)) }
                            Text("立即更新", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
                        }
                    }
                    val sources = snapshot?.sources.orEmpty()
                    if (sources.isEmpty()) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { HxSpinner() }
                    sources.forEachIndexed { index, source ->
                        if (index > 0) HxDivider(16.dp)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(settingsLineIcon(if (source.name.contains("AdGuard", ignoreCase = true)) Icons.Rounded.Shield else Icons.Rounded.Description), null,
                                tint = if (source.lastError.isNotBlank()) c.warn else c.text, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(source.name, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                                Text(buildString {
                                    append("${adblockCount(source.count.toLong())} 条 · ")
                                    append(if (source.lastSuccess > 0) "更新于 ${HxFormat.ago(source.lastSuccess)}" else "使用内置快照")
                                    if (source.lastError.isNotBlank()) append("\n上次更新失败：${source.lastError}")
                                }, fontSize = 14.sp, lineHeight = 18.sp, color = c.textMuted)
                            }
                            HxSwitch(source.enabled, { on -> perform("source") { vm.filters.setRuleSource(source.id, on).ifBlank { "${source.name} 已${if (on) "开启" else "关闭"}" } } }, enabled = busy == null)
                        }
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
            HxSection() {
                HxGroup(title = "代理关闭时") {
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

    if (showHelp) {
        HxSheet(onDismiss = { showHelp = false }, title = "广告过滤说明") {
            Text(
                "应用流量会先经过应用直连与明确白名单，再匹配广告规则 REJECT，之后才进入普通配置分流与兜底。\n\n代理运行时，独立 DNS 过滤会自动暂停，避免两套过滤链同时接管。",
                style = MaterialTheme.typography.bodyMedium,
                color = Hx.colors.textMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    addDomain?.let { allow ->
        HxFormDialog(
            title = if (allow) "添加白名单" else "添加黑名单",
            compactPills = true, widthFraction = .67f,
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
            message = "$domain\n及其子域名将不再被拦截。",
            confirmLabel = "加入",
            onConfirm = {
                whitelistCandidate = null
                perform("domain") { vm.filters.changeDomain(domain, true, true).ifBlank { "已加入白名单" } }
            },
            onDismiss = { whitelistCandidate = null },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DomainList(title: String, domains: List<String>, tone: HxTone, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    val c = Hx.colors
    HxSection {
        HxGroup {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, lineHeight = 24.sp, color = c.text,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "添加", tint = c.text, modifier = Modifier.size(20.dp)) }
            }
            if (domains.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall, color = c.textFaint,
                modifier = Modifier.padding(start = 14.dp, bottom = 12.dp))
            else androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                domains.take(200).forEach { domain -> key(domain) {
                    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(c.surfaceMuted).padding(start = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(domain, style = MaterialTheme.typography.bodySmall, color = c.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 230.dp))
                        IconButton(onClick = { onRemove(domain) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, "移除 $domain", tint = c.textMuted, modifier = Modifier.size(14.dp))
                        }
                    }
                } }
            }
            if (domains.size > 200) Text("仅显示前 200 条，共 ${domains.size} 条", style = MaterialTheme.typography.bodySmall,
                color = c.textMuted, modifier = Modifier.padding(14.dp))
        }
    }
}
@Composable
private fun VerifyRow(label: String, ok: Boolean?, detail: String, neutralFalse: Boolean = false) {
    val c = Hx.colors
    val tint by animateColorAsState(when (ok) { true -> c.good; false -> if (neutralFalse) c.textFaint else c.warn; null -> c.textFaint }, tween(HxMotion.Medium), label = "verifyTint")
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 17.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = ok,
                transitionSpec = {
                    (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .5f)).togetherWith(fadeOut(tween(100)))
                },
                label = "verifyIcon",
            ) { state ->
                when (state) {
                    true -> Icon(Icons.Rounded.CheckCircle, null, tint = tint, modifier = Modifier.size(24.dp))
                    false -> Icon(if (neutralFalse) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = tint, modifier = Modifier.size(24.dp))
                    null -> HxDot(tint, 9.dp)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            Text(detail, fontSize = 14.sp, lineHeight = 18.sp, color = c.textMuted)
        }
    }
}

private fun adblockCount(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

@Composable
private fun AdblockHeading(title: String) {
    Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Hx.colors.text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun AdblockMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, color = Hx.colors.textMuted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(3.dp))
        Text(value, style = HxNumberStyle.copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
            color = if (value == "—") Hx.colors.textFaint else Hx.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
