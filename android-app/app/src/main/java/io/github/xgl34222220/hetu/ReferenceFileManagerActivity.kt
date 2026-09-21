package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val REF_FILE_ROOT = "/data/adb/hetu"

private data class RefFileItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
)

private data class RefFileBadge(val label: String, val background: Color, val foreground: Color)

private fun refFileBadge(name: String): RefFileBadge {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "yaml", "yml" -> RefFileBadge("YAML", Color(0xFFEFF6FF), Color(0xFF2563EB))
        "log" -> RefFileBadge("LOG", Color(0xFFF1F5F9), Color(0xFF64748B))
        "pid" -> RefFileBadge("PID", Color(0xFFF5F3FF), Color(0xFF7C3AED))
        "db", "sqlite", "sqlite3" -> RefFileBadge("DB", Color(0xFFECFDF5), Color(0xFF059669))
        "sh" -> RefFileBadge("SH", Color(0xFFFFF7ED), Color(0xFFEA580C))
        "json", "jsonc" -> RefFileBadge("JSON", Color(0xFFECFEFF), Color(0xFF0891B2))
        "conf", "ini" -> RefFileBadge("CONF", Color(0xFFEEF2FF), Color(0xFF4F46E5))
        else -> RefFileBadge("FILE", Color(0xFFF8FAFC), Color(0xFF64748B))
    }
}


class ReferenceFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ReferenceFileManagerScreen { finish() } } }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

@Composable
private fun RefBreadcrumb(path: String, onNavigate: (String) -> Unit) {
    val t = LocalHetuTokens.current
    val relative = path.removePrefix(REF_FILE_ROOT).trim('/')
    val parts = buildList {
        add("hetu")
        if (relative.isNotBlank()) addAll(relative.split('/').filter { it.isNotBlank() })
    }
    Surface(shape = RoundedCornerShape(14.dp), color = t.selectionBackground) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            parts.forEachIndexed { index, part ->
                val target = if (index == 0) REF_FILE_ROOT else REF_FILE_ROOT + "/" + parts.drop(1).take(index).joinToString("/")
                val current = index == parts.lastIndex
                TextButton(
                    onClick = { if (!current) onNavigate(target) },
                    enabled = !current,
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp),
                    modifier = Modifier.heightIn(min = 34.dp),
                ) {
                    Text(
                        part,
                        color = if (current) t.textPrimary else MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                if (!current) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(15.dp), tint = t.textMuted)
            }
        }
    }
}


private suspend fun readDirectory(context: android.content.Context, path: String): List<RefFileItem> = withContext(Dispatchers.IO) {
    if (!path.startsWith(REF_FILE_ROOT)) return@withContext emptyList()
    val command = """
        P=${shellQuote(path)}
        [ -d "${'$'}P" ] || exit 0
        for f in "${'$'}P"/* "${'$'}P"/.*; do
          [ -e "${'$'}f" ] || continue
          n=${'$'}(basename "${'$'}f")
          [ "${'$'}n" = "." ] && continue
          [ "${'$'}n" = ".." ] && continue
          if [ -d "${'$'}f" ]; then
            printf 'D\t%s\t0\n' "${'$'}n"
          else
            s=${'$'}(wc -c < "${'$'}f" 2>/dev/null || echo 0)
            printf 'F\t%s\t%s\n' "${'$'}n" "${'$'}s"
          fi
        done
    """.trimIndent()
    val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
    if (!result.ok()) return@withContext emptyList()
    result.output.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 3) return@mapNotNull null
        val name = parts[1]
        val dir = parts[0] == "D"
        RefFileItem(name, "$path/$name", dir, parts[2].toLongOrNull() ?: 0L)
    }.sortedWith(compareBy<RefFileItem> { !it.directory }.thenBy { it.name.lowercase() }).toList()
}

private suspend fun readTextFile(context: android.content.Context, path: String): String = withContext(Dispatchers.IO) {
    if (!path.startsWith(REF_FILE_ROOT)) return@withContext "不允许读取此路径"
    val command = "head -c 24000 ${shellQuote(path)} 2>/dev/null"
    val result = RootBridge.rootShell(context.applicationContext, command, 8_000L)
    if (!result.ok()) "无法读取文件" else result.output.ifBlank { "空文件" }
}

@Composable
private fun ReferenceFileManagerScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val t = LocalHetuTokens.current
    var path by rememberSaveable { mutableStateOf(REF_FILE_ROOT) }
    var refresh by remember { mutableIntStateOf(0) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf("") }
    val items by produceState(initialValue = emptyList(), path, refresh) {
        value = try {
            if (path == REF_FILE_ROOT) {
                ProxyComposeController(context).ensureRuntimeFiles()
            }
            loadError = ""
            readDirectory(context, path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = error.message ?: "无法读取河图运行目录"
            emptyList()
        }
    }

    fun goBack() {
        if (path == REF_FILE_ROOT) onClose()
        else {
            val parent = File(path).parentFile?.path.orEmpty()
            path = parent.takeIf { it.startsWith(REF_FILE_ROOT) } ?: REF_FILE_ROOT
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(t.pageBackground),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = hetuContentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text("文件管理", color = t.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "刷新") }
            }
        }
        item {
            RefBreadcrumb(path) { target -> path = target }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    if (items.isEmpty()) {
                        Text(
                            loadError.ifBlank { "此目录暂无运行文件" },
                            Modifier.padding(vertical = 18.dp),
                            color = if (loadError.isBlank()) t.textSecondary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        items.forEachIndexed { index, item ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (item.directory) path = item.path
                                    else {
                                        previewTitle = item.name
                                        previewText = "正在读取…"
                                    }
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val badge = if (item.directory) null else refFileBadge(item.name)
                                Box(
                                    Modifier.size(34.dp).background(
                                        badge?.background ?: MaterialTheme.colorScheme.primary.copy(alpha = .08f),
                                        RoundedCornerShape(11.dp),
                                    ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        if (item.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
                                        null,
                                        tint = badge?.foreground ?: MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.directory) Text(refFileSize(item.size), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                                if (badge != null) {
                                    Surface(shape = CircleShape, color = badge.background) {
                                        Text(
                                            badge.label,
                                            color = badge.foreground,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
                            }
                            if (index < items.lastIndex) HorizontalDivider(color = t.outline)
                        }
                    }
                }
            }
        }
    }

    previewTitle?.let { title ->
        LaunchedEffect(title) {
            val item = items.firstOrNull { it.name == title }
            if (item != null) previewText = readTextFile(context, item.path)
        }
        RefFilePreviewSheet(
            title = title,
            text = previewText,
            onDismiss = { previewTitle = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefFilePreviewSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
) {
    val t = LocalHetuTokens.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.elevatedCardBackground,
        contentColor = t.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = .35f),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(.82f).navigationBarsPadding()
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = t.textPrimary,
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Close, "关闭", tint = t.textSecondary)
                }
            }
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(t.controlBackground.copy(alpha = .45f))
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(text, color = t.textPrimary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun refFileSize(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var v = value.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
    return if (i == 0) "${v.toLong()} ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
}
