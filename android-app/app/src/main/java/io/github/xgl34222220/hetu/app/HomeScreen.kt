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
    var showCoreDetails by remember { mutableStateOf(false) }
    var showWanDetails by remember { mutableStateOf(false) }
    var showSpeedSource by remember { mutableStateOf(false) }

    HxPage(
        title = "河图",
        scrollToTopSignal = vm.reselect,
        bottomPadding = bottomPadding,
        refreshing = vm.refreshing,
        onRefresh = vm::pullRefresh,
        largeTitleStartPadding = 26.dp,
        largeTitleFontSizeSp = 36f,
        largeTitleBottomPadding = 18.dp,
        canvasColor = if (Hx.colors.dark) Hx.colors.canvas else Color(0xFFEBEDFA),
    ) {
        item(key = "hero") {
            Box(Modifier.hxEnter(stagger, 0)) { HomeHero(vm, onDetails = { showCoreDetails = true }) }
        }
        item(key = "launchers") {
            BentoRow(Modifier.hxEnter(stagger, 1)) {
                HomeLauncherCard(
                    title = "WebUI",
                    subtitle = "Web 界面",
                    modifier = Modifier.weight(1f),
                ) { context.startActivity(Intent(context, ProxyWebPanelsActivity::class.java)) }
                HomeLauncherCard(
                    title = "日志",
                    subtitle = "查看",
                    modifier = Modifier.weight(1f),
                ) { context.startActivity(Intent(context, ProxyLogViewerActivity::class.java)) }
            }
        }
        item(key = "latency") {
            Box(Modifier.hxEnter(stagger, 2)) { HomeLatencyCard(vm) }
        }
        item(key = "net") {
            BentoRow(Modifier.hxEnter(stagger, 3)) {
                HomeWanCard(vm, Modifier.weight(1f), onDetails = { showWanDetails = true })
                HomeSpeedCard(vm, Modifier.weight(1f), onOpen = { showSpeedSource = true })
            }
        }
        item(key = "usage") {
            BentoRow(Modifier.hxEnter(stagger, 4)) {
                HomeSubscriptionCard(vm, Modifier.weight(1f)) { vm.openPanel("providers") }
                HomeResourceCard(vm, Modifier.weight(1f)) { showCoreDetails = true }
            }
        }
    }

    if (showCoreDetails) CoreDetails(vm, onDismiss = { showCoreDetails = false })
    if (showWanDetails) PublicIpDetails(vm, onDismiss = { showWanDetails = false })
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
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
            if (trailing != null) trailing()
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun BentoLine(label: String, value: String, valueColor: Color = Hx.colors.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted)
        Spacer(Modifier.weight(1f))
        AnimatedContent(
            targetState = value,
            transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
            label = "bentoLine",
        ) { v ->
            Text(v, style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle), fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1)
        }
    }
}

/* ---------------------------- hero ---------------------------- */

