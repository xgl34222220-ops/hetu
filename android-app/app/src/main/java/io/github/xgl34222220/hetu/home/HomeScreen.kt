package io.github.xgl34222220.hetu.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.ht
import java.util.Locale

/**
 * 首页 (tab root). Stateless: everything shown comes from [state], every tap goes out through
 * [actions] or one of the navigation lambdas.
 *
 * Top to bottom, as in the concept: status card with the large ring, run controls, traffic mode
 * (while running), pending-restart notice, current node, direct probe, then a 2 × 2 grid of
 * WAN / speed / subscription / resources. A soft glow in the status colour sits behind the
 * top of the page and cross-fades when the proxy changes state.
 *
 * @param contentPadding bottom padding must clear the floating dock (default 116 dp).
 * @param motion false keeps every animated piece at its resting state.
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
    // The 1 s traffic sample and the resource samples only feed two cards. Every other card
    // gets this view without them (and with uptime at the minute it displays), kept as the same
    // instance while it is equal, so those cards skip the per-second recomposition — the main
    // source of periodic frame drops while scrolling 首页.
    val calmValue = state.withoutLiveSamples()
    val calm = remember(calmValue) { calmValue }
    val scroll = rememberScrollState()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val lifted by remember(scroll) { derivedStateOf { scroll.value > 6 } }
    val glass = rememberHomeBarGlass(lifted)
    val live = state.status.isLive
    val animate = motion && LocalHomeMotionEnabled.current
    val stagger = rememberHomeStagger()
    val glyph = state.homeHeroGlyphMode()
    val glow by animateColorAsState(
        when (glyph) {
            HomeGlyphMode.On -> c.accent
            HomeGlyphMode.BusyPower, HomeGlyphMode.BusyCheck -> c.warn
            HomeGlyphMode.Attention -> if (state.connection.health == HomeConnectionHealth.ControllerUnavailable) c.bad else c.warn
            HomeGlyphMode.Off, HomeGlyphMode.Unconfirmed -> c.t3
        },
        HomeMotion.fade(animate, 520), label = "home-glow",
    )

    HomeRefreshBox(
        refreshing = state.ipRefreshing,
        onRefresh = actions.onRefreshIp,
        label = "刷新首页状态",
        modifier = modifier.fillMaxSize().homeDiffuseCanvas(),
        indicatorPadding = PaddingValues(top = statusTop + HomeDims.barHeight),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .homeGlassSource(glass)
                .verticalScroll(scroll)
                .drawBehind {
                    // Respect OLED black: a page glow would otherwise tint its empty pixels.
                    if (c.dark && c.bg == Color.Black) return@drawBehind
                    // Status glow: strongest behind the status card, gone by the middle of the page.
                    val reach = 340.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to glow.copy(alpha = if (c.dark) .13f else .10f),
                            1f to glow.copy(alpha = 0f),
                            startY = 0f, endY = reach,
                        ),
                        size = Size(size.width, reach),
                    )
                }
                .padding(contentPadding)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = statusTop + HomeDims.barHeight),
        ) {
            HomeHeroCard(calm, actions, animate, Modifier.homeEnter(stagger, 0))
            HomeControlCard(calm, actions, Modifier.padding(top = HomeDims.gap).homeEnter(stagger, 1))
            if (state.status.isLive && state.connection.health == HomeConnectionHealth.ControllerUnavailable) {
                HomeNotice(state.connection.controllerError.ifBlank { ht("控制接口异常 · 连接状态未确认") }, HomeIcons.TriangleAlert,
                    Modifier.padding(top = 10.dp), tone = HomeTone.Bad, actionLabel = "面板", onAction = actions.onOpenNode)
            } else if (state.status.isLive && state.connection.health == HomeConnectionHealth.TakeoverDegraded && state.connection.runtimeMessage.isNotBlank()) {
                HomeNotice(state.connection.runtimeMessage, HomeIcons.TriangleAlert, Modifier.padding(top = 10.dp), tone = HomeTone.Warn)
            }

            HomeReveal(live) {
                HomeSegmented(
                    options = HomeProxyMode.entries.map { it to it.label },
                    selected = state.proxyMode,
                    onSelect = actions.onProxyModeChange,
                    modifier = Modifier.padding(top = 10.dp),
                    enabled = !state.status.isBusy,
                    style = HomeSegmentStyle.Soft,
                    track = c.surface,
                    height = 44.dp,
                    corner = 22.dp,
                    textStyle = HomeType.button,
                )
            }
            HomeReveal(state.status is HomeStatus.PendingRestart) {
                HomeNotice(
                    ht("设置已修改，重启后生效"), HomeIcons.TriangleAlert, Modifier.padding(top = 10.dp),
                    actionLabel = "立即重启", onAction = actions.onRestart,
                )
            }

            HomeNodeCard(calm, actions.onOpenNode, Modifier.padding(top = HomeDims.gap).homeEnter(stagger, 2))
            HomeProbeCard(calm, onOpenTargets, actions.onProbe, Modifier.padding(top = HomeDims.gap).homeEnter(stagger, 3))

            Row(
                Modifier.padding(top = HomeDims.gap).fillMaxWidth().height(IntrinsicSize.Min).homeEnter(stagger, 4),
                horizontalArrangement = Arrangement.spacedBy(HomeDims.gap),
            ) {
                HomeNetworkCard(calm, Modifier.weight(1f).fillMaxHeight(), onOpenIpDetail, actions.onNetSideChange)
                HomeSpeedCard(state, Modifier.weight(1f).fillMaxHeight(), onOpenSpeedSource)
            }
            Row(
                Modifier.padding(top = HomeDims.gap).fillMaxWidth().height(IntrinsicSize.Min).homeEnter(stagger, 5),
                horizontalArrangement = Arrangement.spacedBy(HomeDims.gap),
            ) {
                HomeSubscriptionCard(calm, Modifier.weight(1f).fillMaxHeight(), actions.onOpenSubscription)
                HomeResourceCard(state, Modifier.weight(1f).fillMaxHeight(), onOpenResource)
            }
            Spacer(Modifier.height(8.dp))
        }

        // The title never moves; the bar behind it turns to glass once cards slide beneath.
        HomeBarBackdrop(glass, Modifier.fillMaxWidth().height(statusTop + HomeDims.barHeight))
        Box(Modifier.fillMaxWidth().padding(top = statusTop).height(HomeDims.barHeight), contentAlignment = Alignment.Center) {
            Text(ht("河图"), color = c.t1, style = HomeType.barTitle)
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Text helpers                                                        */
/* ------------------------------------------------------------------ */

