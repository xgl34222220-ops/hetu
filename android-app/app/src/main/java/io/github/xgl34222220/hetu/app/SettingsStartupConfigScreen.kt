package io.github.xgl34222220.hetu

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
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
    val c = LocalHomeColors.current

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
    HxPage(title = ht("启动配置"), subtitle = ht("河图生成的最终 Mihomo 运行副本"), largeTitle = false, onBack = onBack) {
        item(key = "actions") {
            SettingsSection {
                SettingsGroup {
                    Row(Modifier.fillMaxWidth().padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SettingsRow(ht("重新生成"), icon = HomeIcons.RefreshCw, enabled = !busy, compact = true, onClick = { load(true) }) {
                                if (busy) HxSpinner(18.dp)
                            }
                        }
                        HomeIconButton(HomeIcons.Copy, "复制", { hxCopy(context, "启动配置", text) }, enabled = text.isNotBlank())
                        HomeIconButton(ToolsIcons.Share, "导出", { launchDocumentPicker({ message = it }) { exporter.launch("hetu-startup-config.yaml") } }, enabled = text.isNotBlank())
                    }
                }
            }
        }
        // What the last action did, where it can be seen without scrolling past the whole file.
        if (message.isNotBlank()) item(key = "message") {
            HxBanner(message, tone = HxTone.Accent, modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap))
        }
        item(key = "config") {
            SettingsSection {
                Box(Modifier.fillMaxWidth().heightIn(min = 320.dp).clip(HomeDims.cardShape).background(c.surface).padding(horizontal = 18.dp, vertical = 16.dp)) {
                    SelectionContainer {
                        Text(
                            if (busy && text.isBlank()) ht("正在读取启动配置…") else text.ifBlank { ht("暂无启动配置") },
                            Modifier.horizontalScroll(rememberScrollState()),
                            color = if (text.isBlank()) c.t2 else c.t1, style = HomeType.mono,
                        )
                    }
                }
            }
        }
    }
}
