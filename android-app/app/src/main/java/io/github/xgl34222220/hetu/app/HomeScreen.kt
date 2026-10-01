package io.github.xgl34222220.hetu

import android.content.Intent
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material.icons.rounded.Tune
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val stagger = rememberHxStagger()
    val nav = LocalNav.current
    var showSpeedSource by remember { mutableStateOf(false) }

    HxPage(
        title = "河图",
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        refreshing = vm.refreshing,
        onRefresh = vm::pullRefresh,
        centeredBrandTitle = true,
        canvasColor = Hx.colors.canvas,
    ) {
        item(key = "hero") { Box(Modifier.hxEnter(stagger, 0)) { HomeHero(vm) } }
        item(key = "outbound") {
            val primary = vm.state.groups.firstOrNull { it.name.equals("GLOBAL", true) && vm.state.trafficMode.equals("global", true) }
                ?: vm.state.groups.firstOrNull { it.name != "GLOBAL" && it.now.isNotBlank() }
            Box(Modifier.hxEnter(stagger, 1)) { HomeCurrentNodeSummary(vm, primary) }
        }
        item(key = "latency") { HomeLatencyCard(vm) }
        item(key = "network-information") {
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                HxSection {
                    HomeWanCard(vm, Modifier.fillMaxWidth().testTag("home-address-card")) { nav.push(HxRoute.PublicIp) }
                    Spacer(Modifier.height(12.dp))
                    HomeSpeedCard(vm, Modifier.fillMaxWidth().testTag("home-speed-card")) { showSpeedSource = true }
                }
            } else BentoRow(Modifier.hxEnter(stagger, 2)) {
                HomeWanCard(vm, Modifier.weight(1f).testTag("home-address-card")) { nav.push(HxRoute.PublicIp) }
                HomeSpeedCard(vm, Modifier.weight(1f).testTag("home-speed-card")) { showSpeedSource = true }
            }
        }
        item(key = "capacity-information") {
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                HxSection {
                    HomeSubscriptionCard(vm, Modifier.fillMaxWidth().testTag("home-subscription-card")) { vm.openPanel("providers") }
                    Spacer(Modifier.height(12.dp))
                    HomeResourceCard(vm, Modifier.fillMaxWidth().testTag("home-resources-card")) { nav.push(HxRoute.Resources) }
                }
            } else BentoRow(Modifier.hxEnter(stagger, 3)) {
                HomeSubscriptionCard(vm, Modifier.weight(1f).testTag("home-subscription-card")) { vm.openPanel("providers") }
                HomeResourceCard(vm, Modifier.weight(1f).testTag("home-resources-card")) { nav.push(HxRoute.Resources) }
            }
        }
    }

    if (showSpeedSource) {
        HxChoiceSheet(
            title = "网速数据来源",
            choices = listOf(
                HxChoice("api", "API 模式", "读取 Mihomo 控制器流量，适合查看代理核心吞吐"),
                HxChoice("local", "本地模式", "读取设备本地总流量，适合查看当前网络实际吞吐"),
            ),
            selected = vm.homeSpeedSource,
            onPick = { vm.updateHomeSpeedSource(it); showSpeedSource = false },
            onDismiss = { showSpeedSource = false },
        )
    }
}

/* ---------------------------- bento building blocks ---------------------------- */

@Composable
private fun BentoRow(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** White bento tile with a small header; the whole tile is pressable when [onClick] is set. */
@Composable
private fun Bento(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (onClick != null) Modifier.hxPressScale(source, .97f) else Modifier)
            .clip(Hx.cardShape)
            .background(c.surface)
            .then(if (c.dark) Modifier.border(0.5.dp, c.line.copy(alpha = .62f), Hx.cardShape) else Modifier)
            .then(
                if (onClick != null) Modifier.combinedClickableCompat(
                    source = source,
                    enabled = true,
                    onClick = { haptics.perform(HetuHaptic.Tap); onClick() },
                    onLongClick = onLongClick?.let { l -> { haptics.perform(HetuHaptic.LongPress); l() } },
                ) else Modifier,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun BentoLine(label: String, value: String, valueColor: Color = Hx.colors.text, modifier: Modifier = Modifier) {
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val number: @Composable () -> Unit = {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle).copy(fontSize = 17.sp, lineHeight = 23.sp),
            fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 2)
    }
    if (large) Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.textMuted)
        number()
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 27.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.textMuted)
            Spacer(Modifier.weight(1f))
            number()
        }
    }
}

/* ---------------------------- hero ---------------------------- */

