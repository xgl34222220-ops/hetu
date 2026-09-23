package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProxyLogViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HetuTheme {
                ProxyLogViewerScreen(onBack = { finish() })
            }
        }
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
                .height(58.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
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
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
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
                modifier = Modifier.size(44.dp),
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
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, "清空当前日志", tint = t.textSecondary)
            }

            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(44.dp),
            ) {
                Icon(Icons.Rounded.MoreHoriz, "选择日志", tint = t.textSecondary)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                .background(t.cardBackground, RoundedCornerShape(18.dp))
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                content,
                color = if (error.isBlank()) t.textPrimary else MaterialTheme.colorScheme.error,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
            )
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
                    fontWeight = FontWeight.ExtraBold,
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
