package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.ReferenceButton as Button

import io.github.xgl34222220.hetu.ui.ReferenceModalBottomSheet as ModalBottomSheet

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.graphicsLayer
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMenuDivider
import io.github.xgl34222220.hetu.home.HomeMenuItem
import io.github.xgl34222220.hetu.home.HomeMenuTitle
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeSearchField
import io.github.xgl34222220.hetu.home.HomeSegmentStyle
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.home.homeSpinAngle
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProxyLogViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hxHost { vm -> HxLogFilesScreen(vm) { finish() } }
    }
}

private data class HetuLogFile(
    val name: String,
    val path: String,
)

private const val LOG_ROOT = "/data/adb/hetu"

private fun logQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

private fun validLogPath(path: String): Boolean =
    path.startsWith(LOG_ROOT + "/") &&
        path.endsWith(".log") &&
        !path.contains("/../") &&
        path.length <= 512

private suspend fun listLogFiles(context: android.content.Context): List<HetuLogFile> =
    withContext(Dispatchers.IO) {
        val command =
            "find " + logQuote(LOG_ROOT) +
                " -maxdepth 2 -type f -name '*.log' -print 2>/dev/null | head -n 200"
        val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
        if (!result.ok()) return@withContext emptyList()
        result.output.lineSequence()
            .map(String::trim)
            .filter(::validLogPath)
            .map { path -> HetuLogFile(File(path).name, path) }
            .distinctBy { it.path }
            .sortedWith(
                compareBy<HetuLogFile> { it.name != "core.log" }
                    .thenBy { it.name.lowercase() },
            )
            .toList()
    }

private suspend fun readLogFile(
    context: android.content.Context,
    file: HetuLogFile,
): String = withContext(Dispatchers.IO) {
    require(validLogPath(file.path)) { "日志路径无效" }
    val result = RootBridge.rootShell(
        context.applicationContext,
        "tail -n 1600 " + logQuote(file.path) + " 2>/dev/null",
        8_000L,
    )
    if (!result.ok()) {
        throw IllegalStateException(result.output.ifBlank { "日志读取失败" })
    }
    result.output.ifBlank { "日志为空" }
}

