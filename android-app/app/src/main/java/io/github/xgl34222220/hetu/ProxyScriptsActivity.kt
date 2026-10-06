package io.github.xgl34222220.hetu


import io.github.xgl34222220.hetu.ui.ReferenceButton as Button

import io.github.xgl34222220.hetu.ui.ReferenceModalBottomSheet as ModalBottomSheet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyScriptsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyScriptsScreen { finish() } } }
    }
}

private data class ScriptEntry(
    val title: String,
    val subtitle: String,
    val path: String,
)

private fun scriptSize(bytes: Long): String = when {
    bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyScriptsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val t = LocalHetuTokens.current
    val scope = rememberCoroutineScope()
    val entries = remember {
        listOf(
            ScriptEntry(
                "服务启动前",
                "代理核心启动前执行；非 0 退出码会阻止手动启动",
                ProxyScriptHooks.PRE_START,
            ),
            ScriptEntry(
                "服务停止后",
                "代理网络恢复完成后执行；非 0 退出码会报告失败",
                ProxyScriptHooks.POST_STOP,
            ),
        )
    }
    var editor by remember { mutableStateOf<ScriptEntry?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var environment by remember { mutableStateOf(false) }
    var managedRevision by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var hookRevision by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    var deleteTarget by remember { mutableStateOf<ManagedScript?>(null) }
    val fixedStatus by produceState(initialValue = mapOf<String, Boolean>(), hookRevision) {
        value = try { entries.associate { it.path to ProxyScriptHooks.read(context, it.path).isNotBlank() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { emptyMap() }
    }
    val managedScripts by produceState(initialValue = emptyList<ManagedScript>(), managedRevision) {
        value = try {
            ProxyScriptHooks.listManaged(context)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "脚本列表读取失败"; messageError = true
            emptyList()
        } finally { refreshing = false }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            messageError = false; message = ""
            try {
                val input = ProxyScriptImport.read(context, uri)
                val imported = ProxyScriptHooks.importManaged(context, input.name, input.bytes)
                message = "已导入 " + imported.name
                managedRevision++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本导入失败"; messageError = true
            } finally {
                busy = false
            }
        }
    }

    fun open(entry: ScriptEntry) {
        if (busy) return
        scope.launch {
            busy = true
            messageError = false; message = ""
            try {
                text = ProxyScriptHooks.read(context, entry.path)
                editor = entry
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本读取失败"; messageError = true
            } finally {
                busy = false
            }
        }
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFECEEFB)
    io.github.xgl34222220.hetu.tools.ToolsPullRefresh(refreshing, {
        if (!busy && !refreshing) { refreshing = true; managedRevision++; hookRevision++ }
    }, Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(
            start = 14.dp,
            top = 8.dp,
            end = 14.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary, modifier = Modifier.size(28.dp))
                    }
                    Text("脚本", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.size(42.dp).onGloballyPositioned { menuAnchor = it.boundsInWindow() },
                    ) {
                        Icon(Icons.Rounded.MoreHoriz, "更多", tint = t.textPrimary, modifier = Modifier.size(28.dp))
                    }
                }

            }
        }

        item("hooks") {
            Surface(
                shape = HomeContinuousShape(24.dp),
                color = t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 70.dp).clickable { open(entry) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (entry.path == ProxyScriptHooks.PRE_START) ScriptReferenceIcons.Start else ScriptReferenceIcons.Stop,
                                null, tint = t.textPrimary, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(entry.title, color = t.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text(if (fixedStatus[entry.path] == true) "已设置" else "不设置", color = t.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.ExpandMore, null, tint = t.textSecondary, modifier = Modifier.size(22.dp))
                        }
                        if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = t.outline.copy(alpha = .55f))
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = t.outline.copy(alpha = .55f))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 70.dp).clickable { environment = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(ScriptReferenceIcons.Environment, null, tint = t.textPrimary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("脚本环境", color = t.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        if (managedScripts.isEmpty()) {
            item("empty") {
                Text("暂无脚本", color = t.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
            }
        } else {
            item("custom-title") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("自定义脚本", color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { launchDocumentPicker({ messageError = true; message = it }) { importer.launch(arrayOf("text/*", "application/octet-stream")) } }, enabled = !busy) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(24.dp), tint = t.textPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("导入", color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            items(managedScripts, key = { it.name }) { script ->
                Surface(shape = HomeContinuousShape(24.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(ScriptReferenceIcons.File, null, tint = t.textPrimary, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(script.name, color = t.textPrimary, fontSize = 18.sp, lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold, overflow = TextOverflow.Ellipsis)
                            Text(scriptSize(script.size), color = t.textSecondary, fontSize = 14.sp, lineHeight = 19.sp,
                                fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (!busy) scope.launch {
                                    busy = true; messageError = false; message = ""
                                    try { message = ProxyScriptHooks.runManaged(context, script.name) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) { message = failure.message ?: "脚本执行失败"; messageError = true }
                                    finally { busy = false }
                                }
                            }, enabled = !busy, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.PlayArrow, "执行", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
                            IconButton(onClick = { if (!busy) deleteTarget = script }, enabled = !busy, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = t.textPrimary, modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) {
            item("feedback") { HetuTaskFeedback(message, error = messageError, busy = busy) }
        }
    }

    if (menuOpen) {
        menuAnchor?.let { anchor ->
            // This legacy host uses HetuTheme, so keep the anchored menu in its active palette too.
            val menuColors = Hx.colors.copy(surface = t.cardBackground, text = t.textPrimary,
                textMuted = t.textSecondary, line = t.outline, dark = dark)
            CompositionLocalProvider(LocalHx provides menuColors) {
                HxAnchoredMenu(anchor, onDismiss = { menuOpen = false }, minWidth = 160.dp, anchorEndInset = 0.dp) { close ->
                    HxMenuItem("导入自定义脚本", icon = Icons.Rounded.Add, onClick = {
                        close { menuOpen = false; launchDocumentPicker({ messageError = true; message = it }) { importer.launch(arrayOf("text/*", "application/octet-stream")) } }
                    })
                    HorizontalDivider(Modifier.padding(horizontal = 14.dp), thickness = .5.dp, color = t.outline.copy(alpha = .55f))
                    HxMenuItem("脚本环境", icon = Icons.Outlined.Info, onClick = {
                        close { menuOpen = false; environment = true }
                    })
                }
            }
        }
    }

    }

    editor?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) editor = null },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = { ScriptSheetHandle() },
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    entry.title,
                    color = t.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    entry.subtitle,
                    color = t.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                ScriptCodeField(
                    value = text,
                    onValueChange = { if (it.length <= 64 * 1024) text = it },
                    enabled = !busy,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.clear(context, entry.path)
                                    text = ""
                                    messageError = false; message = "已清空${entry.title}脚本"; hookRevision++
                                } catch (failure: Exception) {
                                    message = failure.message ?: "清空失败"; messageError = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("清空", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.write(context, entry.path, text)
                                    messageError = false; message = "${entry.title}脚本已保存"
                                    hookRevision++
                                    editor = null
                                } catch (failure: Exception) {
                                    message = failure.message ?: "保存失败"; messageError = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (busy) "处理中" else "保存", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) deleteTarget = null },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            dragHandle = { ScriptSheetHandle() },
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("删除脚本", color = t.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(
                    "确定删除 “" + target.name + "” 吗？\n此操作只删除自定义脚本，不会影响固定启动/停止 Hook。",
                    color = t.textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { deleteTarget = null },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = t.controlBackground, contentColor = t.textPrimary),
                    ) { Text("取消", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.deleteManaged(context, target.name)
                                    messageError = false; message = "已删除 " + target.name
                                    managedRevision++
                                    deleteTarget = null
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    message = failure.message ?: "脚本删除失败"; messageError = true
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEE4055), contentColor = Color.White),
                    ) { Text(if (busy) "处理中" else "删除", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }

    if (environment) {
        ModalBottomSheet(
            onDismissRequest = { environment = false },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = { ScriptSheetHandle() },
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "脚本环境",
                    color = t.textPrimary,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Column(
                    Modifier.fillMaxWidth()
                        .background(t.controlBackground, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val environmentLines = remember { ProxyScriptHooks.environmentText().lines() }
                    environmentLines.takeWhile { it.isNotBlank() }.forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"), limit = 2)
                        Column {
                            Text(parts.first(), color = t.textPrimary, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(parts.getOrElse(1) { "" }, color = t.textSecondary, fontSize = 14.sp, lineHeight = 19.sp)
                        }
                    }
                    Text(
                        environmentLines.dropWhile { it.isNotBlank() }.dropWhile { it.isBlank() }.joinToString("\n"),
                        color = t.textSecondary,
                        fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

/** Screen-local glyphs following the supplied script controls, without changing shared icons. */
private object ScriptReferenceIcons {
    private fun line(name: String, vararg paths: String): ImageVector = ImageVector.Builder(
        name = "HetuScripts.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = null, stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
    }.build()

    private fun control(name: String, mark: String): ImageVector = ImageVector.Builder(
        name = "HetuScripts.$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(PathParser().parsePathString("M22 12a10 10 0 1 1-20 0a10 10 0 1 1 20 0Z").toNodes(),
            fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f)
        addPath(PathParser().parsePathString(mark).toNodes(), fill = SolidColor(Color.Black))
    }.build()

    val Start = control("Start", "m10 7 7 5-7 5V7Z")
    val Stop = control("Stop", "M8 8h8v8H8V8Z")
    val Environment = line("Environment", "m12 1 10 5.5v11L12 23 2 17.5v-11L12 1Z", "m7 8 4 4-4 4", "M13 16h4")
    val File = ImageVector.Builder(name = "HetuScripts.File", defaultWidth = 24.dp, defaultHeight = 28.dp,
        viewportWidth = 24f, viewportHeight = 28f).apply {
        // Knockouts stay transparent when Icon applies the active light/dark text tint.
        addPath(PathParser().parsePathString("M4 1h11l6 6v18a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V3a2 2 0 0 1 2-2Z " +
            "M14.2 1.8h1.4v5.5H21v1.4h-6.8V1.8Z M6 13l4 4-4 4-1-1 3-3-3-3 1-1Z M11 20h6v1.5h-6V20Z").toNodes(),
            fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd)
    }.build()
}

@Composable
private fun ScriptSheetHandle() {
    val t = LocalHetuTokens.current
    Box(Modifier.padding(top = 9.dp, bottom = 24.dp).size(width = 44.dp, height = 5.dp)
        .clip(RoundedCornerShape(3.dp)).background(t.textSecondary.copy(alpha = .38f)))
}

/** A bounded, top-aligned editor; long hook content scrolls inside its own input well. */
@Composable
private fun ScriptCodeField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("脚本内容", color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(14.dp))
                .background(if (dark) t.controlBackground else Color(0xFFEEEDF8))
                .semantics { contentDescription = "脚本内容" }
                .padding(14.dp),
            textStyle = LocalTextStyle.current.copy(color = t.textPrimary, fontFamily = HetuSystemFontFamily, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    if (value.isEmpty()) Text("#!/system/bin/sh\n# 在这里输入脚本", color = t.textSecondary,
                        fontFamily = HetuSystemFontFamily, fontSize = 14.sp, lineHeight = 20.sp)
                    inner()
                }
            },
        )
    }
}
