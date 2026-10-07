package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.ht

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeFloat
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.home.rememberHomeReveal
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.tools.ToolsMenuOption
import io.github.xgl34222220.hetu.tools.ToolsMenuPopup
import io.github.xgl34222220.hetu.tools.ToolsOptionMenuCard
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
    val home = LocalHomeColors.current
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
        title = ht("应用管理"),
        onBack = { nav.pop() },
        largeTitle = false,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 72.dp,
        overlay = {
            // What the list means, pinned to the bottom edge whatever the list scrolls to.
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = HomeDims.gutter, vertical = 8.dp)) {
                Text(buildAnnotatedString {
                    if (scope == "core") append("核心模式不按 Android UID 区分；名单会保留但暂不生效")
                    else {
                        withStyle(SpanStyle(color = home.t1, fontWeight = FontWeight.SemiBold)) { append("已选 ") }
                        withStyle(SpanStyle(color = home.accent, fontWeight = FontWeight.Bold)) { append(selected.size.toString()) }
                        withStyle(SpanStyle(color = home.t1, fontWeight = FontWeight.SemiBold)) { append(" 个") }
                        append(if (scope == "blacklist") " · 名单内应用直连，其余应用由配置规则决定" else " · 仅名单内应用进入代理")
                    }
                }, color = home.t2, style = HomeType.caption,
                    modifier = Modifier.fillMaxWidth().testTag("app-routing-summary")
                        .shadow(14.dp, HomeDims.controlShape, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .12f))
                        .clip(HomeDims.controlShape).background(home.raised).heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 14.dp))
            }
        },
        actions = {
            HxBarAction(ToolsIcons.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) query = ""
            })
            Box {
                HxBarAction(ToolsFeatureIcons.ArrowUpDown, "排序", onClick = { sortMenu = true })
                if (sortMenu) ToolsMenuPopup(onDismiss = { sortMenu = false }) {
                    ToolsOptionMenuCard(listOf(
                        ToolsMenuOption("按名称", checked = sortMode == "name") { sortMode = "name"; sortMenu = false },
                        ToolsMenuOption("按 UID", checked = sortMode == "uid") { sortMode = "uid"; sortMenu = false },
                        ToolsMenuOption("按包名", checked = sortMode == "package") { sortMode = "package"; sortMenu = false },
                        ToolsMenuOption(if (descending) "改为升序" else "改为降序", dividerBefore = true) { descending = !descending; sortMenu = false },
                    ))
                }
            }
            HxBarAction(if (onlySelected) HomeIcons.CircleCheck else ToolsFeatureIcons.CheckCheck, "仅看已选", onClick = { onlySelected = !onlySelected })
            Box {
                HxBarAction(ToolsFeatureIcons.EllipsisVertical, "更多", onClick = { moreMenu = true })
                if (moreMenu) ToolsMenuPopup(onDismiss = { moreMenu = false }) {
                    ToolsOptionMenuCard(listOf(
                        ToolsMenuOption(if (showSystem) "隐藏系统应用" else "显示系统应用", checked = showSystem) { showSystem = !showSystem; moreMenu = false },
                        ToolsMenuOption("全选当前结果") { commit(selected + visible.map { it.selectionKey }); moreMenu = false },
                        ToolsMenuOption("清空名单") { commit(emptySet()); moreMenu = false },
                        ToolsMenuOption("刷新应用") { reload++; moreMenu = false },
                    ))
                }
            }
        },
    ) {
        item(key = "mode-tabs") {
            Column(Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap)) {
                if (searching) {
                    HxSearchField(query, { query = it }, "搜索应用名称、包名或 UID", autoFocus = true)
                    Spacer(Modifier.height(10.dp))
                }
                HxSegmented(
                    options = listOf("blacklist" to "黑名单", "whitelist" to "白名单", "core" to "核心"),
                    selected = scope,
                    onSelect = ::setScope,
                )
            }
        }
        if (loading && apps.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(9) }
        } else if (visible.isEmpty()) {
            item(key = "empty") { HxEmpty(ToolsIcons.LayoutGrid, "没有匹配的应用") }
        }
        items(visible, key = { it.selectionKey }) { app ->
            val key = app.selectionKey
            TargetAppRow(vm, app, key in selected, enabled = scope != "core") { checked ->
                commit(if (checked) selected + key else selected - key)
            }
        }

    }
}