private suspend fun clearLogFile(
    context: android.content.Context,
    file: HetuLogFile,
) = withContext(Dispatchers.IO) {
    require(validLogPath(file.path)) { "日志路径无效" }
    val result = RootBridge.rootShell(
        context.applicationContext,
        ": > " + logQuote(file.path),
        6_000L,
    )
    if (!result.ok()) {
        throw IllegalStateException(result.output.ifBlank { "日志清空失败" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyLogViewerScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<HetuLogFile>>(emptyList()) }
    var selectedPath by rememberSaveable { mutableStateOf("") }
    var content by remember { mutableStateOf("正在读取…") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    var autoRefresh by rememberSaveable { mutableStateOf(prefs.getBoolean("logAutoRefresh", false)) }
    var cards by rememberSaveable { mutableStateOf(prefs.getBoolean("logCardView", true)) }
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("all") }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(autoRefresh, lifecycle) {
        if (autoRefresh) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(2000); if (!loading) revision++ }
        }
    }
    val visibleLines = remember(content, query, level) {
        content.lines().filter { line -> line.isNotBlank() && line.contains(query, true) && (level == "all" || line.contains(level, true)) }
    }

    LaunchedEffect(revision, selectedPath) {
        loading = true
        error = ""
        try {
            files = listLogFiles(context)
            val selected =
                files.firstOrNull { it.path == selectedPath } ?: files.firstOrNull()
            if (selected == null) {
                selectedPath = ""
                content = "暂无日志文件"
            } else {
                if (selectedPath != selected.path) selectedPath = selected.path
                content = readLogFile(context, selected)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "日志读取失败"
            content = error
        } finally {
            loading = false
        }
    }

    val selected = files.firstOrNull { it.path == selectedPath }

    Column(
        Modifier
            .fillMaxSize()
            .crystalPageBackground(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .heightIn(min = HetuPageMetrics.ToolbarHeight)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
            }

            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { menuOpen = true },
                    contentPadding = PaddingValues(horizontal = 6.dp),
                ) {
                    Text(
                        selected?.name ?: "日志查看",
                        color = t.textPrimary,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    if (files.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("暂无日志") },
                            onClick = { menuOpen = false },
                        )
                    } else {
                        files.forEach { file ->
                            DropdownMenuItem(
                                text = { Text(file.name) },
                                trailingIcon = {
                                    if (file.path == selectedPath) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                },
                                onClick = {
                                    selectedPath = file.path
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = { revision++ },
                enabled = !loading,
                modifier = Modifier.size(40.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.Refresh, "刷新", tint = t.textSecondary)
                }
            }

            IconButton(
                onClick = { if (selected != null) clearConfirm = true },
                enabled = selected != null && !loading,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, "清空当前日志", tint = t.textSecondary)
            }

            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Rounded.MoreHoriz, "选择日志", tint = t.textSecondary)
            }
        }

        LiquidGlassTextField(query, { query = it }, "搜索日志", Modifier.fillMaxWidth().padding(horizontal = 10.dp), leadingIcon = Icons.Rounded.Search)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidChoicePill(if (autoRefresh) "自动刷新中" else "自动刷新", autoRefresh, { autoRefresh = !autoRefresh; prefs.edit().putBoolean("logAutoRefresh", autoRefresh).apply() })
            LiquidChoicePill("逐条卡片", cards, { cards = !cards; prefs.edit().putBoolean("logCardView", cards).apply() })
            listOf("all" to "全部", "error" to "错误", "warn" to "警告", "info" to "信息", "debug" to "调试").forEach { (key,label) -> LiquidChoicePill(label, level == key, { level = key }) }
        }
        if (error.isNotBlank()) HetuTaskFeedback(error, true, modifier = Modifier.padding(horizontal = 10.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(12.dp, 6.dp, 12.dp, hetuContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(if (cards) 6.dp else 0.dp)) {
            if (visibleLines.isEmpty()) item { Text("没有匹配的日志", color = t.textSecondary, modifier = Modifier.padding(12.dp)) }
            itemsIndexed(visibleLines, key = { index, _ -> index }) { _, line ->
                if (cards) StructuredLogCard(line)
                else androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(line, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        color = t.textPrimary, fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
        }
    }

    val clearTarget = selected
    if (clearConfirm && clearTarget != null) {
        ModalBottomSheet(
            onDismissRequest = { clearConfirm = false },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "清空 " + clearTarget.name + "？",
                    color = t.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "只清空当前日志，不影响代理配置和其他日志。",
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Button(
                    onClick = {
                        clearConfirm = false
                        scope.launch {
                            try {
                                clearLogFile(context, clearTarget)
                                revision++
                            } catch (failure: Exception) {
                                error = failure.message ?: "日志清空失败"
                                content = error
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = CircleShape,
                ) {
                    Text("清空")
                }
            }
        }
    }
}


/* ------------------------------------------------------------------ */
/*  Hx log files viewer                                                 */
/* ------------------------------------------------------------------ */

private fun hxLineLevel(line: String): String {
    val l = line.lowercase()
    return when {
        "error" in l || "fatal" in l || "[e]" in l -> "error"
        "warn" in l || "[w]" in l -> "warn"
        "debug" in l || "[d]" in l -> "debug"
        else -> "info"
    }
}

/**
 * 日志: one log file at a time, newest lines first by default. The bar picks the file, searches
 * it and clears it; the two cards under it filter by level and switch live refresh and order.
 */
@Composable
internal fun HxLogFilesScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<HetuLogFile>>(emptyList()) }
    var selectedPath by rememberSaveable { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var pickFile by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var autoRefresh by rememberSaveable { mutableStateOf(prefs.getBoolean("logAutoRefresh", false)) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("all") }
    val lifecycle = LocalLifecycleOwner.current

    LaunchedEffect(autoRefresh, lifecycle) {
        if (autoRefresh) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { delay(2000); if (!loading) revision++ }
        }
    }
    LaunchedEffect(revision, selectedPath) {
        loading = true
        try {
            files = listLogFiles(context)
            val selected = files.firstOrNull { it.path == selectedPath } ?: files.firstOrNull()
            if (selected == null) {
                selectedPath = ""
                content = ""
            } else {
                if (selectedPath != selected.path) selectedPath = selected.path
                content = readLogFile(context, selected)
            }
            error = ""
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "日志读取失败"
        } finally {
            loading = false
        }
    }
    val lines = remember(content, query, level, newestFirst) {
        content.lines()
            .filter { it.isNotBlank() && (query.isBlank() || it.contains(query, true)) && (level == "all" || hxLineLevel(it) == level) }
            .let { if (newestFirst) it.asReversed() else it }
    }
    val selected = files.firstOrNull { it.path == selectedPath }

    HxPage(
        title = selected?.name ?: "core.log",
        largeTitle = false,
        subtitle = if (selected == null) ht("查看运行日志与调试输出") else "${lines.size} 行 · ${selected.path.removePrefix(LOG_ROOT + "/")}",
        onBack = onBack,
        refreshing = loading && content.isNotEmpty() && !autoRefresh,
        onRefresh = { revision++ },
        actions = {
            HxBarAction(if (searching) PanelIcons.SearchX else ToolsIcons.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) query = ""
            })
            HxBarAction(HxIcons.FolderOpen, "选择日志", onClick = { pickFile = true }, anchorMenu = true)
            HxBarAction(ToolsIcons.Trash2, "清空当前日志", onClick = { clearConfirm = true }, enabled = selected != null && !loading)
        },
    ) {
        item(key = "controls") {
            Column(Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap)) {
                HomeReveal(searching) {
                    HomeSearchField(query, { query = it }, "搜索日志", Modifier.padding(bottom = 10.dp), icon = ToolsIcons.Search)
                }
                HxLogLevelFilter(selected = level, onSelect = { level = it })
                Spacer(Modifier.height(10.dp))
                HxLogControls(
                    autoRefresh = autoRefresh,
                    newestFirst = newestFirst,
                    onAutoRefresh = { autoRefresh = it; prefs.edit().putBoolean("logAutoRefresh", it).apply() },
                    onOrderChange = { newestFirst = !newestFirst },
                )
            }
        }
        if (error.isNotBlank()) {
            item(key = "error") { HxBanner(error, tone = HxTone.Bad, modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap)) }
        }
        when {
            loading && content.isEmpty() -> item(key = "loading") { HxSkeletonRows(6) }
            files.isEmpty() -> item(key = "none") { HxEmpty(PanelIcons.ScrollText, "暂无日志文件", ht("代理运行后会在运行目录生成日志")) }
            lines.isEmpty() -> item(key = "empty") { HxEmpty(PanelIcons.SearchX, "当前筛选条件下没有日志") }
            else -> itemsIndexed(lines, key = { index, line -> "l:" + index + ":" + line.hashCode() }) { _, line -> HxLogLine(line) }
        }
    }

    if (pickFile) {
        HxLogFilePicker(
            files = files,
            selectedPath = selectedPath,
            onPick = { selectedPath = it; pickFile = false },
            onDismiss = { pickFile = false },
        )
    }
    val clearTarget = selected
    if (clearConfirm && clearTarget != null) {
        HxConfirmDialog(
            title = "清空 ${clearTarget.name}？",
            message = "只清空当前日志，不影响代理配置和其他日志。",
            confirmLabel = "清空",
            danger = true,
            onConfirm = {
                clearConfirm = false
                scope.launch {
                    try {
                        clearLogFile(context, clearTarget)
                        vm.toast("已清空 ${clearTarget.name}")
                        revision++
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        vm.toast(failure.message ?: "日志清空失败")
                    }
                }
            },
            onDismiss = { clearConfirm = false },
        )
    }
}