@Composable
private fun HomeHero(vm: HetuViewModel, onDetails: () -> Unit) {
    val c = Hx.colors
    val state = vm.state
    val running = state.running
    val op = vm.operation
    val healthy = running && state.message.isBlank()
    val tint by animateColorAsState(
        when {
            op != null -> c.warn
            running -> c.accent
            else -> c.textFaint
        },
        tween(HxMotion.Medium),
        label = "heroTint",
    )
    val wash by animateColorAsState(
        when {
            op != null -> c.warnSoft
            running -> c.accentSoft
            else -> c.surfaceMuted
        },
        tween(HxMotion.Long),
        label = "heroWash",
    )
    val glyphScale = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(running) {
        glyphScale.snapTo(.7f)
        glyphScale.animateTo(1f, spring(dampingRatio = .5f, stiffness = 300f))
    }
    val status = when (op) {
        HxRunOp.Start -> "正在启动"
        HxRunOp.Stop -> "正在停止"
        HxRunOp.Restart -> "正在重启"
        HxRunOp.Reload -> "正在重载"
        null -> if (running) "运行中" else "未运行"
    }

    HxSection {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface)
                .then(if (c.dark) Modifier.border(0.5.dp, c.line, RoundedCornerShape(20.dp)) else Modifier),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(wash, wash.copy(alpha = .35f), c.surface.copy(alpha = 0f))))
                    .clickable(enabled = running && op == null, onClick = onDetails),
            ) {
                // Oversized status glyph, cropped by the card edge.
                Icon(
                    if (running || op != null) Icons.Rounded.TaskAlt else Icons.Rounded.PowerSettingsNew,
                    null,
                    tint = tint.copy(alpha = if (running) .95f else .35f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 14.dp)
                        .size(112.dp)
                        .graphicsLayer {
                            translationX = 22.dp.toPx()
                            scaleX = glyphScale.value
                            scaleY = glyphScale.value
                        },
                )
                Column(Modifier.padding(start = 18.dp, end = 110.dp, top = 16.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(tint, pulsing = op != null || healthy)
                        Spacer(Modifier.width(4.dp))
                        AnimatedContent(
                            targetState = status,
                            transitionSpec = {
                                (fadeIn(tween(HxMotion.Medium)) + slideInVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)) { it / 2 })
                                    .togetherWith(fadeOut(tween(HxMotion.Short)) + slideOutVertically(tween(HxMotion.Short)) { -it / 2 })
                            },
                            label = "heroStatus",
                        ) { text ->
                            Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            op != null -> vm.operationText.ifBlank { "请稍候…" }
                            running -> HxFormat.duration(vm.runtime.elapsedSeconds)
                            else -> "点按下方启动代理"
                        },
                        style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.core + " · " + state.mode,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        maxLines = 1,
                    )
                    Text(
                        state.config,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HxSegmented(
                options = listOf("rule" to "规则", "global" to "全局", "direct" to "直连"),
                selected = state.trafficMode.lowercase().ifBlank { "rule" },
                onSelect = vm::setTrafficMode,
                enabled = running && op == null,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            val pending = running && vm.settingsRevision >= 0 && vm.settingsPending()
            AnimatedVisibility(
                visible = pending && op == null,
                enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            ) {
                HxBanner("设置已修改，重启后生效", tone = HxTone.Warn, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
            }
            AnimatedVisibility(
                visible = state.message.isNotBlank() && op == null,
                enter = fadeIn(tween(HxMotion.Medium)) + expandVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
                exit = fadeOut(tween(HxMotion.Short)) + shrinkVertically(tween(HxMotion.Medium, easing = HxMotion.Emphasized)),
            ) {
                HxBanner(state.message, tone = if (running) HxTone.Warn else HxTone.Neutral, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp))
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(thickness = 0.5.dp, color = c.line.copy(alpha = .7f))
            // Action bar: reload / stop / restart while running, a single start action otherwise.
            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "heroActions",
            ) { isRunning ->
                Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isRunning) {
                        HeroAction("重载", c.accent, op == HxRunOp.Reload, op == null, Modifier.weight(1f), vm::reload)
                        Box(Modifier.width(0.5.dp).height(22.dp).background(c.line))
                        HeroAction("停止", c.bad, op == HxRunOp.Stop, op == null, Modifier.weight(1f), vm::toggle)
                        Box(Modifier.width(0.5.dp).height(22.dp).background(c.line))
                        HeroAction("重启", c.warn, op == HxRunOp.Restart, op == null, Modifier.weight(1f), vm::restart)
                    } else {
                        HeroAction("启动代理", c.accent, op == HxRunOp.Start, op == null, Modifier.weight(1f), vm::toggle)
                    }
                }
            }
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
            "延迟",
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    HomeHeaderIcon(
                        icon = Icons.Rounded.Tune,
                        contentDescription = "延迟设置",
                        enabled = true,
                    ) { context.startActivity(Intent(context, ProxyLatencyTargetsActivity::class.java)) }
                    HomeHeaderIcon(
                        icon = Icons.Rounded.Sync,
                        contentDescription = "重新测速",
                        enabled = running && !vm.siteTesting,
                        spinning = vm.siteTesting,
                    ) { vm.measureSites() }
                }
            },
        ) {
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
            .size(30.dp)
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
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = title,
                transitionSpec = { fadeIn(tween(HxMotion.Medium)).togetherWith(fadeOut(tween(HxMotion.Short))) },
                label = "wanLanTitle",
            ) { text ->
                Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.text)
            }
            Spacer(Modifier.weight(1f))
            if (!showLan && wan != null) {
                val detailSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .clip(Hx.pillShape)
                        .background(c.accentSoft)
                        .clickable(interactionSource = detailSource, indication = null) {
                            haptics.perform(HetuHaptic.Tap)
                            onDetails()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("详情", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.accent)
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
                style = MaterialTheme.typography.titleMedium.merge(HxNumberStyle),
                fontWeight = FontWeight.SemiBold,
                color = c.text,
                maxLines = 1,
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
        BentoLine("上行", HxFormat.speed(if (running) up else 0L))
        BentoLine("下行", HxFormat.speed(if (running) down else 0L), Hx.colors.accent)
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
        BentoLine("内存", if (running && memory > 0) HxFormat.bytes(memory) else "—")
        HxProgressBar(if (running) (memory / (512f * 1024 * 1024)).coerceIn(0f, 1f) else 0f, c.accent, Modifier.padding(vertical = 3.dp), height = 3.dp)
        BentoLine("CPU", if (running) String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent) else "—")
        HxProgressBar(if (running) (vm.cpuPercent / 100f).coerceIn(0f, 1f) else 0f, c.accent, Modifier.padding(top = 3.dp), height = 3.dp)
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
    val selected = group?.now.orEmpty()
    val node = group?.nodes?.firstOrNull { it.name == selected }
    val delay = if (selected.isNotBlank()) vm.delays[selected] ?: node?.lastDelay else null
    val testing = group != null && vm.testingGroups[group.name] == true

    HxSection("当前出站节点") {
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
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        group?.name ?: vm.state.config,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (group != null && vm.state.running) {
                    Spacer(Modifier.width(8.dp))
                    HxDelayPill(delay, testing) { vm.testGroup(group) }
                    Spacer(Modifier.width(3.dp))
                }
                HxChevron()
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
                    title = "站点延迟",
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
private fun PublicIpDetails(vm: HetuViewModel, onDismiss: () -> Unit) {
    val c = Hx.colors
    val rt = vm.runtime
    val flag = HxFormat.flag(rt.wanCountryCode)
    val location = buildList {
        if (flag.isNotBlank()) add(flag)
        if (rt.wanCountry.isNotBlank() && rt.wanCountry != "—") add(rt.wanCountry)
        if (rt.wanRegion.isNotBlank() && rt.wanRegion != "—" && rt.wanRegion != rt.wanCountry) add(rt.wanRegion)
        if (rt.wanCity.isNotBlank() && rt.wanCity != "—" && rt.wanCity != rt.wanRegion) add(rt.wanCity)
    }.joinToString(" · ").ifBlank { "—" }
    val isV6 = rt.wanAddress.contains(':')
    val rows = listOf(
        (if (isV6) "IPv6 IP" else "IPv4 IP") to rt.wanAddress.ifBlank { "—" },
        "地区" to location,
        "ISP" to rt.wanIsp.ifBlank { "—" },
        "ASN" to rt.wanAsn.ifBlank { "—" },
        (if (isV6) "IPv4" else "IPv6") to "暂不可用",
    )
    HxSheet(onDismiss = onDismiss, title = "公网 IP 详情") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            HxGroup {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) HxDivider(16.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textMuted, modifier = Modifier.width(76.dp))
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium.merge(HxNumberStyle),
                            color = c.text,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
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
        "PID" to (if (vm.runtime.pid > 0) vm.runtime.pid.toString() else if (state.corePid > 0) state.corePid.toString() else "—"),
        "版本" to vm.coreVersion.ifBlank { state.core.ifBlank { "—" } },
        "CPU 核心分配" to vm.runtime.cpuAffinity.ifBlank { "—" },
        "当前 CPU" to (if (vm.runtime.currentCpu >= 0) "CPU ${vm.runtime.currentCpu}" else "—"),
        "CPU 使用" to String.format(java.util.Locale.US, "%.1f%%", vm.cpuPercent),
        "内存" to (if (memory > 0) HxFormat.bytes(memory) else "—"),
        "运行时长" to HxFormat.duration(vm.runtime.elapsedSeconds),
        "模式" to "${state.mode} · ${state.trafficMode.ifBlank { "rule" }}",
        "配置" to state.config,
        "活动连接" to state.connections.size.toString(),
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
