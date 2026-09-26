package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.CrystalSurface as Surface
import io.github.xgl34222220.hetu.ui.*

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.coroutines.withTimeoutOrNull

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
    var loadError by remember { mutableStateOf("") }
    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        loadingApps = true
        loadError = ""
        value = try {
            withTimeoutOrNull(20_000L) { ProxyUserAppsRepository.load(context, controller, reload > 0) } ?: run {
                loadError = "应用列表读取超时"
                controller.cachedApps()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            loadError = error.message ?: "应用列表读取失败"
            controller.cachedApps()
        } finally {
            loadingApps = false
        }
    }
    fun proxyApps(): Set<String> = prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().toSet()
    fun saveProxyApps(next: Set<String>) {
        prefs.edit().putStringSet("proxyAppPackages", next.toSet()).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyAppPackages")
    }
    fun setProxyApp(packageName: String, enabled: Boolean) {
        val next = proxyApps().toMutableSet()
        if (enabled) next.add(packageName) else next.remove(packageName)
        saveProxyApps(next)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var appTypeFilter by rememberSaveable { mutableStateOf("user") }
    var userFilter by rememberSaveable { mutableIntStateOf(-1) }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(proxyApps()) }
    var appScopeId by rememberSaveable { mutableStateOf(prefs.getString("proxyAppScope", "blacklist") ?: "blacklist") }
    var showBatchActions by rememberSaveable { mutableStateOf(false) }
    var showGidRules by rememberSaveable { mutableStateOf(false) }
    var gidRules by remember { mutableStateOf(prefs.getStringSet("proxyDirectGids", emptySet()).orEmpty().toSet()) }
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
    val visible = remember(apps, query, appTypeFilter, userFilter, selectedOnly, selected) {
        apps.filter { app ->
            val typeMatch = when (appTypeFilter) {
                "system" -> app.system
                "all" -> true
                else -> !app.system
            }
            (!selectedOnly || app.selectionKey in selected) && (userFilter < 0 || app.userId == userFilter) &&
                typeMatch &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().crystalPageBackground(),
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
                    if (next == "whitelist" && selected.isEmpty()) {
                        android.widget.Toast.makeText(
                            context,
                            "请先选择至少一个应用，再启用“仅所选应用代理”",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        appScopeId = next
                        prefs.edit().putString("proxyAppScope", next).apply()
                        ProxyRuntimeSettings.markDirty(prefs, "proxyAppScope")
                    }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (gidRules.isEmpty()) "GID 直连未配置" else "GID 直连 ${gidRules.size} 项",
                                color = t.textSecondary,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { showGidRules = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                Text("GID 规则", fontSize = 11.sp)
                                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }

        item("search") {
            LiquidGlassTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = "搜索",
                placeholder = "搜索应用名称或包名",
                leadingIcon = Icons.Rounded.Search,
            )
        }

        item("filters") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiquidChoicePill("全部", appTypeFilter == "all", { appTypeFilter = "all" })
                    LiquidChoicePill("用户应用", appTypeFilter == "user", { appTypeFilter = "user" })
                    LiquidChoicePill("系统应用", appTypeFilter == "system", { appTypeFilter = "system" })
                }
                if (apps.map { it.userId }.distinct().size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiquidChoicePill("全部用户", userFilter == -1, { userFilter = -1 })
                    apps.map { it.userId }.distinct().sorted().forEach { user -> LiquidChoicePill(if (user == 0) "主用户" else "用户 $user", userFilter == user, { userFilter = user }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiquidChoicePill("已选择", selectedOnly, { selectedOnly = !selectedOnly })
                    Spacer(Modifier.weight(1f))
                    Text("${visible.size}/${apps.size}", color = t.textSecondary, fontSize = 12.sp)
                    TextButton(onClick = { showBatchActions = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.Checklist, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("批量", fontSize = 12.sp)
                    }
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

        if (!loadingApps && apps.isEmpty()) {
            item("apps-empty") {
                Surface(shape = RoundedCornerShape(18.dp), color = t.elevatedCardBackground) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Rounded.Apps, null, tint = t.textMuted, modifier = Modifier.size(28.dp))
                        Text(if (loadError.isBlank()) "未读取到应用" else "应用列表读取失败", color = t.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(
                            loadError.ifBlank { "可以点击右上角刷新重新读取本机应用。" },
                            color = t.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        TextButton(onClick = { reload++ }) { Text("重新读取") }
                    }
                }
            }
        }

        itemsIndexed(visible, key = { _, app -> app.selectionKey }) { index, app ->
            val checked = app.selectionKey in selected
            val icon by produceState(initialValue = app.icon, app.packageName) { value = controller.appIcon(app.packageName) }
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .graphicsLayer {
                        alpha = listReveal
                        translationY = (1f - listReveal) * revealOffsetPx * (1f + (index.coerceAtMost(6) * .03f))
                    }
                    .clip(RoundedCornerShape(18.dp)).clickable {
                    setProxyApp(app.selectionKey, !checked)
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
                            val androidUserId = if (app.uid >= 0) app.uid / 100000 else 0
                            Text(
                                buildString {
                                    if (androidUserId > 0) append("用户 ").append(androidUserId).append(" · ")
                                    append(if (app.system) "系统应用" else "用户应用")
                                },
                                color = t.textMuted,
                                fontSize = 9.5.sp,
                            )
                        }
                    }
                    val checkScale by animateFloatAsState(if (checked) 1f else .78f, spring(dampingRatio = .50f, stiffness = 620f), label = "appCheck${app.packageName}")
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { value -> setProxyApp(app.selectionKey, value); selected = proxyApps() },
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

    if (showGidRules) {
        var text by remember(showGidRules) { mutableStateOf(gidRules.sorted().joinToString("\n")) }
        var error by remember(showGidRules) { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showGidRules = false },
            shape = RoundedCornerShape(topStart = HetuGlassRadius.Sheet, topEnd = HetuGlassRadius.Sheet),
            containerColor = Color.Transparent,
            contentColor = t.textPrimary,
            tonalElevation = 0.dp,
            scrimColor = Color.Black.copy(alpha = .35f),
        ) {
            Column(
                Modifier.fillMaxWidth().liquidSheetMaterial().navigationBarsPadding().imePadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("GID 直连规则", fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    "每行一个 GID 或范围，例如 10123 或 10123-10130。仅允许 >=10000 的普通应用 GID；系统权限组（如 3003）禁止加入，避免整机流量绕过代理。",
                    color = t.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                LiquidGlassTextField(
                    value = text,
                    onValueChange = { text = it; error = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                    label = "GID / GID 范围",
                    placeholder = "10123\n10123-10130",
                    singleLine = false,
                    textStyle = LocalTextStyle.current.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .crystalMaterial(
                            RoundedCornerShape(HetuGlassRadius.Input),
                            depth = CrystalDepth.InsetItem,
                        )
                ) {
                    Text(
                        "GID 规则属于 Root OUTPUT 直连绕过，保存后需要重启代理才会应用；仅支持 TPROXY / Redirect / Enhance。TUN / eBPF 不会假装生效，而会在预检时明确提示先清空 GID 规则。它不会改变黑名单/白名单中的 UID 选择。",
                        Modifier.fillMaxWidth().padding(12.dp),
                        color = t.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            text = ""
                            error = ""
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("清空") }
                    Button(
                        onClick = {
                            val raw = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                            val parsed = LinkedHashSet<String>()
                            var failure = ""
                            raw.forEach { value ->
                                if (failure.isNotBlank()) return@forEach
                                if (!value.matches(Regex("[0-9]{1,10}(?:-[0-9]{1,10})?"))) {
                                    failure = "格式无效：$value"
                                    return@forEach
                                }
                                val parts = value.split("-")
                                val start = parts[0].toLongOrNull() ?: -1L
                                val end = parts.getOrNull(1)?.toLongOrNull() ?: start
                                if (start < 10000L || end < start || end > Int.MAX_VALUE.toLong()) {
                                    failure = "只允许普通应用 GID（>=10000）：$value"
                                    return@forEach
                                }
                                parsed += if (start == end) start.toString() else "$start-$end"
                            }
                            if (parsed.size > 64) failure = "最多允许 64 条 GID 规则"
                            if (failure.isNotBlank()) {
                                error = failure
                            } else {
                                prefs.edit().putStringSet("proxyDirectGids", parsed).apply()
                                ProxyRuntimeSettings.markDirty(prefs, "proxyDirectGids")
                                gidRules = parsed
                                showGidRules = false
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text("保存") }
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
                                "select" -> visible.forEach { next.add(it.selectionKey) }
                                "invert" -> visible.forEach { app -> if (!next.add(app.selectionKey)) next.remove(app.selectionKey) }
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