/** 全部 / 错误 / 警告 / 信息 / 调试. */
@Composable
private fun HxLogLevelFilter(selected: String, onSelect: (String) -> Unit) {
    val c = LocalHomeColors.current
    HomeSegmented(
        listOf("all" to ht("全部"), "error" to ht("错误"), "warn" to ht("警告"), "info" to ht("信息"), "debug" to ht("调试")),
        selected, onSelect, Modifier.fillMaxWidth(),
        style = HomeSegmentStyle.Soft, track = c.surface, height = 56.dp, corner = 20.dp, textStyle = HomeType.buttonSmall.copy(fontSize = 15.sp), inset = 6.dp,
    )
}

/** Live refresh on the left, line order on the right. The refresh glyph turns while it is on. */
@Composable
private fun HxLogControls(
    autoRefresh: Boolean,
    newestFirst: Boolean,
    onAutoRefresh: (Boolean) -> Unit,
    onOrderChange: () -> Unit,
) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    val motion = LocalHomeMotionEnabled.current
    Row(
        Modifier.fillMaxWidth().clip(HomeDims.cardShape).background(c.surface).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val liveSource = remember { MutableInteractionSource() }
        val liveFill by animateColorAsState(if (autoRefresh) c.accentSoft else c.sunken, HomeMotion.fade(motion), label = "log-live-fill")
        val liveTint by animateColorAsState(if (autoRefresh) c.accent else c.t2, HomeMotion.fade(motion), label = "log-live-tint")
        val angle = homeSpinAngle(autoRefresh, periodMs = 2400)
        Row(
            Modifier.weight(1f).heightIn(min = 50.dp).clip(HomeDims.controlShape).background(liveFill).homeRowPressTint(liveSource)
                .toggleable(value = autoRefresh, interactionSource = liveSource, indication = null, role = Role.Switch) { on -> haptics(HomeHaptic.Tick); onAutoRefresh(on) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(HomeIcons.RefreshCw, null, Modifier.size(22.dp).graphicsLayer { rotationZ = angle }, tint = liveTint)
            Spacer(Modifier.width(8.dp))
            Text(ht(if (autoRefresh) "自动刷新中" else "自动刷新"), color = liveTint, style = HomeType.buttonSmall.copy(fontSize = 15.sp), maxLines = 1)
        }
        val orderSource = remember { MutableInteractionSource() }
        val flip by animateFloatAsState(if (newestFirst) 0f else 180f, HomeMotion.glide(motion), label = "log-order-flip")
        Row(
            Modifier.weight(1f).heightIn(min = 50.dp).clip(HomeDims.controlShape).background(c.sunken).homeRowPressTint(orderSource)
                .clickable(interactionSource = orderSource, indication = null, role = Role.Button) { haptics(HomeHaptic.Tick); onOrderChange() }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(ToolsFeatureIcons.ArrowDownWideNarrow, null, Modifier.size(22.dp).graphicsLayer { rotationX = flip }, tint = c.t2)
            Spacer(Modifier.width(8.dp))
            Text(ht(if (newestFirst) "最新在前" else "最早在前"), color = c.t2, style = HomeType.buttonSmall.copy(fontSize = 15.sp), maxLines = 1)
        }
    }
}

