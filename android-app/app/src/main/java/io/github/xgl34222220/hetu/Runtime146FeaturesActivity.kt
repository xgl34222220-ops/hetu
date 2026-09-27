package io.github.xgl34222220.hetu

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Extra 146 functions are reachable without replacing the accepted UI92 pages or dock. */
class Runtime146FeaturesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { Runtime146Features { finish() } } }
    }
}

@Composable
internal fun Runtime146Features(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val scope = rememberCoroutineScope()
    val t = LocalHetuTokens.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var mirrorDialog by remember { mutableStateOf(false) }
    var mirrorPrefix by remember { mutableStateOf(prefs.getString("downloadMirrorPrefix", "").orEmpty()) }
    var mirrorEnabled by remember { mutableStateOf(prefs.getBoolean("downloadMirrorEnabled", false)) }
    var disconnect by remember { mutableStateOf(prefs.getBoolean("proxySelectorDisconnectOnSelect", false)) }
    var startPanel by remember { mutableStateOf(prefs.getBoolean("startOnPanel", false)) }
    var restoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            try { message = "已备份 ${HetuSettingsBackup.export(context, uri)} 项设置与配置" }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { message = error.message ?: "备份失败" }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }
    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(t.pageBackground).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text("更多功能设置", color = t.textPrimary, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
            }
        }
        item { RefSectionLabel("服务与控制") }
        item { RefGroup {
            RefToolRow(Icons.Rounded.Notifications, Color(0xFF2563EB), "通知与快捷控制", "状态通知、快捷按钮与系统磁贴") { open(ProxyNotificationSettingsActivity::class.java) }
            RefDivider()
            RefToolRow(Icons.Rounded.Terminal, Color(0xFF2563EB), "脚本", "脚本管理与启动、停止钩子") { open(ProxyScriptsActivity::class.java) }
            RefDivider()
            RefToolRow(Icons.Rounded.Article, Color(0xFF9333EA), "日志管理", "筛选、搜索、暂停和导出运行日志") { open(ProxyLogViewerActivity::class.java) }
            RefDivider()
            RefToolRow(Icons.Rounded.Description, Color(0xFF64748B), "启动配置", "查看和校验当前运行配置") { open(ProxyStartupConfigActivity::class.java) }
        } }
        item { RefSectionLabel("面板与订阅") }
        item { RefGroup {
            RefToolRow(Icons.Rounded.Web, Color(0xFF2563EB), "Web 面板", "管理本地与远程面板") { open(ProxyWebPanelsActivity::class.java) }
            RefDivider()
            RefSwitchRow(Icons.Rounded.SwapHoriz, Color(0xFF0EA5E9), "切换节点后断开旧连接", "只关闭经过当前策略组的旧连接", disconnect) {
                disconnect = it; prefs.edit().putBoolean("proxySelectorDisconnectOnSelect", it).apply()
            }
            RefDivider()
            RefToolRow(Icons.Rounded.Speed, Color(0xFF2563EB), "延迟目标", "自定义探测地址及超时") { open(ProxyLatencyTargetsActivity::class.java) }
            RefDivider()
            RefToolRow(Icons.Rounded.CloudSync, Color(0xFF2563EB), "Sub-Store", "订阅处理与配置导入") { open(ProxySubStoreActivity::class.java) }
            RefDivider()
            RefSwitchRow(Icons.Rounded.Dashboard, Color(0xFF2563EB), "启动后进入面板", "保留当前底栏与页面布局", startPanel) {
                startPanel = it; prefs.edit().putBoolean("startOnPanel", it).apply()
            }
        } }
        item { RefSectionLabel("共享网络与下载") }
        item { RefGroup {
            RefToolRow(Icons.Rounded.WifiTethering, Color(0xFF10B981), "接口与下游设备", "接口选择与 MAC 直连规则") { open(ProxySharedNetworkSettingsActivity::class.java) }
            RefDivider()
            RefToolRow(Icons.Rounded.Download, Color(0xFF2563EB), "下载镜像", if (mirrorEnabled) mirrorPrefix.ifBlank { "已开启，尚未填写前缀" } else "关闭") { mirrorDialog = true }
        } }
        item { RefSectionLabel("备份与恢复") }
        item { RefGroup {
            RefToolRow(Icons.Rounded.UploadFile, Color(0xFF2563EB), "导出备份", if (busy) "正在处理…" else "导出应用设置与配置库") { if (!busy) exporter.launch("Hetu-backup.json") }
            RefDivider()
            RefToolRow(Icons.Rounded.Restore, Color(0xFF2563EB), "恢复备份", "恢复前确认，不自动卸载或清除应用数据") { if (!busy) importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            RefDivider()
            RefToolRow(Icons.Rounded.Info, Color(0xFF64748B), "关于河图", "版本、开源许可与支持") { open(ProxyAboutActivity::class.java) }
        } }
        if (message.isNotBlank()) item { Text(message, color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
    }
    if (mirrorDialog) AlertDialog(onDismissRequest = { mirrorDialog = false }, title = { Text("下载镜像") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用镜像", Modifier.weight(1f)); Switch(checked = mirrorEnabled, onCheckedChange = { mirrorEnabled = it })
            }
            OutlinedTextField(mirrorPrefix, { mirrorPrefix = it }, label = { Text("镜像前缀") }, supportingText = { Text("仅使用你信任的镜像；不会附加控制器密钥。") })
        }
    }, confirmButton = { TextButton(onClick = {
        prefs.edit().putBoolean("downloadMirrorEnabled", mirrorEnabled).putString("downloadMirrorPrefix", mirrorPrefix.trim()).apply(); mirrorDialog = false
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = { mirrorDialog = false }) { Text("取消") } })
    restoreUri?.let { uri -> AlertDialog(onDismissRequest = { restoreUri = null }, title = { Text("恢复备份？") },
        text = { Text("备份内的设置与配置将写入当前应用。不会自动重启正在运行的代理。") },
        confirmButton = { TextButton(onClick = {
            restoreUri = null
            if (!busy) scope.launch {
                busy = true
                try { message = "已恢复 ${HetuSettingsBackup.restore(context, uri)} 项，请检查设置后手动应用" }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { message = error.message ?: "恢复失败" }
                finally { busy = false }
            }
        }) { Text("恢复") } }, dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("取消") } }) }
}
