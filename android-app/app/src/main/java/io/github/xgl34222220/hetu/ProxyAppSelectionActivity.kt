package io.github.xgl34222220.hetu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import io.github.xgl34222220.hetu.ui.HetuComposeController
import io.github.xgl34222220.hetu.ui.HetuTheme
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import kotlinx.coroutines.CancellationException

class ProxyAppSelectionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyAppSelectionPage(onBack = { finish() }) } }
    }
}

private enum class AppSortMode { NAME, UID, PACKAGE }

@Composable
private fun ProxyAppSelectionPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("hetu", 0) }
    val controller = remember { HetuComposeController(context) }
    var reload by remember { mutableIntStateOf(0) }
    var loadingApps by remember { mutableStateOf(controller.cachedApps().isEmpty()) }
    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        loadingApps = true
        value = try { controller.loadApps(forceRefresh = reload > 0) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { controller.cachedApps() }
        finally { loadingApps = false }
    }

    fun proxyApps(): Set<String> = prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().toSet()
    fun setProxyApp(packageName: String, enabled: Boolean) {
        val next = proxyApps().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        prefs.edit().putStringSet("proxyAppPackages", next).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyAppPackages")
    }
    fun setScope(scope: ProxyRuntimeProfile.AppScope) {
        prefs.edit().putString("proxyAppScope", scope.id).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyAppScope")
    }

    var selected by remember { mutableStateOf(proxyApps()) }
    var scopeMode by remember { mutableStateOf(ProxyRuntimeProfile.load(prefs).appScope) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(true) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(AppSortMode.NAME) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else Color(0xFFF2F0F9)
    val card = if (dark) t.cardBackground else Color(0xFFFCFBFF)
    val outline = if (dark) t.outline else Color(0xFF777986).copy(alpha = .72f)

    val visible = remember(apps, selected, query, showSystem, selectedOnly, sortMode, descending) {
        val filtered = apps.filter { app ->
            (!selectedOnly || app.packageName in selected) &&
                (showSystem || !app.system || app.packageName in selected) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true) || app.uid.toString().contains(query))
        }
        val sorted = when (sortMode) {
            AppSortMode.NAME -> filtered.sortedBy { it.label.lowercase() }
            AppSortMode.UID -> filtered.sortedBy { it.uid }
            AppSortMode.PACKAGE -> filtered.sortedBy { it.packageName.lowercase() }
        }
        if (descending) sorted.asReversed() else sorted
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(pageBg),
        contentPadding = PaddingValues(
            start = 14.dp,
            top = 8.dp,
            end = 14.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("top") {
            Column(Modifier.statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(42.dp)) {
                        Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "搜索", tint = t.textPrimary, modifier = Modifier.size(27.dp))
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Rounded.Sort, "排序", tint = t.textPrimary, modifier = Modifier.size(27.dp))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("按名称") }, onClick = { sortMode = AppSortMode.NAME; sortMenu = false })
                            DropdownMenuItem(text = { Text("按 UID") }, onClick = { sortMode = AppSortMode.UID; sortMenu = false })
                            DropdownMenuItem(text = { Text("按包名") }, onClick = { sortMode = AppSortMode.PACKAGE; sortMenu = false })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(if (descending) "改为升序" else "改为降序") }, onClick = { descending = !descending; sortMenu = false })
                        }
                    }
                    IconButton(onClick = { selectedOnly = !selectedOnly }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.Check, "仅看已选", tint = if (selectedOnly) MaterialTheme.colorScheme.primary else t.textPrimary, modifier = Modifier.size(29.dp))
                    }
                    Box {
                        IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Rounded.MoreHoriz, "更多", tint = t.textPrimary, modifier = Modifier.size(28.dp))
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text(if (showSystem) "隐藏系统应用" else "显示系统应用") }, onClick = { showSystem = !showSystem; moreMenu = false })
                            DropdownMenuItem(text = { Text("全选当前结果") }, onClick = {
                                val next = proxyApps().toMutableSet().apply { addAll(visible.map { it.packageName }) }
                                prefs.edit().putStringSet("proxyAppPackages", next).apply(); ProxyRuntimeSettings.markDirty(prefs, "proxyAppPackages")
                                selected = next; moreMenu = false
                            })
                            DropdownMenuItem(text = { Text("清空名单") }, onClick = {
                                prefs.edit().putStringSet("proxyAppPackages", emptySet()).apply(); ProxyRuntimeSettings.markDirty(prefs, "proxyAppPackages")
                                selected = emptySet(); moreMenu = false
                            })
                            DropdownMenuItem(text = { Text("刷新应用") }, onClick = { reload++; moreMenu = false })
                        }
                    }
                }
                Text(
                    "应用管理",
                    color = t.textPrimary,
                    fontSize = 31.sp,
                    lineHeight = 37.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp),
                )
                AnimatedVisibility(visible = searching, enter = fadeIn(), exit = fadeOut()) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        singleLine = true,
                        placeholder = { Text("搜索应用名称、包名或 UID") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = card,
                            unfocusedContainerColor = card,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
        }

        item("scope-tabs") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    ProxyRuntimeProfile.AppScope.BLACKLIST to "黑名单",
                    ProxyRuntimeProfile.AppScope.WHITELIST to "白名单",
                    ProxyRuntimeProfile.AppScope.CORE to "核心",
                ).forEach { (mode, label) ->
                    val active = scopeMode == mode
                    Surface(
                        modifier = Modifier.weight(1f).height(44.dp).clickable {
                            scopeMode = mode; setScope(mode)
                        },
                        shape = RoundedCornerShape(17.dp),
                        color = if (active) card else Color.Transparent,
                        border = if (active) null else androidx.compose.foundation.BorderStroke(1.dp, outline),
                        shadowElevation = 0.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, color = if (active) t.textPrimary else t.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (loadingApps && apps.isEmpty()) {
            items(6) {
                Surface(shape = RoundedCornerShape(18.dp), color = card) {
                    Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(t.controlBackground))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.fillMaxWidth(.42f).height(11.dp).clip(CircleShape).background(t.controlBackground))
                            Box(Modifier.fillMaxWidth(.66f).height(8.dp).clip(CircleShape).background(t.controlBackground))
                        }
                    }
                }
            }
        }

        itemsIndexed(visible, key = { _, app -> app.packageName }) { _, app ->
            val checked = app.packageName in selected
            val icon by produceState(initialValue = app.icon, app.packageName) { value = controller.appIcon(app.packageName) }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(enabled = scopeMode != ProxyRuntimeProfile.AppScope.CORE) {
                    setProxyApp(app.packageName, !checked); selected = proxyApps()
                },
                shape = RoundedCornerShape(18.dp),
                color = card,
                shadowElevation = 0.dp,
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(t.controlBackground), contentAlignment = Alignment.Center) {
                        if (icon != null) Image(icon!!.asImageBitmap(), app.label, modifier = Modifier.size(42.dp))
                        else Icon(Icons.Rounded.Android, null, tint = t.textSecondary, modifier = Modifier.size(25.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = t.textPrimary, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = t.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("#${app.uid}", color = MaterialTheme.colorScheme.primary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape)
                            .background(if (checked && scopeMode != ProxyRuntimeProfile.AppScope.CORE) MaterialTheme.colorScheme.primary else t.controlBackground)
                            .border(1.dp, if (checked && scopeMode != ProxyRuntimeProfile.AppScope.CORE) MaterialTheme.colorScheme.primary else t.outline.copy(alpha = .45f), CircleShape)
                            .clickable(enabled = scopeMode != ProxyRuntimeProfile.AppScope.CORE) {
                                setProxyApp(app.packageName, !checked); selected = proxyApps()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (checked && scopeMode != ProxyRuntimeProfile.AppScope.CORE) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }

        item("footer") {
            Text(
                when (scopeMode) {
                    ProxyRuntimeProfile.AppScope.BLACKLIST -> "已选 ${selected.size} 个 · 名单内应用直连，其余由配置规则决定"
                    ProxyRuntimeProfile.AppScope.WHITELIST -> "已选 ${selected.size} 个 · 仅名单内应用进入代理"
                    ProxyRuntimeProfile.AppScope.CORE -> "核心模式不按 Android UID 区分，名单保留但暂不生效"
                },
                color = t.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}
