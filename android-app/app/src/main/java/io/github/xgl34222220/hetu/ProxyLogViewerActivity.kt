package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.ReferenceButton as Button

import io.github.xgl34222220.hetu.ui.ReferenceModalBottomSheet as ModalBottomSheet

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Article
import androidx.compose.foundation.horizontalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
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

@Composable
internal fun HxLogFilesScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val c = Hx.colors
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<HetuLogFile>>(emptyList()) }
    var selectedPath by rememberSaveable { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var pickFile by remember { mutableStateOf(false) }
    var pickerAnchor by remember { mutableStateOf<Rect?>(null) }
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

    HxPage(flatCanvas = true,
        title = selected?.name ?: "core.log",
        largeTitle = false,
        compactTitleFontSizeSp = 20f,
        subtitle = if (selected == null) "查看运行日志与调试输出" else "${lines.size} 行 · ${selected.path.removePrefix(LOG_ROOT + "/")}",
        onBack = onBack,
        refreshing = loading && content.isNotEmpty() && !autoRefresh,
        onRefresh = { revision++ },
        actions = {
            HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) query = ""
            })
            IconButton(onClick = { pickFile = true }, modifier = Modifier.onGloballyPositioned { pickerAnchor = it.boundsInWindow() }) {
                Icon(Icons.Rounded.FolderOpen, "选择日志", tint = c.text)
            }
            HxBarAction(Icons.Rounded.DeleteOutline, "清空当前日志", onClick = { clearConfirm = true }, enabled = selected != null && !loading)
        },
    ) {
        item(key = "controls") {
            Column(Modifier.padding(horizontal = 14.dp).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (searching) HxLogSearchField(query, { query = it })
                HxLogLevelFilter(
                    selected = level,
                    onSelect = { level = it },
                )
                HxLogControls(
                    autoRefresh = autoRefresh,
                    newestFirst = newestFirst,
                    onAutoRefresh = { autoRefresh = it; prefs.edit().putBoolean("logAutoRefresh", it).apply() },
                    onOrderChange = { newestFirst = !newestFirst },
                )
            }
        }
        if (error.isNotBlank()) {
            item(key = "error") { HxBanner(error, tone = HxTone.Bad, modifier = Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) }
        }
        when {
            loading && content.isEmpty() -> item(key = "loading") { HxSkeletonRows(8) }
            files.isEmpty() -> item(key = "none") { HxEmpty(Icons.Rounded.Article, "暂无日志文件", "代理运行后会在运行目录生成日志") }
            lines.isEmpty() -> item(key = "empty") { HxEmpty(Icons.Rounded.SearchOff, "当前筛选条件下没有日志") }
            else -> itemsIndexed(lines, key = { index, line -> "l:" + index + ":" + line.hashCode() }) { index, line ->
                HxLogLine(line, first = index == 0, last = index == lines.lastIndex)
            }
        }
    }

    if (pickFile) {
        HxLogFilePicker(
            files = files,
            selectedPath = selectedPath,
            anchor = pickerAnchor,
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

@Composable
private fun HxLogSearchField(value: String, onChange: (String) -> Unit) {
    val c = Hx.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focus.requestFocus() }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(Hx.pillShape).background(c.surface)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 9.dp)
                .semantics { contentDescription = "搜索日志" },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = c.text, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("搜索日志", color = c.textFaint, fontSize = 16.sp, lineHeight = 22.sp)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }, modifier = Modifier.size(40.dp)) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(c.textFaint.copy(alpha = .3f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "清除", tint = c.surface, modifier = Modifier.size(14.dp))
                }
            }
        } else Spacer(Modifier.width(14.dp))
    }
}

@Composable
private fun HxLogLevelFilter(selected: String, onSelect: (String) -> Unit) {
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    val options = listOf("all" to "全部", "error" to "错误", "warn" to "警告", "info" to "信息", "debug" to "调试")
    Row(
        Modifier.fillMaxWidth().clip(HomeContinuousShape(24.dp)).background(c.surface)
            .padding(8.dp).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, (key, label) ->
            val active = selected == key
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (active) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = active, role = Role.Tab) {
                        if (!active) { haptics.perform(HetuHaptic.Tick); onSelect(key) }
                    }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) c.accent else c.textMuted, fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
            }
            if (index != options.lastIndex) {
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c.line))
            }
        }
    }
}

