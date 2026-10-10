package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeCoreUnsupportedCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomeNotice
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRollingText
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.HomeVerticalDivider
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.ui.HetuStaggerState
import io.github.xgl34222220.hetu.ui.ht

/**
 * 广告过滤 (工具 › 广告过滤). Stateless. One scrolling page, top to bottom:
 * status → 运行链验证 → 最近拦截 → 拦截强度 → 规则源 → 白名单 → 黑名单 → 代理关闭时.
 *
 * The status card has five states ([ToolsAdStatus]); its colours cross-fade between them, the
 * shield breathes while protecting, and the three figures roll when they change. 最近拦截 only
 * shows while protecting. The help sheet and the three dialogs are hosted by the route; their
 * contents are below.
 *
 * @param scroll hoisted so the route (and previews) can position the page.
 */
@Composable
internal fun ToolsAdblockScreen(
    state: ToolsAdblockState,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onSwitchToRuleMode: (() -> Unit)?,
    onPickRecent: (String) -> Unit,
    onLevelChange: (ToolsAdLevel) -> Unit,
    onUpdate: () -> Unit,
    onSourceChange: (ToolsAdSource, Boolean) -> Unit,
    onAddDomain: (allow: Boolean) -> Unit,
    onRemoveDomain: (domain: String, allow: Boolean) -> Unit,
    onStandaloneDnsChange: (Boolean) -> Unit,
    onCnameChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    onProbe: (() -> Unit)? = null,
) {
    val ready = state.load is ToolsLoad.Ready
    val idle = ready && !state.busy
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "广告过滤",
        onBack = onBack,
        modifier = modifier,
        subtitle = "在 Mihomo 内按域名拦截广告与追踪",
        refreshing = state.refreshing,
        onRefresh = if (idle) onRefresh else null,
        scroll = scroll,
        actions = {
            HomeIconButton(ToolsFeatureIcons.CircleHelp, "说明", onHelp)
            HomeIconButton(HomeIcons.RefreshCw, "重新检测", onRefresh, enabled = idle && !state.refreshing, spinning = state.refreshing)
        },
    ) {
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading(cards = 4)
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.ShieldBan, "广告过滤状态读取失败", subtitle = load.message) {
                ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> if (!state.coreRoutesAds) {
                // A core without rule routing (Hysteria 2): ad rules cannot apply, so the page says so
                // once instead of showing switches that do nothing. The proxy-off DNS filter still works.
                HomeCoreUnsupportedCard(
                    state.coreLabel, "广告过滤 · 规则拦截",
                    "${state.coreLabel} 是单服务器客户端，所有流量直接发往同一台服务器，没有规则分流，广告规则无法生效。代理关闭时的独立 DNS 过滤不受影响。",
                    Modifier.homeEnter(stagger, 0), icon = ToolsFeatureIcons.ShieldOff,
                )
                ProxyOffCard(state, idle, onStandaloneDnsChange, onCnameChange, Modifier.homeEnter(stagger, 1))
            } else {
                StatusCard(state, idle, onEnabledChange, onSwitchToRuleMode, Modifier.homeEnter(stagger, 0))
                if (state.coreLiveVerify) ChainCard(state, Modifier.homeEnter(stagger, 1), onProbe)
                else HomeCoreUnsupportedCard(
                    state.coreLabel, "运行链验证 · 热更新过滤规则",
                    "广告规则在启动时写入 ${state.coreLabel} 配置并按规则拦截；${state.coreLabel} 不提供 Mihomo 的规则读取接口，无法实时验证运行链，修改规则在重启代理后生效。",
                    Modifier.homeEnter(stagger, 1), icon = ToolsFeatureIcons.ShieldAlert,
                )
                Column(Modifier.homeEnter(stagger, 2)) {
                    // 最近拦截 brings its own gap, so it can unfold without a jump.
                    HomeReveal(state.effective && state.recent.isNotEmpty()) { RecentCard(state.recent, onPickRecent, Modifier.padding(bottom = HomeDims.gap)) }
                    LevelCard(state, idle, onLevelChange, Modifier)
                }
                SourcesCard(state, idle, onUpdate, onSourceChange, Modifier.homeEnter(stagger, 3))
                DomainCard("白名单（永不拦截）", "添加白名单", state.allow, idle, stagger, 4, onAdd = { onAddDomain(true) }, onRemove = { onRemoveDomain(it, true) })
                DomainCard("黑名单（额外拦截）", "添加黑名单", state.block, idle, stagger, 5, onAdd = { onAddDomain(false) }, onRemove = { onRemoveDomain(it, false) })
                ProxyOffCard(state, idle, onStandaloneDnsChange, onCnameChange, Modifier.homeEnter(stagger, 6))
            }
        }
    }
}

