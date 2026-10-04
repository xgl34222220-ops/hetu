package io.github.xgl34222220.hetu

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsStartupConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", Context.MODE_PRIVATE) }
    val manager = remember { RootProxyManager(context) }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun load(regenerate: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                text = withContext(Dispatchers.IO) {
                    if (regenerate) manager.prepare(ProxyRuntimeProfile.load(prefs))
                    manager.startupConfig()
                }
                message = if (regenerate) "启动配置已重新生成（未重启代理）" else ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message = error.message ?: "尚未生成启动配置" }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/yaml")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法创建导出文件")
                }
                message = "启动配置已导出"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message = error.message ?: "导出失败" }
        }
    }
    HxPage(title = "启动配置", subtitle = "河图生成的最终 Mihomo 运行副本", largeTitle = false, compactTitleFontSizeSp = 20f, onBack = onBack) {
        item(key = "actions") {
            SettingsSection {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SettingsRow("重新生成", icon = Icons.Rounded.Refresh, enabled = !busy, compact = true, onClick = { load(true) })
                        }
                        IconButton(onClick = { hxCopy(context, "启动配置", text) }, enabled = text.isNotBlank()) {
                            Icon(Icons.Rounded.ContentCopy, "复制", tint = Hx.colors.textMuted)
                        }
                        IconButton(onClick = { launchDocumentPicker({ message = it }) { exporter.launch("hetu-startup-config.yaml") } }, enabled = text.isNotBlank()) {
                            Icon(Icons.Rounded.IosShare, "导出", tint = Hx.colors.textMuted)
                        }
                    }
                }
            }
        }
        item(key = "config") {
            SettingsSection {
                Box(Modifier.fillMaxWidth().heightIn(min = 400.dp).clip(RoundedCornerShape(24.dp)).background(Hx.colors.surface).padding(16.dp)) {
                    SelectionContainer {
                        Text(if (busy && text.isBlank()) "正在读取启动配置…" else text.ifBlank { "暂无启动配置" },
                            color = Hx.colors.textMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()))
                    }
                }
            }
        }
        if (message.isNotBlank()) item(key = "message") {
            Text(message, color = Hx.colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}
