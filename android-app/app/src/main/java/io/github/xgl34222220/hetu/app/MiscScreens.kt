package io.github.xgl34222220.hetu

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
    var showSystem by rememberSaveable { mutableStateOf(false) }
    var onlySelected by rememberSaveable { mutableStateOf(false) }

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

    val visible = apps.filter { app ->
        val key = app.selectionKey
        (!onlySelected || key in selected) &&
            (showSystem || !app.system || key in selected) &&
            (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
    }

    HxPage(
        title = "应用名单",
        subtitle = "已选 ${selected.size} 个 · 修改后重启代理生效",
        onBack = { nav.pop() },
        actions = {
            HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                searching = !searching
                if (!searching) query = ""
            })
            HxBarAction(Icons.Rounded.Refresh, "刷新", onClick = { reload++ }, busy = loading && apps.isNotEmpty())
        },
    ) {
        item(key = "scope") {
            Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp)) {
                HxSegmented(
                    options = listOf("core" to "不区分", "blacklist" to "名单直连", "whitelist" to "名单代理"),
                    selected = scope,
                    onSelect = {
                        scope = it
                        prefs.edit().putString("proxyAppScope", it).apply()
                        ProxyRuntimeSettings.markDirty(prefs, "proxyAppScope")
                        vm.bumpSettings()
                    },
                )
                Text(
                    when (scope) {
                        "blacklist" -> "勾选的应用绕过代理，其余应用正常走代理。"
                        "whitelist" -> "只有勾选的应用走代理，其余应用直连。"
                        else -> "不按应用区分，全部由配置规则决定；名单会保留但不生效。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
                if (searching) {
                    Spacer(Modifier.height(10.dp))
                    HxSearchField(query, { query = it }, "搜索应用或包名")
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    HxFilterChip("系统应用", showSystem) { showSystem = it }
                    HxFilterChip("仅已选", onlySelected) { onlySelected = it }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (visible.isNotEmpty() && visible.all { it.selectionKey in selected }) "取消全选" else "全选",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.accent,
                        modifier = Modifier.clip(Hx.chipShape).clickable(enabled = visible.isNotEmpty()) {
                            val keys = visible.map { it.selectionKey }.toSet()
                            commit(if (keys.all { it in selected }) selected - keys else selected + keys)
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
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
            AppRow(vm, app, key in selected) { checked -> commit(if (checked) selected + key else selected - key) }
        }
    }
}

@Composable
private fun HxFilterChip(label: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hx.colors
    FilterChip(
        selected = selected,
        onClick = { onChange(!selected) },
        label = { Text(label) },
        shape = Hx.pillShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = c.accentSoft,
            selectedLabelColor = c.accent,
            labelColor = c.textMuted,
        ),
    )
}

@Composable
private fun AppRow(vm: HetuViewModel, app: AppItem, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Hx.colors
    val icon by produceState<Bitmap?>(app.icon, app.packageName) {
        if (value == null) value = vm.filters.appIcon(app.packageName)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Hx.gutter)
            .padding(bottom = 6.dp)
            .clip(Hx.rowShape)
            .background(c.surface)
            .clickable { onChange(!checked) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
        } else {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surfaceMuted))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName + (if (app.userId != android.os.Process.myUid() / 100000) " · 用户 ${app.userId}" else "") + if (app.system) " · 系统" else "",
                style = MaterialTheme.typography.bodySmall,
                color = c.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = c.accent, uncheckedColor = c.textFaint, checkmarkColor = c.onAccent),
        )
    }
}

/* ------------------------------------------------------------------ */
/*  Core management                                                     */
/* ------------------------------------------------------------------ */

@Composable
internal fun CoresScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
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
        subtitle = if (vm.coreVersion.isNotBlank()) "运行中：${vm.coreVersion}" else "内置 Mihomo，可在线更新",
        onBack = { nav.pop() },
        actions = { HxBarAction(Icons.Rounded.Refresh, "检查更新", onClick = { load(true) }, busy = checking) },
    ) {
        if (statuses.isEmpty()) {
            item(key = "loading") { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { HxSpinner(26.dp) } }
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
                        Text(progress, style = MaterialTheme.typography.bodySmall, color = c.accent, modifier = Modifier.padding(top = 10.dp))
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

    HxPage(title = "关于", onBack = { nav.pop() }) {
        item(key = "brand") {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_hetu_official), null, Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)))
                Spacer(Modifier.height(14.dp))
                Text("河图", style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Root 透明代理与广告过滤，基于 Mihomo。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
        item(key = "info") {
            HxSection("信息") {
                HxGroup {
                    HxRow("内置核心", subtitle = revision.ifBlank { "Mihomo" }, icon = Icons.Rounded.Memory)
                    HxDivider()
                    HxRow("运行目录", subtitle = "/data/adb/hetu", icon = Icons.Rounded.Description, iconTint = c.textMuted)
                }
            }
        }
        item(key = "licenses") {
            HxSection("开源许可") {
                HxGroup {
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
