package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun HomeScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val state = vm.state
    val stagger = rememberHxStagger()
    val primaryGroup = remember(state.groups) {
        state.groups.firstOrNull { it.name == "节点选择" }
            ?: state.groups.firstOrNull { it.type.equals("Selector", true) && !it.name.equals("GLOBAL", true) }
            ?: state.groups.firstOrNull()
    }

    HxPage(
        title = "河图",
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        refreshing = vm.refreshing,
        onRefresh = vm::pullRefresh,
        actions = {
            if (state.running) {
                HxBarAction(Icons.Rounded.Refresh, "重载配置", onClick = vm::reload, busy = vm.operation == HxRunOp.Reload)
            }
            HxBarAction(Icons.Rounded.Settings, "设置", onClick = { vm.tab = HxTab.Settings })
        },
    ) {
        item(key = "connection-hub") {
            Box(Modifier.hxEnter(stagger, 0)) { HomeConnectionHub(vm) }
        }
        item(key = "live-network") {
            Box(Modifier.hxEnter(stagger, 1)) { HomeLiveNetwork(vm) }
        }
        item(key = "outbound-node") {
            Box(Modifier.hxEnter(stagger, 2)) { HomeOutboundNode(vm, primaryGroup) }
        }
    }
}

@Composable
private fun HomeConnectionHub(vm: HetuViewModel) {
    val c = Hx.colors
    val state = vm.state
    val running = state.running
    val op = vm.operation
    val aurora = rememberHxAurora(active = running && op == null)
    val status = when (op) {
        HxRunOp.Start -> "正在连接"
        HxRunOp.Stop -> "正在断开"
        HxRunOp.Restart -> "正在重启"
        HxRunOp.Reload -> "正在重载"
        null -> if (running) "代理运行中" else "代理未连接"
    }

    HxSection {
        HxCard(
            brush = aurora,
            padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 17.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    HxDot(if (running) c.good else c.textFaint, 9.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, style = MaterialTheme.typography.titleMedium, color = c.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (running) HxFormat.duration(vm.runtime.elapsedSeconds) else state.config,
                        style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle),
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConnectionToggle(running = running, busy = op != null, onClick = vm::toggle)
            }

            Spacer(Modifier.height(15.dp))
            HxSegmented(
                options = listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
                selected = state.trafficMode.lowercase().ifBlank { "rule" },
                onSelect = vm::setTrafficMode,
                enabled = running && op == null,
            )
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("出口 IP", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    Text(
                        if (running) vm.runtime.wanAddress.ifBlank { "—" } else "—",
                        style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle),
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    if (running) vm.runtime.wanRegion.takeIf { it.isNotBlank() && it != "—" } ?: "经代理出口" else "等待连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    maxLines = 1,
                )
            }
            if (running && vm.settingsRevision >= 0 && vm.settingsPending()) {
                Spacer(Modifier.height(9.dp))
                HxPill("设置待重启", HxTone.Warn)
            }
        }
    }
}

