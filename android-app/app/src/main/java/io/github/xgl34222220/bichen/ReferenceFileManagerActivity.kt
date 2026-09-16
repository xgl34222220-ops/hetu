package io.github.xgl34222220.bichen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val REF_FILE_ROOT = "/data/adb/bichen/proxy"

private data class RefFileItem(
    val name: String,
    val path: String,
    val directory: Boolean,
    val size: Long,
)

class ReferenceFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ReferenceFileManagerScreen { finish() } } }
    }
}

private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

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
    val context = LocalContext.current
    val t = LocalBichenTokens.current
    var path by rememberSaveable { mutableStateOf(REF_FILE_ROOT) }
    var refresh by remember { mutableIntStateOf(0) }
    var previewTitle by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf("") }
    val items by produceState(initialValue = emptyList(), path, refresh) {
        value = try {
            readDirectory(context, path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
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
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text("文件管理", color = t.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { refresh++ }) { Icon(Icons.Rounded.Refresh, "刷新") }
            }
        }
        item {
            val relative = path.removePrefix(REF_FILE_ROOT).trim('/')
            Surface(shape = RoundedCornerShape(12.dp), color = t.selectionBackground) {
                Text(
                    if (relative.isBlank()) "proxy" else "proxy  ›  ${relative.replace('/', ' › ')}",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = t.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = t.cardBackground) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                    if (items.isEmpty()) {
                        Text("此目录为空或暂时无法读取", Modifier.padding(vertical = 18.dp), color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
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
                                Icon(if (item.directory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, color = t.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.directory) Text(refFileSize(item.size), color = t.textSecondary, style = MaterialTheme.typography.labelSmall)
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
        AlertDialog(
            onDismissRequest = { previewTitle = null },
            shape = RoundedCornerShape(22.dp),
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text(previewText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { previewTitle = null }) { Text("关闭") } },
        )
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
