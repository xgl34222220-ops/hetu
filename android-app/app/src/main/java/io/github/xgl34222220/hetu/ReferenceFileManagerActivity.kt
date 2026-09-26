package io.github.xgl34222220.hetu

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReferenceFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { HetuTheme { ReferenceFileManagerScreen { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceFileManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val t = LocalHetuTokens.current
    val repo = remember { RuntimeFilesRepository(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf(RuntimeFilesRepository.ROOT) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("name") }
    var revision by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<RuntimeFileEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RuntimeFileEntry?>(null) }
    var form by remember { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var userAgent by rememberSaveable { mutableStateOf("Hetu-Android") }
    var preview by remember { mutableStateOf<RuntimeFileContent?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var exportPath by rememberSaveable { mutableStateOf("") }

    fun operate(success: String, action: suspend () -> Unit) {
        if (working) return
        working = true; message = "正在处理…"; error = false
        scope.launch {
            try { action(); message = success; error = false; revision++ }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = true; message = failure.message ?: "操作失败" }
            finally { working = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operate("已导入，同名文件已保留副本") {
            val fileName = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "导入文件.txt"
            }
            repo.importFile(path, fileName, uri)
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val target = exportPath
        if (uri != null && target.isNotBlank()) operate("导出成功") { repo.export(RuntimeFileEntry(File(target).name, target, false, 0), uri) }
    }
    fun back() { if (path == RuntimeFilesRepository.ROOT) onClose() else { path = File(path).parent ?: RuntimeFilesRepository.ROOT; query = "" } }
    BackHandler { back() }
    LaunchedEffect(path, revision) {
        loading = true
        try {
            if (path == RuntimeFilesRepository.ROOT) ProxyComposeController(context).ensureRuntimeFiles()
            entries = repo.list(path)
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { entries = emptyList(); error = true; message = failure.message ?: "无法读取目录" }
        finally { loading = false }
    }
    val filtered = remember(entries, query, sort) {
        val matched = entries.filter { it.name.contains(query.trim(), true) }
        when (sort) {
            "size" -> matched.sortedWith(compareBy<RuntimeFileEntry> { !it.directory }.thenByDescending { it.size })
            "date" -> matched.sortedWith(compareBy<RuntimeFileEntry> { !it.directory }.thenByDescending { it.modified })
            else -> matched.sortedWith(compareBy<RuntimeFileEntry> { !it.directory }.thenBy { it.name.lowercase() })
        }
    }
    Column(Modifier.fillMaxSize().crystalPageBackground().statusBarsPadding().testTag("runtime-files")) {
        HetuPageHeader("文件管理", ::back, "${entries.size} 项") {
            IconButton(onClick = { revision++ }, enabled = !loading) { Icon(Icons.Rounded.Refresh, "刷新") }
            IconButton(onClick = { selected = null; form = "add" }, enabled = !working) { Icon(Icons.Rounded.Add, "新建或导入") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { path = RuntimeFilesRepository.ROOT }) { Text("hetu") }
            var current = RuntimeFilesRepository.ROOT
            path.removePrefix(RuntimeFilesRepository.ROOT).split('/').filter { it.isNotBlank() }.forEach { segment ->
                current += "/$segment"; val target = current
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = t.textMuted)
                TextButton(onClick = { path = target }) { Text(segment) }
            }
        }
        LiquidGlassTextField(query, { query = it }, "搜索文件或文件夹", Modifier.fillMaxWidth().padding(horizontal = 16.dp), leadingIcon = Icons.Rounded.Search)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("name" to "名称", "date" to "最近修改", "size" to "大小").forEach { (key, label) -> LiquidChoicePill(label, sort == key, { sort = key }) }
        }
        if (message.isNotBlank()) HetuTaskFeedback(message, error, working, Modifier.padding(horizontal = 16.dp))
        HetuRefreshBox(loading, { revision++ }, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, hetuContentBottomPadding()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!loading && filtered.isEmpty()) item {
                    GroupedInsetSection {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (error) "目录读取失败" else if (query.isBlank()) "此目录暂无文件" else "没有匹配的文件", color = t.textPrimary, fontSize = 16.sp)
                            Text(if (error) "检查上方错误信息后重试" else if (query.isBlank()) "可以新建配置，或导入已有文件" else "换个关键词，或清空搜索查看所有文件", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            TextButton(onClick = { if (error) revision++ else if (query.isNotBlank()) query = "" else { selected = null; form = "add" } }) {
                                Text(if (error) "重试" else if (query.isNotBlank()) "清空搜索" else "新建或导入")
                            }
                        }
                    }
                }
                items(filtered, key = { it.path }, contentType = { if (it.directory) "folder" else "file" }) { entry ->
                    Row(Modifier.fillMaxWidth().animateItem().crystalMaterial(RoundedCornerShape(16.dp)).clickable {
                        if (entry.directory) { path = entry.path; query = "" }
                        else { selected = entry; preview = null; previewing = true }
                    }.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        HetuListIcon(if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(entry.name, color = t.textPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (entry.directory) "文件夹" else refBytes(entry.size), color = t.textSecondary, fontSize = 11.sp)
                        }
                        IconButton(onClick = { selected = entry; form = "actions" }) { Icon(Icons.Rounded.MoreVert, "${entry.name} 更多操作", tint = t.textSecondary) }
                    }
                }
            }
        }
    }
    if (previewing) {
        LaunchedEffect(selected?.path) {
            try { preview = repo.read(requireNotNull(selected).path) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { previewing = false; error = true; message = failure.message ?: "无法读取文件" }
        }
        ModalBottomSheet(onDismissRequest = { previewing = false }, containerColor = Color.Transparent) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).liquidSheetMaterial().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected?.name.orEmpty(), Modifier.weight(1f), color = t.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(enabled = preview?.editable == true, onClick = {
                        context.startActivity(Intent(context, RuntimeFileEditorActivity::class.java).putExtra(EXTRA_RUNTIME_PATH, selected?.path)); previewing = false
                    }) { Text("编辑") }
                    IconButton(onClick = { previewing = false }) { Icon(Icons.Rounded.Close, "关闭") }
                }
                Text(preview?.description ?: "正在读取…", color = t.textSecondary, fontSize = 11.sp)
                if (preview == null) HetuBusyIndicator() else androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(preview!!.text, color = t.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }
    form?.let { kind ->
        ModalBottomSheet(onDismissRequest = { if (!working) form = null }, containerColor = Color.Transparent) {
            Column(Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when (kind) { "add" -> "新建与导入"; "actions" -> selected?.name.orEmpty(); "file" -> "新建文件"; "folder" -> "新建文件夹"; "rename" -> "重命名"; "delete" -> "删除文件"; "update" -> "更新文件"; else -> "从链接下载" }, color = t.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                @Composable fun Action(label: String, onClick: () -> Unit) { TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) } }
                when (kind) {
                    "add" -> {
                        Action("新建文件") { name = ""; form = "file" }
                        Action("新建文件夹") { name = ""; form = "folder" }
                        Action("从本地导入") { form = null; importer.launch(arrayOf("*/*")) }
                        Action("从链接下载") { name = ""; address = ""; form = "download" }
                    }
                    "actions" -> {
                        val item = requireNotNull(selected)
                        Action("重命名") { name = item.name; form = "rename" }
                        if (!item.directory) Action("导出") { exportPath = item.path; form = null; exporter.launch(item.name) }
                        if (!item.directory && repo.source(item.path).isNotBlank()) Action("从原地址更新") { address = repo.source(item.path); form = "update" }
                        Action("删除") { form = "delete" }
                    }
                    else -> {
                        if (kind == "delete") Text("确定删除“${selected?.name}”${if (selected?.directory == true) "及其中的文件" else ""}？此操作无法撤销。", color = t.textSecondary)
                        else if (kind == "update") Text("从保存的下载地址更新“${selected?.name}”，将替换当前文件。", color = t.textSecondary)
                        else LiquidGlassTextField(name, { name = it }, "名称", Modifier.fillMaxWidth())
                        if (kind == "download") {
                            LiquidGlassTextField(address, { address = it }, "HTTPS 链接", Modifier.fillMaxWidth())
                            LiquidGlassTextField(userAgent, { userAgent = it }, "User-Agent", Modifier.fillMaxWidth())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { form = null }, modifier = Modifier.weight(1f)) { Text("取消") }
                            Button(enabled = !working && (name.isNotBlank() || kind in listOf("delete", "update")), modifier = Modifier.weight(1f), onClick = {
                                val item = selected; val label = name; val url = address; val agent = userAgent; val directory = path
                                form = null
                                operate(when (kind) { "delete" -> "已删除"; "rename" -> "已重命名"; "download", "update" -> "下载完成"; else -> "已创建" }) {
                                    when (kind) {
                                        "file", "folder" -> repo.create(directory, label, kind == "folder")
                                        "rename" -> repo.rename(requireNotNull(item), label)
                                        "delete" -> repo.delete(requireNotNull(item))
                                        "update" -> repo.download(directory, requireNotNull(item).name, url, agent, item)
                                        else -> repo.download(directory, label, url, agent)
                                    }
                                }
                            }) { Text(if (kind == "delete") "删除" else "确定") }
                        }
                    }
                }
            }
        }
    }
}
