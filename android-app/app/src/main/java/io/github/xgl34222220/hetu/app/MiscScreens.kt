package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.AppItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ------------------------------------------------------------------ */
/*  Per-app routing list                                                */
/* ------------------------------------------------------------------ */

@Composable
internal fun AppListScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val prefs = vm.prefs
    val c = Hx.colors
    var reload by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf(vm.filters.cachedApps()) }
    var selected by remember { mutableStateOf(prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().toSet()) }
    var scope by remember { mutableStateOf(ProxyRuntimeProfile.load(prefs).appScope.id) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var showSystem by rememberSaveable { mutableStateOf(true) }
    var onlySelected by rememberSaveable { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf("name") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        loading = true
        try {
            apps = ProxyUserAppsRepository.load(context, vm.filters, reload > 0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            vm.toast(error.message ?: "应用列表读取失败")
        } finally {
            loading = false
        }
    }

    fun commit(next: Set<String>) {
        selected = next
        prefs.edit().putStringSet("proxyAppPackages", next).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyAppPackages")
        vm.bumpSettings()
    }
    fun setScope(value: String) {
        scope = value
        prefs.edit().putString("proxyAppScope", value).apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyAppScope")
        vm.bumpSettings()
    }

    val visible = remember(apps, selected, query, showSystem, onlySelected, sortMode, descending) {
        val filtered = apps.filter { app ->
            val key = app.selectionKey
            (!onlySelected || key in selected) &&
                (showSystem || !app.system || key in selected) &&
                (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true) || app.uid.toString().contains(query))
        }
        val sorted = when (sortMode) {
            "uid" -> filtered.sortedBy { it.uid }
            "package" -> filtered.sortedBy { it.packageName.lowercase() }
            else -> filtered.sortedBy { it.label.lowercase() }
        }
        if (descending) sorted.asReversed() else sorted
    }

    HxPage(
        title = "应用管理",
        onBack = { nav.pop() },
        largeTitle = true,
        actions = {
            HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) query = ""
            })
            Box {
                HxBarAction(Icons.Rounded.Sort, "排序", onClick = { sortMenu = true })
                androidx.compose.material3.DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("按名称") }, onClick = { sortMode = "name"; sortMenu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("按 UID") }, onClick = { sortMode = "uid"; sortMenu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("按包名") }, onClick = { sortMode = "package"; sortMenu = false })
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(text = { Text(if (descending) "改为升序" else "改为降序") }, onClick = { descending = !descending; sortMenu = false })
                }
            }
            HxBarAction(Icons.Rounded.Check, "仅看已选", onClick = { onlySelected = !onlySelected })
            Box {
                HxBarAction(Icons.Rounded.MoreHoriz, "更多", onClick = { moreMenu = true })
                androidx.compose.material3.DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(if (showSystem) "隐藏系统应用" else "显示系统应用") }, onClick = { showSystem = !showSystem; moreMenu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("全选当前结果") }, onClick = { commit(selected + visible.map { it.selectionKey }); moreMenu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("清空名单") }, onClick = { commit(emptySet()); moreMenu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("刷新应用") }, onClick = { reload++; moreMenu = false })
                }
            }
        },
    ) {
        item(key = "mode-tabs") {
            Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp)) {
                if (searching) {
                    HxSearchField(query, { query = it }, "搜索应用名称、包名或 UID", autoFocus = true)
                    Spacer(Modifier.height(10.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("blacklist" to "黑名单", "whitelist" to "白名单", "core" to "核心").forEach { (id, label) ->
                        val active = scope == id
                        androidx.compose.material3.Surface(
                            modifier = Modifier.weight(1f).height(44.dp).clickable { setScope(id) },
                            shape = RoundedCornerShape(17.dp),
                            color = if (active) c.surface else Color.Transparent,
                            border = if (active) null else androidx.compose.foundation.BorderStroke(1.dp, c.textFaint.copy(alpha = .72f)),
                            shadowElevation = 0.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(label, style = MaterialTheme.typography.titleSmall, color = if (active) c.text else c.textMuted)
                            }
                        }
                    }
                }
            }
        }
        if (loading && apps.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(9) }
        } else if (visible.isEmpty()) {
            item(key = "empty") { HxEmpty(Icons.Rounded.Apps, "没有匹配的应用") }
        }
        items(visible, key = { it.selectionKey }) { app ->
            val key = app.selectionKey
            TargetAppRow(vm, app, key in selected, enabled = scope != "core") { checked ->
                commit(if (checked) selected + key else selected - key)
            }
        }
        item(key = "scope-note") {
            Text(
                when (scope) {
                    "blacklist" -> "已选 ${selected.size} 个 · 名单内应用直连，其余应用由配置规则决定"
                    "whitelist" -> "已选 ${selected.size} 个 · 仅名单内应用进入代理"
                    else -> "核心模式不按 Android UID 区分；名单会保留但暂不生效"
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                modifier = Modifier.padding(horizontal = Hx.gutter + 4.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun TargetAppRow(vm: HetuViewModel, app: AppItem, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hx.colors
    val icon by produceState<Bitmap?>(app.icon, app.packageName) {
        if (value == null) value = vm.filters.appIcon(app.packageName)
    }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .clickable(enabled = enabled) {
                haptics.perform(if (checked) io.github.xgl34222220.hetu.ui.HetuHaptic.ToggleOff else io.github.xgl34222220.hetu.ui.HetuHaptic.ToggleOn)
                onChange(!checked)
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)))
        } else {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(c.surfaceMuted))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text("#${app.uid}", style = MaterialTheme.typography.labelMedium, color = c.accent)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (checked && enabled) c.accent else c.surfaceMuted)
                .clickable(enabled = enabled) { onChange(!checked) },
            contentAlignment = Alignment.Center,
        ) {
            if (checked && enabled) androidx.compose.material3.Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(17.dp))
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Core management                                                     */
/* ------------------------------------------------------------------ */

