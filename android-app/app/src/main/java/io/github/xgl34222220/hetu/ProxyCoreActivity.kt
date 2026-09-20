package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyCoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { CoreManagerScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun CoreManagerScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { ProxyCoreDownloadManager(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalHetuTokens.current
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var statuses by remember { mutableStateOf(emptyList<ProxyCoreRemoteStatus>()) }
    var busyCore by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var explanationOpen by remember { mutableStateOf(false) }

    LaunchedEffect(revision) {
        loading = true
        try {
            statuses = manager.statuses(forceNetwork = true)
            notice = if (statuses.any { it.latestVersion.isNotBlank() }) "已检查 GitHub 发布源" else "未获取到远程版本"
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            notice = error.message ?: "检查更新失败"
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(tokens.pageBackground),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = hetuContentBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Column(Modifier.statusBarsPadding()) {
                HetuPageHeader("内核管理", onBack, subtitle = "安装版本与后端能力分别展示") {
                    IconButton(onClick = { if (busyCore.isBlank()) revision++ }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Refresh, "检查更新")
                    }
                }
            }
        }
        item("intro") {
            Surface(shape = RoundedCornerShape(18.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("按当前设备架构获取核心", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(onClick = { explanationOpen = !explanationOpen }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (explanationOpen) "收起说明" else "查看核心使用说明")
                    }
                    if (explanationOpen) {
                    Text("Mihomo 没有下载更新时自动回退到 App 内置版本；下载更新后优先使用下载版。其他核心先完成下载与版本管理，运行后端未接入时不会假报可用。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(context, ProxyAdvancedSettingsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Rounded.Tune, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Root 网络高级设置")
                    }
                    if (progress.isNotBlank() || notice.isNotBlank() || loading) {
                        HetuTaskFeedback(progress.ifBlank { if (loading) "正在检查发布源" else notice },
                            error = notice.contains("失败") && progress.isBlank(), busy = loading || busyCore.isNotBlank())
                    }
                }
            }
        }
        items(statuses, key = { it.id }) { item ->
            CoreStatusCard(
                item = item,
                busy = busyCore == item.id,
                enabled = busyCore.isBlank(),
                onInstall = {
                    if (busyCore.isNotBlank()) return@CoreStatusCard
                    val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == item.id } ?: return@CoreStatusCard
                    busyCore = item.id
                    progress = "准备下载 ${item.label}…"
                    scope.launch {
                        try {
                            manager.downloadOrUpdate(core) { text -> progress = text }
                            notice = "${item.label} 已更新"
                        } catch (error: Exception) {
                            notice = error.message ?: "${item.label} 下载失败"
                        } finally {
                            busyCore = ""
                            progress = ""
                            revision++
                        }
                    }
                },
                onRemove = {
                    if (busyCore.isNotBlank()) return@CoreStatusCard
                    val core = ProxyRuntimeProfile.Core.values().firstOrNull { it.id == item.id } ?: return@CoreStatusCard
                    busyCore = item.id
                    scope.launch {
                        try {
                            manager.removeDownloaded(core)
                            notice = if (item.bundled) "${item.label} 已恢复内置版本" else "${item.label} 下载核心已删除"
                        } catch (error: Exception) {
                            notice = error.message ?: "删除失败"
                        } finally {
                            busyCore = ""
                            revision++
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CoreStatusCard(
    item: ProxyCoreRemoteStatus,
    busy: Boolean,
    enabled: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val tokens = LocalHetuTokens.current
    Surface(shape = RoundedCornerShape(18.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = tokens.elevatedCardBackground, modifier = Modifier.size(44.dp)) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(if (item.downloaded || item.bundled) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (item.bundled) {
                            Spacer(Modifier.width(7.dp))
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
                                Text("内置", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            }
                        }
                    }
                    Text((if (item.downloaded) "已安装下载版本" else if (item.bundled) "已内置" else "未安装") + " · " + if (item.runtimeReady) "支持此运行后端" else "运行后端待接入", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoreVersionText("本机版本", item.installedVersion.ifBlank { "未安装" }, Modifier.weight(1f))
                CoreVersionText("可下载版本", item.latestVersion.ifBlank { "—" }, Modifier.weight(1f))
            }
            if (item.source.isNotBlank()) Text("来源：${item.source}", color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.message.isNotBlank()) Text(item.message, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                val installText = when {
                    item.updateAvailable -> "更新"
                    item.downloaded -> "重新下载"
                    item.bundled -> "下载更新"
                    else -> "下载"
                }
                FilledTonalButton(
                    onClick = onInstall,
                    enabled = enabled && item.canDownload,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(17.dp),
                ) {
                    Icon(if (busy) Icons.Rounded.Downloading else Icons.Rounded.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "处理中" else installText)
                }
                if (item.downloaded) {
                    OutlinedButton(
                        onClick = onRemove,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(17.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (item.bundled) "恢复内置" else "删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreVersionText(label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = LocalHetuTokens.current
    Column(modifier) {
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.bodyMedium, softWrap = true)
    }
}
