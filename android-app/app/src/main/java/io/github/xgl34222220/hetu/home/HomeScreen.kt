package io.github.xgl34222220.hetu.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 首页 (tab root). Stateless: everything shown comes from [state], every tap goes out through
 * [actions] or one of the navigation lambdas.
 *
 * @param contentPadding bottom padding must clear the floating dock (default 116 dp).
 * @param motion false disables the dot-matrix animation (user "reduce motion" switch).
 */
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onOpenIpDetail: () -> Unit,
    onOpenTargets: () -> Unit,
    onOpenResource: () -> Unit,
    onOpenSpeedSource: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    motion: Boolean = true,
) {
    val c = LocalHomeColors.current
    val scroll = rememberScrollState()
    val collapseAt = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(scroll, collapseAt) { derivedStateOf { scroll.value > collapseAt } }
    val live = state.status.isLive

    Box(modifier.fillMaxSize().background(c.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(contentPadding)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            Box(Modifier.padding(bottom = 4.dp).height(44.dp), contentAlignment = Alignment.CenterStart) {
                Text("河图", color = c.t1, style = HomeType.largeTitle)
            }

            HomeHeroCard(state, actions, motion)

            if (live) {
                HomeSegmented(
                    options = HomeProxyMode.entries.map { it to it.label },
                    selected = state.proxyMode,
                    onSelect = actions.onProxyModeChange,
                    enabled = !state.status.isBusy,
                )
            }
            if (state.status is HomeStatus.PendingRestart) {
                HomeBanner("设置已修改，重启后生效", HomeIcons.TriangleAlert, actionLabel = "立即重启", onAction = actions.onRestart)
            }

            HomeNodeCard(state, actions.onOpenNode)
            HomeProbeCard(state, onOpenTargets, actions.onProbe)

            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                HomeNetworkCard(state, Modifier.weight(1f).fillMaxHeight(), onOpenIpDetail, actions.onNetSideChange)
                HomeSpeedCard(state, Modifier.weight(1f).fillMaxHeight(), onOpenSpeedSource)
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                HomeSubscriptionCard(state, Modifier.weight(1f).fillMaxHeight(), actions.onOpenSubscription)
                HomeResourceCard(state, Modifier.weight(1f).fillMaxHeight(), onOpenResource)
            }
        }

        // Compact centred title that fades in once the large title has scrolled 40 dp.
        val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(HomeMotion.SwitchMs), label = "home-bar")
        if (barAlpha > 0f) {
            Column(Modifier.fillMaxWidth().alpha(barAlpha).background(c.bg.copy(alpha = .94f))) {
                Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).height(HomeDims.barHeight), contentAlignment = Alignment.Center) {
                    Text("河图", color = c.t1, style = HomeType.barTitle)
                }
                HomeDivider()
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Hero: status + 河图 glyph + run controls                             */
/* ------------------------------------------------------------------ */

/** Status dot tone (null = grey), headline and supporting line of the hero card. */
private class HeroCopy(val tone: HomeTone?, val title: String, val subtitle: String)

private fun heroCopy(status: HomeStatus): HeroCopy = when (status) {
    is HomeStatus.Running -> HeroCopy(HomeTone.Good, "运行中", "已运行 ${HomeFormat.uptime(status.uptimeSeconds)}")
    is HomeStatus.PendingRestart -> HeroCopy(HomeTone.Good, "运行中", "已运行 ${HomeFormat.uptime(status.uptimeSeconds)}")
    HomeStatus.Starting -> HeroCopy(HomeTone.Warn, "正在启动", "请稍候…")
    HomeStatus.Restarting -> HeroCopy(HomeTone.Warn, "正在重启", "请稍候…")
    HomeStatus.Stopping -> HeroCopy(HomeTone.Warn, "正在停止", "请稍候…")
    HomeStatus.NotRunning, is HomeStatus.StartFailed -> HeroCopy(null, "未运行", "尚未启动代理")
}

