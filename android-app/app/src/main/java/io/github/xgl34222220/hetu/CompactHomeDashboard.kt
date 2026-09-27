package io.github.xgl34222220.hetu

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.Locale

/** Presentation-only input. Unknown values stay unknown; no sample metrics ship in the app. */
internal data class CompactHomeData(
    val running: Boolean = false,
    val busy: Boolean = false,
    val operation: HomeOperation? = null,
    val refreshing: Boolean = false,
    val testing: Boolean = false,
    val uptimeSeconds: Long = 0,
    val core: String = "",
    val mode: String = "",
    val config: String = "",
    val message: String = "",
    val pendingSettings: Boolean = false,
    val delays: Map<String, Long> = emptyMap(),
    val latencyTargets: List<String> = CompactHomeFormat.sites,
    val wan: String = "—",
    val lan: String = "—",
    val countryCode: String = "",
    val region: String = "",
    val isp: String = "",
    val asn: String = "",
    val lanInterface: String = "",
    val up: Long = 0,
    val down: Long = 0,
    val used: Long = 0,
    val total: Long = 0,
    val memory: Long = 0,
    val cpu: Float = Float.NaN,
    val connections: Int = 0,
    val diagnosticLoading: Boolean = false,
)

internal object CompactHomeFormat {
    val sites = listOf("Baidu", "Cloudflare", "Google")
    fun ordered(delays: Map<String, Long>, ascending: Boolean, targets: List<String> = sites): List<String> =
        if (!ascending) targets else targets.sortedWith(compareBy<String> {
            delays[it]?.takeIf { value -> value > 0 } ?: Long.MAX_VALUE
        }.thenBy { targets.indexOf(it) })
    fun remaining(used: Long, total: Long): Int? = if (total <= 0) null else
        ((1.0 - used.coerceAtLeast(0).toDouble() / total) * 100).toInt().coerceIn(0, 100)
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")
        var number = value.toDouble()
        var unit = 0
        while (number >= 1024 && unit < units.lastIndex) { number /= 1024; unit++ }
        return if (unit == 0) "$value B" else String.format(Locale.US, "%.1f %s", number, units[unit])
    }
    fun flag(raw: String): String {
        val code = raw.trim().uppercase(Locale.ROOT)
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
        return code.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("")
    }
    fun uptime(seconds: Long): String = when {
        seconds < 60 -> "少于 1 分钟"
        seconds < 3600 -> "${seconds / 60} 分钟"
        seconds < 86400 -> "${seconds / 3600} 小时 ${seconds % 3600 / 60} 分钟"
        else -> "${seconds / 86400} 天 ${seconds % 86400 / 3600} 小时"
    }
}

private data class HomePalette(val page: Color, val card: Color, val text: Color,
    val muted: Color, val soft: Color, val blue: Color, val red: Color, val line: Color)
private val LocalHomePalette = staticCompositionLocalOf {
    HomePalette(Color(0xFFF4F6FB), Color.White, Color(0xFF1E293B), Color(0xFF64748B),
        Color(0xFFF8FAFC), Color(0xFF2563EB), Color(0xFFEF4444), Color(0xFFF1F5F9))
}
private val LocalHomeMotion = staticCompositionLocalOf { false }

@Composable
private fun homeMotionAvailable(requested: Boolean): Boolean {
    val owner = LocalLifecycleOwner.current
    var available by remember(owner) { mutableStateOf(false) }
    DisposableEffect(owner, requested) {
        fun refresh() { available = requested && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && ValueAnimator.areAnimatorsEnabled() }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        owner.lifecycle.addObserver(observer)
        refresh()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return available
}

/** Uses semantic clickable (including keyboard/TalkBack); scrolling cancels a press normally. */
@Composable
private fun Modifier.homeClick(enabled: Boolean = true, label: String? = null, onClick: () -> Unit): Modifier =
    nativePress(enabled, label, LocalHomeMotion.current, onClick)

@Composable
private fun HomeCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val p = LocalHomePalette.current
    val shape = RoundedCornerShape(24.dp)
    val interactive = if (onClick == null) modifier else modifier.homeClick(enabled, onClick = onClick)
    Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card), content = content)
}

