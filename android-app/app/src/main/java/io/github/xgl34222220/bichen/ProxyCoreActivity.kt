package io.github.xgl34222220.bichen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ProxyCoreActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { CoreManagerScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun CoreManagerScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { ProxyCoreDownloadManager(context) }
    val scope = rememberCoroutineScope()
    val tokens = LocalBichenTokens.current
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var statuses by remember { mutableStateOf(emptyList<ProxyCoreRemoteStatus>()) }
    var busyCore by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

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
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = tokens.cardBackground, modifier = Modifier.size(46.dp), shadowElevation = 1.dp) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("内核管理", color = tokens.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("只有 Mihomo 随 App 内置，其余按需下载", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Surface(shape = CircleShape, color = tokens.cardBackground, modifier = Modifier.size(46.dp), shadowElevation = 1.dp) {
                    IconButton(onClick = { if (busyCore.isBlank()) revision++ }) { Icon(Icons.Rounded.Refresh, "检查更新", tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
        item("intro") {
            Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("核心按设备 ABI 从发布源直接拉取", color = tokens.textPrimary, style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Mihomo 没有下载更新时自动回退到 App 内置版本；下载更新后优先使用下载版。Mihomo Smart 可下载后直接用于现有 Root 后端。其他核心先完成下载与版本管理，运行后端未接入时不会假报可用。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (progress.isNotBlank()) Text(progress, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    else if (notice.isNotBlank()) Text(notice, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
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
    val tokens = LocalBichenTokens.current
    Surface(shape = RoundedCornerShape(24.dp), color = tokens.cardBackground, shadowElevation = 1.dp) {
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
                                Text("内置", Modifier.padding(horizontal = 7.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                            }
                        }
                    }
                    Text(if (item.runtimeReady) "当前后端可运行" else "已纳入下载管理 · 运行后端待接入", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .3f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CoreVersionText("当前", item.installedVersion, Modifier.weight(1f))
                CoreVersionText("远程", item.latestVersion.ifBlank { "—" }, Modifier.weight(1f))
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
                Button(
                    onClick = onInstall,
                    enabled = enabled && item.canDownload,
                    modifier = Modifier.weight(1f).height(46.dp),
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
                        modifier = Modifier.height(46.dp),
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
    val tokens = LocalBichenTokens.current
    Column(modifier) {
        Text(label, color = tokens.textSecondary, style = MaterialTheme.typography.labelSmall)
        Text(value, color = tokens.textPrimary, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
