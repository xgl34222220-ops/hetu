package io.github.xgl34222220.hetu

import androidx.compose.animation.animateContentSize

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
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
import io.github.xgl34222220.hetu.ui.hetuStaggerIn
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
    /** Unknown totals have no determinate progress; ratios never overflow Long arithmetic. */
    fun usedFraction(used: Long, total: Long): Float? = if (total <= 0L) null else
        (used.coerceAtLeast(0L).toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
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
internal fun homeMotionAvailable(requested: Boolean): Boolean {
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
    enabled: Boolean = true, clickLabel: String? = null, content: @Composable BoxScope.() -> Unit) {
    val p = LocalHomePalette.current
    val shape = HomeContinuousShape(20.dp)
    val interactive = if (onClick == null) modifier else modifier.homeClick(enabled, clickLabel, onClick)
    if (LocalHomeGroupedSurface.current) {
        Box(interactive, propagateMinConstraints = true, content = content)
    } else Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card),
        propagateMinConstraints = true, content = content)
}

@Composable
private fun HomeLabel(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, color = LocalHomePalette.current.muted, fontSize = 12.sp,
        lineHeight = 17.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HomeNumber(value: String, modifier: Modifier = Modifier, color: Color = LocalHomePalette.current.text,
    size: Int = 15, align: TextAlign = TextAlign.End) {
    val motion = LocalHomeMotion.current
    val roll = rememberMetricRoll(value, motion)
    val shown = if (motion) roll.displayed else value
    val split = shown.lastIndexOf(' ')
    val annotated = buildAnnotatedString {
        if (split > 0) {
            append(shown.substring(0, split))
            withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = color.copy(alpha = .66f))) { append(shown.substring(split)) }
        } else append(shown)
    }
    Text(annotated, modifier.graphicsLayer {
        val progress = if (motion) roll.progress.value.coerceIn(0f, 1f) else 1f
        alpha = progress
        translationY = (1f - progress) * (if (roll.leaving) -10f else 10f) * density
        rotationX = (1f - progress) * (if (roll.leaving) 10f else -10f)
        cameraDistance = 800f * density
    }, color = color, fontSize = size.sp, lineHeight = (size + 6).sp,
        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default, maxLines = 2,
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
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    onPullRefresh: () -> Unit = onRefresh,
    onCoreDetails: (() -> Unit)? = null,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val light = LocalHomePalette.current
    val palette = if (!dark) light else HomePalette(
        MaterialTheme.colorScheme.background, Color(0xFF1C2430), Color(0xFFE2E8F0),
        Color(0xFF9AA9BD), Color(0xFF253142), Color(0xFF8AB4FF), Color(0xFFFF8585), Color(0xFF293545))
    val motion = homeMotionAvailable(motionEnabled && io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current)
    CompositionLocalProvider(LocalHomePalette provides palette, LocalHomeMotion provides motion) {
        val listState = rememberLazyListState()
        val headerHaze = remember { HazeState() }
        val (pull, pullConnection) = rememberHomePull(listState, data.refreshing,
            !data.busy && data.operation == null, motion, onPullRefresh)
        val (bottomRebound, bottomConnection) = rememberBottomRebound12(listState, motion)
        val collapseDistance = with(LocalDensity.current) { 30.dp.toPx() }
        val collapse by remember(listState, collapseDistance) { derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
        } }
        var feedbackDetails by remember { mutableStateOf<String?>(null) }
        val notice = rememberHomeNotice(data)
        // V18.3: one-shot entrance cascade. Deliberately keyed to the user/system motion
        // switch rather than the lifecycle gate (which is still false on the first frame).
        val entranceMotion = motionEnabled && io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current
        val stagger = io.github.xgl34222220.hetu.ui.rememberHetuStagger(entranceMotion)
        // The header is a sibling of the scroll viewport, never a lazy item.
        // Insets are applied outside the clipped viewport and consumed exactly once.
        Box(modifier.fillMaxSize().background(palette.page).testTag("home-viewport")) {
        val clearance = LocalHomeDockClearance.current ?: (86.dp + contentInsets.asPaddingValues().calculateBottomPadding())
        Column(Modifier.fillMaxSize().padding(bottom = clearance).windowInsetsPadding(
            contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // A measured lane above the header, NOT an overlay over the first list item.
            // Its animated bounds clip the entering/exiting pill, including at large font scales.
            Box(Modifier.fillMaxWidth().animateContentSize(
                animationSpec = if (motion) spring(dampingRatio = 1f, stiffness = 700f) else snap()
            ).clipToBounds().testTag("home-notice-lane")) {
                HomeFeedbackPill(notice.value, motion, headerHaze,
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp, bottom = 8.dp,
                        start = 16.dp, end = 16.dp)) { raw ->
                    feedbackDetails = raw
                    notice.value = null
                }
            }
            // UI11: a clean centered title; refresh remains a real pull/accessibility action.
            // V19: reference-style large leading title that glides to the centre as it collapses.
            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, centered = false,
                modifier = Modifier.zIndex(2f)) { }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
        // Short windows reduce spacing, never the user's font size or address content.
        val compact = maxHeight < 710.dp
        CompositionLocalProvider(LocalHomeCompactSpacing provides compact,
            LocalHomeShortSpacing provides (maxHeight < 650.dp)) {
        LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = pull.offsetPx + bottomRebound.offsetPx }
            .nestedScroll(pullConnection).nestedScroll(bottomConnection)
            .hazeSource(headerHaze).testTag("compact-home").semantics {
                if (!data.busy && !data.refreshing && data.operation == null) {
                    customActions = listOf(CustomAccessibilityAction("刷新首页状态") { onPullRefresh(); true })
                }
            }, state = listState,
            overscrollEffect = null,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = if (compact) 2.dp else 4.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
            item("hero") { Box(Modifier.hetuStaggerIn(stagger, 0, entranceMotion)) { HomeHero(data, onToggle, onReload, onRestart, onSettings, onCoreDetails) } }
            item("shortcuts") { Box(Modifier.hetuStaggerIn(stagger, 1, entranceMotion)) { HomeShortcutStrip(onWebUi, onLog) } }
            item("latency") { Box(Modifier.hetuStaggerIn(stagger, 2, entranceMotion)) { HomeLatency(data, onDelay) } }
            item("telemetry") { Box(Modifier.hetuStaggerIn(stagger, 3, entranceMotion)) { HomeTelemetryGrid(data, onConnections, onSubscription) } }
        }
        HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))
        }
        }
        }
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
    restart: () -> Unit, settings: () -> Unit, details: (() -> Unit)? = null) {
    NativeStatusHero(data, toggle, reload, restart, settings, LocalHomeMotion.current, details = details)
}