@Composable
private fun HomeLabel(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, color = LocalHomePalette.current.muted, fontSize = 12.sp,
        lineHeight = 17.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HomeNumber(value: String, modifier: Modifier = Modifier, color: Color = LocalHomePalette.current.text,
    size: Int = 14, align: TextAlign = TextAlign.End) {
    val split = value.lastIndexOf(' ')
    val annotated = buildAnnotatedString {
        if (split > 0) {
            append(value.substring(0, split))
            withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = color.copy(alpha = .66f))) { append(value.substring(split)) }
        } else append(value)
    }
    Text(annotated, modifier, color = color, fontSize = size.sp, lineHeight = (size + 5).sp,
        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 2,
        overflow = TextOverflow.Ellipsis, textAlign = align,
        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
}

@Composable
private fun HomeIcon(icon: ImageVector, label: String, enabled: Boolean = true,
    spinning: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val motion = LocalHomeMotion.current
    val rotation = if (spinning && motion) {
        val transition = rememberInfiniteTransition(label = "home-refresh")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "rotation").value
    } else 0f
    Box(modifier.size(48.dp).homeClick(enabled, label, onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp).graphicsLayer { rotationZ = rotation },
            tint = LocalHomePalette.current.muted.copy(alpha = if (enabled || spinning) 1f else .4f))
    }
}

@Composable
internal fun CompactHomeDashboard(
    data: CompactHomeData,
    onRefresh: () -> Unit,
    onToggle: () -> Unit,
    onReload: () -> Unit,
    onRestart: () -> Unit,
    onDelay: () -> Unit,
    onWebUi: () -> Unit,
    onLog: () -> Unit,
    onSubscription: () -> Unit,
    onConnections: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onAdblock: () -> Unit,
    modifier: Modifier = Modifier,
    motionEnabled: Boolean = true,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val light = LocalHomePalette.current
    val palette = if (!dark) light else HomePalette(
        MaterialTheme.colorScheme.background, Color(0xFF1C2430), Color(0xFFE2E8F0),
        Color(0xFF9AA9BD), Color(0xFF253142), Color(0xFF8AB4FF), Color(0xFFFF8585), Color(0xFF293545))
    val motion = homeMotionAvailable(motionEnabled && io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current)
    CompositionLocalProvider(LocalHomePalette provides palette, LocalHomeMotion provides motion) {
        var more by remember { mutableStateOf(false) }
        var feedbackDetails by remember { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(data.message, data.busy, data.operation) {
            if (data.busy || data.operation != null) {
                snackbar.currentSnackbarData?.dismiss()
            } else {
                HomeLifecyclePresentation.feedback(data.message)?.let { summary ->
                    if (snackbar.showSnackbar(summary, actionLabel = "详情", withDismissAction = true,
                        duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) feedbackDetails = data.message
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(modifier.fillMaxSize().background(palette.page).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .testTag("compact-home"), contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 94.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("title") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("河图", Modifier.weight(1f), color = palette.text, fontSize = 27.sp,
                        lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.7).sp)
                    HomeIcon(Icons.Rounded.Refresh, "刷新状态", !data.refreshing && !data.busy, data.refreshing,
                        onClick = onRefresh)
                    Box {
                        HomeIcon(Icons.Rounded.MoreHoriz, "更多首页功能", onClick = { more = true })
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            listOf("应用连接" to onConnections, "广告过滤" to onAdblock,
                                "网络诊断" to onDiagnostics, "代理设置" to onSettings).forEach { (title, callback) ->
                                DropdownMenuItem(text = { Text(title) }, enabled = title != "网络诊断" || !data.diagnosticLoading,
                                    onClick = { more = false; callback() })
                            }
                        }
                    }
                }
            }
            item("hero") { HomeHero(data, onToggle, onReload, onRestart, onSettings) }
            item("shortcuts") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HomeShortcut("WebUI", "Web 界面", Modifier.weight(1f).testTag("home-webui"), onWebUi)
                    HomeShortcut("日志", "查看", Modifier.weight(1f).testTag("home-log"), onLog)
                }
            }
            item("latency") { HomeLatency(data, onDelay) }
            item("network") { HomePair(
                left = { HomeNetwork(data, it) },
                right = { HomeSpeed(data, it, onConnections) }) }
            item("resources") { HomePair(
                left = { HomeSubscription(data, it, onSubscription) },
                right = { HomeResources(data, it) }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp))
        }
        feedbackDetails?.let { detail -> NativeDetailsSheet("操作详情", { feedbackDetails = null }) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(detail, color = palette.text, fontSize = 12.sp, lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace)
            }
        } }
    }
}