@Composable
private fun ConnectionToggle(running: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()
    val bg by animateColorAsState(
        if (running) c.surfaceMuted else c.accent,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "connection-toggle-bg",
    )
    val fg by animateColorAsState(
        if (running) c.text else c.onAccent,
        spring(stiffness = Spring.StiffnessMediumLow),
        label = "connection-toggle-fg",
    )
    Row(
        Modifier
            .hxPressScale(source, .97f)
            .height(42.dp)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(
                interactionSource = source,
                indication = androidx.compose.foundation.LocalIndication.current,
                enabled = !busy,
            ) {
                haptics.perform(HetuHaptic.Confirm)
                onClick()
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (busy) {
            HxSpinner(15.dp, fg)
        } else {
            Icon(Icons.Rounded.PowerSettingsNew, null, tint = fg, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (running) "断开" else "连接", style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

@Composable
private fun HomeLiveNetwork(vm: HetuViewModel) {
    val c = Hx.colors
    val running = vm.state.running
    val tracked = vm.providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remaining = (total - used).coerceAtLeast(0L)
    val ratio = if (total > 0L) (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    val memory = if (vm.runtime.rssBytes > 0) vm.runtime.rssBytes else vm.state.memoryBytes

    HxSection {
        HxCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时网络", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                HxPill(if (running) "代理运行中" else "未连接", if (running) HxTone.Good else HxTone.Neutral)
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().height(142.dp)) {
                Sparkline(
                    vm.rateHistory.toList(),
                    vm.upHistory.toList(),
                    c.accent,
                    c.textMuted,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(94.dp).graphicsLayer { alpha = if (running) .92f else .38f },
                )
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    HomeRate("下载", vm.downRate, "↓", c.accent, Modifier.weight(1f))
                    HomeRate("上传", vm.upRate, "↑", c.textMuted, Modifier.weight(1f))
                }
            }

            if (total > 0L) {
                Spacer(Modifier.height(5.dp))
                HxProgressBar(ratio, color = c.accent, height = 5.dp)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("已用 " + HxFormat.bytes(used), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                    Spacer(Modifier.weight(1f))
                    Text("剩余 " + HxFormat.bytes(remaining), style = MaterialTheme.typography.bodySmall, color = c.text)
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                HomeMiniStat(
                    "CPU",
                    if (running) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—",
                    Modifier.weight(1f),
                )
                HomeMiniStat(
                    "内存",
                    if (running && memory > 0) HxFormat.bytes(memory) else "—",
                    Modifier.weight(1f),
                )
                HomeMiniStat(
                    "连接",
                    if (running) vm.state.connections.size.toString() else "—",
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HomeRate(
    label: String,
    value: Long,
    arrow: String,
    tint: Color,
    modifier: Modifier,
) {
    val c = Hx.colors
    val parts = HxFormat.speedParts(value)
    val number = parts.first
    val unit = parts.second
    Column(modifier) {
        Text(arrow + " " + label, style = MaterialTheme.typography.labelMedium, color = tint)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HxRollingText(
                number,
                style = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.4).sp),
                color = c.text,
            )
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun HomeMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    val c = Hx.colors
    Row(
        modifier
            .height(30.dp)
            .clip(Hx.pillShape)
            .background(c.surfaceMuted)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        Spacer(Modifier.width(5.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
            color = c.text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeOutboundNode(vm: HetuViewModel, group: ProxyGroupUi?) {
    val c = Hx.colors
    val selected = group?.now.orEmpty()
    val node = group?.nodes?.firstOrNull { it.name == selected }
    val delay = if (selected.isNotBlank()) vm.delays[selected] ?: node?.lastDelay else null

    HxSection("当前出站节点") {
        HxCard(onClick = { vm.tab = HxTab.Proxies }, padding = androidx.compose.foundation.layout.PaddingValues(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceMuted),
                    contentAlignment = Alignment.Center,
                ) {
                    if (group != null) HxGroupIcon(group, Modifier.size(23.dp))
                    else Icon(Icons.Rounded.Public, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        selected.ifBlank { if (vm.state.running) "等待节点信息" else "代理未运行" },
                        style = MaterialTheme.typography.titleMedium,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        group?.name ?: vm.state.config,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (delay != null) {
                    HxPill(HxFormat.delay(delay) + if (delay > 0) " ms" else "", if (delay > 0) HxTone.Good else HxTone.Neutral)
                    Spacer(Modifier.width(5.dp))
                }
                HxChevron()
            }
        }
    }
}

@Composable
private fun CoreDetails(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    val state = vm.state
    val memory = if (vm.runtime.rssBytes > 0) vm.runtime.rssBytes else state.memoryBytes
    val rows = listOf(
        "PID" to (if (state.corePid > 0) state.corePid.toString() else "—"),
        "核心" to state.core,
        "版本" to vm.coreVersion.ifBlank { "—" },
        "运行时长" to HxFormat.duration(vm.runtime.elapsedSeconds),
        "模式" to "${state.mode} · ${state.trafficMode.ifBlank { "rule" }}",
        "配置" to state.config,
        "CPU" to String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent),
        "内存" to (if (memory > 0) HxFormat.bytes(memory) else "—"),
        "活动连接" to state.connections.size.toString(),
        "控制器" to "127.0.0.1:${state.controllerPort}",
        "DNS" to listOf(state.dnsMode.ifBlank { "—" }, if (state.dnsListenerReady) "监听正常" else "监听未就绪").joinToString(" · "),
        "数据面" to if (state.dataPlaneHealthy) "健康" else "未完全通过",
        "守护" to if (state.watchdog) "已启用" else "未启用",
    )
    HxSheet(onDismiss = onDismiss, title = "核心运行详情") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            HxGroup {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HxDivider(16.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(76.dp))
                        Text(value, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/* ---------------------------- status ---------------------------- */

@Composable
private fun StatusCard(vm: HetuViewModel, onDetails: () -> Unit) {
    val c = Hx.colors
    val state = vm.state
    val op = vm.operation
    val running = state.running
    val healthy = running && state.message.isBlank()
    val statusTitle = when (op) {
        HxRunOp.Start -> "正在启动"
        HxRunOp.Stop -> "正在停止"
        HxRunOp.Restart -> "正在重启"
        HxRunOp.Reload -> "正在重载"
        null -> if (running) "代理运行中" else "代理未运行"
    }
    val statusDetail = when {
        op != null -> vm.operationText.ifBlank { "请稍候…" }
        running -> "已运行 ${HxFormat.duration(vm.runtime.elapsedSeconds)} · ${state.config}"
        else -> state.config
    }
    val dotColor by animateColorAsState(
        when {
            op != null -> c.warn
            healthy -> c.good
            running -> c.warn
            else -> c.textFaint
        },
        tween(HxMotion.Medium),
        label = "statusDot",
    )

    HxSection {
        val aurora = rememberHxAurora(active = running && op == null)
        HxCard(
            brush = aurora,
            padding = androidx.compose.foundation.layout.PaddingValues(18.dp),
            onClick = if (running && op == null) onDetails else null,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(dotColor, pulsing = op != null || healthy)
                        Spacer(Modifier.width(8.dp))
                        AnimatedContent(
                            targetState = statusTitle,
                            transitionSpec = { fadeIn(tween(HxMotion.Medium)) togetherWith fadeOut(tween(HxMotion.Short)) },
                            label = "statusTitle",
                        ) { text ->
                            Text(text, style = MaterialTheme.typography.titleLarge, color = c.text)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statusDetail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                PowerButton(running = running, busy = op != null, onClick = vm::toggle)
            }

            AnimatedVisibility(
                visible = running && op == null,
                enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            ) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    HxSegmented(
                        options = listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
                        selected = state.trafficMode.lowercase().ifBlank { "rule" },
                        onSelect = vm::setTrafficMode,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HxButton("重载配置", onClick = vm::reload, icon = Icons.Rounded.Refresh, filled = false, modifier = Modifier.weight(1f))
                        HxButton("重启代理", onClick = vm::restart, icon = Icons.Rounded.RestartAlt, filled = false, modifier = Modifier.weight(1f))
                    }
                }
            }

            val settingsRevision = vm.settingsRevision
            val pending = running && settingsRevision >= 0 && vm.settingsPending()
            AnimatedVisibility(pending && op == null) {
                HxBanner(
                    "网络设置已修改，重启代理后生效",
                    tone = HxTone.Warn,
                    modifier = Modifier.padding(top = 12.dp),
                    actionLabel = "重启",
                    onAction = vm::restart,
                )
            }
            AnimatedVisibility(state.message.isNotBlank() && op == null) {
                HxBanner(state.message, tone = if (running) HxTone.Warn else HxTone.Neutral, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun PulseDot(color: Color, pulsing: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "pulseValue",
    )
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (pulsing) {
            Box(
                Modifier
                    .size(16.dp)
                    .graphicsLayer {
                        scaleX = .5f + pulse
                        scaleY = .5f + pulse
                        alpha = (1f - pulse) * .45f
                    }
                    .clip(CircleShape)
                    .background(color),
            )
        }
        HxDot(color, 9.dp)
    }
}

@Composable
private fun PowerButton(running: Boolean, busy: Boolean, onClick: () -> Unit) {
    ConnectionToggle(running = running, busy = busy, onClick = onClick)
}

/* ---------------------------- traffic ---------------------------- */

@Composable
private fun TrafficCard(vm: HetuViewModel) {
    val c = Hx.colors
    val running = vm.state.running
    HxSection("流量") {
        HxCard {
            Row {
                SpeedFigure("下载", vm.downRate, Icons.Rounded.ArrowDownward, c.accent, Modifier.weight(1f), running)
                SpeedFigure("上传", vm.upRate, Icons.Rounded.ArrowUpward, c.warn, Modifier.weight(1f), running)
            }
            Spacer(Modifier.height(12.dp))
            Sparkline(vm.rateHistory.toList(), vm.upHistory.toList(), c.accent, c.warn, Modifier.fillMaxWidth().height(52.dp))
            Spacer(Modifier.height(14.dp))
            Row {
                HxMetric("总下载", HxFormat.bytes(vm.state.downloadTotal), Modifier.weight(1f))
                HxMetric("总上传", HxFormat.bytes(vm.state.uploadTotal), Modifier.weight(1f))
                HxMetric("连接", if (running) vm.state.connections.size.toString() else "—", Modifier.weight(.8f))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                val memory = if (vm.runtime.rssBytes > 0) vm.runtime.rssBytes else vm.state.memoryBytes
                HxMetric("内存", if (running && memory > 0) HxFormat.bytes(memory) else "—", Modifier.weight(1f))
                HxMetric("CPU", if (running) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—", Modifier.weight(1f))
                HxMetric("运行", if (running) HxFormat.duration(vm.runtime.elapsedSeconds) else "—", Modifier.weight(.8f))
            }
        }
    }
}

@Composable
private fun SpeedFigure(label: String, value: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, modifier: Modifier, active: Boolean) {
    val c = Hx.colors
    val (number, unit) = HxFormat.speedParts(value)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textMuted)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            HxRollingText(
                if (active) number else "0",
                style = MaterialTheme.typography.displaySmall.copy(letterSpacing = (-0.5).sp),
                color = if (active) c.text else c.textFaint,
            )
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Sparkline(down: List<Long>, up: List<Long>, downColor: Color, upColor: Color, modifier: Modifier) {
    val c = Hx.colors
    // Scale transitions smoothly instead of jumping when a spike enters or leaves.
    val targetMax = ((down + up).maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val max by animateFloatAsState(targetMax, tween(HxMotion.Long, easing = HxMotion.Emphasized), label = "sparkMax")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(c.line, Offset(0f, h - 1f), Offset(w, h - 1f), strokeWidth = 1f)
        val step = w / (40 - 1).toFloat()
        fun series(values: List<Long>, color: Color, fillAlpha: Float, width: Float) {
            if (values.size < 2) return
            val startX = w - step * (values.size - 1)
            val line = Path()
            val fill = Path()
            var prevX = 0f
            var prevY = 0f
            values.forEachIndexed { i, v ->
                val x = startX + step * i
                val y = h - 2f - (v / max).coerceIn(0f, 1f) * (h - 8f)
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, h)
                    fill.lineTo(x, y)
                } else {
                    // Smooth curve through the midpoints.
                    val mx = (prevX + x) / 2f
                    line.cubicTo(mx, prevY, mx, y, x, y)
                    fill.cubicTo(mx, prevY, mx, y, x, y)
                }
                prevX = x
                prevY = y
            }
            fill.lineTo(w, h)
            fill.close()
            if (fillAlpha > 0f) drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = fillAlpha), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, radius = width * 1.6f, center = Offset(prevX, prevY))
        }
        series(up, upColor.copy(alpha = .82f), .08f, 1.5.dp.toPx())
        series(down, downColor, .20f, 2.2.dp.toPx())
    }
}

/* ---------------------------- network ---------------------------- */

@Composable
private fun NetworkCard(vm: HetuViewModel) {
    val c = Hx.colors
    val rt = vm.runtime
    val running = vm.state.running
    HxSection("网络") {
        HxGroup {
            val flag = HxFormat.flag(rt.wanCountryCode)
            val wan = if (running && rt.wanAddress.isNotBlank() && rt.wanAddress != "—") rt.wanAddress else "—"
            HxRow(
                "出口 IP",
                subtitle = when {
                    !running -> "代理未运行"
                    rt.wanState == "error" && rt.wanError.isNotBlank() -> rt.wanError
                    rt.wanRegion.isNotBlank() && rt.wanRegion != "—" -> listOf(flag, rt.wanRegion).filter { it.isNotBlank() }.joinToString(" ")
                    else -> "经代理出口探测"
                },
                icon = Icons.Rounded.Public,
            ) {
                Text(wan, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text, maxLines = 1)
            }
            HxDivider()
            HxRow("本机地址", subtitle = rt.lanInterface.takeIf { it.isNotBlank() && it != "—" } ?: "当前网络", icon = Icons.Rounded.Router, iconTint = c.good) {
                Text(rt.lanAddress.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), color = c.text, maxLines = 1)
            }
            HxDivider()
            HxRow(
                "站点延迟",
                subtitle = if (running) "点按重新测试" else "启动代理后可测试",
                icon = Icons.Rounded.Speed,
                iconTint = c.warn,
                enabled = running,
                onClick = vm::measureSites,
            ) {
                if (vm.siteTesting) HxSpinner(16.dp)
                else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyLatencyTargets.load(vm.prefs).forEach { target ->
                        val value = vm.siteDelays[target.name]
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                HxFormat.delay(value),
                                style = MaterialTheme.typography.labelLarge.merge(HxNumberStyle),
                                color = HxFormat.delayColor(value),
                            )
                            Text(target.name, style = MaterialTheme.typography.labelSmall, color = c.textFaint, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------- config ---------------------------- */

@Composable
private fun ConfigCard(vm: HetuViewModel, onOpen: () -> Unit) {
    val c = Hx.colors
    val tracked = vm.providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val expire = tracked.mapNotNull { p -> p.expire.takeIf { it > 0L } }.minOrNull() ?: 0L
    HxSection("配置与订阅") {
        HxCard(onClick = onOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HxIconBadge(Icons.Rounded.Description)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.state.config, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !vm.state.running -> "点按管理配置和订阅"
                            vm.providers.isEmpty() -> "当前配置没有在线订阅"
                            else -> "${vm.providers.size} 个订阅 · ${vm.providers.sumOf { it.nodes.size }} 个节点"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
                if (vm.state.running && vm.providers.isNotEmpty()) {
                    HxBarAction(Icons.Rounded.Sync, "更新全部订阅", onClick = vm::updateAllProviders, busy = vm.providersUpdatingAll)
                } else HxChevron()
            }
            if (total > 0L) {
                Spacer(Modifier.height(14.dp))
                val ratio = (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                HxProgressBar(ratio, if (ratio > .9f) c.bad else if (ratio > .75f) c.warn else c.accent)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("已用 ${HxFormat.bytes(used)} / ${HxFormat.bytes(total)}", style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.weight(1f))
                    Text(HxFormat.expireLabel(expire), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                }
            }
        }
    }
}

/* ---------------------------- adblock ---------------------------- */

@Composable
private fun AdblockCard(vm: HetuViewModel, onOpen: () -> Unit) {
    val c = Hx.colors
    val prefs = vm.prefs
    val enabled = vm.settingsRevision >= 0 && prefs.getBoolean("proxyAdblockChain", true)
    val effective = vm.state.running && prefs.getBoolean("proxyAdblockLastEffective", false)
    val count = prefs.getInt("proxyAdblockLastRuleCount", 0)
    val hits = prefs.getLong("proxyAdblockSessionHits", 0L)
    val error = prefs.getString("proxyAdblockLastError", "").orEmpty()
    val (label, tone) = when {
        !enabled -> "已关闭" to HxTone.Neutral
        !vm.state.running -> "代理启动后生效" to HxTone.Neutral
        error.isNotBlank() -> "未完整加载" to HxTone.Warn
        effective -> "保护中" to HxTone.Good
        else -> "规则已加载 · 非规则模式" to HxTone.Warn
    }
    HxSection("广告过滤") {
        HxCard(onClick = onOpen) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HxIconBadge(Icons.Rounded.Shield, tint = if (effective) c.good else c.textMuted)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("DNS 广告过滤", style = MaterialTheme.typography.titleMedium, color = c.text)
                        Spacer(Modifier.width(8.dp))
                        HxPill(label, tone)
                    }
                    Text(
                        if (enabled) "${HxFormat.count(count.toLong())} 条规则 · 本次拦截 ${HxFormat.count(hits)} 次" else "开启后在代理内拦截广告与追踪域名",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                    )
                }
                HxChevron()
            }
        }
    }
}