@Composable
private fun HomeShortcutStrip(webUi: () -> Unit, log: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("home-shortcut-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeHeroShortcut("WebUI", "Web 界面", Modifier.weight(1f).testTag("home-webui"), webUi)
        HomeHeroShortcut("日志", "查看记录", Modifier.weight(1f).testTag("home-log"), log)
    }
}

@Composable
private fun HomeHeroShortcut(title: String, subtitle: String, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    HomeCard(modifier.heightIn(min = 60.dp), onClick = click, clickLabel = title) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = p.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = p.muted, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
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
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网络延迟", Modifier.weight(1f), color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                HomeIcon(Icons.Rounded.Sort, if (ascending) "恢复站点顺序" else "按延迟升序", !data.testing,
                    modifier = Modifier.testTag("home-latency-sort"), onClick = { ascending = !ascending })
                HomeIcon(Icons.Rounded.Refresh, if (data.testing) "正在测量延迟" else "刷新延迟", data.running && !data.testing,
                    data.testing, Modifier.testTag("home-latency-refresh"), refresh)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                names.forEachIndexed { index, name ->
                    if (index > 0) Box(Modifier.width(1.dp).height(24.dp)
                        .testTag("latency-divider-$index")
                        .background(if (p.page.luminance() < .5f) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .06f)))
                    key(name) {
                        Column(Modifier.weight(1f).testTag("latency-$name"), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(name, color = p.muted, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val value = data.delays[name]
                            val text = when { data.testing -> "···"; value == null -> "—"; value == -1L -> "超时"; value <= 0 -> "失败"; else -> "$value ms" }
                            val tint = when { value == null || data.testing -> p.muted; value <= 0 || value >= 1000 -> p.red
                                value >= 300 -> Color(0xFFD97706); else -> p.blue }
                            AnimatedContent(text, transitionSpec = {
                                (fadeIn(tween(if (motion) 200 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { it / 5 })
                                    .togetherWith(fadeOut(tween(if (motion) 100 else 0)))
                            }, contentKey = { if (it.endsWith(" ms")) "number" else it }, label = "latency-result-$name") { shown ->
                                when (shown) {
                                    "···" -> LoadingWaveDots(motion, p.blue, Modifier.padding(top = 4.dp))
                                    "超时", "失败" -> Text(shown,
                                        Modifier.padding(top = 4.dp).testTag("latency-badge-$name")
                                            .clip(CircleShape).background(p.red.copy(alpha = .08f))
                                            .padding(horizontal = 10.dp, vertical = 3.dp),
                                        color = p.red, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold)
                                    else -> HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 18, TextAlign.Center)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One shared grid: every tile uses the same measured row geometry, not independent heights. */
private val LocalGridStackedMetrics = staticCompositionLocalOf { false }

/** Four independent surfaces, two equal columns. Address length never changes column count. */
@Composable
private fun HomeTelemetryGrid(data: CompactHomeData, connections: () -> Unit, subscription: () -> Unit) {
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home-telemetry-grid")) {
        val cellWidth = (maxWidth - 10.dp) / 2
        // Accessibility reflows labels within each half, never four empty full-width cards.
        val addressStyle = textStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-.2).sp)
        val addressPixels = listOf(data.wan, data.lan).filterNot { it.contains(':') }.maxOfOrNull {
            measurer.measure(androidx.compose.ui.text.AnnotatedString(it.ifBlank { "—" }),
                style = addressStyle, softWrap = false, maxLines = 1).size.width
        } ?: 0
        val inlinePixels = with(density) { (cellWidth - 24.dp - 22.dp).roundToPx() }
        // Reclaim label space inside the card instead of clipping the IPv4 tail.
        val stacked = (cellWidth - 24.dp) / density.fontScale < 104.dp || addressPixels + 2 > inlinePixels
        val regionWidth = with(density) { (cellWidth - 24.dp - 54.dp).roundToPx() }.coerceAtLeast(1)
        fun measuredHeight(value: String, width: Int, style: androidx.compose.ui.text.TextStyle, maxLines: Int): androidx.compose.ui.unit.Dp {
            val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(value.ifBlank { "—" }),
                style = style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = width), maxLines = maxLines)
            return with(density) { kotlin.math.ceil(result.multiParagraph.height).toInt().toDp() }
        }
        val line = with(density) { 21.sp.toDp() }
        val metricHeight = if (stacked) {
            val labelStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.Medium)
            val labelPixels = listOf("上行", "下行", "已用", "总量", "内存", "CPU").maxOf {
                kotlin.math.ceil(measurer.measure(androidx.compose.ui.text.AnnotatedString(it),
                    style = labelStyle, maxLines = 1).multiParagraph.height).toInt()
            }
            val numberPixels = kotlin.math.ceil(measurer.measure(
                androidx.compose.ui.text.AnnotatedString("180.0 MB/s"), style = textStyle,
                maxLines = 1, softWrap = false).multiParagraph.height).toInt()
            with(density) { (labelPixels + numberPixels).toDp() } + 2.dp
        } else line
        val heading = maxOf(if (LocalHomeShortSpacing.current) 24.dp else 32.dp, with(density) { 21.sp.toDp() })
        val first = maxOf(metricHeight, with(density) {
            listOf(data.wan, data.lan).maxOf { address ->
                val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(address.ifBlank { "—" }),
                    style = textStyle, softWrap = false, maxLines = 1)
                kotlin.math.ceil(result.multiParagraph.height).toInt()
            }.toDp()
        })
        val regionStyle = textStyle.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp)
        val anchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"), style = textStyle).firstBaseline
        val smallAnchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"), style = regionStyle).firstBaseline
        val baseline = with(density) { anchor.toDp() }
        val baselineExtra = with(density) { (anchor - smallAnchor).coerceAtLeast(0f).toDp() }
        val second = maxOf(metricHeight,
            measuredHeight(data.region, regionWidth, regionStyle, 2) + baselineExtra,
            measuredHeight(data.lanInterface, regionWidth, regionStyle, 2) + baselineExtra)
        val rows = HomeGridRows(heading, maxOf(0.dp, (48.dp - heading) / 2), first, second, baseline,
            footer = if (LocalHomeShortSpacing.current) 10.dp else 14.dp)
        CompositionLocalProvider(LocalHomeGridRows provides rows, LocalGridStackedMetrics provides stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().testTag("home-network-group"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeNetwork(data, Modifier.weight(1f).height(rows.height))
                    HomeSpeed(data, Modifier.weight(1f).height(rows.height), connections)
                }
                Row(Modifier.fillMaxWidth().testTag("home-health-group"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeSubscription(data, Modifier.weight(1f).height(rows.height), subscription)
                    HomeResources(data, Modifier.weight(1f).height(rows.height))
                }
            }
        }
    }
}

