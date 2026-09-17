package io.github.xgl34222220.bichen

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import io.github.xgl34222220.bichen.ui.BichenGlassDock
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.DockItem
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Proxy workspace rebuilt around the compact reference-video language:
 * flat warm canvas, pale coral selected surfaces, grouped rows and a small floating dock.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ReferenceProxyActivity : ComponentActivity() {
    private var resumeRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Re-read theme preferences on resume without destroying the Compose tree.
            // The previous forced wrapper recreated RefProxyShell and briefly exposed
            // default/empty runtime state before the async refresh completed.
            val revision = resumeRevision
            BichenTheme { RefProxyShell(resumeRevision = revision) { finish() } }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeRevision++
    }
}

private enum class RefProxyPage { Home, Panel, Tools, Settings }
private enum class RefPanelTab(val label: String) {
    Overview("节点"), Nodes("概览"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}

@Composable
private fun RefProxyShell(resumeRevision: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { ProxyComposeController(context) }
    val repo = remember { ProxyDashboardRepository(context) }
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    val liquidBackdrop = rememberLayerBackdrop()
    val liquid = isRuntimeShaderSupported()
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val showPanelTab = prefs.getBoolean("showPanelTab", true)

    var page by rememberSaveable { mutableStateOf(RefProxyPage.Home) }
    var state by remember { mutableStateOf(ProxyComposeState()) }
    var runtime by remember { mutableStateOf(ProxyRuntimeSnapshot()) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var siteDelays by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val delays = remember { mutableStateMapOf<String, Long>() }
    var cpuPercent by remember { mutableFloatStateOf(0f) }
    var lastProcessTicks by remember { mutableLongStateOf(0L) }
    var lastSystemTicks by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }
    var lastUp by remember { mutableLongStateOf(0L) }
    var lastDown by remember { mutableLongStateOf(0L) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var operation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        try {
            val next = repo.state()
            val now = SystemClock.elapsedRealtime()
            if (lastAt > 0L && now > lastAt && next.uploadTotal >= lastUp && next.downloadTotal >= lastDown) {
                val elapsed = now - lastAt
                upRate = ((next.uploadTotal - lastUp) * 1000L / elapsed).coerceAtLeast(0L)
                downRate = ((next.downloadTotal - lastDown) * 1000L / elapsed).coerceAtLeast(0L)
            }
            next.groups.flatMap { it.nodes }.forEach { node ->
                node.lastDelay?.takeIf { it > 0L }?.let { delays.putIfAbsent(node.name, it) }
            }
            val sampled = if (next.running) runCatching { inspector.sample() }.getOrDefault(runtime) else ProxyRuntimeSnapshot()
            if (next.running && lastSystemTicks > 0L && sampled.systemTicks > lastSystemTicks && sampled.processTicks >= lastProcessTicks) {
                val deltaProcess = sampled.processTicks - lastProcessTicks
                val deltaSystem = sampled.systemTicks - lastSystemTicks
                val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                cpuPercent = ((deltaProcess.toDouble() / deltaSystem.toDouble()) * 100.0 * cores).toFloat().coerceIn(0f, 100f)
            } else if (!next.running) {
                cpuPercent = 0f
            }
            lastProcessTicks = sampled.processTicks
            lastSystemTicks = sampled.systemTicks
            runtime = sampled
            providers = if (next.running) runCatching { repo.providers() }.getOrDefault(providers) else emptyList()
            state = next
            message = next.message
            lastAt = now
            lastUp = next.uploadTotal
            lastDown = next.downloadTotal
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            message = error.message ?: "状态读取失败"
        }
    }

    fun toggle() {
        if (operation.isNotBlank()) return
        scope.launch {
            operation = if (state.running) "正在停止…" else "正在启动…"
            try {
                if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                delay(250)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "操作失败"
            } finally {
                operation = ""
            }
        }
    }

    fun reload() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重载…"
            try {
                inspector.reloadConfig()
                message = "运行配置已重载"
                delay(180)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重载失败"
            } finally {
                operation = ""
            }
        }
    }

    fun restart() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {
            operation = "正在重启…"
            try {
                controller.stop { operation = it }
                delay(160)
                controller.start { operation = it }
                delay(250)
                refresh()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "重启失败"
            } finally {
                operation = ""
            }
        }
    }

    fun measureSites() {
        if (!state.running || testing) return
        scope.launch {
            testing = true
            try {
                siteDelays = repo.siteLatencies()
                if (siteDelays.values.none { it > 0L }) message = "关键站点测速失败，请检查当前网络"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                message = error.message ?: "测速失败"
            } finally {
                testing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2200)
            refresh()
        }
    }

    // Returning from Theme/secondary activities should refresh data in place. Never
    // replace the composition or reset state/runtime/providers to their empty defaults.
    LaunchedEffect(resumeRevision) {
        if (resumeRevision > 1) refresh()
    }

    LaunchedEffect(state.running) {
        if (!state.running) { siteDelays = emptyMap(); return@LaunchedEffect }
        siteDelays = runCatching { repo.siteLatencies() }.getOrDefault(siteDelays)
        while (true) {
            delay(15_000)
            siteDelays = runCatching { repo.siteLatencies() }.getOrDefault(siteDelays)
        }
    }

    val dockPages = remember(showPanelTab) {
        if (showPanelTab) listOf(RefProxyPage.Home, RefProxyPage.Panel, RefProxyPage.Tools, RefProxyPage.Settings)
        else listOf(RefProxyPage.Home, RefProxyPage.Tools, RefProxyPage.Settings)
    }
    LaunchedEffect(showPanelTab) { if (!showPanelTab && page == RefProxyPage.Panel) page = RefProxyPage.Home }
    val dock = remember(showPanelTab) {
        dockPages.map { destination ->
            when (destination) {
                RefProxyPage.Home -> DockItem("首页", Icons.Rounded.Home, .94f)
                RefProxyPage.Panel -> DockItem("面板", Icons.Rounded.Link, .96f)
                RefProxyPage.Tools -> DockItem("工具", Icons.Rounded.GridView, .96f)
                RefProxyPage.Settings -> DockItem("设置", Icons.Rounded.Settings, .94f)
            }
        }
    }

    val shellBackground = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        LocalBichenTokens.current.pageBackground
    } else {
        Color(0xFFF4F6F9)
    }
    Box(Modifier.fillMaxSize().background(shellBackground)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),
        ) {
            // Match LuoShu's backdrop architecture: the full-screen page backdrop must live
            // INSIDE the layerBackdrop source. Keeping it only on the parent leaves transparent
            // pixels near the Home tail, which the RuntimeShader can stretch into a white strip.
            Box(Modifier.matchParentSize().background(shellBackground))
            key(page) {
                val pageEnter = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    pageEnter.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = .86f, stiffness = 430f),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 1f
                            translationY = (1f - pageEnter.value) * 10.dp.toPx()
                        },
                ) {
            when (page) {
                RefProxyPage.Home -> RefHome(
                    state = state,
                    runtime = runtime,
                    providers = providers,
                    siteDelays = siteDelays,
                    upRate = upRate,
                    downRate = downRate,
                    cpuPercent = cpuPercent,
                    operation = operation,
                    message = message,
                    testing = testing,
                    onBack = onBack,
                    onRefresh = { scope.launch { refresh() } },
                    onToggle = ::toggle,
                    onReload = ::reload,
                    onRestart = ::restart,
                    onDelay = ::measureSites,
                    onWebUi = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) },
                    onLog = { scope.launch { logText = runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" } } },
                )
                RefProxyPage.Panel -> RefPanel(state, repo, delays) { scope.launch { refresh() } }
                RefProxyPage.Tools -> RefTools(state) { logText = it }
                RefProxyPage.Settings -> RefSettings(state) { scope.launch { refresh() } }
            }
                }
            }
        }
        BichenGlassDock(
            items = dock,
            selected = dockPages.indexOf(page).coerceAtLeast(0),
            onSelect = { page = dockPages[it] },
            hazeState = haze,
            backdrop = liquidBackdrop.takeIf { liquid },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    logText?.let { text ->
        RefInfoBottomSheet(
            title = "运行日志",
            text = text,
            actionLabel = "关闭",
            onDismiss = { logText = null },
        )
    }
}

