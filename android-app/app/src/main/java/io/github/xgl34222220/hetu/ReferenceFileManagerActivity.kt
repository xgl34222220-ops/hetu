package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.togetherWith
import io.github.xgl34222220.hetu.ui.hetuPressHighlight
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

class ReferenceFileManagerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hxHost { vm -> FileManagerScreen(vm) { finish() } }
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

    // V19: reference-style file manager. Large title that collapses into the bar, grouped
    // outline rows with press highlight, directional slide between folders, system back
    // walks up the tree, and previews open in the shared bottom sheet.
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val motion = io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val page = if (dark) t.pageBackground else Color(0xFFF2F0FB)
    androidx.activity.compose.BackHandler(enabled = path != REF_FILE_ROOT) { goBack() }
    Box(Modifier.fillMaxSize().background(page)) {
        androidx.compose.animation.AnimatedContent(
            targetState = path,
            transitionSpec = {
                val deeper = targetState.length > initialState.length
                if (!motion) {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                } else {
                    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280,
                            easing = io.github.xgl34222220.hetu.ui.HetuMotion.EmphasizedDecelerate)) { w -> if (deeper) w / 5 else -w / 5 }) togetherWith
                        (androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
                            androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { w -> if (deeper) -w / 8 else w / 8 })
                }
            },
            label = "file-manager-path",
        ) { shownPath ->
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            val relative = shownPath.removePrefix(REF_FILE_ROOT).trim('/')
            val folderTitle = if (relative.isBlank()) "文件管理" else relative.substringAfterLast('/')
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(start = 12.dp, top = 44.dp, end = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "title") {
                        Text(folderTitle, color = t.textPrimary, fontSize = 28.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp))
                    }
                    item(key = "crumbs") {
                        Text(
                            if (relative.isBlank()) "/data/adb/hetu" else "hetu  ›  ${relative.replace("/", "  ›  ")}",
                            color = t.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    item(key = "group") {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                .background(if (dark) t.cardBackground else t.cardBackground),
                        ) {
                            if (shownPath != path || items.isEmpty()) {
                                Text(
                                    if (shownPath != path) "" else loadError.ifBlank { "此目录暂无运行文件" },
                                    Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                                    color = if (loadError.isBlank()) t.textSecondary else MaterialTheme.colorScheme.error,
                                    fontSize = 14.sp,
                                )
                            } else {
                                items.forEachIndexed { index, item ->
                                    val source = remember(item.path) { MutableInteractionSource() }
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                            .hetuPressHighlight(source, (if (dark) Color.White else Color(0xFF12161A)).copy(alpha = .05f))
                                            .clickable(interactionSource = source, indication = null) {
                                                haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tap)
                                                if (item.directory) path = item.path
                                                else {
                                                    previewTitle = item.name
                                                    previewText = "正在读取…"
                                                }
                                            }
                                            .padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(if (item.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null,
                                            tint = t.textPrimary.copy(alpha = .78f), modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name, color = t.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (!item.directory) Text(refFileSize(item.size), color = t.textSecondary, fontSize = 12.sp)
                                        }
                                        if (item.directory) Icon(Icons.Rounded.ChevronRight, null, tint = t.textSecondary, modifier = Modifier.size(20.dp))
                                    }
                                    if (index < items.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), thickness = .5.dp, color = t.outline)
                                }
                            }
                        }
                    }
                }
                io.github.xgl34222220.hetu.RefCollapsingTitleBar(folderTitle, list)
            }
        }
        // Top actions float above both the large title and the collapsed bar.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = ::goBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick); refresh++ }) {
                Icon(Icons.Rounded.Refresh, "刷新", tint = t.textPrimary)
            }
        }
    }

    previewTitle?.let { title ->
        LaunchedEffect(title) {
            val item = items.firstOrNull { it.name == title }
            if (item != null) previewText = readTextFile(context, item.path)
        }
        NativeDetailsSheet(title, { previewTitle = null }) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(previewText, color = t.textPrimary, fontSize = 12.sp, lineHeight = 18.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