@Composable
private fun HxLogControls(
    autoRefresh: Boolean,
    newestFirst: Boolean,
    onAutoRefresh: (Boolean) -> Unit,
    onOrderChange: () -> Unit,
) {
    val c = Hx.colors
    Row(
        Modifier.fillMaxWidth().clip(HomeContinuousShape(24.dp)).background(c.surface).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(if (autoRefresh) c.accentSoft else c.surfaceMuted)
                .toggleable(value = autoRefresh, role = Role.Switch, onValueChange = onAutoRefresh)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            val tint = if (autoRefresh) c.accent else c.textMuted
            Icon(Icons.Rounded.Sync, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (autoRefresh) "自动刷新中" else "自动刷新", color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(c.surfaceMuted)
                .clickable(role = Role.Button, onClick = onOrderChange).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Sort, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (newestFirst) "最新在前" else "最早在前", color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HxLogFilePicker(
    files: List<HetuLogFile>,
    selectedPath: String,
    anchor: Rect?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Hx.colors
    @Composable
    fun Choices(pick: (String) -> Unit) {
        Text("选择日志", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp))
        HorizontalDivider(Modifier.padding(horizontal = 10.dp), thickness = .5.dp, color = c.line)
        if (files.isEmpty()) {
            Text("暂无日志", color = c.textMuted, modifier = Modifier.padding(16.dp))
        }
        files.forEachIndexed { index, file ->
            val selected = file.path == selectedPath
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { pick(file.path) })
                    .semantics { contentDescription = file.path }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape)
                        .background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                        .then(if (selected) Modifier else Modifier.border(1.25.dp, c.textFaint, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, color = c.text, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    // Preserve disambiguation when different folders contain the same filename.
                    if (files.count { it.name == file.name } > 1) {
                        Text(file.path.removePrefix(LOG_ROOT + "/"), color = c.textMuted, fontSize = 11.sp)
                    }
                }
            }
            if (index != files.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 10.dp), thickness = .5.dp, color = c.line)
        }
    }
    if (anchor != null) {
        HxAnchoredMenu(anchor, onDismiss, minWidth = 152.dp, anchorEndInset = (-36).dp) { close ->
            Choices { path -> close { onPick(path) } }
        }
    } else {
        HxSheet(onDismiss = onDismiss) {
            val close = LocalHxSheetClose.current
            Choices { path -> close { onPick(path) } }
        }
    }
}

@Composable
private fun LazyItemScope.HxLogLine(line: String, first: Boolean, last: Boolean) {
    val c = Hx.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember(line) { mutableStateOf(false) }
    val parsed = remember(line) { parseStructuredLog(line) }
    val fallback = remember(line) { refParseLogs19(line, 1).firstOrNull() }
    val level = parsed?.level?.takeIf { it.isNotBlank() }?.lowercase() ?: hxLineLevel(line)
    val time = remember(parsed?.time, fallback?.time) {
        Regex("""\d{2}:\d{2}:\d{2}""").find(parsed?.time ?: fallback?.time.orEmpty())?.value.orEmpty()
    }
    val message = parsed?.message ?: fallback?.message ?: line
    val tint = when (level) { "error", "fatal" -> c.bad; "warn", "warning" -> c.warn; "debug" -> c.textFaint; else -> c.accent }
    Column(
        Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(HxMotion.Medium), placementSpec = HxMotion.glide(), fadeOutSpec = null)
            .fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 12.dp)
            .clip(HomeContinuousShape(24.dp)).background(c.surface)
            .hxCombinedClick(onLongClick = { hxCopy(context, "日志", line) }, onClick = { expanded = !expanded })
            .animateContentSize(androidx.compose.animation.core.tween(HxMotion.Medium, easing = HxMotion.Emphasized))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (time.isNotBlank()) {
                Text(time, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(10.dp))
            }
            Text(level.uppercase(), fontSize = 13.sp, lineHeight = 17.sp, color = tint,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = .1f)).padding(horizontal = 10.dp, vertical = 2.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text(if (expanded) line else message, fontSize = 16.sp, lineHeight = 23.sp, color = c.text,
            maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
    }
}