@Composable
private fun RefHome(
    state: ProxyComposeState,
    runtime: ProxyRuntimeSnapshot,
    providers: List<DashboardProviderUi>,
    siteDelays: Map<String, Long>,
    upRate: Long,
    downRate: Long,
    cpuPercent: Float,
    operation: String,
    message: String,
    testing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onWebUi: () -> Unit,
    onLog: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "BoxProxy",
                    color = t.textPrimary,
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = t.heroBackground,
                border = BorderStroke(.7.dp, scheme.primary.copy(alpha = .10f)),
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Spacer(Modifier.width(9.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        if (state.running) "运行中" else "已停止",
                                        color = t.textPrimary,
                                        fontSize = 20.sp,
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                    Text(
                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",
                                        color = t.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                }
                            }
                            Text("${state.core} · ${state.mode}", color = t.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(state.config, color = t.textMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier
                                .size(56.dp)
                                .shadow(if (state.running) 10.dp else 0.dp, CircleShape, clip = false)
                                .background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.running) Icons.Rounded.Check else Icons.Rounded.PowerSettingsNew,
                                null,
                                tint = if (state.running) Color.White else t.textMuted,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = scheme.primary.copy(alpha = .10f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val neutralAction = if (scheme.background.luminance() < .5f) Color(0xFFE2E8F0) else Color(0xFF334155)
                        RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), neutralAction)
                        RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), Color(0xFFEF4444))
                        RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), neutralAction)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefSmallTool("WebUI", "Web 界面", Icons.Rounded.Language, onWebUi, Modifier.weight(1f))
                RefSmallTool("日志", "查看", Icons.Rounded.Article, onLog, Modifier.weight(1f))
            }
        }
        item {
            RefLatencyPanel(
                baidu = siteDelays["Baidu"],
                cloudflare = siteDelays["Cloudflare"],
                google = siteDelays["Google"],
                testing = testing,
                onClick = onDelay,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefNetworkIdentityCard(runtime, state.connections.size, Modifier.weight(1f))
                RefSpeedCard(upRate, downRate, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RefSubscriptionCompact(providers, Modifier.weight(1f))
                RefResourceCard(memory, cpuPercent, Modifier.weight(1f))
            }
        }
        if (operation.isNotBlank() || message.isNotBlank()) {
            item { Text(operation.ifBlank { message }, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) }
        }
    }
}

@Composable
private fun RefLatencyPanel(baidu: Long?, cloudflare: Long?, google: Long?, testing: Boolean, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(onClick = onClick, enabled = !testing, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
      Text("延迟", color = t.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      Text(if (testing) "测试中" else "全部测速", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
      Spacer(Modifier.width(6.dp))
      if (testing) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
      else Icon(Icons.Rounded.Refresh, "测速", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
  }
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      RefLatencyColumn("Baidu", baidu, testing, Modifier.weight(1f))
      VerticalDivider(Modifier.height(38.dp), color = t.outline)
      RefLatencyColumn("Cloudflare", cloudflare, testing, Modifier.weight(1f))
      VerticalDivider(Modifier.height(38.dp), color = t.outline)
      RefLatencyColumn("Google", google, testing, Modifier.weight(1f))
  }
        }
    }
}

