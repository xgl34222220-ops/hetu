package io.github.xgl34222220.bichen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.bichen.ui.BichenComposeController
import io.github.xgl34222220.bichen.ui.BichenTheme
import io.github.xgl34222220.bichen.ui.LocalBichenTokens
import kotlinx.coroutines.CancellationException

class ProxyAppSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BichenTheme { ProxyAppSelectionPage(onBack = { finish() }) } }
    }
}

@Composable
private fun ProxyAppSelectionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("bichen", 0) }
    val controller = remember { BichenComposeController(context) }
    var reload by remember { mutableIntStateOf(0) }
    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        value = try { controller.loadApps(forceRefresh = reload > 0) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { controller.cachedApps() }
    }
    fun proxyApps(): Set<String> = prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().toSet()
    fun setProxyApp(packageName: String, enabled: Boolean) {
        val next = proxyApps().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        prefs.edit().putStringSet("proxyAppPackages", next).apply()
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(proxyApps()) }
    val profile = remember(selected, reload) { ProxyRuntimeProfile.load(prefs) }
    val t = LocalBichenTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF1F5F9)

    val scopeTitle = when (profile.appScope) {
        ProxyRuntimeProfile.AppScope.BLACKLIST -> "所选应用直连"
        ProxyRuntimeProfile.AppScope.WHITELIST -> "仅所选应用代理"
        ProxyRuntimeProfile.AppScope.CORE -> "核心配置"
    }
    val scopeDescription = when (profile.appScope) {
        ProxyRuntimeProfile.AppScope.BLACKLIST -> "勾选的应用绕过 Root 透明代理，其余应用进入代理。"
        ProxyRuntimeProfile.AppScope.WHITELIST -> "只有勾选的应用进入 Root 透明代理，其余应用直连。"
        ProxyRuntimeProfile.AppScope.CORE -> "当前不按 Android UID 过滤；名单已保存，但不会参与 Root 规则。"
    }
    val visible = remember(apps, query, showSystem, selectedOnly, selected) {
        apps.filter { app ->
            (!selectedOnly || app.packageName in selected) &&
                (showSystem || !app.system || app.packageName in selected) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("应用名单", color = t.textPrimary, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Root 分应用代理 · 与去广告应用放行页面分离", color = t.textSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = { reload++ }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新应用", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item("scope") {
            Surface(shape = RoundedCornerShape(22.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scopeTitle, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(scopeDescription, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        Text("已选择 ${selected.size} 个应用 · 修改后下次启动/重启 Root 代理生效", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item("search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(17.dp),
            )
        }

        item("filters") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("系统应用") })
                FilterChip(selected = selectedOnly, onClick = { selectedOnly = !selectedOnly }, label = { Text("已选择") })
                Spacer(Modifier.weight(1f))
                Text("${visible.size}/${apps.size}", color = t.textSecondary, fontSize = 11.sp)
            }
        }

        if (apps.isEmpty()) {
            item("loading") {
                Box(Modifier.fillMaxWidth().padding(vertical = 34.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        Text("正在读取本机应用…", color = t.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected
            val icon by produceState(initialValue = app.icon, app.packageName) { value = controller.appIcon(app.packageName) }
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable {
                    setProxyApp(app.packageName, !checked)
                    selected = proxyApps()
                },
                shape = RoundedCornerShape(18.dp),
                color = if (checked) t.selectionBackground else if (dark) t.elevatedCardBackground else Color.White,
                shadowElevation = if (dark) 0.dp else 1.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(t.controlBackground), contentAlignment = Alignment.Center) {
                        if (icon != null) Image(icon!!.asImageBitmap(), app.label, modifier = Modifier.size(34.dp))
                        else Icon(Icons.Rounded.Android, null, tint = t.textSecondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(app.label, color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = t.textSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Checkbox(checked = checked, onCheckedChange = { value ->
                        setProxyApp(app.packageName, value)
                        selected = proxyApps()
                    })
                }
            }
        }

        item("note") {
            Surface(shape = RoundedCornerShape(16.dp), color = t.selectionBackground) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Root 数据面会把包名解析为 UID。为避免破坏系统网络，系统 UID 会被自动忽略；已卸载应用也会在启动时跳过。", color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}