@Composable
private fun TargetAppRow(vm: HetuViewModel, app: AppItem, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalHomeColors.current
    val icon by produceState<Bitmap?>(app.icon, app.packageName) {
        if (value == null) value = vm.filters.appIcon(app.packageName)
    }
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = HomeDims.gutter)
            .padding(bottom = 8.dp)
            .clip(HomeDims.innerShape)
            .background(c.surface)
            .homeRowPressTint(source)
            .clickable(interactionSource = source, indication = null, enabled = enabled) {
                haptics.perform(if (checked) io.github.xgl34222220.hetu.ui.HetuHaptic.ToggleOff else io.github.xgl34222220.hetu.ui.HetuHaptic.ToggleOn)
                onChange(!checked)
            }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)))
        } else {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.sunken))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(app.label, style = HomeType.rowTitle, color = c.t1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, style = HomeType.caption, color = c.t2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text("#${app.uid}", style = HomeType.badge, color = c.accent)
        Spacer(Modifier.width(12.dp))
        HxSelectMark(checked && enabled)
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
    val families = listOf("mihomo", "xray", "sing-box")
    var variants by remember { mutableStateOf(families.associateWith { vm.prefs.getString("coreManagerSlot.$it", it).orEmpty() }) }
    var variantMenu by remember { mutableStateOf<String?>(null) }
    val cards = coreManagerCards(statuses, variants)

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
        title = ht("核心管理"),
        largeTitle = false,
        onBack = { onBackOverride?.invoke() ?: nav?.pop() },
        actions = { HxBarAction(Icons.Rounded.Refresh, "检查更新", onClick = { load(true) }, busy = checking) },
    ) {
        item(key = "core-runtime-status") {
            Text(
                when {
                    !vm.state.running -> "代理未运行"
                    vm.coreVersion.isNotBlank() -> "运行中：${vm.coreVersion}"
                    else -> "代理运行中"
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 28.dp),
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        if (statuses.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(3) }
        }
        items(cards, key = { it.first }) { (family, status) ->
            val core = ProxyRuntimeProfile.Core.from(status.id)
            val busy = working == status.id
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 14.dp)) {
                HxCard(padding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 16.dp)) {
                    Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        CoreConceptGlyph(status.id)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(coreManagerDisplayName(status.id, status.label),
                                    modifier = Modifier.weight(1f, fill = status.updateAvailable).hxAnchorSource().clickable(enabled = working == null) { variantMenu = family },
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), color = c.text)
                                if (status.updateAvailable) {
                                    Spacer(Modifier.width(8.dp))
                                    HxPill(ht("有更新"), HxTone.Accent)
                                }
                                if (!status.runtimeReady) {
                                    Spacer(Modifier.width(8.dp))
                                    HxPill(ht("仅下载管理"), HxTone.Neutral)
                                }
                            }
                            Text(
                                listOf(
                                    "当前 " + status.installedVersion.ifBlank { if (status.bundled) "内置版本" else "未安装" },
                                    if (status.latestVersion.isNotBlank()) "最新 ${status.latestVersion}" else "",
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                color = c.textMuted,
                            )
                        }
                    }
                    if (busy && progress.isNotBlank()) {
                        Row(Modifier.padding(start = 78.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            HxSpinner(20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(progress, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = c.textMuted, maxLines = 2)
                        }
                    } else if (status.message.isNotBlank()) {
                        Text(status.message, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = c.textMuted, modifier = Modifier.padding(top = 10.dp))
                    }
                    Spacer(Modifier.height(if (busy && progress.isNotBlank()) 14.dp else 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CoreConceptButton(
                            if (status.downloaded || status.bundled) "更新" else "下载",
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
                            filled = status.downloaded || status.bundled,
                            enabled = working == null && status.canDownload,
                            modifier = Modifier.weight(1f),
                        )
                        CoreConceptButton(
                            "导入",
                            onClick = {
                                importTarget = status.id
                                launchDocumentPicker(vm::toast) { importer.launch(arrayOf("*/*")) }
                            },
                            icon = Icons.Rounded.FileOpen,
                            filled = false,
                            enabled = working == null,
                            modifier = Modifier.weight(1f),
                        )
                        if (status.downloaded) {
                            CoreConceptButton(
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
    variantMenu?.let { family ->
        CoreManagerPicker(family, variants[family] ?: family, statuses,
            cards.filter { it.first != family }.map { it.second.id }.toSet(),
            onPick = { id ->
                variants = variants + (family to id)
                vm.prefs.edit().putString("coreManagerSlot.$family", id).apply()
                variantMenu = null
            }, onDismiss = { variantMenu = null })
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
    val c = LocalHomeColors.current
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
    val arrive = rememberHomeReveal(Unit, 620)
    HxPage(title = ht("关于"), onBack = { nav.pop() }, listState = aboutList, largeTitle = false) {
        item(key = "brand") {
            // The mark arrives with a small rise; scrolling lets it drift up slower than the
            // list and fade, handing the page over to the bar title.
            val glow = c.accent
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val offset = if (aboutList.firstVisibleItemIndex == 0) aboutList.firstVisibleItemScrollOffset.toFloat() else size.height
                        translationY = offset * .45f
                        alpha = (1f - offset / (size.height * .8f)).coerceIn(0f, 1f)
                    }
                    .drawBehind {
                        // A soft light of the accent behind the mark, with no edge to it.
                        val centre = Offset(size.width / 2f, 78.dp.toPx())
                        drawCircle(
                            Brush.radialGradient(listOf(glow.copy(alpha = if (c.dark) .26f else .20f), glow.copy(alpha = 0f)), centre, 190.dp.toPx()),
                            190.dp.toPx(), centre,
                        )
                    }
                    .padding(top = 18.dp, bottom = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painterResource(R.drawable.ic_hetu_concept),
                    null,
                    Modifier
                        .graphicsLayer {
                            val p = arrive()
                            val scale = .86f + .14f * p
                            scaleX = scale
                            scaleY = scale
                            alpha = p
                        }
                        .homeFloat(3.dp)
                        .size(112.dp)
                        .shadow(22.dp, RoundedCornerShape(28.dp), ambientColor = glow.copy(alpha = .30f), spotColor = glow.copy(alpha = .45f))
                        .clip(RoundedCornerShape(28.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Text("河图", color = c.accent, style = HomeType.largeTitle.copy(fontSize = 32.sp, lineHeight = 40.sp))
                Spacer(Modifier.height(8.dp))
                HomePill("${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", tone = HomeTone.Neutral, height = 26.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    ht("Root 透明代理与广告过滤，基于 Mihomo。"),
                    Modifier.padding(horizontal = 32.dp),
                    color = c.t2, style = HomeType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "info") {
            SettingsSection {
                SettingsGroup(title = ht("信息")) {
                    SettingsRow(ht("内置核心"), subtitle = "Mihomo", icon = ToolsIcons.Cpu) {
                        if (revision.isNotBlank()) Text(revision.take(12), color = c.t3, style = HomeType.mono.copy(fontSize = 12.sp), maxLines = 1)
                    }
                    SettingsDivider()
                    SettingsRow(ht("运行目录"), subtitle = "/data/adb/hetu", icon = ToolsIcons.Folder)
                }
            }
        }
        item(key = "licenses") {
            SettingsSection {
                SettingsGroup(title = ht("开源许可")) {
                    SettingsNavRow("Mihomo", subtitle = "GPL-3.0", icon = HxIcons.Scale) { openAsset("Mihomo", "MIHOMO-LICENSE") }
                    SettingsDivider()
                    SettingsNavRow("AdGuard DNS Filter", subtitle = "GPL-3.0", icon = HxIcons.Scale) { openAsset("AdGuard", "ADGUARD-LICENSE") }
                    SettingsDivider()
                    SettingsNavRow("Lucide Icons", subtitle = "ISC", icon = HxIcons.Scale) { openAsset("Lucide", "licenses/lucide.txt") }
                }
            }
        }
    }

    sheet?.let { (title, text) -> HxTextSheet(title, text, onDismiss = { sheet = null }, wrapLines = true, referenceDocument = true) }
}