@Composable
private fun HomeDataHeading(title: String, modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null) {
    val p = LocalHomePalette.current
    Row(modifier.fillMaxWidth().height(LocalHomeGridRows.current.heading), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeNetwork(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    var isLan by rememberSaveable { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val transition = rememberNetworkModeTransition(isLan, motion)
    val shownLan = if (motion) transition.displayedLan else isLan
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    // Native clickable consumes the child Details click; no parent Initial-pass interception.
    // A horizontal drag cancels the press, while vertical drags remain owned by LazyColumn.
    HomeCard(modifier.testTag("home-network").semantics { stateDescription = if (isLan) "LAN" else "WAN" }
        .draggable(state = rememberDraggableState { dragDistance += it }, orientation = Orientation.Horizontal,
            onDragStarted = { dragDistance = 0f }, onDragStopped = {
                if (dragDistance < -threshold) isLan = true
                else if (dragDistance > threshold) isLan = false
                dragDistance = 0f
            }), onClick = { isLan = !isLan }, clickLabel = "切换 WAN 和 LAN") {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            HomeDataHeading(if (shownLan) "LAN" else "WAN", Modifier.testTag("home-network-switch")) {
                Box(Modifier.requiredSize(48.dp)
                    .testTag("home-network-details").homeClick(label = "网络详情", onClick = { details = true }),
                    contentAlignment = Alignment.Center) {
                    Text("详情", Modifier.clip(CircleShape).background(p.blue.copy(alpha = .07f))
                        .padding(horizontal = 9.dp, vertical = 3.dp), color = p.blue,
                        fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            // The same body exits, swaps at zero opacity, then enters. No dual IP layers.
            key(Unit) {
                Column(Modifier.fillMaxWidth().testTag("home-network-body").graphicsLayer {
                    val progress = if (motion) transition.progress.value else 1f
                    alpha = progress.coerceIn(0f, 1f)
                    rotationY = if (motion) networkPerspective(progress, transition.leaving) else 0f
                    cameraDistance = 800f * density
                    scaleX = .97f + .03f * progress
                    scaleY = scaleX
                    translationY = (1f - progress) * (if (transition.leaving) -6f else 6f) * density
                }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (LocalGridStackedMetrics.current) {
                        Column(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {
                            HomeLabel("IP", Modifier.testTag("home-label-IP"))
                            Spacer(Modifier.height(2.dp))
                            HomeSingleLineAddress(if (shownLan) data.lan else data.wan, p.text,
                                Modifier.fillMaxWidth().testTag("home-network-ip"))
                        }
                    } else Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {
                        val anchor = with(LocalDensity.current) { LocalHomeGridRows.current.baseline.roundToPx() }
                        Spacer(Modifier.width(0.dp).height(LocalHomeGridRows.current.first).alignBy { anchor })
                        HomeLabel("IP", Modifier.width(18.dp).alignByBaseline().testTag("home-label-IP"))
                        Spacer(Modifier.width(4.dp))
                        HomeSingleLineAddress(if (shownLan) data.lan else data.wan, p.text,
                            Modifier.weight(1f).alignByBaseline().testTag("home-network-ip"))
                    }
                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.second), verticalAlignment = Alignment.Top) {
                        val base = LocalHomeGridRows.current.baseline
                        val anchor = with(LocalDensity.current) { base.roundToPx() }
                        Spacer(Modifier.width(0.dp).height(LocalHomeGridRows.current.second).alignBy { anchor })
                        HomeLabel(if (shownLan) "接口" else "地区", Modifier.width(26.dp).alignByBaseline().testTag("home-label-network-region"))
                        Spacer(Modifier.width(4.dp))
                        val flag = if (shownLan) "" else CompactHomeFormat.flag(data.countryCode)
                        if (flag.isNotBlank()) {
                            Box(Modifier.alignByBaseline().clip(RoundedCornerShape(4.dp)).background(p.blue.copy(alpha = .06f))
                                .padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                                Text(flag, fontSize = 13.sp, lineHeight = 18.sp)
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        Text((if (shownLan) data.lanInterface else data.region)
                            .takeUnless { it.isBlank() || it == "—" } ?: if (shownLan) "接口未知" else "地区未知",
                            Modifier.weight(1f).alignByBaseline().testTag("home-value-network-region"), color = p.text.copy(alpha = .82f),
                            fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(LocalHomeGridRows.current.footer))
        }
    }
    if (details) NativeNetworkDetails(data) { details = false }
}

@Composable
private fun MetricLine(label: String, value: String, color: Color = LocalHomePalette.current.text,
    transmitting: Boolean = false) {
    val rows = LocalHomeGridRows.current
    val first = label in listOf("上行", "已用", "内存")
    val modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 4.dp)
        .height(if (first) rows.first else rows.second)
    @Composable fun Label(modifier: Modifier = Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            if (label == "上行" || label == "下行") {
                val tint = if (label == "上行") LocalHomePalette.current.blue else Color(0xFF10B981)
                val motion = LocalHomeMotion.current
                val alpha = if (transmitting && motion) {
                    val pulse = rememberInfiniteTransition(label = "traffic-$label")
                    pulse.animateFloat(.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "traffic-dot").value
                } else if (transmitting) 1f else .3f
                Box(Modifier.size(5.dp).graphicsLayer { this.alpha = alpha }.background(tint, CircleShape)
                    .testTag("home-traffic-dot-$label").semantics { stateDescription = if (transmitting) "有传输" else "无传输" })
                Spacer(Modifier.width(4.dp))
            }
            HomeLabel(label, Modifier.alignByBaseline().testTag("home-label-$label"))
        }
    }
    if (LocalGridStackedMetrics.current) {
        Column(modifier) {
            Label()
            // Keep even large units available inside their own scroll viewport.
            HomeNumber(value, Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).width(IntrinsicSize.Max)
                .testTag("home-value-$label"), color)
        }
    } else {
        Row(modifier) {
            Label(Modifier.alignByBaseline())
            Spacer(Modifier.width(6.dp))
            HomeNumber(value, Modifier.weight(1f).alignByBaseline().testTag("home-value-$label"), color)
        }
    }
}

@Composable
private fun HomeSpeed(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val up = data.up
    val down = data.down
    HomeCard(modifier.testTag("home-speed"), click) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            HomeDataHeading("网速")
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {
                MetricLine("上行", if (data.running) CompactHomeFormat.bytes(up) + "/s" else "—", transmitting = data.running && up > 0L)
                MetricLine("下行", if (data.running) CompactHomeFormat.bytes(down) + "/s" else "—", transmitting = data.running && down > 0L)
                Spacer(Modifier.height(LocalHomeGridRows.current.footer))
            }
        }
    }
}

/** Common six-dp tracks align the subscription and resource cards, including unknown data. */
@Composable
internal fun HomeUsageBar(fraction: Float?, tag: String, description: String,
    tint: Color = LocalHomePalette.current.blue) {
    val motion = LocalHomeMotion.current
    val target = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val track = if (dark) Color(0xFF334155) else Color(0xFFE2E8F0)
    val progress by animateFloatAsState(target ?: 0f,
        if (motion) tween(500, easing = FastOutSlowInEasing) else snap(), label = "$tag-progress")
    val color by animateColorAsState(tint, if (motion) tween(300) else snap(), label = "$tag-color")
    Box(Modifier.fillMaxWidth().padding(top = LocalHomeGridRows.current.footer - 6.dp).height(6.dp).clip(CircleShape)
        .background(track).testTag(tag).semantics {
            if (target != null) progressBarRangeInfo = ProgressBarRangeInfo(target, 0f..1f)
            stateDescription = description
        }) {
        Canvas(Modifier.matchParentSize()) {
            if (target != null && progress > 0f) drawRoundRect(color,
                size = androidx.compose.ui.geometry.Size(size.width * progress, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f))
        }
    }
}

@Composable
private fun HomeSubscription(data: CompactHomeData, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    val remaining = CompactHomeFormat.remaining(data.used, data.total)
    val fraction = CompactHomeFormat.usedFraction(data.used, data.total)
    HomeCard(modifier.testTag("home-subscription"), click) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            HomeDataHeading("订阅") {
                if (remaining != null) Text("剩余 $remaining%",
                    Modifier.clip(CircleShape).background(p.blue.copy(alpha = .07f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    color = p.blue, fontSize = 11.sp, lineHeight = 16.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {
                MetricLine("已用", if (data.total > 0) CompactHomeFormat.bytes(data.used.coerceAtLeast(0)) else "—")
                MetricLine("总量", if (data.total > 0) CompactHomeFormat.bytes(data.total) else "—")
                HomeUsageBar(fraction, "home-subscription-bar",
                    if (fraction == null) "订阅总量未知" else "流量已用 ${String.format(Locale.US, "%.1f", fraction * 100)}%")
            }
        }
    }
}

@Composable
private fun HomeResources(data: CompactHomeData, modifier: Modifier) {
    val p = LocalHomePalette.current
    val motion = LocalHomeMotion.current
    val known = data.running && data.cpu.isFinite() && data.cpu >= 0
    val cpu = if (known) data.cpu else 0f
    val tint = when { !known -> p.muted; data.cpu >= 100 -> p.red
        data.cpu >= 80 -> Color(0xFFD97706); else -> p.blue }
    HomeCard(modifier.testTag("home-resources")) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            HomeDataHeading("资源占用")
            Spacer(Modifier.height(LocalHomeGridRows.current.gap))
            Column {
                MetricLine("内存", if (data.running && data.memory > 0) CompactHomeFormat.bytes(data.memory) else "—")
                MetricLine("CPU", if (known) String.format(Locale.US, "%.1f%%", cpu) else "—",
                    if (known && data.cpu >= 80) tint else p.text)
                HomeUsageBar(if (known) (data.cpu / 100).coerceIn(0f, 1f) else null, "home-cpu-bar",
                    if (known) "CPU ${data.cpu}%" else "CPU 未知", tint)
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