@Composable
private fun ProxyOffCard(state: ToolsAdblockState, idle: Boolean, onStandaloneDnsChange: (Boolean) -> Unit, onCnameChange: (Boolean) -> Unit, modifier: Modifier) {
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardTitle("代理关闭时")
        ToolsSwitchRow("独立 DNS 过滤", state.standaloneDns, onStandaloneDnsChange, icon = ToolsFeatureIcons.Globe, subtitle = "代理未运行时用本地 VPN 继续过滤广告", enabled = idle)
        HomeRowDivider(start = HomeRowDims.textStart)
        ToolsSwitchRow("CNAME 追踪防护", state.cnameProtection, onCnameChange, icon = ToolsFeatureIcons.Shield, subtitle = "拦截伪装成正常域名的追踪 CNAME", enabled = idle)
    }
}

private class StatusLook(val icon: ImageVector, val tint: Color, val text: Color, val soft: Color, val title: String, val body: String)

@Composable
private fun statusLook(state: ToolsAdblockState): StatusLook {
    val c = LocalHomeColors.current
    // Applied by a core that cannot report its chain: say what is known, not 「运行链未确认」.
    if (state.coreManaged) return StatusLook(
        ToolsFeatureIcons.ShieldCheck, c.good, c.goodText, c.goodSoft, ht("已随核心启动"),
        ht("广告域名在 ${state.coreLabel} 中按规则拦截；\n该核心不支持实时验证。"),
    )
    return when (state.status) {
        ToolsAdStatus.Protecting -> StatusLook(ToolsFeatureIcons.ShieldCheck, c.good, c.goodText, c.goodSoft, ht("保护中"), ht("广告域名直接 REJECT，\n其余流量照常分流。"))
        ToolsAdStatus.WrongMode -> StatusLook(
            ToolsFeatureIcons.ShieldAlert, c.warn, c.warnText, c.warnSoft,
            ht("当前为${state.modeLabel.ifBlank { "非规则" }}模式"), ht("广告规则只在「规则」模式下生效。"),
        )
        ToolsAdStatus.Waiting -> StatusLook(ToolsFeatureIcons.Shield, c.t2, c.t1, c.sunken, ht("已开启"), ht("启动代理后自动验证运行链。"))
        ToolsAdStatus.Unverified -> StatusLook(ToolsFeatureIcons.ShieldAlert, c.warn, c.warnText, c.warnSoft, ht("运行链未确认"), ht("尚未确认规则模式及广告规则加载；下拉刷新后重试验证。"))
        ToolsAdStatus.Off -> StatusLook(ToolsFeatureIcons.ShieldOff, c.t3, c.t2, c.sunken, ht("已关闭"), ht("代理仅负责转发，不执行广告规则。"))
    }
}

@Composable
private fun StatusCard(state: ToolsAdblockState, idle: Boolean, onEnabledChange: (Boolean) -> Unit, onSwitchToRuleMode: (() -> Unit)?, modifier: Modifier) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val look = statusLook(state)
    val tint by animateColorAsState(look.tint, HomeMotion.fade(motion, 320), label = "tools-ad-tint")
    val titleColor by animateColorAsState(look.text, HomeMotion.fade(motion, 320), label = "tools-ad-title")
    val soft by animateColorAsState(look.soft, HomeMotion.fade(motion, 320), label = "tools-ad-soft")
    val number by animateColorAsState(if (state.enabled) c.accent else c.t3, HomeMotion.fade(motion), label = "tools-ad-number")
    // A slow ring leaves the shield while protection is live. Drawn outside the disc's bounds.
    val wave = if (motion && state.effective) {
        val transition = rememberInfiniteTransition(label = "tools-ad-breath")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(HomeMotion.BreathMs, easing = LinearEasing), RepeatMode.Restart), label = "tools-ad-wave")
    } else null
    HomeCard(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 18.dp, end = 16.dp, top = 20.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).drawBehind {
                    val phase = wave?.value
                    if (phase != null) drawCircle(tint.copy(alpha = .22f * (1f - phase)), size.minDimension / 2f * (1f + .34f * phase))
                }.background(soft, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(look.icon, null, Modifier.size(38.dp), tint = tint) }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(look.title, color = titleColor, style = HomeType.heroStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(look.body, color = c.t2, style = HomeRowSubStyle)
            }
            Spacer(Modifier.width(10.dp))
            ToolsSwitch(state.enabled, onEnabledChange, enabled = idle, label = "广告过滤")
        }
        HomeReveal(state.status == ToolsAdStatus.WrongMode) {
            HomeNotice(
                ht("当前模式不会经过规则，广告过滤不会生效"), HomeIcons.TriangleAlert,
                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp),
                actionLabel = if (onSwitchToRuleMode != null) "切到规则" else null, onAction = onSwitchToRuleMode,
            )
        }
        HomeDivider(inset = HomeRowDims.start)
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Metric("有效规则", "%,d".format(state.ruleCount), number, Modifier.weight(1f))
            HomeVerticalDivider(Modifier.height(34.dp))
            Metric("本次拦截", "%,d".format(state.shownHits), number, Modifier.weight(1f))
            HomeVerticalDivider(Modifier.height(34.dp))
            Metric("白名单", state.allow.size.toString(), number, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    val c = LocalHomeColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ht(label), color = c.t2, style = HomeType.note.copy(fontWeight = FontWeight.Medium), maxLines = 1)
        HomeRollingText(value, color, HomeType.metric.copy(fontSize = HomeType.sheetTitle.fontSize), alignment = Alignment.Center)
    }
}