internal fun String.fill(vararg args: Any): String = String.format(Locale.ROOT, this, *args)

/**
 * [HomeUiState] without the values that change every second (throughput, resource samples)
 * and with uptime floored to the minute [homeUptimeText] shows. Every text drawn from it is
 * identical to the full state; only 网速 and 资源占用 read the live values.
 */
internal fun HomeUiState.withoutLiveSamples(): HomeUiState = copy(
    status = when (val s = status) {
        is HomeStatus.Running -> HomeStatus.Running(s.uptimeSeconds / 60L * 60L)
        is HomeStatus.PendingRestart -> HomeStatus.PendingRestart(s.uptimeSeconds / 60L * 60L)
        else -> s
    },
    uploadBytesPerSecond = null,
    downloadBytesPerSecond = null,
    resource = HomeResource(),
)

/** [HomeFormat.uptime] in the app language. */
@Composable
internal fun homeUptimeText(seconds: Long): String = when {
    seconds < 60 -> ht("少于 1 分钟")
    seconds < 3600 -> ht("%d 分钟").fill(seconds / 60)
    seconds < 86400 -> ht("%d 小时 %d 分钟").fill(seconds / 3600, seconds % 3600 / 60)
    else -> ht("%d 天 %d 小时").fill(seconds / 86400, seconds % 86400 / 3600)
}