@Composable
internal fun CoresScreen(vm: HetuViewModel, onBackOverride: (() -> Unit)? = null) {
    val nav = if (onBackOverride == null) LocalNav.current else null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    val manager = remember { ProxyCoreDownloadManager(context) }
    var statuses by remember { mutableStateOf<List<ProxyCoreRemoteStatus>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var importTarget by remember { mutableStateOf<String?>(null) }

    fun load(network: Boolean) {
        checking = true
        scope.launch {
            try {
                statuses = manager.statuses(network)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "检查失败")
            } finally {
                checking = false
            }
        }
    }
    LaunchedEffect(Unit) { load(false) }

    fun afterChange(message: String) {
        vm.toast(if (vm.state.running) "$message，重启代理后使用新核心" else message)
        load(false)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val target = importTarget
        importTarget = null
        if (uri != null && target != null) {
            working = target
            scope.launch {
                try {
                    manager.importFromUri(ProxyRuntimeProfile.Core.from(target), uri, uri.lastPathSegment.orEmpty())
                    afterChange("核心已导入")
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    vm.toast(error.message ?: "导入失败")
                } finally {
                    working = null
                }
            }
        }
    }

    HxPage(
        title = "核心管理",
        largeTitle = false,
        subtitle = if (vm.coreVersion.isNotBlank()) "运行中：${vm.coreVersion}" else "内置 Mihomo，可在线更新",
        onBack = { onBackOverride?.invoke() ?: nav?.pop() },
        actions = { HxBarAction(Icons.Rounded.Refresh, "检查更新", onClick = { load(true) }, busy = checking) },
    ) {
        if (statuses.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(3) }
        }
        items(statuses, key = { it.id }) { status ->
            val core = ProxyRuntimeProfile.Core.from(status.id)
            val busy = working == status.id
            HxSection {
                HxCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HxIconBadge(Icons.Rounded.Memory)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(status.label, style = MaterialTheme.typography.titleMedium, color = c.text)
                                if (status.updateAvailable) {
                                    Spacer(Modifier.width(8.dp))
                                    HxPill("有更新", HxTone.Accent)
                                }
                                if (!status.runtimeReady) {
                                    Spacer(Modifier.width(8.dp))
                                    HxPill("仅下载管理", HxTone.Neutral)
                                }
                            }
                            Text(
                                listOf(
                                    "当前 " + status.installedVersion.ifBlank { if (status.bundled) "内置版本" else "未安装" },
                                    if (status.latestVersion.isNotBlank()) "最新 ${status.latestVersion}" else "",
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textMuted,
                            )
                        }
                    }
                    if (busy && progress.isNotBlank()) {
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            HxSpinner(12.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(progress, style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = c.accent, maxLines = 2)
                        }
                    } else if (status.message.isNotBlank()) {
                        Text(status.message, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HxButton(
                            if (status.downloaded) "更新" else "下载",
                            onClick = {
                                working = status.id
                                progress = ""
                                scope.launch {
                                    try {
                                        manager.downloadOrUpdate(core) { text -> scope.launch { progress = text } }
                                        afterChange("${status.label} 已安装")
                                    } catch (cancel: CancellationException) {
                                        throw cancel
                                    } catch (error: Exception) {
                                        vm.toast(error.message ?: "下载失败")
                                    } finally {
                                        working = null
                                        progress = ""
                                    }
                                }
                            },
                            icon = Icons.Rounded.CloudDownload,
                            busy = busy,
                            enabled = working == null && status.canDownload,
                            modifier = Modifier.weight(1f),
                        )
                        HxButton(
                            "导入",
                            onClick = {
                                importTarget = status.id
                                importer.launch(arrayOf("*/*"))
                            },
                            icon = Icons.Rounded.FileOpen,
                            filled = false,
                            enabled = working == null,
                            modifier = Modifier.weight(1f),
                        )
                        if (status.downloaded) {
                            HxButton(
                                "删除",
                                onClick = {
                                    working = status.id
                                    scope.launch {
                                        try {
                                            manager.removeDownloaded(core)
                                            afterChange("已删除下载的核心")
                                        } catch (cancel: CancellationException) {
                                            throw cancel
                                        } catch (error: Exception) {
                                            vm.toast(error.message ?: "删除失败")
                                        } finally {
                                            working = null
                                        }
                                    }
                                },
                                icon = Icons.Rounded.DeleteOutline,
                                tone = HxTone.Bad,
                                filled = false,
                                enabled = working == null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  About                                                               */
/* ------------------------------------------------------------------ */

@Composable
internal fun AboutScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Hx.colors
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    val revision = remember {
        runCatching { context.assets.open("mihomo-revision.txt").bufferedReader().use { it.readText().trim() } }.getOrDefault("")
    }

    fun openAsset(title: String, path: String) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrElse { it.message ?: "读取失败" }
            }
            sheet = title to text
        }
    }

    val aboutList = androidx.compose.foundation.lazy.rememberLazyListState()
    HxPage(title = "关于", onBack = { nav.pop() }, listState = aboutList, largeTitle = false) {
        item(key = "brand") {
            val appear = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .6f, stiffness = 260f)) }
            val float = androidx.compose.animation.core.rememberInfiniteTransition(label = "logoFloat")
            val drift by float.animateFloat(
                -1f,
                1f,
                androidx.compose.animation.core.infiniteRepeatable(tween(3200), androidx.compose.animation.core.RepeatMode.Reverse),
                label = "logoDrift",
            )
            val wash = androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(c.accentSoft, androidx.compose.ui.graphics.lerp(c.accentSoft, Color(0xFFF7D9E8), if (c.dark) .15f else .55f), c.canvas),
            )
            // Hero drifts up slower than the list and fades out, handing over to the bar title.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .graphicsLayer {
                        val offset = if (aboutList.firstVisibleItemIndex == 0) aboutList.firstVisibleItemScrollOffset.toFloat() else size.height
                        translationY = offset * .45f
                        alpha = (1f - offset / (size.height * .8f)).coerceIn(0f, 1f)
                    }
                    .background(wash),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painterResource(R.drawable.ic_hetu_official),
                        null,
                        Modifier
                            .size(96.dp)
                            .graphicsLayer {
                                val s = .7f + .3f * appear.value
                                scaleX = s
                                scaleY = s
                                alpha = appear.value.coerceIn(0f, 1f)
                                translationY = drift * 4.dp.toPx()
                            }
                            .hxSoftShadow(RoundedCornerShape(26.dp), 16.dp)
                            .clip(RoundedCornerShape(26.dp)),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("河图", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = c.accent)
                    Text("${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle), color = c.textMuted)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Root 透明代理与广告过滤，基于 Mihomo。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        item(key = "info") {
            HxSection() {
                HxGroup(title = "信息") {
                    HxRow("内置核心", subtitle = revision.ifBlank { "Mihomo" }, icon = Icons.Rounded.Memory)
                    HxDivider()
                    HxRow("运行目录", subtitle = "/data/adb/hetu", icon = Icons.Rounded.Description, iconTint = c.textMuted)
                }
            }
        }
        item(key = "licenses") {
            HxSection() {
                HxGroup(title = "开源许可") {
                    HxNavRow("Mihomo", subtitle = "GPL-3.0", icon = Icons.Rounded.Description, iconTint = c.textMuted) { openAsset("Mihomo", "MIHOMO-LICENSE") }
                    HxDivider()
                    HxNavRow("AdGuard DNS Filter", subtitle = "GPL-3.0", icon = Icons.Rounded.Description, iconTint = c.textMuted) { openAsset("AdGuard", "ADGUARD-LICENSE") }
                    HxDivider()
                    HxNavRow("Lucide Icons", subtitle = "ISC", icon = Icons.Rounded.Description, iconTint = c.textMuted) { openAsset("Lucide", "licenses/lucide.txt") }
                }
            }
        }
    }

    sheet?.let { (title, text) -> HxTextSheet(title, text, onDismiss = { sheet = null }) }
}