@Composable
private fun RefLatencyColumn(label: String, value: Long?, testing: Boolean, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val valueColor = when {
        testing -> MaterialTheme.colorScheme.primary
        value == null -> Color(0xFF94A3B8)
        value <= 0L || value > 300L -> Color(0xFFDC2626)
        value < 100L -> Color(0xFF059669)
        else -> Color(0xFFD97706)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = t.textSecondary, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
        Text(
            if (testing) "…" else refDelay(value),
            color = valueColor,
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "networkCardPress")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier
            .height(96.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) { lanMode = !lanMode },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(if (lanMode) "LAN" else "WAN", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(
                if (lanMode) runtime.lanAddress else runtime.wanAddress,
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (lanMode) "${runtime.lanInterface} · $connections 连接" else "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("网速", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("上行", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(refSpeed(up), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("下行", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(refSpeed(down), color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun RefSubscriptionCompact(items: List<DashboardProviderUi>, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("订阅", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
                    Text("${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                if (total > 0L) refBytes(used) else "${items.size} 个",
                color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A),
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
            )
            Text(if (total > 0L) "总 ${refBytes(total)}" else "远程订阅", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)
    Surface(modifier = modifier.height(96.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("资源占用", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(refBytes(memory), color = valueColor, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("内存", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(String.format(java.util.Locale.US, "%.1f%%", cpuPercent), color = valueColor, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("CPU", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun countryEmoji(code: String): String {
    val upper = code.trim().uppercase(java.util.Locale.ROOT)
    if (upper.length != 2 || upper.any { it !in 'A'..'Z' }) return "🌐"
    val first = Character.toChars(0x1F1E6 + (upper[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (upper[1] - 'A'))
    return String(first) + String(second)
}

@Composable
private fun RefActionText(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier, color: Color = LocalBichenTokens.current.textPrimary) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val source = remember(text) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .72f, stiffness = 620f), label = "heroAction$text")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .height(38.dp)
            .clip(CircleShape)
            .background(if (dark) t.elevatedCardBackground.copy(alpha = .82f) else Color.White.copy(alpha = .84f))
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else t.textSecondary.copy(alpha = .42f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun RefMetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val clickable = if (onClick == null) modifier else modifier.clickable(onClick = onClick)
    Surface(modifier = clickable, shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                Spacer(Modifier.weight(1f))
                Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RefSmallTool(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .98f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "smallTool$title")
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .height(56.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .09f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, color = t.textPrimary, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RefSubscriptionCard(items: List<DashboardProviderUi>) {
    val t = LocalBichenTokens.current
    val tracked = items.filter { it.hasSubscriptionInfo && it.total > 0L }
    val used = tracked.sumOf { it.used }
    val total = tracked.sumOf { it.total }
    val remain = (total - used).coerceAtLeast(0L)
    Surface(shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("订阅", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                Text(if (tracked.isEmpty()) "${items.size} 个远程订阅" else "剩余 ${refBytes(remain)}", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            }
            if (total > 0L) {
                val ratio = (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
                CircularProgressIndicator(progress = { ratio }, modifier = Modifier.size(34.dp), strokeWidth = 4.dp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefPanel(
    state: ProxyComposeState,
    repo: ProxyDashboardRepository,
    delays: MutableMap<String, Long>,
    onRefreshState: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    var tab by rememberSaveable { mutableStateOf(RefPanelTab.Overview) }
    var refreshing by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<DashboardProviderUi>>(emptyList()) }
    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }
    var ruleSets by remember { mutableStateOf<List<DashboardRuleSetUi>>(emptyList()) }
    var selectedGroupName by rememberSaveable { mutableStateOf<String?>(null) }
    val groupSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingNode by rememberSaveable { mutableStateOf("") }
    val selectedLocal = remember { mutableStateMapOf<String, String>() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    var error by remember { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var connectionView by rememberSaveable { mutableStateOf("active") }
    val closedConnections = remember { mutableStateListOf<ProxyConnectionUi>() }
    var previousConnections by remember { mutableStateOf<List<ProxyConnectionUi>>(emptyList()) }

    LaunchedEffect(state.connections) {
        if (previousConnections.isNotEmpty()) {
            val currentIds = state.connections.mapTo(HashSet()) { it.id }
            previousConnections.filter { it.id !in currentIds }.forEach { item ->
                if (closedConnections.none { it.id == item.id }) closedConnections.add(0, item)
            }
            while (closedConnections.size > 80) closedConnections.removeAt(closedConnections.lastIndex)
        }
        previousConnections = state.connections
    }

    val filteredGroups = remember(state.groups, query) {
        state.groups.filter { group ->
            query.isBlank() || group.name.contains(query, true) || group.now.contains(query, true) ||
                group.nodes.any { it.name.contains(query, true) }
        }
    }
    val filteredNodes = remember(state.groups, query) {
        state.groups.flatMap { it.nodes }.distinctBy { it.name }.filter { node ->
            query.isBlank() || node.name.contains(query, true) || node.type.contains(query, true)
        }
    }

    suspend fun loadTab() {
        if (!state.running) return
        try {
            when (tab) {
                RefPanelTab.Subscriptions -> providers = repo.providers()
                RefPanelTab.Rules -> rules = repo.rules()
                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()
                else -> Unit
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            error = e.message ?: "读取失败"
        }
    }

    fun refresh() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            error = ""
            try {
                when (tab) {
                    RefPanelTab.Overview -> delays.putAll(repo.globalDelay())
                    RefPanelTab.Nodes -> onRefreshState()
                    RefPanelTab.Subscriptions -> providers = repo.refreshSubscriptions()
                    RefPanelTab.RuleSets -> ruleSets = repo.refreshRuleSets()
                    RefPanelTab.Rules -> rules = repo.rules()
                    RefPanelTab.Connections -> onRefreshState()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                error = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(tab, state.running) { loadTab() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize().blur(if (selectedGroupName != null) 4.dp else 0.dp)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("代理面板", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
                            Text("${state.groups.size} 个策略组", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Rounded.Language, "WebUI", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, modifier = Modifier.size(40.dp)) {
                            Icon(if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", tint = t.textSecondary, modifier = Modifier.size(19.dp))
                        }
                    }
                    RefPanelTabs(tab) { tab = it }
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("搜索策略组或节点") },
                            leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                            shape = RoundedCornerShape(13.dp),
                        )
                    }
                }
            }
            if (error.isNotBlank()) item { RefNotice(error) }
            if (!state.running) {
                item { RefNotice("代理未运行") }
            } else when (tab) {
                RefPanelTab.Overview -> {
                    item {
                        Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(34.dp).background(t.selectionBackground, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("策略组", color = t.textPrimary, style = MaterialTheme.typography.titleMedium)
                                    Text("${filteredGroups.size} 个策略组 · 点击卡片选择节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = ::refresh, enabled = !refreshing) { Text(if (refreshing) "测速中" else "全部测速") }
                            }
                        }
                    }
                    itemsIndexed(filteredGroups.chunked(2), key = { index, _ -> "groups-$index" }) { _, pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { group ->
                                val selected = selectedLocal[group.name] ?: group.now
                                RefGroupCard(
                                    group = group,
                                    selected = selected,
                                    expanded = selectedGroupName == group.name,
                                    delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                    modifier = Modifier.weight(1f),
                                    onClick = { selectedGroupName = group.name; pendingNode = selected },
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                RefPanelTab.Nodes -> item { RefTrafficOverview(state) }
                RefPanelTab.Subscriptions -> items(providers, key = { it.name }) { item ->
                    RefProviderRow(
                        item = item,
                        onRefresh = {
                            scope.launch {
                                repo.refreshProvider(item.name)?.let { updated ->
                                    providers = providers.map { if (it.name == updated.name) updated else it }
                                }
                            }
                        },
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    )
                }
                RefPanelTab.Connections -> {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = connectionView == "active", onClick = { connectionView = "active" }, label = { Text("活跃 ${state.connections.size}") })
                            FilterChip(selected = connectionView == "closed", onClick = { connectionView = "closed" }, label = { Text("已关闭 ${closedConnections.size}") })
                            Spacer(Modifier.weight(1f))
                            if (connectionView == "active" && state.connections.isNotEmpty()) {
                                TextButton(onClick = { scope.launch { repo.closeAll(); onRefreshState() } }) { Text("终止全部", color = t.danger) }
                            }
                        }
                    }
                    val shown = if (connectionView == "active") state.connections else closedConnections
                    items(shown, key = { (if (connectionView == "active") "a-" else "c-") + it.id }) { c ->
                        RefConnectionRow(c, if (connectionView == "active") ({ scope.launch { repo.closeConnection(c.id); onRefreshState() } }) else null)
                    }
                }
                RefPanelTab.Rules -> items(rules, key = { it.index }) { RefRuleRow(it) }
                RefPanelTab.RuleSets -> items(ruleSets, key = { it.name }) { item ->
                    RefRuleSetRow(item) { scope.launch { ruleSets = repo.refreshRuleSets() } }
                }
            }
        }
    }

    val selectedGroup = selectedGroupName?.let { name -> state.groups.firstOrNull { it.name == name } }
    if (selectedGroup != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedGroupName = null },
            sheetState = groupSheetState,
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = t.elevatedCardBackground,
            contentColor = t.textPrimary,
            tonalElevation = 0.dp,
            scrimColor = Color.Black.copy(alpha = .35f),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .background(Color(0xFFCBD5E1), CircleShape),
                )
            },
        ) {
            RefNodeSheet(
                group = selectedGroup,
                selected = pendingNode.ifBlank { selectedLocal[selectedGroup.name] ?: selectedGroup.now },
                delays = delays,
                testing = testing,
                onSelect = { pendingNode = it },
                onDelay = { node ->
                    if (testing[node] != true) scope.launch {
                        testing[node] = true
                        try { delays[node] = repo.delay(node) }
                        catch (_: Exception) { delays[node] = -1L }
                        finally { testing.remove(node) }
                    }
                },
                onTestAll = {
                    selectedGroup.nodes.forEach { node ->
                        if (testing[node.name] != true) scope.launch {
                            testing[node.name] = true
                            try { delays[node.name] = repo.delay(node.name) }
                            catch (_: Exception) { delays[node.name] = -1L }
                            finally { testing.remove(node.name) }
                        }
                    }
                },
                onConfirm = {
                    val node = pendingNode.ifBlank { selectedLocal[selectedGroup.name] ?: selectedGroup.now }
                    if (node.isNotBlank()) {
                        selectedLocal[selectedGroup.name] = node
                        scope.launch {
                            try {
                                repo.select(selectedGroup.name, node)
                                onRefreshState()
                            } catch (_: Exception) {
                                selectedLocal.remove(selectedGroup.name)
                            }
                        }
                    }
                    selectedGroupName = null
                },
            )
        }
    }
}

@Composable
private fun RefNodeSheet(
    group: ProxyGroupUi,
    selected: String,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onDelay: (String) -> Unit,
    onTestAll: () -> Unit,
    onConfirm: () -> Unit,
) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RefGroupVisualIcon(group, Modifier.size(38.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, color = t.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${refGroupType(group.type)} · ${group.nodes.size} 个节点", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .86f), t.cardBackground.copy(alpha = .70f))),
                    RoundedCornerShape(14.dp),
                )
                .border(.7.dp, t.outline.copy(alpha = .45f), RoundedCornerShape(14.dp))
                .clickable(onClick = onTestAll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("测试全部节点", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text("${group.nodes.size} 个节点", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Rounded.Refresh, "测试全部节点", tint = scheme.primary, modifier = Modifier.size(21.dp))
        }
        if (group.nodes.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = t.controlBackground,
                shadowElevation = 0.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Icon(Icons.Rounded.Info, null, tint = t.textSecondary, modifier = Modifier.size(24.dp))
                    Text("当前策略组没有可选节点", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                    Text("请刷新订阅或检查当前配置后再试", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(group.nodes, key = { it.name }) { node ->
                    val active = node.name == selected
                    val interactionSource = remember(node.name) { MutableInteractionSource() }
                    val pressed by interactionSource.collectIsPressedAsState()
                    val pressScale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "node${node.name}")
                    val shape = RoundedCornerShape(14.dp)
                    val fill = if (active) {
                        Brush.verticalGradient(listOf(t.selectionBackground.copy(alpha = .94f), scheme.primaryContainer.copy(alpha = .56f)))
                    } else {
                        Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .82f), t.cardBackground.copy(alpha = .64f)))
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (pressed) .90f else 1f }
                            .background(fill, shape)
                            .border(.7.dp, if (active) scheme.primary.copy(alpha = .28f) else t.outline.copy(alpha = .34f), shape)
                            .clickable(interactionSource = interactionSource, indication = null) { onSelect(node.name) }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(node.type, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                        if (active) {
                            Icon(Icons.Rounded.Check, "已选择", tint = scheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        RefDelayBadge(
                            value = delays[node.name] ?: node.lastDelay,
                            testing = testing[node.name] == true,
                            onClick = { onDelay(node.name) },
                        )
                    }
                }
            }
        }
        Button(
            onClick = onConfirm,
            enabled = group.nodes.isNotEmpty() && selected.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text("确定", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RefPanelTabs(selected: RefPanelTab, onSelect: (RefPanelTab) -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    val tabs = RefPanelTab.entries
    val track = if (dark) t.controlBackground.copy(alpha = .86f) else Color(0xFFE2E8F0)
    val activeFill = if (dark) scheme.surfaceContainerHigh.copy(alpha = .94f) else Color.White
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(42.dp).background(track, CircleShape).padding(4.dp),
    ) {
        val itemWidth = maxWidth / tabs.size.toFloat()
        val index = tabs.indexOf(selected).coerceAtLeast(0)
        val indicatorX by animateDpAsState(
            targetValue = itemWidth * index.toFloat(),
            animationSpec = spring(dampingRatio = .80f, stiffness = 440f),
            label = "panelSegmentIndicator",
        )
        Box(
            Modifier.offset(x = indicatorX).width(itemWidth).fillMaxHeight()
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(activeFill),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val active = tab == selected
                val source = remember(tab) { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tab${tab.name}")
                Box(
                    Modifier.width(itemWidth).fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
                        .clip(CircleShape)
                        .clickable(interactionSource = source, indication = null) { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tab.label,
                        color = if (active) (if (dark) scheme.onSurface else Color(0xFF0F172A)) else (if (dark) t.textSecondary else Color(0xFF64748B)),
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun RefPanelOverview(state: ProxyComposeState, delays: Map<String, Long>) {
    val t = LocalBichenTokens.current
    val values = delays.values.filter { it > 0L }
    val avg = values.takeIf { it.isNotEmpty() }?.average()?.toInt()?.toString() ?: "--"
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.config, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RefMetric("连接", state.connections.size.toString(), Modifier.weight(1f))
                RefMetric("延迟", "$avg ms", Modifier.weight(1f))
                RefMetric("规则组", state.groups.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(group.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .76f, stiffness = 560f), label = "group${group.name}")
    val shape = RoundedCornerShape(18.dp)
    val nodeName = selected.ifBlank { "未选择" }
    val nodeFlag = refNodeFlag(nodeName)
    Column(
        modifier
            .height(92.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .shadow(if (expanded) 3.dp else 1.dp, shape, clip = false)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RefGroupVisualIcon(group, Modifier.size(22.dp))
            Spacer(Modifier.width(7.dp))
            Text(group.name, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            RefDelayBadge(delay, false, null)
        }
        Text("${refGroupTypeCompact(group.type)} · ${group.nodes.size} 节点", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
        Text(if (nodeFlag.isBlank()) nodeName else "$nodeFlag $nodeName", color = t.textSecondary, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RefGroupVisualIcon(group: ProxyGroupUi, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val bitmap = remember(group.iconPath) {
        runCatching {
  group.iconPath.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }.getOrNull()
    }
    Box(modifier.background(t.controlBackground, RoundedCornerShape(11.dp)).clip(RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
  Image(bitmap = bitmap, contentDescription = group.name, modifier = Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
        } else {
  Icon(refScenarioIcon(group.name, group.type), null, tint = scheme.primary, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun RefLeafNodeCard(node: ProxyNodeUi, delay: Long?, modifier: Modifier, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.background(t.cardBackground, shape).border(.7.dp, t.outline.copy(alpha = .45f), shape).clickable(onClick = onClick).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(scheme.primary.copy(alpha = .08f), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Public, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(7.dp))
            Text(node.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            RefDelayBadge(delay, false, null)
        }
        Text(listOf(node.type.ifBlank { "节点" }, if (node.udp) "UDP" else "").filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun refScenarioIcon(name: String, type: String): ImageVector = when {
    name.contains("chatgpt", true) || name.contains("openai", true) || name.contains("ai", true) -> Icons.Rounded.SmartToy
    name.contains("youtube", true) -> Icons.Rounded.PlayCircle
    name.contains("tiktok", true) -> Icons.Rounded.MusicNote
    name.contains("netflix", true) -> Icons.Rounded.Movie
    name.contains("apple", true) -> Icons.Rounded.PhoneIphone
    else -> refGroupIcon(type)
}

private fun refGroupIcon(type: String): ImageVector = when (type.lowercase()) {
    "urltest", "fallback" -> Icons.Rounded.Speed
    "selector" -> Icons.Rounded.Tune
    "loadbalance" -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Hub
}

private fun refGroupType(type: String): String = when (type.lowercase()) {
    "urltest" -> "自动测速"
    "selector" -> "手动选择"
    "fallback" -> "故障转移"
    "loadbalance" -> "负载均衡"
    else -> type.ifBlank { "策略组" }
}

private fun refGroupTypeCompact(type: String): String = when (type.lowercase()) {
    "urltest", "url-test" -> "URLTest"
    "selector" -> "手动选择"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> type.ifBlank { "Group" }
}

private fun refNodeFlag(name: String): String {
    val value = name.trim()
    if (listOf("🇭🇰", "🇹🇼", "🇯🇵", "🇸🇬", "🇰🇷", "🇺🇸", "🇬🇧", "🇩🇪", "🇫🇷").any(value::contains)) return ""
    val upper = value.uppercase(java.util.Locale.ROOT)
    return when {
        value.contains("香港") || upper.contains("HONG KONG") || upper.startsWith("HK") -> "🇭🇰"
        value.contains("台湾") || value.contains("台灣") || upper.contains("TAIWAN") || upper.startsWith("TW") -> "🇹🇼"
        value.contains("日本") || upper.contains("JAPAN") || upper.startsWith("JP") -> "🇯🇵"
        value.contains("新加坡") || upper.contains("SINGAPORE") || upper.startsWith("SG") -> "🇸🇬"
        value.contains("韩国") || value.contains("韓國") || upper.contains("KOREA") || upper.startsWith("KR") -> "🇰🇷"
        value.contains("美国") || value.contains("美國") || upper.contains("UNITED STATES") || upper.startsWith("US") -> "🇺🇸"
        value.contains("英国") || value.contains("英國") || upper.contains("UNITED KINGDOM") || upper.startsWith("UK") -> "🇬🇧"
        value.contains("德国") || value.contains("德國") || upper.contains("GERMANY") || upper.startsWith("DE") -> "🇩🇪"
        value.contains("法国") || value.contains("法國") || upper.contains("FRANCE") || upper.startsWith("FR") -> "🇫🇷"
        else -> ""
    }
}

@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val text = if (testing) "…" else refDelay(value)
    val (background, textColor) = when {
        testing -> scheme.primary.copy(alpha = .12f) to scheme.primary
        value == null -> Color(0x1F94A3B8) to Color(0xFF64748B)
        value <= 0L || value > 300L -> Color(0x1FEF4444) to Color(0xFFDC2626)
        value < 100L -> Color(0x1F10B981) to Color(0xFF059669)
        else -> Color(0x1FF59E0B) to Color(0xFFD97706)
    }
    val source = remember(text, onClick) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = .78f, stiffness = 620f), label = "delayBadge")
    val modifier = Modifier
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
        .background(background, CircleShape)
        .then(if (onClick != null) Modifier.clickable(enabled = !testing, interactionSource = source, indication = null, onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Text(text, modifier = modifier, color = textColor, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
private fun RefTrafficOverview(state: ProxyComposeState) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val history = remember { mutableStateListOf<Triple<Long, Long, Long>>() }
    var lastUpload by remember { mutableLongStateOf(state.uploadTotal) }
    var lastDownload by remember { mutableLongStateOf(state.downloadTotal) }
    var lastAt by remember { mutableLongStateOf(0L) }
    var upRate by remember { mutableLongStateOf(0L) }
    var downRate by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.uploadTotal, state.downloadTotal) {
        val now = SystemClock.elapsedRealtime()
        if (lastAt > 0L && now > lastAt && state.uploadTotal >= lastUpload && state.downloadTotal >= lastDownload) {
  val elapsed = now - lastAt
  upRate = ((state.uploadTotal - lastUpload) * 1000L / elapsed).coerceAtLeast(0L)
  downRate = ((state.downloadTotal - lastDownload) * 1000L / elapsed).coerceAtLeast(0L)
  history += Triple(now, upRate, downRate)
  while (history.isNotEmpty() && history.first().first < now - 60_000L) history.removeAt(0)
        }
        lastAt = now
        lastUpload = state.uploadTotal
        lastDownload = state.downloadTotal
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
  RefRateCard("上行速率", upRate, Icons.Rounded.ArrowUpward, t.success, Modifier.weight(1f))
  RefRateCard("下行速率", downRate, Icons.Rounded.ArrowDownward, scheme.primary, Modifier.weight(1f))
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
  Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("累计吞吐", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
      Row {
          RefMetric("总上行", refBytes(state.uploadTotal), Modifier.weight(1f))
          RefMetric("总下行", refBytes(state.downloadTotal), Modifier.weight(1f))
      }
  }
        }
        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
  Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
          Text("最近 60 秒流量", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
          Text("↑ 上行   ↓ 下行", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
      }
      val points = history.toList()
      Canvas(Modifier.fillMaxWidth().height(150.dp)) {
          if (points.size > 1) {
              val maxRate = points.maxOf { maxOf(it.second, it.third) }.coerceAtLeast(1L).toFloat()
              val end = points.last().first
              val start = end - 60_000L
              fun series(index: Int): List<Offset> = points.map { point ->
                  val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                  val value = if (index == 1) point.second else point.third
                  val y = size.height - (value.toFloat() / maxRate * size.height * .86f)
                  Offset(x, y)
              }
              fun smooth(coords: List<Offset>, closeArea: Boolean): Path {
                  val path = Path()
                  if (coords.isEmpty()) return path
                  path.moveTo(coords.first().x, coords.first().y)
                  for (i in 0 until coords.lastIndex) {
                      val p0 = if (i == 0) coords[i] else coords[i - 1]
                      val p1 = coords[i]
                      val p2 = coords[i + 1]
                      val p3 = if (i + 2 < coords.size) coords[i + 2] else p2
                      val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                      val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                      path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                  }
                  if (closeArea) {
                      path.lineTo(coords.last().x, size.height)
                      path.lineTo(coords.first().x, size.height)
                      path.close()
                  }
                  return path
              }
              val upload = series(1)
              val download = series(2)
              drawPath(
                  smooth(upload, true),
                  brush = Brush.verticalGradient(listOf(t.success.copy(alpha = .18f), Color.Transparent), 0f, size.height),
              )
              drawPath(
                  smooth(download, true),
                  brush = Brush.verticalGradient(listOf(scheme.primary.copy(alpha = .25f), Color.Transparent), 0f, size.height),
              )
              drawPath(smooth(upload, false), color = t.success, style = Stroke(width = 2.25.dp.toPx()))
              drawPath(smooth(download, false), color = scheme.primary, style = Stroke(width = 2.25.dp.toPx()))
          }
      }
  }
        }
    }
}

@Composable
private fun RefRateCard(title: String, value: Long, icon: ImageVector, color: Color, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
  Text(refSpeed(value), color = t.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 1)
  Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RefProviderRow(item: DashboardProviderUi, onRefresh: () -> Unit, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(item.name) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 520f), label = "provider${item.name}")
    val shape = RoundedCornerShape(18.dp)
    Surface(
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }.clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.nodes.size} 个节点 · 剩余 ${refExpireDays(item.expire)}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Sync, "同步更新", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
            if (item.hasSubscriptionInfo && item.total > 0L) {
                LinearProgressIndicator(progress = { item.ratio }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RefMetric("已上传", refBytes(item.upload), Modifier.weight(1f))
                    RefMetric("已下载", refBytes(item.download), Modifier.weight(1f))
                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = t.selectionBackground) {
                        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp)) {
                            Text(refBytes(item.remaining), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("剩余流量", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            } else {
                Text("该订阅没有上报流量信息", color = t.textMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text("到期 ${refExpireDate(item.expire)} · ${refUpdatedAt(item.updatedAt)}", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun refExpireDays(expire: Long): String {
    if (expire <= 0L) return "—"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    val days = ((millis - System.currentTimeMillis()) / 86_400_000L)
    return if (days < 0L) "已到期" else "${days} 天"
}

private fun refExpireDate(expire: Long): String {
    if (expire <= 0L) return "—"
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    return runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(millis)) }.getOrDefault("—")
}

@Composable
private fun RefConnectionRow(item: ProxyConnectionUi, onClose: (() -> Unit)?) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
      Text(item.host, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(listOf(item.network, item.inbound).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
      Text(item.chain.ifBlank { item.rule.ifBlank { "DIRECT" } }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("↑ ${refBytes(item.upload)}   ↓ ${refBytes(item.download)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
  }
  if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "终止连接", tint = t.danger) }
        }
    }
}

@Composable
private fun RefRuleRow(item: ProxyRuleUi) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
  Text("#${item.index}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(42.dp))
  Column(Modifier.weight(1f)) {
      Text(item.type, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
      Text(item.payload.ifBlank { "—" }, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
  Surface(shape = RoundedCornerShape(999.dp), color = if (item.proxy.equals("REJECT", true)) t.danger.copy(alpha = .12f) else t.selectionBackground) {
      Text(item.proxy, Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = if (item.proxy.equals("REJECT", true)) t.danger else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
  }
        }
    }
}

@Composable
private fun RefRuleSetRow(item: DashboardRuleSetUi, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(15.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
  Icon(Icons.Rounded.Inventory2, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
  Spacer(Modifier.width(9.dp))
  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
      Text(listOf(item.behavior, item.format, item.vehicleType).filter { it.isNotBlank() }.joinToString(" · "), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
      Text("${if (item.ruleCount > 0) "${item.ruleCount} 条规则" else "规则数 —"} · ${refUpdatedAt(item.updatedAt)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
  }
  IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Sync, "远端更新", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)) }
        }
    }
}

@Composable
private fun RefTools(state: ProxyComposeState, onLog: (String) -> Unit) {
    val context = LocalContext.current
    val inspector = remember { ProxyRuntimeInspector(context) }
    val scope = rememberCoroutineScope()
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    var unavailable by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF4F6F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("工具") }
        item { RefSectionLabel("系统服务") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "启动配置与运行文件") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Article, Color(0xFF8B5CF6), "日志查看", "实时查看 Mihomo stdout / stderr") { scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) } }
                RefDivider()
                RefToolRow(Icons.Rounded.Apps, Color(0xFFF97316), "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
            }
        }
        item { RefSectionLabel("网络与共享") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Wifi, Color(0xFF0EA5E9), "网络匹配", "按 Wi‑Fi / SSID 自动匹配") { unavailable = "网络匹配目前只有规格，没有真实 SSID 自动切换后端。我已取消错误的基础代理跳转，接入完成前不会假装可用。" }
                RefDivider()
                RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "共享网络", "热点与局域网共享") { unavailable = "共享网络开关目前没有独立后端控制。我已取消错误跳转，避免看起来能设置但实际无效。" }
                RefDivider()
                RefToolRow(Icons.Rounded.AltRoute, Color(0xFFEF4444), "绕过规则", "CIDR 与接口绕过") { unavailable = "自定义 CIDR/接口绕过还没有接入运行时规则生成器，因此不再把你带到基础代理页。" }
            }
        }
        item { RefSectionLabel("订阅与数据") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.CloudDownload, Color(0xFF3B82F6), "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Public, Color(0xFFF59E0B), "CNIP 设置", "国内 IP 数据与分流") { unavailable = "CNIP 下载源和运行时应用后端尚未接入；当前不再提供假入口。" }
            }
        }
        item { RefSectionLabel("维护") }
        item {
            RefGroup {
                RefToolRow(Icons.Rounded.Language, Color(0xFF6366F1), "更新 WebUI", "Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
                RefDivider()
                RefToolRow(Icons.Rounded.Memory, Color(0xFF334155), "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
            }
        }
    }
    unavailable?.let { text ->
        RefInfoBottomSheet(
            title = "功能尚未接入",
            text = text,
            actionLabel = "知道了",
            onDismiss = { unavailable = null },
        )
    }
}

@Composable
private fun RefSettings(state: ProxyComposeState, onChanged: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    var modePicker by remember { mutableStateOf(false) }
    var ipv6Picker by remember { mutableStateOf(false) }
    var portsInfo by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    LazyColumn(
        Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF4F6F9)),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 8.dp,
            end = 16.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 132.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RefTitleBar("设置") }
        item { RefSectionLabel("核心与运行") }
        item {
            RefGroup {
                RefValueRow("核心选择", state.core, Icons.Rounded.Memory, Color(0xFF334155)) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
                RefDivider()
                RefValueRow("运行模式", state.mode, Icons.Rounded.Tune, Color(0xFF2563EB)) { modePicker = true }
                RefDivider()
                RefValueRow("IPv6", state.ipv6, Icons.Rounded.Public, Color(0xFF10B981)) { ipv6Picker = true }
            }
        }
        item { RefSectionLabel("网络与配置") }
        item {
            RefGroup {
                RefValueRow("端口细则", "TProxy ${MihomoStartupConfig.TPROXY_PORT} · Redir ${MihomoStartupConfig.REDIRECT_PORT}", Icons.Rounded.Hub, Color(0xFFF97316)) { portsInfo = true }
                RefDivider()
                RefValueRow("当前配置", state.config, Icons.Rounded.Description, Color(0xFF8B5CF6)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
        item { RefSectionLabel("界面") }
        item {
            RefGroup {
                RefValueRow("主题与界面", "Miuix · Material · Monet · OLED", Icons.Rounded.Palette, Color(0xFFEC4899)) { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
                RefDivider()
                RefValueRow("WebUI 面板", "本地 Zashboard · 同源控制器", Icons.Rounded.Language, Color(0xFF3B82F6)) { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
            }
        }
        item { RefSectionLabel("管理") }
        item {
            RefGroup {
                RefValueRow("文件管理", "代理运行目录", Icons.Rounded.Folder, Color(0xFFF59E0B)) { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
                RefDivider()
                RefValueRow("订阅与配置", "链接 · User-Agent · 更新", Icons.Rounded.CloudDownload, Color(0xFF0EA5E9)) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
            }
        }
    }

    if (modePicker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val choices = ProxyRuntimeProfile.Mode.values().filter { ProxyRuntimeProfile.capability(profile.core, it).available }
        RefChoiceBottomSheet(
            title = "运行模式",
            options = choices.map { it.label to (profile.mode == it) },
            onDismiss = { modePicker = false },
            onSelect = { index ->
                val mode = choices[index]
                prefs.edit().putString("proxyBaseMode", mode.id).apply()
                modePicker = false
                notice = if (state.running) "运行模式已保存，重启代理后生效" else "运行模式已保存"
                onChanged()
            },
        )
    }

    if (ipv6Picker) {
        val profile = ProxyRuntimeProfile.load(prefs)
        val values = listOf(
            ProxyRuntimeProfile.Ipv6.ENABLE to "启用 IPv6",
            ProxyRuntimeProfile.Ipv6.BYPASS to "IPv6 不进核心",
            ProxyRuntimeProfile.Ipv6.DISABLE to "禁用系统 IPv6",
        )
        RefChoiceBottomSheet(
            title = "IPv6",
            options = values.map { (value, label) -> label to (profile.ipv6 == value) },
            onDismiss = { ipv6Picker = false },
            onSelect = { index ->
                val value = values[index].first
                prefs.edit().putString("proxyBaseIpv6", value.id).apply()
                ipv6Picker = false
                notice = if (state.running) "IPv6 设置已保存，重启代理后生效" else "IPv6 设置已保存"
                onChanged()
            },
        )
    }

    if (portsInfo) {
        RefInfoBottomSheet(
            title = "端口细则",
            text = """TProxy：${MihomoStartupConfig.TPROXY_PORT}
Redirect：${MihomoStartupConfig.REDIRECT_PORT}
控制器：127.0.0.1:${MihomoStartupConfig.CONTROLLER_PORT}

这些是辟尘运行副本使用的安全端口。源订阅文件不会被直接修改。""".trimIndent(),
            actionLabel = "关闭",
            onDismiss = { portsInfo = false },
        )
    }

    notice?.let { text ->
        RefInfoBottomSheet(
            title = "设置已保存",
            text = text,
            actionLabel = "知道了",
            onDismiss = { notice = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefChoiceBottomSheet(
    title: String,
    options: List<Pair<String, Boolean>>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            options.forEachIndexed { index, option ->
                val selected = option.second
                val shape = RoundedCornerShape(15.dp)
                Row(
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(if (selected) t.selectionBackground else t.controlBackground.copy(alpha = .42f), shape)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.first, color = t.textPrimary, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, "已选择", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefInfoBottomSheet(
    title: String,
    text: String,
    actionLabel: String,
    onDismiss: () -> Unit,
) {
    val t = LocalBichenTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
        dragHandle = { RefSheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.fillMaxWidth()
                    .heightIn(max = 430.dp)
                    .background(t.controlBackground.copy(alpha = .54f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, color = t.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            }
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RefSheetDragHandle() {
    Box(
        Modifier.padding(top = 10.dp, bottom = 6.dp)
            .size(width = 36.dp, height = 4.dp)
            .background(Color(0xFFCBD5E1), CircleShape),
    )
}

@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.elevatedCardBackground else Color.White,
        shadowElevation = if (dark) 0.dp else 2.dp,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun RefSectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFF94A3B8),
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .6.sp,
        modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 0.dp),
    )
}

@Composable
private fun RefToolRow(icon: ImageVector, accent: Color, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .78f, stiffness = 560f), label = "tool$title")
    val badgeShape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .background(if (pressed) t.controlBackground.copy(alpha = .46f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp)
                .shadow(5.dp, badgeShape, clip = false, ambientColor = accent.copy(alpha = .22f), spotColor = accent.copy(alpha = .22f))
                .background(accent, badgeShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun RefValueRow(title: String, value: String, icon: ImageVector? = null, accent: Color = Color(0xFF64748B), onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val source = remember(title) { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) .98f else 1f, spring(dampingRatio = .80f, stiffness = 560f), label = "value$title")
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth()
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
        .background(if (pressed) t.controlBackground.copy(alpha = .46f) else Color.Transparent)
        .clickable(interactionSource = source, indication = null, onClick = onClick)
    Row(modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            val badgeShape = RoundedCornerShape(10.dp)
            Box(Modifier.size(36.dp).background(accent, badgeShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Text(title, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 164.dp))
        if (onClick != null) {
            Spacer(Modifier.width(7.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = Color(0xFFCBD5E1), modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun RefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp, end = 14.dp),
        thickness = 1.dp,
        color = if (MaterialTheme.colorScheme.background.luminance() < .5f) LocalBichenTokens.current.outline else Color(0xFFF4F6F9),
    )
}

@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A), fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun RefNotice(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = LocalBichenTokens.current.selectionBackground, shadowElevation = 0.dp) {
        Text(text, Modifier.fillMaxWidth().padding(12.dp), color = LocalBichenTokens.current.textPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RefTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
        Text(title, color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "刷新", tint = t.textPrimary) }
    }
}

@Composable
private fun RefTitleBar(title: String) {
    Text(
        title,
        color = LocalBichenTokens.current.textPrimary,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp),
    )
}

private fun refUpdatedAt(value: String): String {
    if (value.isBlank()) return "更新于 —"
    val raw = value.trim()
    val compact = when {
        raw.length >= 16 && raw[4] == '-' && raw[7] == '-' -> raw.substring(5, 16).replace('T', ' ')
        raw.length >= 16 -> raw.take(16).replace('T', ' ')
        else -> raw.replace('T', ' ')
    }
    return "更新于 $compact"
}

private fun refDelay(value: Long?): String = when {
    value == null -> "--"
    value <= 0L -> "超时"
    else -> "$value ms"
}

private fun refBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}

private fun refSpeed(value: Long): String = "${refBytes(value)}/s"

private fun refDuration(seconds: Long): String {
    if (seconds <= 0L) return "0s"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    val minutes = (seconds % 3_600) / 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds % 60}秒"
        else -> "${seconds}秒"
    }
}