@Composable
private fun HomeHero(data: CompactHomeData, toggle: () -> Unit, reload: () -> Unit,
    restart: () -> Unit, settings: () -> Unit) {
    NativeStatusHero(data, toggle, reload, restart, settings, LocalHomeMotion.current)
}

@Composable
private fun HomeShortcut(title: String, subtitle: String, modifier: Modifier, click: () -> Unit) {
    HomeCard(modifier, click) {
        Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = LocalHomePalette.current.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            HomeLabel(subtitle)
        }
    }
}

@Composable
private fun HomeLatency(data: CompactHomeData, refresh: () -> Unit) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    var ascending by rememberSaveable { mutableStateOf(false) }
    val names = CompactHomeFormat.ordered(data.delays, ascending, data.latencyTargets)
    HomeCard(Modifier.fillMaxWidth().testTag("home-latency")) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("延迟", Modifier.weight(1f), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                HomeIcon(Icons.Rounded.Sort, if (ascending) "恢复站点顺序" else "按延迟升序", !data.testing,
                    modifier = Modifier.testTag("home-latency-sort"), onClick = { ascending = !ascending })
                HomeIcon(Icons.Rounded.Refresh, if (data.testing) "正在测量延迟" else "刷新延迟", data.running && !data.testing,
                    data.testing, Modifier.testTag("home-latency-refresh"), refresh)
            }
            val pulse = if (data.testing && motion) {
                val transition = rememberInfiniteTransition(label = "latency-pulse")
                transition.animateFloat(.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "pulse").value
            } else 1f
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { index, name ->
                    if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(p.line))
                    key(name) {
                        Column(Modifier.weight(1f).testTag("latency-$name"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(name, color = p.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val value = data.delays[name]
                            val text = when { data.testing -> "···"; value == null -> "—"; value == -1L -> "超时"; value <= 0 -> "失败"; else -> "$value ms" }
                            val tint = when { value == null || data.testing -> p.muted; value <= 0 || value >= 1000 -> p.red
                                value >= 300 -> Color(0xFFD97706); else -> p.blue }
                            AnimatedContent(text, transitionSpec = {
                                (fadeIn(tween(if (motion) 200 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { it / 5 })
                                    .togetherWith(fadeOut(tween(if (motion) 100 else 0)))
                            }, label = "latency-result-$name") { shown ->
                                if (shown == "···") LoadingWaveDots(motion, p.blue, Modifier.padding(top = 4.dp))
                                else HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 21, TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePair(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth / scale < 290.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { left(Modifier.fillMaxWidth()); right(Modifier.fillMaxWidth()) }
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            left(Modifier.weight(1f)); right(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeNetwork(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    val motion = LocalHomeMotion.current
    var details by remember { mutableStateOf(false) }
    HomeCard(modifier.testTag("home-network")) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("home-network-switch").homeClick(label = "切换 WAN 和 LAN") {
                    scope.launch { if (motion) pager.animateScrollToPage(1 - pager.currentPage) else pager.scrollToPage(1 - pager.currentPage) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    HomeLabel(if (pager.currentPage == 0) "WAN" else "LAN")
                    Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(14.dp), tint = p.muted)
                }
                Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).homeClick(onClick = { details = true })
                    .testTag("home-network-details"), contentAlignment = Alignment.Center) {
                    Text("详情", Modifier.background(p.blue.copy(alpha = .07f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp), color = p.blue, fontSize = 11.sp)
                }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().testTag("home-network-pager")) { page ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text((if (page == 0) data.wan else data.lan).ifBlank { "—" }, color = p.text,
                        fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (page == 0) listOf(CompactHomeFormat.flag(data.countryCode), data.region)
                        .filter { it.isNotBlank() && it != "—" }.joinToString(" ").ifBlank { "地区未知" }
                        else "${data.lanInterface.ifBlank { "本地网络" }} · ${data.connections} 连接",
                        Modifier.padding(top = 4.dp), color = p.muted, fontSize = 11.sp, lineHeight = 16.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (details) NativeNetworkDetails(data) { details = false }
}

@Composable
private fun animatedMetric(value: Long): Long {
    val motion = LocalHomeMotion.current
    val animated by animateFloatAsState(value.coerceAtLeast(0).toFloat(),
        if (motion) tween(350, easing = FastOutSlowInEasing) else snap(), label = "live-metric")
    return if (motion) animated.toLong() else value
}

@Composable
private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text) {
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        HomeLabel(label)
        Spacer(Modifier.width(6.dp))
        HomeNumber(value, Modifier.weight(1f), color)
    }
}

@Composable
private fun HomeSpeed(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val up = animatedMetric(data.up)
    val down = animatedMetric(data.down)
    HomeCard(modifier.testTag("home-speed"), click) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(16.dp)) {
            HomeLabel("网速")
            Spacer(Modifier.height(7.dp))
            MetricLine("上行", if (data.running) CompactHomeFormat.bytes(up) + "/s" else "—")
            MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—")
        }
    }
}

@Composable
private fun HomeSubscription(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    val remaining = CompactHomeFormat.remaining(data.used, data.total)
    HomeCard(modifier.testTag("home-subscription"), click) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HomeLabel("订阅", Modifier.weight(1f))
                if (remaining != null) Text("剩余 $remaining%", Modifier.background(p.blue.copy(alpha = .07f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp), color = p.blue, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(7.dp))
            MetricLine("已用", if (data.total > 0) CompactHomeFormat.bytes(data.used) else "—")
            MetricLine("总量", if (data.total > 0) CompactHomeFormat.bytes(data.total) else "—")
        }
    }
}

@Composable
private fun HomeResources(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    val known = data.running && data.cpu.isFinite() && data.cpu >= 0
    val cpu by animateFloatAsState(if (known) data.cpu else 0f, if (motion) tween(400) else snap(), label = "cpu-number")
    val progress by animateFloatAsState(if (known) (data.cpu / 100).coerceIn(0f, 1f) else 0f,
        if (motion) tween(400) else snap(), label = "cpu-progress")
    val targetColor = when { !known -> p.muted; data.cpu >= 100 -> p.red; data.cpu >= 80 -> Color(0xFFD97706); else -> p.blue }
    val color by animateColorAsState(targetColor, if (motion) tween(300) else snap(), label = "cpu-color")
    val alpha by animateFloatAsState(if (known && data.cpu >= 80) 1f else .2f,
        if (motion) tween(300) else snap(), label = "cpu-emphasis")
    HomeCard(modifier.testTag("home-resources")) {
        Column(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(16.dp)) {
            HomeLabel("资源占用")
            Spacer(Modifier.height(7.dp))
            MetricLine("内存", if (data.running && data.memory > 0) CompactHomeFormat.bytes(data.memory) else "—")
            MetricLine("CPU", if (known) String.format(Locale.US, "%.1f%%", cpu) else "—",
                if (known && data.cpu >= 80) color else p.text)
            Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(CircleShape)
                .testTag("home-cpu-bar").semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(if (known) (data.cpu / 100).coerceIn(0f, 1f) else 0f, 0f..1f)
                    stateDescription = if (known) "CPU ${data.cpu}%" else "CPU 未知"
                }) {
                drawRoundRect(p.line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
                if (progress > 0f) drawRoundRect(color.copy(alpha = alpha),
                    size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
            }
        }
    }
}

/** test.92 removed the bundled WebUI. Configure a real endpoint; never pretend the native panel is WebUI. */
@Composable
internal fun CompactHomeWebUiDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val port = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
    var address by rememberSaveable { mutableStateOf(prefs.getString("homeWebUiAddress", "http://127.0.0.1:$port/ui/").orEmpty()) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("WebUI 地址") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("此基线不内置 Web 面板。填写已经部署的面板地址后打开；本地 /ui/ 需要核心配置 external-ui。不会向面板地址附加控制器密钥。")
            OutlinedTextField(address, { address = it; error = "" }, label = { Text("面板地址") },
                singleLine = true, isError = error.isNotBlank())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            val uri = runCatching { Uri.parse(address.trim()) }.getOrNull()
            val scheme = uri?.scheme?.lowercase(Locale.ROOT)
            if (uri == null || scheme !in listOf("https", "http") || uri.host.isNullOrBlank() || uri.userInfo != null) {
                error = "请输入不含账号密码的 http 或 https 地址"
            } else runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                prefs.edit().putString("homeWebUiAddress", uri.toString()).apply()
                onDismiss()
            }.onFailure { error = "没有可打开此地址的浏览器" }
        }) { Text("打开") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