@Composable
private fun ChainCard(state: ToolsAdblockState, modifier: Modifier, onProbe: (() -> Unit)? = null) {
    val running = state.enabled && state.proxyRunning
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardTitle("运行链验证")
        CheckRow(state.enabled && state.ruleCount > 0, ht("本地规则库"), "%,d ".format(state.ruleCount) + ht("条有效规则"))
        HomeRowDivider(start = HomeRowDims.textStart)
        CheckRow(state.enabled && state.startupInjected, ht("启动配置注入"), ht(if (state.enabled && state.startupInjected) "hetu-adblock 已写入运行副本" else "当前启动副本没有广告 provider"))
        HomeRowDivider(start = HomeRowDims.textStart)
        CheckRow(running && state.controllerLoaded, ht("Mihomo 规则链"), state.chainNote.takeIf { running && it.isNotBlank() }
            ?: ht(if (running && state.controllerLoaded) "核心已加载 REJECT 规则" else if (running) "核心尚未加载广告规则" else "等待代理启动"))
        HomeRowDivider(start = HomeRowDims.textStart)
        if (onProbe != null) {
            val probe = state.probe
            ToolsRow(
                AnnotatedString(ht("拦截实测")),
                icon = if (probe?.ok == true && !state.probing) HomeIcons.CircleCheck else ToolsFeatureIcons.CircleDashed,
                iconTint = if (probe?.ok == true && !state.probing) LocalHomeColors.current.good else if (probe?.ok == false && !state.probing) LocalHomeColors.current.bad else LocalHomeColors.current.t3,
                subtitle = when {
                    state.probing -> ht("正在向广告域名发送一次请求…")
                    probe != null -> probe.detail
                    running -> ht("向已拦截域名发一次请求，读取核心的实际判定")
                    else -> ht("代理启动后可实测")
                },
                compact = true,
                trailing = {
                    if (state.probing) HomeSpinner(size = 18.dp, color = LocalHomeColors.current.accent)
                    else if (running) ToolsButton("实测", onProbe, kind = HomeButtonKind.Soft, height = 36.dp) else Unit
                },
            )
            HomeRowDivider(start = HomeRowDims.textStart)
        }
        CheckRow(state.effective && state.hits > 0, ht("实际拦截"), "%,d ".format(state.shownHits) + ht("次"))
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, detail: String) {
    val c = LocalHomeColors.current
    val tint by animateColorAsState(if (ok) c.good else c.t3, HomeMotion.fade(LocalHomeMotionEnabled.current, 260), label = "tools-ad-check")
    ToolsRow(
        AnnotatedString(title),
        icon = if (ok) HomeIcons.CircleCheck else ToolsFeatureIcons.CircleDashed,
        iconTint = tint,
        subtitle = detail, compact = true,
    )
}

@Composable
private fun RecentCard(recent: List<String>, onPick: (String) -> Unit, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardTitle("最近拦截")
        recent.forEachIndexed { index, domain ->
            if (index > 0) HomeRowDivider(start = HomeRowDims.textStart)
            ToolsRow(
                AnnotatedString(domain), icon = ToolsFeatureIcons.Ban, iconTint = c.bad,
                subtitle = ht("点按可加入白名单"), compact = true, onClick = { onPick(domain) }, trailing = { ToolsChevron() },
            )
        }
    }
}

