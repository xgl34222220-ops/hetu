package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeBanner
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsHairline as HomeDivider
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.tools.ToolsSegmented as HomeSegmented
import io.github.xgl34222220.hetu.tools.ToolsSheetContent as HomeSheetContent
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.tools.ToolsTopBar as HomeTopBar
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.HomeVerticalDivider
import io.github.xgl34222220.hetu.home.LocalHomeColors

/**
 * 广告过滤 (工具 › 广告过滤). Stateless. One scrolling page, top to bottom:
 * status → 运行链验证 → 最近拦截 → 拦截强度 → 规则源 → 白名单 → 黑名单 → 代理关闭时.
 *
 * - Page 45 is the top of the page; page 44 is the same page scrolled to 拦截强度.
 * - Pages 46 (sheet) and 47–49 (dialogs) are hosted by the route; their contents are below.
 *
 * The status card has four states ([ToolsAdStatus]); 最近拦截 only shows while protecting.
 *
 * @param scroll hoisted so the route (and the page-44 preview) can position the page.
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
) {
    val c = LocalHomeColors.current
    val ready = state.load is ToolsLoad.Ready
    val idle = ready && !state.busy
    Column(modifier.fillMaxSize().background(c.bg)) {
        HomeTopBar(title = "广告过滤", onBack = onBack, subtitle = "在 Mihomo 内按域名拦截广告与追踪") {
            HomeIconButton(ToolsFeatureIcons.CircleHelp, "说明", onHelp)
            HomeIconButton(HomeIcons.RefreshCw, "重新检测", onRefresh, enabled = idle)
        }
        ToolsPullRefresh(state.refreshing, if (idle) onRefresh else null, Modifier.fillMaxSize()) {
          Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            when (val load = state.load) {
                ToolsLoad.Loading -> ToolsLoading()
                is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.ShieldBan, "广告过滤状态读取失败", subtitle = load.message) {
                    HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
                }
                ToolsLoad.Ready -> {
                    StatusCard(state, idle, onEnabledChange, onSwitchToRuleMode)
                    ChainCard(state)
                    if (state.effective && state.recent.isNotEmpty()) RecentCard(state.recent, onPickRecent)
                    LevelCard(state, idle, onLevelChange)
                    SourcesCard(state, idle, onUpdate, onSourceChange)
                    DomainCard("白名单（永不拦截）", "添加白名单", state.allow, idle, onAdd = { onAddDomain(true) }, onRemove = { onRemoveDomain(it, true) })
                    DomainCard("黑名单（额外拦截）", "添加黑名单", state.block, idle, onAdd = { onAddDomain(false) }, onRemove = { onRemoveDomain(it, false) })
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("代理关闭时", minHeight = 42.dp, topPadding = 0.dp)
                        ToolsSwitchRow("独立 DNS 过滤", state.standaloneDns, onStandaloneDnsChange, icon = ToolsFeatureIcons.Globe, subtitle = ht("代理未运行时用本地 VPN 继续过滤广告"), enabled = idle, subtitleStyle = HomeType.rowSub.copy(fontSize = 12.sp, lineHeight = 17.sp), minHeight = 51.dp, verticalPadding = 5.dp)
                        ToolsSwitchRow("CNAME 追踪防护", state.cnameProtection, onCnameChange, icon = ToolsFeatureIcons.Shield, subtitle = ht("拦截伪装成正常域名的追踪 CNAME"), enabled = idle, subtitleStyle = HomeType.rowSub.copy(fontSize = 12.sp, lineHeight = 17.sp), minHeight = 51.dp, verticalPadding = 5.dp)
                    }
                }
            }
        }
      }
    }
}

private class StatusLook(val icon: ImageVector, val tint: Color, val soft: Color, val title: String, val text: String)

@Composable
private fun statusLook(state: ToolsAdblockState): StatusLook {
    val c = LocalHomeColors.current
    return when (state.status) {
        ToolsAdStatus.Protecting -> StatusLook(ToolsIcons.AdblockProtectingPdf45, c.good, c.goodSoft, "保护中", "广告域名直接 REJECT，\n其余流量照常分流。")
        ToolsAdStatus.WrongMode -> StatusLook(ToolsIcons.AdblockWrongModePdf03B01, c.warn, c.warnSoft, "当前为${state.modeLabel.ifBlank { "非规则" }}模式", "广告规则只在「规则」模式下生效。")
        ToolsAdStatus.Waiting -> StatusLook(ToolsFeatureIcons.Shield, c.t2, c.sunken, "已开启", "启动代理后自动验证运行链。")
        ToolsAdStatus.Unverified -> StatusLook(ToolsFeatureIcons.ShieldAlert, c.warn, c.warnSoft, "运行链未确认", "尚未确认规则模式及广告规则加载；下拉刷新后重试验证。")
        ToolsAdStatus.Off -> StatusLook(ToolsFeatureIcons.ShieldOff, c.t3, c.sunken, "已关闭", "代理仅负责转发，不执行广告规则。")
    }
}

@Composable
private fun StatusCard(state: ToolsAdblockState, idle: Boolean, onEnabledChange: (Boolean) -> Unit, onSwitchToRuleMode: (() -> Unit)?) {
    val c = LocalHomeColors.current
    val look = statusLook(state)
    val number = if (state.enabled) c.accent else c.t3
    val referenceStatus = state.status == ToolsAdStatus.Protecting || state.status == ToolsAdStatus.WrongMode
    val statusCircleSize = if (referenceStatus) 76.dp else 56.dp
    val statusIconSize = if (referenceStatus) 44.dp else 28.dp
    HomeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(statusCircleSize).background(look.soft, CircleShape), contentAlignment = Alignment.Center) {
                Icon(look.icon, null, Modifier.size(statusIconSize), tint = look.tint)
            }
            Column(Modifier.weight(1f)) {
                Text(ht(look.title), color = look.tint, style = HomeType.heroStatus, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ht(look.text), color = c.t2, style = HomeType.rowSub)
            }
            ToolsSwitch(state.enabled, onEnabledChange, enabled = idle, label = "广告过滤")
        }
        if (state.status == ToolsAdStatus.WrongMode) {
            HomeBanner(
                "当前模式不会经过规则，广告过滤不会生效", HomeIcons.TriangleAlert,
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                actionLabel = if (onSwitchToRuleMode != null) "切到规则" else null, onAction = onSwitchToRuleMode,
            )
        }
        HomeDivider(Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Metric("有效规则", "%,d".format(state.ruleCount), number, Modifier.weight(1f))
            HomeVerticalDivider(Modifier.height(32.dp))
            Metric("本次拦截", "%,d".format(state.shownHits), number, Modifier.weight(1f))
            HomeVerticalDivider(Modifier.height(32.dp))
            Metric("白名单", state.allow.size.toString(), number, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, color: Color, modifier: Modifier) {
    val c = LocalHomeColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(ht(label), color = c.t2, style = HomeType.caption, maxLines = 1)
        Text(value, Modifier.padding(top = 2.dp), color = color, style = HomeType.metric, maxLines = 1)
    }
}

@Composable
private fun ChainCard(state: ToolsAdblockState) {
    val running = state.enabled && state.proxyRunning
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardTitle("运行链验证")
        CheckRow(state.enabled && state.ruleCount > 0, "本地规则库", "${state.ruleCount} 条有效规则")
        HomeDivider(Modifier.padding(start = 52.dp, end = 16.dp))
        CheckRow(state.enabled && state.startupInjected, "启动配置注入", if (state.enabled && state.startupInjected) "hetu-adblock 已写入运行副本" else "当前启动副本没有广告 provider")
        HomeDivider(Modifier.padding(start = 52.dp, end = 16.dp))
        CheckRow(running && state.controllerLoaded, "Mihomo 规则链", if (running && state.controllerLoaded) "核心已加载 REJECT 规则" else if (running) "核心尚未加载广告规则" else "等待代理启动")
        HomeDivider(Modifier.padding(start = 52.dp, end = 16.dp))
        CheckRow(state.effective && state.hits > 0, "实际拦截", "${state.shownHits} 次")
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, detail: String) {
    val c = LocalHomeColors.current
    ToolsRow(
        AnnotatedString(title),
        icon = if (ok) ToolsIcons.AdblockCheckPdf45 else ToolsFeatureIcons.CircleDashed,
        iconTint = if (ok) c.good else c.t3,
        subtitle = detail, compact = true,
    )
}

@Composable
private fun RecentCard(recent: List<String>, onPick: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardTitle("最近拦截")
        recent.forEachIndexed { index, domain ->
            if (index > 0) HomeDivider(Modifier.padding(start = 52.dp, end = 16.dp))
            ToolsRow(
                AnnotatedString(domain), icon = ToolsFeatureIcons.Ban, iconTint = c.bad,
                subtitle = ht("点按可加入白名单"), compact = true, onClick = { onPick(domain) }, trailing = { ToolsChevron() },
            )
        }
    }
}

@Composable
private fun LevelCard(state: ToolsAdblockState, idle: Boolean, onLevelChange: (ToolsAdLevel) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardTitle("拦截强度", minHeight = 42.dp, topPadding = 0.dp)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CompositionLocalProvider(LocalHomeColors provides c.copy(surface = if (c.dark) c.sunken else Color(0xFFF1F1FB))) {
                HomeSegmented<ToolsAdLevel?>(
                    options = ToolsAdLevel.entries.map { it to it.label },
                    selected = state.level,
                    onSelect = { if (it != null) onLevelChange(it) },
                    enabled = idle,
                    referenceVisualInset = 3.5.dp,
                    referenceOuterPadding = 2.dp,
                )
            }
            Text(state.note, color = c.t2, style = HomeType.note)
        }
    }
}

@Composable
private fun SourcesCard(state: ToolsAdblockState, idle: Boolean, onUpdate: () -> Unit, onSourceChange: (ToolsAdSource, Boolean) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardTitle("规则源") {
            HomeButton(if (state.updating) "更新中" else "立即更新", onUpdate, Modifier.padding(end = 10.dp), kind = HomeButtonKind.Primary, enabled = idle || state.updating, loading = state.updating, visualHeight = 29.dp, visualCornerRadius = 7.dp, textStyle = HomeType.button.copy(fontSize = 12.sp, lineHeight = 18.sp), horizontalPadding = 12.dp)
        }
        if (state.sources.isEmpty()) Text(ht("没有可用的规则源。"), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color = c.t2, style = HomeType.note)
        state.sources.forEach { source ->
            ToolsSwitchRow(
                source.name, source.enabled, { onSourceChange(source, it) },
                rawContent = true,
                icon = if (source.id.contains("adguard", ignoreCase = true)) ToolsFeatureIcons.Shield else ToolsIcons.AdblockDocumentPdf44,
                subtitle = "%,d 条 · ".format(source.count) + if (state.updating && source.enabled) "正在更新…" else source.meta,
                enabled = idle,
                minHeight = 60.dp,
            )
        }
        Box(Modifier.height(6.dp))
    }
}

@Composable
private fun DomainCard(title: String, addLabel: String, domains: List<String>, idle: Boolean, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardTitle(title, topPadding = 0.dp) { HomeIconButton(ToolsIcons.Plus, addLabel, onAdd, enabled = idle, tint = c.t1) }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            if (domains.isEmpty()) {
                Text(ht("暂无"), color = c.t3, style = HomeType.note)
            } else {
                ToolsWrap { domains.forEach { domain -> ToolsChip(domain, { onRemove(domain) }, enabled = idle, textStyle = HomeType.bodySmall.copy(fontWeight = FontWeight.SemiBold)) } }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheet (page 46)                                                     */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsAdblockHelpSheetContent(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val closeLabel = ht("关闭")
    HomeSheetContent(title = "广告过滤说明", modifier = modifier.semantics {
        customActions = listOf(CustomAccessibilityAction(closeLabel) { onClose(); true })
    }) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(ht("应用流量会先经过应用直连与明确白名单，再匹配广告规则 REJECT，之后才进入普通配置分流与兜底。"), color = c.t2, style = ToolsType.dialogText)
            Text(ht("代理运行时，独立 DNS 过滤会自动暂停，避免两套过滤链同时接管。"), color = c.t2, style = ToolsType.dialogText)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog cards (pages 47–49)                                          */
/* ------------------------------------------------------------------ */

/** Pages 47 (白名单) and 48 (黑名单). The error line appears under the field after a failed 添加. */
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
        confirmLabel = "添加", confirmLoading = overlay.saving, neutralCancel = true,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    ) { ToolsField("域名", overlay.draft, onDraftChange, placeholder = "example.com", monospace = true, keyboardType = KeyboardType.Uri, error = overlay.error, clearable = !overlay.saving) }
}

/** Page 49: reached by tapping a row of 最近拦截. */
@Composable
internal fun ToolsConfirmAllowDialogCard(overlay: ToolsAdOverlay.ConfirmAllow, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    ToolsDialogCard(
        title = "加入白名单？", confirmLabel = "加入", confirmLoading = overlay.saving, neutralCancel = true,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    ) {
        Text(overlay.domain, Modifier.fillMaxWidth(), color = c.t2, style = HomeType.mono.copy(fontFamily = FontFamily.Default, letterSpacing = .8.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(ht("及其子域名将不再被拦截。"), Modifier.fillMaxWidth().padding(top = 4.dp), color = c.t2, style = ToolsType.dialogText, textAlign = TextAlign.Center)
    }
}