@Composable
private fun HomeHeroCard(state: HomeUiState, actions: HomeActions, motion: Boolean) {
    val c = LocalHomeColors.current
    val copy = heroCopy(state.status)
    val busy = state.status.isBusy
    HomeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 108.dp).padding(start = 16.dp, top = 16.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeStatusDot(copy.tone?.foreground() ?: c.t3)
                    Text(copy.title, color = c.t1, style = HomeType.heroStatus)
                }
                Text(copy.subtitle, Modifier.padding(top = 2.dp), color = c.t2, style = HomeType.bodySmall.copy(fontFeatureSettings = "tnum"))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HomeBadge(state.core, outlined = true, onClick = actions.onOpenBasicSettings)
                    HomeBadge(state.runMode, outlined = true, onClick = actions.onOpenBasicSettings)
                    if (state.config.isNotBlank()) HomeBadge(state.config, Modifier.weight(1f, fill = false), outlined = true, onClick = actions.onOpenConfigs)
                }
            }
            Spacer(Modifier.width(12.dp))
            HeTuDotMatrix(state.status.dotMode(), Modifier.offset(y = (-4).dp), animate = motion)
        }
        HomeDivider()
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            when (state.status) {
                HomeStatus.NotRunning, is HomeStatus.StartFailed ->
                    HeroAction("启动", actions.onStart, color = c.accent, icon = HomeIcons.Power, strong = true)
                HomeStatus.Starting -> HeroAction("启动中", {}, enabled = false, loading = true)
                HomeStatus.Stopping -> HeroAction("停止中", {}, enabled = false, loading = true)
                is HomeStatus.Running, is HomeStatus.PendingRestart, HomeStatus.Restarting -> {
                    HeroAction("重载", actions.onReload, enabled = !busy)
                    HomeVerticalDivider(Modifier.fillMaxHeight())
                    HeroAction("停止", actions.onStop, enabled = !busy, color = c.bad)
                    HomeVerticalDivider(Modifier.fillMaxHeight())
                    HeroAction("重启", actions.onRestart, enabled = !busy, loading = state.status is HomeStatus.Restarting)
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeroAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
    color: Color = LocalHomeColors.current.t1,
    icon: ImageVector? = null,
    strong: Boolean = false,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val tint = if (enabled) color else c.t3
    Row(
        Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (loading) HomeSpinner(size = 16.dp, color = c.t3)
        else if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(text, color = tint, style = if (strong) HomeType.button.copy(fontWeight = HomeType.value.fontWeight) else HomeType.button)
    }
}

/* ------------------------------------------------------------------ */
/*  Current node                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeNodeCard(state: HomeUiState, onOpen: () -> Unit) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    val direct = live && state.proxyMode == HomeProxyMode.Direct
    val node = state.node.takeIf { live && !direct }
    val subtitle = when {
        direct -> "直连模式，流量不经过节点"
        node != null -> "${node.group} · ${node.name}"
        else -> "节点信息未确认"
    }
    val clickable = node != null || state.status is HomeStatus.Starting
    HomeCard(Modifier.fillMaxWidth(), onClick = if (clickable) onOpen else null, clickLabel = "查看策略与节点") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HomeIcons.Server, null, Modifier.size(20.dp), tint = c.t2)
            Column(Modifier.weight(1f)) {
                Text("当前节点", color = c.t1, style = HomeType.rowTitle, maxLines = 1)
                Text(subtitle, color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (node != null) HomeDelayText(node.delayMs)
            if (clickable) Icon(HomeIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.t3)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Direct probe (本机直测)                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeProbeCard(state: HomeUiState, onOpenTargets: () -> Unit, onProbe: () -> Unit) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    HomeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("本机直测", Modifier.weight(1f), color = c.t2, style = HomeType.section)
            HomeIconButton(HomeIcons.SlidersHorizontal, "测速目标", onOpenTargets)
            HomeIconButton(HomeIcons.RefreshCw, "重新测速", onProbe, enabled = live, loading = state.probing)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
            state.probes.forEachIndexed { index, probe ->
                if (index > 0) HomeVerticalDivider(Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(probe.name, color = c.t3, style = HomeType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProbeValue(probe.delayMs.takeIf { live }, state.probing && live)
                }
            }
        }
        Text(
            "由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            color = c.t3, style = HomeType.caption,
        )
    }
}

@Composable
private fun ProbeValue(delayMs: Long?, probing: Boolean) {
    val c = LocalHomeColors.current
    Row(Modifier.height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            probing -> HomeSpinner(size = 13.dp, color = c.t3, strokeWidth = 1.5.dp)
            delayMs == null -> Text(HomeFormat.Dash, color = c.t3, style = HomeType.metric)
            delayMs < 0 -> Text("超时", color = c.bad, style = HomeType.rowTitle)
            else -> {
                Text(delayMs.toString(), Modifier.alignByBaseline(), color = c.t1, style = HomeType.metric)
                Text("ms", Modifier.alignByBaseline().padding(start = 2.dp), color = c.t3, style = HomeType.caption.copy(fontWeight = HomeType.rowTitle.fontWeight))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Metric cards (2 × 2)                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun MetricCard(
    title: String,
    modifier: Modifier,
    onClick: () -> Unit,
    clickLabel: String,
    trailing: @Composable RowScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    val c = LocalHomeColors.current
    HomeCard(modifier, onClick = onClick, clickLabel = clickLabel) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 20.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = c.t2, style = HomeType.section, maxLines = 1)
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String?, muted: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val c = LocalHomeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.t3, style = HomeType.rowSub, maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (leading != null && value != null) { leading(); Spacer(Modifier.width(4.dp)) }
        if (value == null) Text(HomeFormat.Dash, color = c.t3, style = HomeType.value)
        else if (muted) Text(value, color = c.t3, style = HomeType.rowSub, maxLines = 1)
        else Text(value, color = c.t1, style = HomeType.value, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
    }
}

@Composable
private fun HomeNetworkCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit, onSide: (HomeNetSide) -> Unit) {
    val live = state.status.isLive
    val wan = state.netSide == HomeNetSide.Wan
    val other = if (wan) HomeNetSide.Lan else HomeNetSide.Wan
    MetricCard(
        title = state.netSide.label, modifier = modifier, onClick = onOpen, clickLabel = "查看 IP 详情",
        trailing = { HomeBadge(other.label, icon = HomeIcons.Repeat2, onClick = { onSide(other) }) },
    ) {
        if (wan) {
            MetricLine("IP", state.wan.ip.takeIf { live })
            if (live && state.wan.region != null) {
                MetricLine("地区", state.wan.region, leading = { HomeRegionCode(state.wan.countryCode) })
            } else MetricLine("地区", "等待连接", muted = true)
        } else {
            MetricLine("IP", state.lan.ip)
            MetricLine("接口", state.lan.iface)
        }
    }
}

@Composable
private fun HomeSpeedCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    MetricCard(
        title = "网速", modifier = modifier, onClick = onOpen, clickLabel = "选择网速数据来源",
        trailing = { Text(state.speedSource.short, color = c.t3, style = HomeType.note) },
    ) {
        MetricLine("上行", state.uploadBytesPerSecond?.takeIf { live }?.let(HomeFormat::speed))
        MetricLine("下行", state.downloadBytesPerSecond?.takeIf { live }?.let(HomeFormat::speed))
    }
}

@Composable
private fun HomeSubscriptionCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalHomeColors.current
    val sub = state.subscription
    MetricCard(
        title = "订阅", modifier = modifier, onClick = onOpen, clickLabel = "查看订阅",
        trailing = { Text(sub?.remainingPercent?.let { "剩余 $it%" } ?: "总量未知", color = c.t3, style = HomeType.note) },
    ) {
        MetricLine("已用", sub?.let { HomeFormat.bytes(it.usedBytes) })
        MetricLine("总量", sub?.takeIf { it.totalBytes > 0L }?.let { HomeFormat.bytes(it.totalBytes) })
        HomeProgressBar(sub?.usedFraction, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun HomeResourceCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    val res = state.resource
    MetricCard(
        title = "资源占用", modifier = modifier, onClick = onOpen, clickLabel = "查看资源占用",
        trailing = { Icon(HomeIcons.ChevronRight, null, Modifier.size(16.dp), tint = c.t3) },
    ) {
        MetricLine("内存", res.memoryBytes?.takeIf { live }?.let(HomeFormat::bytes))
        MetricLine("CPU", res.cpuPercent?.takeIf { live && !it.isNaN() }?.let(HomeFormat::percent))
        HomeProgressBar(res.cpuPercent?.takeIf { live && !it.isNaN() }?.let { (it / 100f).coerceAtLeast(.02f) }, Modifier.padding(top = 10.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets that belong to the home page                                 */
/* ------------------------------------------------------------------ */

/** 网速数据来源: two radio rows; picking one applies it and closes the sheet. */
@Composable
internal fun HomeSpeedSourceSheetContent(selected: HomeSpeedSource, onSelect: (HomeSpeedSource) -> Unit, onClose: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeSheetContent(title = "网速数据来源", onClose = onClose) {
        HomeCard(Modifier.fillMaxWidth(), background = c.bg) {
            HomeSpeedSource.entries.forEachIndexed { index, source ->
                if (index > 0) HomeDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight)
                        .clickable(role = Role.RadioButton) { haptics(HomeHaptic.Tick); onSelect(source) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(source.title, color = c.t1, style = HomeType.rowTitle)
                        Text(source.description, color = c.t2, style = HomeType.rowSub)
                    }
                    HomeRadio(source == selected)
                }
            }
        }
    }
}

/** 启动失败: the error text can be copied; two exits, open the config or try again. */
@Composable
internal fun HomeStartFailedSheetContent(detail: String, onCopy: () -> Unit, onViewConfig: () -> Unit, onRetry: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeSheetContent(
        title = "启动失败",
        trailing = {
            Row(
                Modifier.heightIn(min = HomeDims.touch).clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onCopy() }.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(HomeIcons.Copy, null, Modifier.size(20.dp), tint = c.accent)
                Text("复制", color = c.accent, style = HomeType.button)
            }
        },
        footer = {
            HomeButton("查看配置", onViewConfig, Modifier.weight(1f))
            HomeButton("重新启动", onRetry, Modifier.weight(1f), kind = HomeButtonKind.Primary)
        },
    ) { HomeCodeBox(detail) }
}
