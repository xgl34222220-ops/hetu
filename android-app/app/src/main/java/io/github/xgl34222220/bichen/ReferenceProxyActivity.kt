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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    private var uiRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            key(uiRevision) { BichenTheme { RefProxyShell { finish() } } }
        }
    }

    override fun onResume() {
        super.onResume()
        uiRevision++
    }
}

private enum class RefProxyPage { Home, Panel, Tools, Settings }
private enum class RefPanelTab(val label: String) {
    Overview("节点"), Nodes("概览"), Subscriptions("订阅"), Connections("连接"), Rules("规则"), RuleSets("规则集")
}

@Composable
private fun RefProxyShell(onBack: () -> Unit) {
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

    Box(Modifier.fillMaxSize().background(LocalBichenTokens.current.pageBackground)) {
        Box(
            Modifier.fillMaxSize()
                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)
                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier)
                .padding(bottom = 88.dp),
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
                RefProxyPage.Settings -> RefSettings(state)
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
        AlertDialog(
            onDismissRequest = { logText = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text("运行日志") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 24, overflow = TextOverflow.Ellipsis) },
            confirmButton = { TextButton(onClick = { logText = null }) { Text("关闭") } },
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
  Row(
      Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
      Text("代理", color = t.textPrimary, fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
      IconButton(onClick = onRefresh, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary, modifier = Modifier.size(20.dp))
      }
      IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
          Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary, modifier = Modifier.size(20.dp))
      }
  }
        }
        item {
  Surface(shape = RoundedCornerShape(26.dp), color = t.heroBackground, shadowElevation = 0.dp) {
      Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
          Row(verticalAlignment = Alignment.Top) {
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                      Text(if (state.running) "运行中" else "已停止", color = t.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                      Text(if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动", color = t.textSecondary, style = MaterialTheme.typography.labelMedium)
                  }
                  Text("${state.core} · ${state.mode}", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                  Text(state.config, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
              }
              Box(
                  Modifier.size(58.dp).background(if (state.running) scheme.primary else t.controlBackground, CircleShape),
                  contentAlignment = Alignment.Center,
              ) {
                  Icon(
                      if (state.running) Icons.Rounded.Check else Icons.Rounded.PowerSettingsNew,
                      null,
                      tint = if (state.running) Color.White else t.textMuted,
                      modifier = Modifier.size(31.dp),
                  )
              }
          }
          HorizontalDivider(color = t.outline.copy(alpha = .75f))
          Row(Modifier.fillMaxWidth()) {
              RefActionText("重载", state.running && operation.isBlank(), onReload, Modifier.weight(1f), scheme.primary)
              VerticalDivider(Modifier.height(32.dp), color = t.outline)
              RefActionText(if (state.running) "停止" else "启动", operation.isBlank(), onToggle, Modifier.weight(1f), t.danger)
              VerticalDivider(Modifier.height(32.dp), color = t.outline)
              RefActionText("重启", state.running && operation.isBlank(), onRestart, Modifier.weight(1f), t.warning)
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
      RefResourceCard(memory, cpuPercent, runtime.pid, Modifier.weight(1f))
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
    val color = when {
        testing -> MaterialTheme.colorScheme.primary
        value == null -> t.textMuted
        value <= 0L -> t.danger
        value < 100L -> t.success
        value <= 300L -> t.warning
        else -> t.danger
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        Text(if (testing) "…" else refDelay(value), color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    var lanMode by rememberSaveable { mutableStateOf(true) }
    Surface(onClick = { lanMode = !lanMode }, modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
      Text(if (lanMode) "LAN" else "WAN", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      Text("点击切换", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
  }
  if (lanMode) {
      Text("IP  ${runtime.lanAddress}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("接口  ${runtime.lanInterface}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
  } else {
      Text("IP  ${runtime.wanAddress}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text("${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion}", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
  Text("连接  $connections", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RefSpeedCard(up: Long, down: Long, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
  Text("网速", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
  Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.ArrowUpward, null, tint = t.success, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(5.dp))
      Text(refSpeed(up), color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
  }
  Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(Icons.Rounded.ArrowDownward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
      Spacer(Modifier.width(5.dp))
      Text(refSpeed(down), color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
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
    val remain = (total - used).coerceAtLeast(0L)
    val ratio = if (total <= 0L) 0f else (used.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
  Row(verticalAlignment = Alignment.CenterVertically) {
      Text("订阅", color = t.textPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
      if (total > 0L) Surface(shape = RoundedCornerShape(999.dp), color = t.selectionBackground) {
          Text("剩余 ${((1f - ratio) * 100f).toInt()}%", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
      }
  }
  Text(if (total > 0L) "已用 ${refBytes(used)}" else "${items.size} 个远程订阅", color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
  Text(if (total > 0L) "总量 ${refBytes(total)}" else "剩余 —", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
  if (total > 0L) LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape))
  if (total > 0L) Text("剩余 ${refBytes(remain)}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun RefResourceCard(memory: Long, cpuPercent: Float, pid: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
  Text("资源占用", color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
  Text("内存  ${refBytes(memory)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
  Text("CPU  ${String.format(java.util.Locale.US, "%.1f%%", cpuPercent)}", color = t.textPrimary, style = MaterialTheme.typography.bodyMedium)
  Text("PID  ${if (pid > 0) pid else "—"}", color = t.textMuted, style = MaterialTheme.typography.labelSmall)
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
    Box(
        modifier.height(34.dp).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) color else LocalBichenTokens.current.textSecondary.copy(alpha = .45f), style = MaterialTheme.typography.labelLarge)
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
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(14.dp), color = t.cardBackground, shadowElevation = 0.dp) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
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
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            containerColor = t.cardBackground,
            scrimColor = Color.Black.copy(alpha = .30f),
            dragHandle = {
                Box(
                    Modifier.padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 38.dp, height = 4.dp)
                        .background(t.outline.copy(alpha = .75f), CircleShape),
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
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
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
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(group.nodes, key = { it.name }) { node ->
                val active = node.name == selected
                val shape = RoundedCornerShape(14.dp)
                val fill = if (active) {
                    Brush.verticalGradient(listOf(t.selectionBackground.copy(alpha = .94f), scheme.primaryContainer.copy(alpha = .56f)))
                } else {
                    Brush.verticalGradient(listOf(t.elevatedCardBackground.copy(alpha = .82f), t.cardBackground.copy(alpha = .64f)))
                }
                Row(
                    Modifier.fillMaxWidth()
                        .background(fill, shape)
                        .border(.7.dp, if (active) scheme.primary.copy(alpha = .28f) else t.outline.copy(alpha = .34f), shape)
                        .clickable { onSelect(node.name) }
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
        Button(
            onClick = onConfirm,
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
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RefPanelTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = RoundedCornerShape(10.dp),
                color = if (active) t.selectionBackground else t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                Box(Modifier.height(34.dp).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                    Text(tab.label, color = if (active) MaterialTheme.colorScheme.primary else t.textPrimary, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
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
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(17.dp)
    val glass = if (expanded) t.selectionBackground else t.cardBackground
    Column(
        modifier.background(glass, shape)
  .border(1.dp, if (expanded) scheme.primary.copy(alpha = .28f) else t.outline, shape)
  .clickable(onClick = onClick)
  .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
  RefGroupVisualIcon(group, Modifier.size(34.dp))
  Spacer(Modifier.width(8.dp))
  Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(group.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(refGroupType(group.type).uppercase(java.util.Locale.ROOT), color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
  }
  RefDelayBadge(delay, false, null)
        }
        Text(selected.ifBlank { "未选择" }, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
  Text("${group.nodes.size} 节点", color = t.textMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
  if (expanded) Icon(Icons.Rounded.CheckCircle, "已打开", tint = scheme.primary, modifier = Modifier.size(16.dp))
        }
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
  Icon(refGroupIcon(group.type), null, tint = scheme.primary, modifier = Modifier.size(19.dp))
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

@Composable
private fun RefDelayBadge(value: Long?, testing: Boolean, onClick: (() -> Unit)?) {
    val t = LocalBichenTokens.current
    val scheme = MaterialTheme.colorScheme
    val text = if (testing) "…" else refDelay(value)
    val textColor = when {
        testing -> scheme.primary
        value == null -> t.textSecondary
        value <= 0L -> scheme.error
        value < 100L -> t.success
        value <= 300L -> t.warning
        else -> t.danger
    }
    val shape = RoundedCornerShape(9.dp)
    val modifier = Modifier
        .background(scheme.primary.copy(alpha = .08f), shape)
        .then(if (onClick != null) Modifier.clickable(enabled = !testing, onClick = onClick) else Modifier)
        .padding(horizontal = 9.dp, vertical = 5.dp)
    Text(text, modifier = modifier, color = textColor, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
              fun makePath(index: Int): Path {
                  val path = Path()
                  points.forEachIndexed { i, point ->
                      val x = (((point.first - start).coerceIn(0L, 60_000L)).toFloat() / 60_000f) * size.width
                      val value = if (index == 1) point.second else point.third
                      val y = size.height - (value.toFloat() / maxRate * size.height * .88f)
                      if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                  }
                  return path
              }
              drawPath(makePath(1), color = t.success, style = Stroke(width = 2.5.dp.toPx()))
              drawPath(makePath(2), color = scheme.primary, style = Stroke(width = 2.5.dp.toPx()))
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
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
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
  Text("到期 ${refExpireDate(item.expire)} · 更新 ${item.updatedAt.ifBlank { "—" }}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
      Text("${if (item.ruleCount > 0) "${item.ruleCount} 条规则" else "规则数 —"} · ${item.updatedAt.ifBlank { "未记录更新时间" }}", color = t.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTitleBar("工具") }
        item {
  RefGroup {
      RefToolRow(Icons.Rounded.Terminal, "脚本", "后台服务生命周期与启动配置") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Article, "日志查看", "实时查看 Mihomo stdout / stderr") { scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) } }
      RefDivider()
      RefToolRow(Icons.Rounded.Apps, "应用管理", "分应用放行与代理相关应用列表") { context.startActivity(Intent(context, CompactMainActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Wifi, "网络匹配", "SSID 与自动切换规则") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.WifiTethering, "共享网络", "热点中继与透明代理") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.AltRoute, "绕过规则", "CIDR 与接口直连配置") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.CloudDownload, "订阅管理", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Public, "CNIP 设置", "IPv4 / IPv6 国内地址库") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Language, "更新 WebUI", "本地面板 · Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
      RefDivider()
      RefToolRow(Icons.Rounded.Memory, "更新核心", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
  }
        }
    }
}

@Composable
private fun RefSettings(state: ProxyComposeState) {
    val context = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { RefTitleBar("设置") }
        item {
  RefGroup {
      RefValueRow("核心选择", state.core) { context.startActivity(Intent(context, ProxyCoreActivity::class.java)) }
      RefDivider()
      RefValueRow("运行模式", state.mode) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefValueRow("IPv6", state.ipv6) { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefValueRow("端口细则", "混合 / Redir / TProxy") { context.startActivity(Intent(context, RootTproxyActivity::class.java)) }
      RefDivider()
      RefValueRow("当前配置", state.config) { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
  }
        }
        item {
  RefGroup {
      RefValueRow("主题与界面", "Miuix · Material · Monet · OLED") { context.startActivity(Intent(context, ThemeSettingsActivity::class.java)) }
      RefDivider()
      RefValueRow("WebUI 面板", "本地 · Zashboard · MetaCubeXD") { context.startActivity(Intent(context, ProxyWebUiActivity::class.java)) }
      RefDivider()
      RefValueRow("文件管理", "代理运行目录") { context.startActivity(Intent(context, ReferenceFileManagerActivity::class.java)) }
      RefDivider()
      RefValueRow("订阅与配置", "链接 · User-Agent · 更新") { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) }
  }
        }
    }
}

@Composable
private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = LocalBichenTokens.current.cardBackground, shadowElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), content = content)
    }
}

@Composable
private fun RefToolRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val t = LocalBichenTokens.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = t.textPrimary, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = t.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RefValueRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    val t = LocalBichenTokens.current
    val modifier = if (onClick == null) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = onClick)
    Row(modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = t.textPrimary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = t.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RefDivider() { HorizontalDivider(color = LocalBichenTokens.current.outline) }

@Composable
private fun RefMetric(title: String, value: String, modifier: Modifier) {
    val t = LocalBichenTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
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