/** Which log to read: a menu from the folder button, or a sheet when there is no touch to grow it from. */
@Composable
private fun HxLogFilePicker(
    files: List<HetuLogFile>,
    selectedPath: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalHomeColors.current
    @Composable
    fun Choices(pick: (String) -> Unit) {
        if (files.isEmpty()) {
            Text(ht("暂无日志"), Modifier.padding(horizontal = 12.dp, vertical = 12.dp), color = c.t2, style = HomeType.label)
        }
        files.forEach { file ->
            HomeMenuItem(
                file.name, { pick(file.path) }, Modifier.semantics { contentDescription = file.path },
                // Preserve disambiguation when different folders contain the same filename.
                description = if (files.count { it.name == file.name } > 1) file.path.removePrefix(LOG_ROOT + "/") else null,
                checked = file.path == selectedPath,
            )
        }
    }
    val anchor = remember { HxAnchor.take() }
    if (anchor != null) {
        HxAnchoredMenu(anchor, onDismiss, minWidth = 200.dp, anchorEndInset = 0.dp) { close ->
            HomeMenuTitle(ht("选择日志"))
            HomeMenuDivider()
            Choices { path -> close { onPick(path) } }
        }
    } else {
        HxSheet(onDismiss = onDismiss, title = ht("选择日志")) {
            val close = LocalHxSheetClose.current
            Column(Modifier.padding(horizontal = 8.dp)) { Choices { path -> close { onPick(path) } } }
        }
    }
}

/**
 * One line of the log as a card: when, how serious, and what was said. A tap unfolds the raw
 * line; a long press copies it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.HxLogLine(line: String) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val haptics = LocalHomeHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(line) { mutableStateOf(false) }
    val parsed = remember(line) { parseStructuredLog(line) }
    val fallback = remember(line) { refParseLogs19(line, 1).firstOrNull() }
    val level = parsed?.level?.takeIf { it.isNotBlank() }?.lowercase() ?: hxLineLevel(line)
    val time = remember(parsed?.time, fallback?.time) {
        Regex("""\d{2}:\d{2}:\d{2}""").find(parsed?.time ?: fallback?.time.orEmpty())?.value.orEmpty()
    }
    val message = parsed?.message ?: fallback?.message ?: line
    val tone = when (level) { "error", "fatal" -> HomeTone.Bad; "warn", "warning" -> HomeTone.Warn; "debug" -> HomeTone.Neutral; else -> HomeTone.Accent }
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier.then(hetuAnimateItem(motion))
            .fillMaxWidth().padding(horizontal = HomeDims.gutter).padding(bottom = 10.dp)
            .clip(HomeDims.cardShape).background(c.surface)
            .homeRowPressTint(source)
            .combinedClickable(
                interactionSource = source, indication = null,
                onLongClick = { haptics(HomeHaptic.Confirm); hxCopy(context, "日志", line) },
                onClick = { haptics(HomeHaptic.Tick); expanded = !expanded },
            )
            .animateContentSize(HomeMotion.glide(motion))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (time.isNotBlank()) Text(time, color = c.t2, style = HomeType.delay.copy(fontSize = 15.sp))
            HomePill(if (level.equals("warning", true)) "WARN" else level.uppercase(), tone = tone, height = 22.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (expanded) line else message, color = c.t1,
            style = if (expanded) HomeType.mono.copy(fontSize = 13.5.sp, lineHeight = 21.sp) else HomeType.body.copy(lineHeight = 24.sp),
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
        )
    }
}