/* ------------------------------------------------------------------ */
/*  Status card                                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeHeroCard(state: HomeUiState, actions: HomeActions, motion: Boolean, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val status = state.status
    val glyph = state.homeHeroGlyphMode()
    val title = when (status) {
        is HomeStatus.Running, is HomeStatus.PendingRestart -> ht("运行中")
        HomeStatus.Starting -> ht("正在启动")
        HomeStatus.Restarting -> ht("正在重启")
        HomeStatus.Stopping -> ht("正在停止")
        HomeStatus.NotRunning, is HomeStatus.StartFailed -> ht("未运行")
    }
    val line = when (status) {
        is HomeStatus.Running -> ht("已运行 %s").fill(homeUptimeText(status.uptimeSeconds))
        is HomeStatus.PendingRestart -> ht("已运行 %s").fill(homeUptimeText(status.uptimeSeconds))
        HomeStatus.Starting, HomeStatus.Restarting, HomeStatus.Stopping -> state.statusDetail?.takeIf { it.isNotBlank() } ?: ht("请稍候…")
        HomeStatus.NotRunning, is HomeStatus.StartFailed -> ht("尚未启动代理")
    }
    val tint by animateColorAsState(
        when (glyph) {
            HomeGlyphMode.On -> c.accent
            HomeGlyphMode.BusyPower, HomeGlyphMode.BusyCheck -> c.warn
            HomeGlyphMode.Attention -> if (state.connection.health == HomeConnectionHealth.ControllerUnavailable) c.bad else c.warn
            HomeGlyphMode.Off, HomeGlyphMode.Unconfirmed -> if (c.dark) c.t2 else lerp(c.t2, c.t3, .45f)
        },
        HomeMotion.fade(motion, 360), label = "home-hero-tint",
    )
    Box(
        modifier
            .fillMaxWidth()
            .testTag("home-status-material")
            .heightIn(min = 134.dp)
            .homeGlassPanel(HomeDims.cardShape, c.hero)
            .drawBehind {
                // A faint sheen from the top-left corner gives the tinted card some depth.
                drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = if (c.dark) .05f else .34f), Color.Transparent), Offset.Zero, Offset(size.width * .7f, size.height)))
            },
    ) {
        HomeStatusGlyph(glyph, Modifier.align(Alignment.BottomEnd).offset(x = 21.dp, y = 26.dp).size(116.dp), animate = motion, tint = tint)
        Column(Modifier.padding(start = 20.dp, top = 12.dp, end = 104.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                HomeStatusDot(tint, size = 10.dp, pulse = glyph == HomeGlyphMode.On)
                HomeRollingText(title, c.t1, HomeType.heroStatus, alignment = Alignment.CenterStart)
            }
            HomeRollingText(line, c.t1, HomeType.heroLine, Modifier.padding(top = 3.dp), alignment = Alignment.CenterStart)
            if (status is HomeStatus.Running || status is HomeStatus.PendingRestart) {
                Text(ht(state.connection.health.caption), Modifier.padding(top = 5.dp, bottom = 3.dp).testTag("home-connection-caption"), color = c.t1,
                    style = HomeType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            HeroLink("${state.core}  ·  ${state.runMode}", "打开基础代理配置") { haptics(HomeHaptic.Tap); actions.onOpenBasicSettings() }
            if (state.config.isNotBlank()) HeroLink(state.config, "打开配置管理") { haptics(HomeHaptic.Tap); actions.onOpenConfigs() }
        }
    }
}

/** One tappable line of the status card. The chevron is the only hint that it leads somewhere. */
@Composable
private fun HeroLink(text: String, label: String, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    Row(
        Modifier.homeTap(onClickLabel = ht(label), role = Role.Button, onClick = onClick).heightIn(min = 25.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, Modifier.weight(1f, fill = false), color = c.t1, style = HomeType.heroLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(HomeIcons.ChevronRight, null, Modifier.size(15.dp), tint = c.t1.copy(alpha = .38f))
    }
}

/** A running core becomes a healthy local glyph only after both observations succeeded. */
internal fun HomeUiState.homeHeroGlyphMode(): HomeGlyphMode {
    if (status !is HomeStatus.Running && status !is HomeStatus.PendingRestart) return status.glyphMode()
    return when (connection.health) {
        HomeConnectionHealth.LocalReady, HomeConnectionHealth.Verified -> HomeGlyphMode.On
        HomeConnectionHealth.Unconfirmed -> HomeGlyphMode.Unconfirmed
        HomeConnectionHealth.ControllerUnavailable, HomeConnectionHealth.TakeoverDegraded -> HomeGlyphMode.Attention
    }
}

/* ------------------------------------------------------------------ */
/*  Run controls                                                        */
/* ------------------------------------------------------------------ */

private enum class ControlSet { Idle, Starting, Stopping, Live }

private fun controlSetOf(status: HomeStatus): ControlSet = when (status) {
    HomeStatus.NotRunning, is HomeStatus.StartFailed -> ControlSet.Idle
    HomeStatus.Starting -> ControlSet.Starting
    HomeStatus.Stopping -> ControlSet.Stopping
    is HomeStatus.Running, is HomeStatus.PendingRestart, HomeStatus.Restarting -> ControlSet.Live
}

/**
 * 启动, or 重载 · 停止 · 重启. The two layouts hand over with a short cross-fade, and whichever
 * action is in flight keeps its label and shows a ring beside it while the others dim.
 */
@Composable
private fun HomeControlCard(state: HomeUiState, actions: HomeActions, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val busy = state.status.isBusy
    val restarting = state.status is HomeStatus.Restarting
    HomeCard(modifier.fillMaxWidth()) {
        AnimatedContent(
            targetState = controlSetOf(state.status),
            transitionSpec = {
                if (!motion) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(260, easing = HomeMotion.Decelerate), initialScale = .96f))
                    .togetherWith(fadeOut(tween(120)))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.Center,
            label = "home-controls",
        ) { set ->
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                when (set) {
                    ControlSet.Idle -> ControlAction(ht("启动"), actions.onStart, c.accent)
                    ControlSet.Starting -> ControlAction(ht("启动中"), {}, c.warn, enabled = false, loading = true, loadingLeads = true)
                    ControlSet.Stopping -> ControlAction(ht("停止中"), {}, c.warn, enabled = false, loading = true, loadingLeads = true)
                    ControlSet.Live -> {
                        ControlAction(ht("重载"), actions.onReload, c.accent, enabled = !busy)
                        ControlDivider()
                        ControlAction(ht("停止"), actions.onStop, c.badText, enabled = !busy)
                        ControlDivider()
                        ControlAction(ht("重启"), actions.onRestart, c.warnText, enabled = !busy, loading = restarting)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlDivider() {
    Box(Modifier.width(1.dp).height(24.dp).background(LocalHomeColors.current.line2))
}

@Composable
private fun RowScope.ControlAction(
    text: String,
    onClick: () -> Unit,
    color: Color,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLeads: Boolean = false,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val tint by animateColorAsState(
        when {
            enabled -> color
            loading -> if (loadingLeads) c.t3 else lerp(color, c.surface, .30f)
            else -> c.t3
        },
        HomeMotion.fade(LocalHomeMotionEnabled.current), label = "home-control-tint",
    )
    Row(
        Modifier.weight(1f).fillMaxHeight().homeTap(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Confirm); onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (loading && loadingLeads) HomeSpinner(size = 24.dp, color = color, strokeWidth = 3.dp)
        Text(text, color = tint, style = HomeType.control, maxLines = 1)
        if (loading && !loadingLeads) HomeSpinner(size = 18.dp, color = color, strokeWidth = 2.5.dp)
    }
}

/* ------------------------------------------------------------------ */
/*  Current node                                                        */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeNodeCard(state: HomeUiState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    val direct = live && state.proxyMode == HomeProxyMode.Direct
    val node = state.node.takeIf { live && !direct }
    val subtitle = when {
        direct -> ht("直连模式，流量不经过节点")
        node != null -> "${node.group} · ${node.name}"
        else -> ht("节点信息未确认")
    }
    val clickable = node != null || state.status is HomeStatus.Starting
    HomeCard(modifier.fillMaxWidth(), onClick = if (clickable) onOpen else null, clickLabel = "查看策略与节点") {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HomeServerGlyph(Modifier.size(26.dp), if (node != null || direct) c.t1 else c.t2)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ht("当前节点"), color = c.t1, style = HomeType.rowTitle, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (node != null) HomeFlag(HomeRegions.codeOf(node.name), height = 14.dp)
                    Text(subtitle, Modifier.weight(1f, fill = false), color = c.t2, style = HomeType.rowSub.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (node != null) {
                Spacer(Modifier.width(8.dp))
                HomeDelayText(node.delayMs)
            }
            if (clickable) Icon(HomeIcons.ChevronRight, null, Modifier.padding(start = 4.dp).size(22.dp), tint = c.t2)
        }
    }
}

/** The concept's solid server mark: two rounded slabs, each with a status light and a slot. */
@Composable
internal fun HomeServerGlyph(modifier: Modifier = Modifier, color: Color = LocalHomeColors.current.t1) {
    val cut = LocalHomeColors.current.surface
    Canvas(modifier) {
        val slab = size.height * .40f
        val gap = size.height * .12f
        val top = (size.height - slab * 2f - gap) / 2f
        val radius = CornerRadius(slab * .34f, slab * .34f)
        for (i in 0..1) {
            val y = top + i * (slab + gap)
            drawRoundRect(color, Offset(0f, y), Size(size.width, slab), radius)
            drawCircle(cut, slab * .15f, Offset(size.width * .19f, y + slab / 2f))
            drawRoundRect(cut, Offset(size.width * .36f, y + slab * .40f), Size(size.width * .46f, slab * .20f), CornerRadius(slab * .1f, slab * .1f))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Direct probe (本机直测)                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun HomeProbeCard(state: HomeUiState, onOpenTargets: () -> Unit, onProbe: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    HomeCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = HomeDims.cardPadding, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(ht("本机直测"), color = c.t1, style = HomeType.section)
                Text(
                    ht("由河图进程直接请求，未指定代理节点；结果不代表其他应用的代理路径。"),
                    Modifier.padding(top = 2.dp), color = c.t2, style = HomeType.caption,
                )
            }
            HomeIconButton(HomeIcons.SlidersHorizontal, "测速目标", onOpenTargets, tint = if (live) c.t1 else c.t2)
            HomeIconButton(HomeIcons.RefreshCw, "重新测速", { if (!state.probing) onProbe() }, enabled = live, spinning = state.probing && live, tint = if (state.probing) c.accent else c.t1)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 14.dp)) {
            state.probes.forEachIndexed { index, probe ->
                if (index > 0) Box(Modifier.padding(vertical = 6.dp).width(1.dp).fillMaxHeight().background(c.line2))
                Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(probe.name, color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.heightIn(min = 30.dp).alpha(if (state.probing && live) .45f else 1f), contentAlignment = Alignment.Center) {
                        val delay = probe.delayMs.takeIf { live }
                        if (delay == null) Text(HomeFormat.Dash, color = c.t2, style = HomeType.metric)
                        else HomeDelayText(delay)
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Metric cards (2 × 2)                                                */
/* ------------------------------------------------------------------ */

@Composable
private fun MetricCard(
    modifier: Modifier,
    onClick: () -> Unit,
    clickLabel: String,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    HomeCard(modifier, onClick = onClick, clickLabel = clickLabel) {
        Column(Modifier.padding(start = HomeDims.cardPadding, end = HomeDims.cardPadding, top = 12.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, content = header)
            content()
        }
    }
}

@Composable
private fun MetricTitle(text: String) {
    Text(ht(text), color = LocalHomeColors.current.t1, style = HomeType.cardLabel, maxLines = 1)
}

/** Label on the left, live value on the right. A null [value] is an honest dash, never a zero. */
@Composable
private fun MetricLine(
    label: String,
    value: String?,
    valueStyle: TextStyle = HomeType.value,
    placeholder: (@Composable RowScope.() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ht(label), color = c.t2, style = HomeType.rowSub.copy(fontSize = 15.sp), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Spacer(Modifier.weight(1f))
        when {
            value != null -> {
                if (leading != null) { leading(); Spacer(Modifier.width(6.dp)) }
                HomeRollingText(value, c.t1, valueStyle)
            }
            placeholder != null -> placeholder()
            else -> Text(HomeFormat.Dash, color = c.t2, style = HomeType.value)
        }
    }
}

/** Long addresses (IPv6) drop to a smaller size instead of being cut. */
private fun addressStyle(text: String?): TextStyle = when {
    text == null || text.length <= 15 -> HomeType.value
    text.length <= 22 -> HomeType.value.copy(fontSize = 14.sp, lineHeight = 20.sp)
    else -> HomeType.value.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HomeNetworkCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit, onSide: (HomeNetSide) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val live = state.status.isLive
    val wan = state.netSide == HomeNetSide.Wan
    MetricCard(
        modifier = modifier, onClick = onOpen, clickLabel = "查看 IP 详情",
        header = {
            // The title is its own switch: the bold side is shown, the quiet one swaps to it.
            HomeNetSide.entries.forEach { side ->
                val active = side == state.netSide
                val tint by animateColorAsState(if (active) c.t1 else c.t3, HomeMotion.fade(LocalHomeMotionEnabled.current), label = "home-net-side")
                Text(
                    side.label,
                    (if (active) Modifier else Modifier.homeTap(onClickLabel = ht("切换到 %s").fill(side.label), role = Role.Button) { haptics(HomeHaptic.Tick); onSide(side) })
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                    color = tint,
                    style = if (active) HomeType.cardLabel else HomeType.cardLabel.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            HomePill(ht("详情"), height = 22.dp)
        },
    ) {
        if (wan) {
            val ip = state.wan.ip.takeIf { live }
            MetricLine("IP", ip, addressStyle(ip))
            when {
                live && state.wan.region != null ->
                    MetricLine("地区", state.wan.region, leading = { HomeFlag(state.wan.countryCode, height = 15.dp) })
                live && state.wan.state == HomeWanState.Failed ->
                    MetricLine("地区", null, placeholder = { Text(ht("查询失败"), color = c.badText, style = HomeType.rowSub.copy(fontSize = 15.sp), maxLines = 1) })
                else -> MetricLine("地区", null, placeholder = {
                    Icon(HomeIcons.Hourglass, null, Modifier.size(15.dp), tint = c.t2)
                    Spacer(Modifier.width(5.dp))
                    Text(ht(if (live) "正在查询" else "等待连接"), color = c.t2, style = HomeType.rowSub.copy(fontSize = 15.sp), maxLines = 1)
                })
            }
        } else {
            MetricLine("IP", state.lan.ip, addressStyle(state.lan.ip))
            MetricLine("接口", state.lan.iface)
        }
    }
}

@Composable
private fun HomeSpeedCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    MetricCard(
        modifier = modifier, onClick = onOpen, clickLabel = "选择网速数据来源",
        header = {
            MetricTitle("网速")
            Spacer(Modifier.weight(1f))
            Text(ht(state.speedSource.short), color = c.t3, style = HomeType.caption, maxLines = 1)
        },
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
        modifier = modifier, onClick = onOpen, clickLabel = "查看订阅",
        header = {
            MetricTitle("订阅")
            Spacer(Modifier.weight(1f))
            Text(sub?.remainingPercent?.let { ht("剩余 %d%%").fill(it) } ?: ht("总量未知"), color = c.t2, style = HomeType.rowSub.copy(fontSize = 15.sp), maxLines = 1)
        },
    ) {
        MetricLine("已用", sub?.let { HomeFormat.bytes(it.usedBytes) })
        MetricLine("总量", sub?.takeIf { it.totalBytes > 0L }?.let { HomeFormat.bytes(it.totalBytes) })
        HomeProgressBar(sub?.usedFraction, Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun HomeResourceCard(state: HomeUiState, modifier: Modifier, onOpen: () -> Unit) {
    val live = state.status.isLive
    val res = state.resource
    val cpu = res.cpuPercent?.takeIf { live && !it.isNaN() }
    MetricCard(
        modifier = modifier, onClick = onOpen, clickLabel = "查看资源占用",
        header = { MetricTitle("资源占用") },
    ) {
        MetricLine("内存", res.memoryBytes?.takeIf { live }?.let(HomeFormat::bytes))
        MetricLine("CPU", cpu?.let(HomeFormat::percent))
        HomeProgressBar(cpu?.let { (it / 100f).coerceIn(.03f, 1f) }, Modifier.padding(top = 10.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets that belong to the home page                                 */
/* ------------------------------------------------------------------ */

/** 网速数据来源: two option cards; picking one applies it and closes the sheet. */
@Composable
internal fun HomeSpeedSourceSheetContent(selected: HomeSpeedSource, onSelect: (HomeSpeedSource) -> Unit, onClose: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeSheetContent(title = "网速数据来源", onClose = onClose) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeSpeedSource.entries.forEach { source ->
                val active = source == selected
                val fill by animateColorAsState(
                    if (active) lerp(if (c.dark) c.sunken else c.bg, c.accent, if (c.dark) .16f else .07f) else if (c.dark) c.sunken else c.bg,
                    HomeMotion.fade(LocalHomeMotionEnabled.current), label = "home-source-fill",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HomeDims.rowMinHeight)
                        .clip(HomeDims.innerShape)
                        .background(fill)
                        .selectable(selected = active, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                            haptics(HomeHaptic.Tick)
                            onSelect(source)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(ht(source.title), color = c.t1, style = HomeType.rowTitle)
                        Text(ht(source.description), color = c.t2, style = HomeType.rowSub)
                    }
                    HomeRadio(active)
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
                Modifier.heightIn(min = HomeDims.touch).clip(HomeDims.controlShape).homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onCopy() }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(HomeIcons.Copy, null, Modifier.size(20.dp), tint = c.accent)
                Text(ht("复制"), color = c.accent, style = HomeType.button)
            }
        },
        footer = {
            HomeButton("查看配置", onViewConfig, Modifier.weight(1f), kind = HomeButtonKind.Soft)
            HomeButton("重新启动", onRetry, Modifier.weight(1f), kind = HomeButtonKind.Primary)
        },
    ) { HomeCodeBox(detail, caption = "错误详情") }
}