@Composable
private fun LevelCard(state: ToolsAdblockState, idle: Boolean, onLevelChange: (ToolsAdLevel) -> Unit, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardTitle("拦截强度")
        Column(Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolsSegmented<ToolsAdLevel?>(
                options = ToolsAdLevel.entries.map { it to it.label },
                selected = state.level,
                onSelect = { if (it != null) onLevelChange(it) },
                enabled = idle,
                track = if (c.dark) c.sunken else c.bg,
                height = 48.dp,
            )
            Text(ht(state.note), color = c.t2, style = HomeType.note)
        }
    }
}

@Composable
private fun SourcesCard(state: ToolsAdblockState, idle: Boolean, onUpdate: () -> Unit, onSourceChange: (ToolsAdSource, Boolean) -> Unit, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardTitle("规则源") {
            ToolsButton(
                if (state.updating) "更新中" else "立即更新", onUpdate, Modifier.padding(end = 8.dp),
                kind = HomeButtonKind.Primary, enabled = idle || state.updating, loading = state.updating, height = 40.dp,
            )
        }
        if (state.sources.isEmpty()) Text(ht("没有可用的规则源。"), Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 16.dp), color = c.t2, style = HomeType.note)
        state.sources.forEachIndexed { index, source ->
            if (index > 0) HomeRowDivider(start = HomeRowDims.textStart)
            ToolsSwitchRow(
                source.name, source.enabled, { onSourceChange(source, it) },
                rawContent = true,
                icon = if (source.id.contains("adguard", ignoreCase = true)) ToolsFeatureIcons.Shield else ToolsIcons.FileText,
                subtitle = "%,d ".format(source.count) + ht("条") + " · " + if (state.updating && source.enabled) ht("正在更新…") else source.meta,
                enabled = idle,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DomainCard(title: String, addLabel: String, domains: List<String>, idle: Boolean, stagger: HetuStaggerState, index: Int, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, index)) {
        ToolsCardTitle(title) { HomeIconButton(ToolsIcons.Plus, addLabel, onAdd, enabled = idle, glyph = 26.dp) }
        Box(Modifier.fillMaxWidth().padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 18.dp)) {
            if (domains.isEmpty()) {
                Text(ht("暂无"), color = c.t3, style = HomeType.note)
            } else {
                ToolsWrap { domains.forEach { domain -> ToolsChip(domain, { onRemove(domain) }, enabled = idle) } }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheet                                                               */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsAdblockHelpSheetContent(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val closeLabel = ht("关闭")
    ToolsSheetContent(title = "广告过滤说明", onClose = onClose, modifier = modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(); true })
    }) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(ht("应用流量会先经过应用直连与明确白名单，再匹配广告规则 REJECT，之后才进入普通配置分流与兜底。"), color = c.t2, style = HomeType.body)
            Text(ht("代理运行时，独立 DNS 过滤会自动暂停，避免两套过滤链同时接管。"), color = c.t2, style = HomeType.body)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog cards                                                        */
/* ------------------------------------------------------------------ */

/** 添加白名单 / 添加黑名单. The error line unfolds under the field after a failed 添加. */
@Composable
internal fun ToolsAddDomainDialogCard(
    overlay: ToolsAdOverlay.AddDomain,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        title = if (overlay.allow) "添加白名单" else "添加黑名单",
        text = "输入域名，匹配它及其所有子域名，\n例如 example.com。",
        confirmLabel = "添加", confirmLoading = overlay.saving,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    ) { ToolsField("域名", overlay.draft, onDraftChange, placeholder = "example.com", monospace = true, keyboardType = KeyboardType.Uri, error = overlay.error, enabled = !overlay.saving, clearable = true) }
}

/** Reached by tapping a row of 最近拦截. */
@Composable
internal fun ToolsConfirmAllowDialogCard(overlay: ToolsAdOverlay.ConfirmAllow, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    ToolsDialogCard(
        title = "加入白名单？", confirmLabel = "加入", confirmLoading = overlay.saving,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    ) {
        Text(overlay.domain, Modifier.fillMaxWidth(), color = c.t1, style = HomeType.label.copy(fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(ht("及其子域名将不再被拦截。"), Modifier.fillMaxWidth().padding(top = 6.dp), color = c.t2, style = ToolsType.dialogText, textAlign = TextAlign.Center)
    }
}
