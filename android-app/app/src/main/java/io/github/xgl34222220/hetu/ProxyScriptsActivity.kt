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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    var environment by remember { mutableStateOf(false) }
    var managedRevision by remember { mutableIntStateOf(0) }
    var hookRevision by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ManagedScript?>(null) }
    val fixedStatus by produceState(initialValue = mapOf<String, Boolean>(), hookRevision) {
        value = try { entries.associate { it.path to ProxyScriptHooks.read(context, it.path).isNotBlank() } }
        catch (_: Exception) { emptyMap() }
    }
    val managedScripts by produceState(initialValue = emptyList<ManagedScript>(), managedRevision) {
        value = try {
            ProxyScriptHooks.listManaged(context)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            message = failure.message ?: "脚本列表读取失败"
            emptyList()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = ""
            try {
                val displayName = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: uri.lastPathSegment ?: "script.sh"
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("无法读取所选脚本")
                val imported = ProxyScriptHooks.importManaged(context, displayName, bytes)
                message = "已导入 " + imported.name
                managedRevision++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本导入失败"
            } finally {
                busy = false
            }
        }
    }

    fun open(entry: ScriptEntry) {
        if (busy) return
        scope.launch {
            busy = true
            message = ""
            try {
                text = ProxyScriptHooks.read(context, entry.path)
                editor = entry
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                message = failure.message ?: "脚本读取失败"
            } finally {
                busy = false
            }
        }
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF2F0F9)
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
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Rounded.MoreHoriz, "更多", tint = t.textPrimary, modifier = Modifier.size(28.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("导入自定义脚本") },
                                leadingIcon = { Icon(Icons.Rounded.Add, null) },
                                onClick = { menuOpen = false; importer.launch(arrayOf("text/*", "application/octet-stream")) },
                            )
                            DropdownMenuItem(
                                text = { Text("脚本环境") },
                                leadingIcon = { Icon(Icons.Rounded.Info, null) },
                                onClick = { menuOpen = false; environment = true },
                            )
                        }
                    }
                }
                Text(
                    "脚本",
                    color = t.textPrimary,
                    fontSize = 31.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp),
                )
            }
        }

        item("hooks") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = t.cardBackground,
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    entries.forEachIndexed { index, entry ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { open(entry) }.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(entry.title, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(if (fixedStatus[entry.path] == true) "已设置" else "不设置", color = t.textSecondary, fontSize = 13.sp)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.ExpandMore, null, tint = t.textSecondary, modifier = Modifier.size(22.dp))
                        }
                        if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = t.outline.copy(alpha = .55f))
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = t.outline.copy(alpha = .55f))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { environment = true }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("脚本环境", color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
                    Text("自定义脚本", color = t.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { importer.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = !busy) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("导入")
                    }
                }
            }
            items(managedScripts, key = { it.name }) { script ->
                Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {
                    WorkspaceSettingRow(script.name, scriptSize(script.size), Icons.Rounded.Code) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                if (!busy) scope.launch {
                                    busy = true; message = ""
                                    try { message = ProxyScriptHooks.runManaged(context, script.name) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (failure: Exception) { message = failure.message ?: "脚本执行失败" }
                                    finally { busy = false }
                                }
                            }, enabled = !busy, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.PlayArrow, "执行", tint = MaterialTheme.colorScheme.primary) }
                            IconButton(onClick = { if (!busy) deleteTarget = script }, enabled = !busy, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.DeleteOutline, "删除", tint = t.textSecondary) }
                        }
                    }
                }
            }
        }
        if (message.isNotBlank()) {
            item("feedback") { HetuTaskFeedback(message, error = message.contains("失败") || message.contains("错误"), busy = busy) }
        }
    }

    editor?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) editor = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(
                Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding()
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
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                LiquidGlassTextField(
                    value = text,
                    onValueChange = { if (it.length <= 64 * 1024) text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 480.dp),
                    label = "脚本内容",
                    placeholder = "#!/system/bin/sh\n# 在这里输入脚本",
                    singleLine = false,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
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
                                    message = "已清空${entry.title}脚本"; hookRevision++
                                } catch (failure: Exception) {
                                    message = failure.message ?: "清空失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("清空")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.write(context, entry.path, text)
                                    message = "${entry.title}脚本已保存"
                                    hookRevision++
                                    editor = null
                                } catch (failure: Exception) {
                                    message = failure.message ?: "保存失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Save, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "处理中" else "保存")
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { if (!busy) deleteTarget = null },
            containerColor = Color.Transparent,
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
        ) {
            Column(
                Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding()
                    .padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("删除脚本", color = t.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "确定删除 “" + target.name + "” 吗？此操作只删除自定义脚本，不会影响固定启动/停止 Hook。",
                    color = t.textSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = { deleteTarget = null },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("取消") }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    ProxyScriptHooks.deleteManaged(context, target.name)
                                    message = "已删除 " + target.name
                                    managedRevision++
                                    deleteTarget = null
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    message = failure.message ?: "脚本删除失败"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(if (busy) "处理中" else "删除") }
                }
            }
        }
    }

    if (environment) {
        ModalBottomSheet(
            onDismissRequest = { environment = false },
            containerColor = t.cardBackground,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(.62f).navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "脚本环境",
                    color = t.textPrimary,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .background(t.controlBackground, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        ProxyScriptHooks.environmentText(),
                        color = t.textPrimary,
                        fontFamily = io.github.xgl34222220.hetu.ui.HetuSystemFontFamily,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}