@Composable
private fun HomeHero(vm: HetuViewModel) {
    val c = Hx.colors
    val state = vm.state
    val running = state.running
    val op = vm.operation
    var controls by rememberSaveable { mutableStateOf(false) }
    val largeFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val status = when (op) {
        HxRunOp.Start -> "正在启动"
        HxRunOp.Stop -> "正在停止"
        HxRunOp.Restart -> "正在重启"
        HxRunOp.Reload -> "正在重载"
        null -> if (running) "运行中" else "未运行"
    }
    val tint by animateColorAsState(if (op != null) c.warn else if (running) c.accent else c.textMuted,
        tween(HxMotion.Medium), label = "heroTint")
    HxSection {
        Box(Modifier.fillMaxWidth().clip(Hx.cardShape)
            .background(if (c.dark) c.accentSoft else Color(0xFFE1DFFF))
            .clickable(enabled = running && op == null) { controls = !controls }
            .semantics { contentDescription = "运行控制" }) {
            if (!largeFont) Icon(if (running) Icons.Rounded.TaskAlt else Icons.Rounded.PowerSettingsNew,
                null, tint = tint.copy(alpha = .88f), modifier = Modifier.align(Alignment.BottomEnd)
                    .offset(x = 22.dp, y = 25.dp).size(134.dp))
            Column(Modifier.fillMaxWidth().padding(20.dp).padding(end = if (largeFont) 0.dp else 62.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HxDot(tint, 10.dp)
                    Spacer(Modifier.width(8.dp))
                    AnimatedContent(targetState = status,
                        transitionSpec = { fadeIn(tween(HxMotion.Short)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                        label = "heroStatus") { text ->
                        Text(text, style = MaterialTheme.typography.headlineMedium.copy(fontSize = 25.sp, lineHeight = 31.sp),
                            fontWeight = FontWeight.Bold, color = tint)
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(when {
                    op != null -> vm.operationText.ifBlank { "请稍候…" }
                    running && vm.runtime.elapsedSeconds > 0 -> "已运行 " + HxFormat.duration(vm.runtime.elapsedSeconds)
                    running -> "运行时长未确认"
                    else -> "尚未启动代理"
                }, style = MaterialTheme.typography.bodyLarge, color = c.text)
                Spacer(Modifier.height(5.dp))
                Text(state.core + " · " + state.mode, style = MaterialTheme.typography.bodyLarge, color = c.text)
                Spacer(Modifier.height(5.dp))
                Text(state.config, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        HxCard(padding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                if (running) {
                    HeroAction("重载", c.accent, op == HxRunOp.Reload, op == null,
                        Modifier.weight(1f).semantics { contentDescription = "重载配置" }, vm::reload)
                    Box(Modifier.padding(vertical = 16.dp).fillMaxHeight().width(.8.dp).background(c.line))
                }
                HeroAction(if (running) "停止" else "启动", if (running) c.bad else c.accent,
                    op == HxRunOp.Start || op == HxRunOp.Stop, op == null, Modifier.weight(1f), vm::toggle)
                if (running) {
                    Box(Modifier.padding(vertical = 16.dp).fillMaxHeight().width(.8.dp).background(c.line))
                    HeroAction("重启", c.warn, op == HxRunOp.Restart, op == null,
                        Modifier.weight(1f).semantics { contentDescription = "重启核心" }, vm::restart)
                }
            }
        }
        AnimatedVisibility(visible = controls && running,
            enter = fadeIn(tween(HxMotion.Short)) + expandVertically(tween(HxMotion.Medium)),
            exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium))) {
            Column(Modifier.padding(top = 12.dp)) {
                HxSegmented(options = listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
                    selected = state.trafficMode.lowercase(), onSelect = vm::setTrafficMode, enabled = op == null)
            }
        }
        if (running && vm.settingsRevision >= 0 && vm.settingsPending() && op == null)
            HxBanner("设置已修改，重启后生效", tone = HxTone.Warn, modifier = Modifier.padding(top = 10.dp))
        if (state.message.isNotBlank() && op == null)
            HxBanner(state.message, tone = if (running) HxTone.Warn else HxTone.Neutral, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun HomeCurrentNodeSummary(vm: HetuViewModel, group: ProxyGroupUi?) {
    val c = Hx.colors
    val known = vm.state.running && group != null && group.now.isNotBlank()
    HxSection {
        HxCard(onClick = { vm.openPanel("proxies") }, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Row(Modifier.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Route, null, tint = c.accent, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(17.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前节点", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(if (known) "${group!!.name} · ${group.now}" else "节点信息未确认",
                        style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (known && androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.3f) {
                    Spacer(Modifier.width(8.dp))
                    HxPill("已选择", HxTone.Accent)
                }
            }
        }
    }
}

@Composable
private fun HomeFlowTile(vm: HetuViewModel, upload: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val c = Hx.colors
    val local = vm.homeSpeedSource == "local"
    val rate = if (upload) { if (local) vm.localUpRate else vm.upRate } else { if (local) vm.localDownRate else vm.downRate }
    val history = if (upload) vm.upHistory.toList() else vm.rateHistory.toList()
    val tint = if (upload) c.accent else c.good
    HxCard(modifier = modifier.fillMaxHeight().testTag(if (upload) "home-upload-card" else "home-download-card"), onClick = onOpen, padding = PaddingValues(14.dp)) {
        Text(if (upload) "上传速度" else "下载速度", style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        Spacer(Modifier.height(6.dp))
        if (!local && vm.state.running && history.size > 1) {
            HxTrafficChart(history, emptyList(), tint, tint, Modifier.fillMaxWidth().height(24.dp))
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(if (local) "本地实时速率" else if (vm.state.running) "正在采样" else "等待连接", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(hxMetricText(if (vm.state.running || local) HxFormat.speed(rate) else "—"),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp).merge(HxNumberStyle), color = tint, maxLines = 2)
    }
}

@Composable
private fun HomeResourceTile(vm: HetuViewModel, cpu: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val c = Hx.colors
    val running = vm.state.running
    val memory = vm.runtime.rssBytes.takeIf { it > 0 } ?: vm.state.memoryBytes
    HxCard(modifier = modifier.fillMaxHeight().testTag(if (cpu) "home-cpu-card" else "home-memory-card"), onClick = onOpen, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (cpu) Icons.Rounded.Speed else Icons.Rounded.Memory, null, tint = c.accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(if (cpu) "CPU 占用" else "内存占用", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Text(hxMetricText(if (!running || cpu && !vm.cpuSampleAvailable) "—" else if (cpu) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent)
                    else if (memory > 0) HxFormat.bytes(memory) else "—"), style = MaterialTheme.typography.headlineSmall.merge(HxNumberStyle), color = c.text)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().heightIn(min = 18.dp), contentAlignment = Alignment.CenterStart) {
            if (cpu && running && vm.cpuSampleAvailable) HxProgressBar((vm.cpuPercent / 100f).coerceIn(0f, 1f), c.accent, height = 5.dp)
            else if (cpu) Text(if (running) "等待有效采样" else "核心未运行", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
            else Text("核心实际驻留", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        }
    }
}

@Composable
private fun HeroAction(label: String, color: Color, busy: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled || busy) 1f else .4f, tween(HxMotion.Medium), label = "heroActionAlpha")
    Box(
        modifier
            .fillMaxHeight()
            .hxPressScale(source, .92f)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled) {
                haptics.perform(HetuHaptic.Confirm)
                onClick()
            }
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) HxSpinner(16.dp, color)
        else Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
    }
}

/* ---------------------------- tiles ---------------------------- */

@Composable
private fun HomeLauncherCard(
    title: String,
    subtitle: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxHeight()
            .hxPressScale(source, .97f)
            .clip(Hx.cardShape)
            .background(c.surface)
            .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.cardShape) else Modifier)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
                haptics.perform(HetuHaptic.Tap)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = c.text,
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = c.textMuted,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeQuickCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    modifier: Modifier,
    valueColor: Color = Hx.colors.textMuted,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxHeight()
            .hxPressScale(source, .96f)
            .clip(Hx.cardShape)
            .background(c.surface)
            .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.cardShape) else Modifier)
            .clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current) {
                haptics.perform(HetuHaptic.Tap)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.text, maxLines = 1)
            AnimatedContent(
                targetState = value,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "quickValue",
            ) { v ->
                Text(v, style = MaterialTheme.typography.bodySmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HomeLatencyCard(vm: HetuViewModel) {
    val c = Hx.colors
    val context = LocalContext.current
    val running = vm.state.running
    val targets = ProxyLatencyTargets.load(vm.prefs)
    HxSection {
        Bento(
            "本机直测",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HomeHeaderIcon(
                        icon = Icons.Rounded.Tune,
                        contentDescription = "本机直测设置",
                        enabled = true,
                    ) { context.startActivity(Intent(context, ProxyLatencyTargetsActivity::class.java)) }
                    HomeHeaderIcon(
                        icon = Icons.Rounded.Sync,
                        contentDescription = "重新本机直测",
                        enabled = running && !vm.siteTesting,
                        spinning = vm.siteTesting,
                    ) { vm.measureSites() }
                }
            },
        ) {
            Text("河图进程请求，未指定代理节点", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), fontWeight = FontWeight.Normal, color = c.textMuted)
            Text("结果不代表其他应用的代理路径", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp), fontWeight = FontWeight.Normal, color = c.textFaint)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                targets.forEachIndexed { index, target ->
                    if (index > 0) Box(Modifier.padding(vertical = 6.dp).width(0.5.dp).height(30.dp).background(c.line))
                    val value = vm.siteDelays[target.name]
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(target.name, style = MaterialTheme.typography.labelMedium, color = c.textMuted, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        AnimatedContent(
                            targetState = HxFormat.delay(value) + if ((value ?: 0L) > 0L) " ms" else "",
                            transitionSpec = {
                                (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .8f)).togetherWith(fadeOut(tween(100)))
                            },
                            label = "siteDelay",
                        ) { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                                fontWeight = FontWeight.SemiBold,
                                color = HxFormat.delayColor(value),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    spinning: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) HxSpinningSync(spinning = true, tint = c.textMuted, size = 18.dp)
        else Icon(
            icon,
            contentDescription,
            tint = if (enabled) c.textMuted else c.textFaint,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun HomeWanCard(
    vm: HetuViewModel,
    modifier: Modifier,
    onDetails: () -> Unit,
) {
    val c = Hx.colors
    val context = LocalContext.current
    val rt = vm.runtime
    val running = vm.state.running
    var showLan by remember { mutableStateOf(false) }
    val wan = if (running) rt.wanAddress.takeIf { it.isNotBlank() && it != "—" } else null
    val lan = if (running) rt.lanAddress.takeIf { it.isNotBlank() && it != "—" } else null
    val address = if (showLan) lan else wan
    val title = if (showLan) "LAN" else "WAN"
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHetuHaptics()

    Column(
        modifier
            .fillMaxHeight()
            .hxPressScale(source, .97f)
            .clip(Hx.cardShape)
            .background(c.surface)
            .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.cardShape) else Modifier)
            .combinedClickableCompat(
                source = source,
                enabled = running,
                onClick = { haptics.perform(HetuHaptic.Tap); showLan = !showLan },
                onLongClick = address?.let { value -> { haptics.perform(HetuHaptic.LongPress); hxCopy(context, if (showLan) "LAN IP" else "出口 IP", value) } },
            )
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "wanLanTitle",
            ) { text ->
                Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.text,
                    modifier = Modifier.semantics { contentDescription = if (showLan) "局域网 IP" else "公网 IP" })
            }
            Spacer(Modifier.weight(1f))
            if (!showLan && wan != null) {
                val detailSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .clickable(interactionSource = detailSource, indication = null) {
                            haptics.perform(HetuHaptic.Tap)
                            onDetails()
                        }
                        .heightIn(min = 48.dp).widthIn(min = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.clip(Hx.pillShape).background(c.accentSoft).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("详情", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = c.accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = address ?: "—",
            transitionSpec = {
                (fadeIn(tween(HxMotion.Medium)) + slideInVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { it / 3 })
                    .togetherWith(fadeOut(tween(HxMotion.Short)) + slideOutVertically(tween(HxMotion.Short)) { -it / 3 })
            },
            label = "wanLanIp",
        ) { ip ->
            Text(
                ip,
                style = MaterialTheme.typography.headlineSmall.merge(HxNumberStyle).copy(fontSize = 18.sp, lineHeight = 24.sp),
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            targetState = showLan,
            transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
            label = "wanLanMeta",
        ) { lanMode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lanMode) "接口" else "地区", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = c.textMuted)
                Spacer(Modifier.width(10.dp))
                if (lanMode) {
                    Text(
                        rt.lanInterface.takeIf { it.isNotBlank() && it != "—" } ?: "当前网络",
                        style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val flag = HxFormat.flag(rt.wanCountryCode)
                    val location = when {
                        rt.wanCountry.isNotBlank() && rt.wanCountry != "—" -> rt.wanCountry
                        rt.wanRegion.isNotBlank() && rt.wanRegion != "—" -> rt.wanRegion
                        running -> "探测中"
                        else -> "等待连接"
                    }
                    Text(flag, fontSize = 18.sp)
                    if (flag.isNotBlank()) Spacer(Modifier.width(5.dp))
                    Text(
                        location,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSpeedCard(vm: HetuViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val running = vm.state.running
    val local = vm.homeSpeedSource == "local"
    val up = if (local) vm.localUpRate else vm.upRate
    val down = if (local) vm.localDownRate else vm.downRate
    Bento(
        "网速",
        modifier = modifier.fillMaxHeight(),
        trailing = {
            Text(
                if (local) "本地" else "API",
                style = MaterialTheme.typography.labelSmall,
                color = Hx.colors.textFaint,
            )
        },
        onClick = onOpen,
    ) {
        BentoLine("上行", if (running) HxFormat.speed(up) else "—", modifier = Modifier.testTag("home-upload-row"))
        BentoLine("下行", if (running) HxFormat.speed(down) else "—", modifier = Modifier.testTag("home-download-row"))
    }
}

@Composable
private fun HomeSubscriptionCard(vm: HetuViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val c = Hx.colors
    val tracked = vm.providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total > 0L) (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Bento(
        "订阅",
        modifier = modifier.fillMaxHeight(),
        trailing = {
            if (total > 0L) Text("剩余 ${((1f - ratio) * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = c.textMuted)
            else if (vm.providersUpdatingAll) HxSpinner(14.dp)
        },
        onClick = onOpen,
        onLongClick = if (vm.state.running && vm.providers.isNotEmpty()) vm::updateAllProviders else null,
    ) {
        if (total > 0L) {
            BentoLine("已用", HxFormat.bytes(used))
            BentoLine("总量", HxFormat.bytes(total))
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(6.dp))
            HxProgressBar(ratio, if (ratio > .9f) c.bad else c.accent, height = 5.dp)
        } else {
            Text(
                if (vm.providers.isEmpty()) "无在线订阅" else "${vm.providers.size} 个订阅 · 无流量信息",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text("长按更新全部", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
        }
    }
}

@Composable
private fun HomeResourceCard(vm: HetuViewModel, modifier: Modifier, onOpen: () -> Unit) {
    val c = Hx.colors
    val running = vm.state.running
    val memory = if (vm.runtime.rssBytes > 0) vm.runtime.rssBytes else vm.state.memoryBytes
    Bento("资源占用", modifier = modifier.fillMaxHeight(), onClick = if (running) onOpen else null) {
        BentoLine("内存", if (running && memory > 0) HxFormat.bytes(memory) else "—", modifier = Modifier.testTag("home-memory-row"))
        BentoLine("CPU", if (running && vm.cpuSampleAvailable) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—",
            modifier = Modifier.testTag("home-cpu-row"))
        Spacer(Modifier.weight(1f))
        if (running && vm.cpuSampleAvailable) HxProgressBar((vm.cpuPercent / 100f).coerceIn(0f, 1f), c.accent, Modifier.padding(top = 8.dp), height = 5.dp)
    }
}

@Composable
private fun HomeTrendCard(vm: HetuViewModel) {
    val c = Hx.colors
    val running = vm.state.running
    val graphAlpha by animateFloatAsState(if (running) 1f else .3f, tween(HxMotion.Long), label = "trendAlpha")
    HxSection {
        Bento(
            "近期趋势",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HxDot(c.textMuted, 7.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("上行", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                    Spacer(Modifier.width(10.dp))
                    HxDot(c.accent, 7.dp)
                    Spacer(Modifier.width(4.dp))
                    Text("下行", style = MaterialTheme.typography.labelSmall, color = c.textMuted)
                }
            },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                HomeRate("下载", if (running) vm.downRate else 0L, "↓", c.accent, Modifier.weight(1f))
                HomeRate("上传", if (running) vm.upRate else 0L, "↑", c.textMuted, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                HxTrafficChart(
                    vm.rateHistory.toList(),
                    vm.upHistory.toList(),
                    c.accent,
                    c.textMuted,
                    Modifier.fillMaxSize().graphicsLayer { alpha = graphAlpha },
                )
                if (!running) {
                    Text("连接后显示实时曲线", style = MaterialTheme.typography.bodySmall, color = c.textFaint, modifier = Modifier.align(Alignment.Center))
                }
            }
            if (running && (vm.state.downloadTotal > 0L || vm.state.uploadTotal > 0L)) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "本次累计  ↓ " + HxFormat.bytes(vm.state.downloadTotal) + "   ↑ " + HxFormat.bytes(vm.state.uploadTotal),
                    style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                    color = c.textFaint,
                )
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
            .hxPressScale(source, .95f)
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
            .animateContentSize(spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        AnimatedContent(
            targetState = if (busy) 0 else if (running) 1 else 2,
            transitionSpec = {
                (fadeIn(tween(HxMotion.Short)) + scaleIn(HxMotion.pop(), initialScale = .7f))
                    .togetherWith(fadeOut(tween(100)) + scaleOut(tween(HxMotion.Short), targetScale = .7f))
            },
            contentAlignment = Alignment.Center,
            label = "toggleContent",
        ) { mode ->
            if (mode == 0) {
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { HxSpinner(15.dp, fg) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, tint = fg, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (mode == 1) "断开" else "连接", style = MaterialTheme.typography.labelLarge, color = fg)
                }
            }
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
    val graphAlpha by animateFloatAsState(if (running) .92f else .3f, tween(HxMotion.Long), label = "graphAlpha")

    HxSection {
        HxCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("实时网络", style = MaterialTheme.typography.titleSmall, color = c.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                HxPill(if (running) "代理运行中" else "未连接", if (running) HxTone.Good else HxTone.Neutral)
            }
            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth().height(142.dp)) {
                HxTrafficChart(
                    vm.rateHistory.toList(),
                    vm.upHistory.toList(),
                    c.accent,
                    c.textMuted,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(94.dp).graphicsLayer { alpha = graphAlpha },
                )
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    HomeRate("下载", if (running) vm.downRate else 0L, "↓", c.accent, Modifier.weight(1f))
                    HomeRate("上传", if (running) vm.upRate else 0L, "↑", c.textMuted, Modifier.weight(1f))
                }
                if (!running) {
                    Text(
                        "连接后显示实时速率曲线",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textFaint,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
                    )
                }
            }

            if (running && (vm.state.downloadTotal > 0L || vm.state.uploadTotal > 0L)) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "本次累计  ↓ " + HxFormat.bytes(vm.state.downloadTotal) + "   ↑ " + HxFormat.bytes(vm.state.uploadTotal),
                    style = MaterialTheme.typography.labelSmall.merge(HxNumberStyle),
                    color = c.textFaint,
                )
            }

            if (total > 0L) {
                Spacer(Modifier.height(8.dp))
                HxProgressBar(ratio, color = if (ratio > .9f) c.bad else if (ratio > .75f) c.warn else c.accent, height = 5.dp)
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
                    if (running && vm.cpuSampleAvailable) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—",
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
                    onClick = if (running) ({ vm.openPanel("conn") }) else null,
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
            AnimatedContent(
                targetState = unit,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "rateUnit",
            ) { u ->
                Text(u, style = MaterialTheme.typography.labelMedium, color = c.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun HomeMiniStat(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = Hx.colors
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .then(if (onClick != null) Modifier.hxPressScale(source, .95f) else Modifier)
            .height(30.dp)
            .clip(Hx.pillShape)
            .background(c.surfaceMuted)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = source, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                else Modifier,
            )
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
    val wideText = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    val selected = group?.now.orEmpty()
    val node = group?.nodes?.firstOrNull { it.name == selected }
    val delay = if (selected.isNotBlank()) vm.delays[selected] ?: node?.lastDelay else null
    val testing = group != null && vm.testingGroups[group.name] == true

    HxSection {
        HxCard(onClick = { vm.openPanel("proxies") }, padding = androidx.compose.foundation.layout.PaddingValues(15.dp)) {
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
                    // The node name slides when the selection changes elsewhere.
                    AnimatedContent(
                        targetState = selected.ifBlank { if (vm.state.running) "等待节点信息" else "代理未运行" },
                        transitionSpec = {
                            (fadeIn(tween(HxMotion.Medium)) + slideInVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { it / 2 })
                                .togetherWith(fadeOut(tween(HxMotion.Short)) + slideOutVertically(tween(HxMotion.Short)) { -it / 2 })
                        },
                        label = "outboundName",
                    ) { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            color = c.text,
                            maxLines = if (wideText) 3 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        listOfNotNull(node?.type?.uppercase(), group?.name).joinToString(" · ").ifBlank { vm.state.config },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = if (wideText) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!wideText && group != null && vm.state.running) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("节点测速", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
                        HxDelayPill(delay, testing) { vm.testGroup(group) }
                    }
                    Spacer(Modifier.width(3.dp))
                }
                HxChevron()
            }
            if (wideText && group != null && vm.state.running) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("节点测速", style = MaterialTheme.typography.labelSmall, color = c.textFaint)
                    HxDelayPill(delay, testing) { vm.testGroup(group) }
                }
            }
        }
    }
}

/* ---------------------------- shortcuts ---------------------------- */

@Composable
private fun HomeShortcuts(vm: HetuViewModel) {
    val c = Hx.colors
    val nav = LocalNav.current
    val prefs = vm.prefs
    val running = vm.state.running
    val adblockOn = vm.settingsRevision >= 0 && prefs.getBoolean("proxyAdblockChain", true)
    val adblockEffective = running && adblockOn && prefs.getBoolean("proxyAdblockLastEffective", false)
    val targets = ProxyLatencyTargets.load(prefs)
    val best = targets.mapNotNull { vm.siteDelays[it.name]?.takeIf { d -> d > 0L } }.minOrNull()

    HxSection("常用") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(
                    icon = Icons.Rounded.Description,
                    title = "配置与订阅",
                    value = vm.state.config,
                    modifier = Modifier.weight(1f),
                    onClick = { nav.push(HxRoute.Configs) },
                )
                HomeTile(
                    icon = Icons.Rounded.Shield,
                    title = "广告过滤",
                    value = when {
                        !adblockOn -> "已关闭"
                        adblockEffective -> "保护中"
                        running -> "验证中"
                        else -> "启动后生效"
                    },
                    valueColor = if (adblockEffective) c.good else c.textMuted,
                    modifier = Modifier.weight(1f),
                    onClick = { nav.push(HxRoute.Adblock) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeTile(
                    icon = Icons.Rounded.Speed,
                    title = "本机直测",
                    value = when {
                        vm.siteTesting -> "测速中…"
                        !running -> "启动后可测"
                        best != null -> "最快 $best ms"
                        else -> "点按测速"
                    },
                    valueColor = if (best != null && !vm.siteTesting) HxFormat.delayColor(best) else c.textMuted,
                    busy = vm.siteTesting,
                    enabled = running,
                    modifier = Modifier.weight(1f),
                    onClick = vm::measureSites,
                )
                HomeTile(
                    icon = Icons.Rounded.Sync,
                    title = "更新订阅",
                    value = when {
                        vm.providersUpdatingAll -> "更新中…"
                        !running -> "启动后可更新"
                        vm.providers.isEmpty() -> "无在线订阅"
                        else -> "${vm.providers.size} 个订阅"
                    },
                    busy = vm.providersUpdatingAll,
                    enabled = running && vm.providers.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = vm::updateAllProviders,
                    onLongClick = { nav.push(HxRoute.Providers) },
                )
            }
        }
    }
}

@Composable
private fun HomeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Hx.colors.textMuted,
    busy: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val source = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled) 1f else .55f, tween(HxMotion.Medium), label = "tileAlpha")
    Column(
        modifier
            .hxPressScale(source, .96f)
            .hxSoftShadow(Hx.rowShape, 4.dp)
            .clip(Hx.rowShape)
            .background(c.surface)
            .then(if (c.dark) Modifier.border(0.5.dp, c.line, Hx.rowShape) else Modifier)
            .combinedClickableCompat(
                source = source,
                enabled = enabled && !busy,
                onClick = { haptics.perform(HetuHaptic.Tap); onClick() },
                onLongClick = onLongClick?.let { long -> { haptics.perform(HetuHaptic.LongPress); long() } },
            )
            .graphicsLayer { this.alpha = alpha }
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceMuted), contentAlignment = Alignment.Center) {
                if (busy) HxSpinner(14.dp) else Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = c.textFaint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1)
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
            label = "tileValue",
        ) { v ->
            Text(v, style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    source: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier = this.then(
    Modifier.combinedClickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = onClick,
    ),
)

@Composable
internal fun PublicIpDetails(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    val context = LocalContext.current
    val rt = vm.runtime
    var lan by rememberSaveable { mutableStateOf(false) }
    fun shown(value: String) = value.takeIf { it.isNotBlank() && it != "—" } ?: "未知"
    val address = if (lan) rt.lanAddress else rt.wanAddress
    val location = listOf(HxFormat.flag(rt.wanCountryCode), rt.wanCountry, rt.wanRegion)
        .filter { it.isNotBlank() && it != "—" }.distinct().joinToString(" · ").ifBlank { "未知" }
    val rows = if (lan) listOf("IP 地址" to shown(address), "网络接口" to shown(rt.lanInterface))
        else listOf("IP 地址" to shown(address), "地理位置" to location,
            "网络运营商" to shown(rt.wanIsp), "ASN" to shown(rt.wanAsn), "城市" to shown(rt.wanCity),
            "组织" to "未知", "IP 类型" to "未知", "时区" to "未知", "经纬度" to "未知")
    HxPage(title = "公网 IP 详情", onBack = onDismiss, largeTitle = false,
        refreshing = vm.refreshing, onRefresh = vm::pullRefresh,
        actions = { HxBarAction(Icons.Rounded.Refresh, "刷新 IP", onClick = vm::pullRefresh) }) {
        item {
            HxSection {
                HxSegmented(options = listOf("wan" to "公网 IP", "lan" to "局域网 IP"),
                    selected = if (lan) "lan" else "wan", onSelect = { lan = it == "lan" })
            }
        }
        item {
            HxSection {
                HxCard(padding = PaddingValues(vertical = 12.dp)) {
                    rows.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.weight(.8f))
                            Text(value, style = MaterialTheme.typography.bodyLarge.merge(HxNumberStyle), color = c.text, modifier = Modifier.weight(1.3f))
                            if (value != "未知") HxBarAction(Icons.Rounded.ContentCopy, "复制" + label, onClick = { hxCopy(context, label, value) })
                            else Spacer(Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
        if (!lan && rt.wanError.isNotBlank()) item { HxSection { HxBanner(rt.wanError, tone = HxTone.Warn) } }
    }
}

@Composable
internal fun CoreDetails(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    val state = vm.state
    val memory = if (vm.runtime.rssBytes > 0) vm.runtime.rssBytes else state.memoryBytes
    val cpuSamples = remember(vm) { androidx.compose.runtime.mutableStateListOf<Long?>() }
    val memorySamples = remember(vm) { androidx.compose.runtime.mutableStateListOf<Long?>() }
    var lastCpuSampleAt by remember(vm) { mutableStateOf(0L) }
    var lastMemorySampleAt by remember(vm) { mutableStateOf(0L) }
    var lastMemoryPid by remember(vm) { mutableStateOf(0) }
    LaunchedEffect(state.running, vm.cpuSampleAvailable, vm.cpuSampledAtElapsed, vm.runtime.processSampleValid, vm.runtime.processSampleAtElapsed) {
        if (state.running) {
            if (vm.cpuSampleAvailable && vm.cpuSampledAtElapsed != lastCpuSampleAt) {
                cpuSamples.add((vm.cpuPercent.coerceAtLeast(0f) * 100).toLong())
                lastCpuSampleAt = vm.cpuSampledAtElapsed
            } else if (!vm.cpuSampleAvailable && cpuSamples.lastOrNull() != null) cpuSamples.add(null)
            if (vm.runtime.processSampleValid && memory > 0 && vm.runtime.processSampleAtElapsed != lastMemorySampleAt) {
                if (lastMemoryPid != 0 && lastMemoryPid != vm.runtime.pid && memorySamples.lastOrNull() != null) memorySamples.add(null)
                memorySamples.add(memory)
                lastMemorySampleAt = vm.runtime.processSampleAtElapsed
                lastMemoryPid = vm.runtime.pid
            } else if ((!vm.runtime.processSampleValid || memory <= 0) && memorySamples.lastOrNull() != null) memorySamples.add(null)
            while (cpuSamples.size > 40) cpuSamples.removeAt(0)
            while (memorySamples.size > 40) memorySamples.removeAt(0)
        } else { cpuSamples.clear(); memorySamples.clear(); lastCpuSampleAt = 0L; lastMemorySampleAt = 0L; lastMemoryPid = 0 }
    }
    val rows = listOf(
        "运行时长" to if (state.running) HxFormat.duration(vm.runtime.elapsedSeconds) else "—",
        "进程 PID" to (if (vm.runtime.pid > 0) vm.runtime.pid.toString() else if (state.corePid > 0) state.corePid.toString() else "—"),
        "核心版本" to vm.coreVersion.ifBlank { state.core.ifBlank { "—" } },
        "CPU 核心分配" to vm.runtime.cpuAffinity.ifBlank { "—" },
        "当前 CPU" to (if (vm.runtime.currentCpu >= 0) "CPU ${vm.runtime.currentCpu}" else "—"),
        "模式" to "${state.mode} · ${state.trafficMode.ifBlank { "未确认" }}",
        "启动配置" to state.config,
        "活动连接" to if (state.running) state.connections.size.toString() else "—",
    )
    HxPage(title = "资源占用", onBack = onDismiss, largeTitle = false) {
        item { ResourceDetailChart("CPU", if (state.running && vm.cpuSampleAvailable) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—", cpuSamples.toList(), c.accent) }
        item { ResourceDetailChart("内存", if (state.running && memory > 0) HxFormat.bytes(memory) else "—", memorySamples.toList(), c.good) }
        item {
            HxSection {
                HxCard(padding = PaddingValues(vertical = 8.dp)) {
                    rows.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 10.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.weight(.85f))
                            Text(value, style = MaterialTheme.typography.bodyLarge.merge(HxNumberStyle), color = c.text, modifier = Modifier.weight(1.15f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResourceDetailChart(title: String, value: String, samples: List<Long?>, tint: Color) {
    HxSection {
        HxCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = Hx.colors.textMuted)
                    Text(value, style = MaterialTheme.typography.headlineSmall.merge(HxNumberStyle), color = Hx.colors.text)
                }
                Text("本次查看", style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted)
            }
            Spacer(Modifier.height(16.dp))
            if (samples.count { it != null } > 1) ResourceHistoryChart(samples, tint, Modifier.fillMaxWidth().height(76.dp))
            else Box(Modifier.fillMaxWidth().height(76.dp), contentAlignment = Alignment.Center) {
                Text("等待连续运行采样", style = MaterialTheme.typography.bodySmall, color = Hx.colors.textFaint)
            }
            if (samples.any { it == null }) Text("断开处为缺测", style = MaterialTheme.typography.labelSmall, color = Hx.colors.textFaint)
        }
    }
}

/** Missing observations split paths; they are never plotted as zero or joined across a gap. */
@Composable
private fun ResourceHistoryChart(samples: List<Long?>, tint: Color, modifier: Modifier) {
    val segments = remember(samples) { resourceSampleSegments(samples) }
    Canvas(modifier) {
        val maximum = samples.filterNotNull().maxOrNull()?.coerceAtLeast(1L) ?: 1L
        fun point(sample: IndexedValue<Long>) = Offset(
            sample.index.toFloat() / (samples.size - 1).coerceAtLeast(1) * size.width,
            size.height - 2.dp.toPx() - sample.value.toFloat() / maximum * (size.height - 4.dp.toPx()),
        )
        segments.forEach { segment ->
            if (segment.size == 1) drawCircle(tint, 2.dp.toPx(), point(segment.first()))
            else {
                val path = Path()
                segment.forEachIndexed { index, sample ->
                    val p = point(sample)
                    if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, tint, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
            HxTrafficChart(vm.rateHistory.toList(), vm.upHistory.toList(), c.accent, c.warn, Modifier.fillMaxWidth().height(52.dp))
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
                HxMetric("CPU", if (running && vm.cpuSampleAvailable) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—", Modifier.weight(1f))
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
                "本机直测",
                subtitle = "河图进程请求，未指定代理节点",
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
