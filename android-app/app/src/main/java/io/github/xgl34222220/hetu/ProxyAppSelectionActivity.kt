package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(241, 245, 249)))
        enableEdgeToEdge()
        setContent { HetuTheme { ProxyAppSelectionPage(onBack = { finish() }) } }
    }
}

@Composable
private fun AppScopeSegmentedControl(selected: String, onSelect: (String) -> Unit) {
    val t = LocalHetuTokens.current
    val options = listOf(
        "blacklist" to "黑名单",
        "whitelist" to "白名单",
        "core" to "核心",
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(t.controlBackground.copy(alpha = .56f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val active = selected == value
            Surface(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f).heightIn(min = 42.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (active) t.selectionBackground else Color.Transparent,
                tonalElevation = 0.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        color = if (active) MaterialTheme.colorScheme.primary else t.textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    fun saveProxyApps(next: Set<String>) {
        prefs.edit().putStringSet("proxyAppPackages", next.toSet()).apply()
    }
    fun setProxyApp(packageName: String, enabled: Boolean) {
        val next = proxyApps().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        saveProxyApps(next)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(proxyApps()) }
    var appScopeId by rememberSaveable { mutableStateOf(prefs.getString("proxyAppScope", "blacklist") ?: "blacklist") }
    var showBatchActions by rememberSaveable { mutableStateOf(false) }
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val pageBg = if (dark) t.pageBackground else MaterialTheme.colorScheme.background
    val shimmer = androidx.compose.animation.core.rememberInfiniteTransition(label = "appSkeletonShimmer")
    val shimmerX by shimmer.animateFloat(initialValue = -1f, targetValue = 2f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "appSkeletonShimmerX")
    val shimmerBrush = Brush.linearGradient(
        listOf(t.controlBackground.copy(alpha = .46f), if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .92f), t.controlBackground.copy(alpha = .46f)),
        start = Offset(shimmerX * 420f, -120f),
        end = Offset((shimmerX + 1f) * 420f, 260f),
    )
    val listReveal by animateFloatAsState(
        targetValue = if (apps.isEmpty()) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "appListReveal",
    )
    val revealOffsetPx = with(LocalDensity.current) { 8.dp.toPx() }

    val scopeTitle = when (appScopeId) {
        "whitelist" -> "仅所选应用代理"
        "core" -> "核心配置"
        else -> "所选应用直连"
    }
    val scopeDescription = when (appScopeId) {
        "whitelist" -> "只有勾选的应用进入 Root 透明代理，其余应用直连。"
        "core" -> "当前不按 Android UID 过滤；名单保留，但不会参与 Root 规则。"
        else -> "勾选的应用绕过 Root 透明代理，其余应用进入代理。"
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
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = hetuContentBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = t.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text("应用名单", color = t.textPrimary, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Root 分应用代理 · 与去广告应用放行页面分离", color = t.textSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = { reload++ }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Refresh, "刷新应用", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item("scope-segments") {
            AppScopeSegmentedControl(
                selected = appScopeId,
                onSelect = { next ->
                    appScopeId = next
                    prefs.edit().putString("proxyAppScope", next).apply()
                },
            )
        }

        item("scope") {
            Surface(shape = RoundedCornerShape(18.dp), color = if (dark) t.elevatedCardBackground else Color.White, shadowElevation = if (dark) 0.dp else 1.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .10f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scopeTitle, color = t.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(scopeDescription, color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        Text("已选择 ${selected.size} 个应用 · 修改后下次启动/重启 Root 代理生效", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                Text("${visible.size}/${apps.size}", color = t.textSecondary, fontSize = 12.sp)
                TextButton(onClick = { showBatchActions = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Rounded.Checklist, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("批量", fontSize = 12.sp)
                }
            }
        }

        item("loading-skeleton") {
            androidx.compose.animation.AnimatedVisibility(
                visible = loadingApps && apps.isEmpty(),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(6) { index ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = if (dark) t.elevatedCardBackground else Color.White,
                            shadowElevation = if (dark) 0.dp else 1.dp,
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).background(shimmerBrush, RoundedCornerShape(12.dp)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) .52f else .66f).height(12.dp).background(shimmerBrush, CircleShape))
                                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) .72f else .58f).height(8.dp).background(shimmerBrush, CircleShape))
                                }
                                Box(Modifier.size(22.dp).background(shimmerBrush, CircleShape))
                            }
                        }
                    }
                }
            }
        }

        itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->
            val checked = app.packageName in selected
            val icon by produceState(initialValue = app.icon, app.packageName) { value = controller.appIcon(app.packageName) }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer {
                        alpha = listReveal
                        translationY = (1f - listReveal) * revealOffsetPx * (1f + (index.coerceAtMost(6) * .03f))
                    }
                    .clip(RoundedCornerShape(18.dp)).clickable {
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
                        Text(app.packageName, color = t.textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .08f)) {
                                Text(
                                    if (app.uid >= 0) "#${app.uid}" else "UID —",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Text(if (app.system) "系统应用" else "用户应用", color = t.textMuted, fontSize = 9.5.sp)
                        }
                    }
                    val checkScale by animateFloatAsState(if (checked) 1f else .78f, spring(dampingRatio = .50f, stiffness = 620f), label = "appCheck${app.packageName}")
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value -> setProxyApp(app.packageName, value); selected = proxyApps() },
                        modifier = Modifier.graphicsLayer { scaleX = checkScale; scaleY = checkScale },
                    )
                }
            }
        }

        item("note") {
            Surface(shape = RoundedCornerShape(16.dp), color = t.selectionBackground) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(9.dp))
                    Text("Root 数据面会把包名解析为 UID。为避免破坏系统网络，系统 UID 会被自动忽略；已卸载应用也会在启动时跳过。", color = t.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }

    if (showBatchActions) {
        ModalBottomSheet(
            onDismissRequest = { showBatchActions = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = t.elevatedCardBackground,
            contentColor = t.textPrimary,
            tonalElevation = 0.dp,
            scrimColor = Color.Black.copy(alpha = .35f),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("批量选择", fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                Text("只对当前筛选结果执行，全选/反选不会修改应用范围模式。", color = t.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                listOf(
                    Triple("全选当前结果", Icons.Rounded.DoneAll, "select"),
                    Triple("反选当前结果", Icons.Rounded.SwapVert, "invert"),
                    Triple("清空名单", Icons.Rounded.DeleteSweep, "clear"),
                ).forEach { (label, icon, action) ->
                    Surface(
                        onClick = {
                            val next = selected.toMutableSet()
                            when (action) {
                                "select" -> visible.forEach { next.add(it.packageName) }
                                "invert" -> visible.forEach { app -> if (!next.add(app.packageName)) next.remove(app.packageName) }
                                "clear" -> next.clear()
                            }
                            saveProxyApps(next)
                            selected = next
                            showBatchActions = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = t.controlBackground.copy(alpha = .52f),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(label, Modifier.weight(1f), color = t.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = t.textMuted)
                        }
                    }
                }
            }
        }
    }
}
