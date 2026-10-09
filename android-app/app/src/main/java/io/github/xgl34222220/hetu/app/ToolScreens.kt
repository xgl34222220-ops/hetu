package io.github.xgl34222220.hetu

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeFormField
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeRowPressTint
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsFeatureIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.tools.ToolsInfoCard
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  Shared: editable list section                                       */
/* ------------------------------------------------------------------ */

/**
 * A titled card of fields, one per entry, each with a remove button, closed by 添加一项.
 * Entries are plain state objects so the caller decides when to save.
 *
 * @param fieldLabel what one entry is (“CIDR”, “SSID”); it names the remove button of each row.
 */
@Composable
internal fun HxEditableList(
    title: String,
    fieldLabel: String,
    placeholder: String,
    entries: SnapshotStateList<HxEditEntry>,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onEdited: () -> Unit = {},
) {
    val c = LocalHomeColors.current
    HxGroup(modifier = modifier, title = ht(title)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            entries.forEach { entry ->
                key(entry.id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HomeFormField(
                            label = "", value = entry.text, onValueChange = { entry.text = it; onEdited() },
                            modifier = Modifier.weight(1f), placeholder = placeholder, keyboardType = keyboardType,
                        )
                        HomeIconButton(ToolsIcons.Trash2, "删除", { entries.remove(entry); onEdited() }, tint = c.t2, glyph = 22.dp)
                    }
                }
            }
            if (entries.isEmpty()) {
                Text(ht("还没有条目"), Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp), color = c.t3, style = HomeType.note)
            }
        }
        HomeButton(
            "添加一项", { entries.add(HxEditEntry("")); onEdited() },
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            kind = HomeButtonKind.Soft, icon = ToolsIcons.Plus, tinted = true,
        )
    }
}

private fun SnapshotStateList<HxEditEntry>.values(): List<String> = map { it.text.trim() }.filter { it.isNotEmpty() }

private fun hxEntries(values: Collection<String>): SnapshotStateList<HxEditEntry> =
    mutableStateListOf<HxEditEntry>().apply { values.sorted().forEach { add(HxEditEntry(it)) } }

private val HxIfaceRegex = Regex("^[A-Za-z0-9_.:@+-]{1,32}$")
private val HxMacRegex = Regex("(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")

/** Top-of-page note in the accent tint. */
@Composable
private fun HxNote(text: String) {
    SettingsSection { ToolsInfoCard(text) }
}

/* ------------------------------------------------------------------ */
/*  绕过规则: CIDR + interfaces                                          */
/* ------------------------------------------------------------------ */

@Composable
internal fun BypassRulesScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    val haptics = rememberHetuHaptics()
    val cidrOriginal = remember { prefs.getStringSet("proxyBypassCidrs", emptySet()).orEmpty().sorted() }
    val ifaceOriginal = remember { prefs.getStringSet("proxyBypassInterfaces", emptySet()).orEmpty().sorted() }
    val cidrs = remember { hxEntries(cidrOriginal) }
    val ifaces = remember { hxEntries(ifaceOriginal) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = cidrs.values().sorted() != cidrOriginal || ifaces.values().sorted() != ifaceOriginal

    fun save() {
        val cidrValues = cidrs.values()
        val ifaceValues = ifaces.values()
        val badCidr = cidrValues.firstOrNull { !hxLooksLikeCidr(it) }
        val badIface = ifaceValues.firstOrNull { !HxIfaceRegex.matches(it) || it == "lo" }
        error = when {
            badCidr != null -> "CIDR 格式不正确：$badCidr"
            badIface != null -> "接口名无效：$badIface（不能填写 lo）"
            else -> null
        }
        if (error != null) {
            haptics.perform(HetuHaptic.Reject)
            return
        }
        prefs.edit()
            .putStringSet("proxyBypassCidrs", cidrValues.toSet())
            .putStringSet("proxyBypassInterfaces", ifaceValues.toSet())
            .apply()
        ProxyRuntimeSettings.markDirty(prefs, "proxyBypassCidrs")
        ProxyRuntimeSettings.markDirty(prefs, "proxyBypassInterfaces")
        vm.bumpSettings()
        haptics.perform(HetuHaptic.Confirm)
        vm.toast("已保存，重启代理后生效")
        onBack()
    }
    BackHandler(enabled = dirty) { confirmLeave = true }

    HxPage(
        title = ht("绕过规则"),
        largeTitle = false,
        subtitle = ht("这些地址与接口在 Root 层直接放行，不进入 Mihomo"),
        onBack = { if (dirty) confirmLeave = true else onBack() },
        actions = { HxBarAction(Icons.Rounded.Check, "保存", onClick = ::save, enabled = dirty) },
    ) {
        item(key = "cidr") {
            HxSection {
                HxEditableList("CIDR", "CIDR", "例如 10.0.0.0/8 或 fd00::/8", cidrs, onEdited = { error = null })
            }
        }
        item(key = "iface") {
            HxSection {
                HxEditableList("禁用接口", "接口", "例如 dummy0、tun+", ifaces, onEdited = { error = null })
            }
        }
        item(key = "error") {
            AnimatedVisibility(error != null) {
                HxBanner(error.orEmpty(), tone = HxTone.Bad, modifier = Modifier.padding(horizontal = HomeDims.gutter))
            }
        }
    }

    if (confirmLeave) {
        HxConfirmDialog(
            title = "放弃修改？",
            message = "绕过规则的修改还没有保存。",
            confirmLabel = "放弃",
            danger = true,
            onConfirm = { confirmLeave = false; onBack() },
            onDismiss = { confirmLeave = false },
        )
    }
}

/* ------------------------------------------------------------------ */
/*  共享网络                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun SharedNetworkScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val haptics = rememberHetuHaptics()
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxySharedNetwork", false)) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf(SharedNetworkSnapshot()) }
    var ifaceBypass by remember { mutableStateOf(prefs.getStringSet("proxyBypassInterfaces", emptySet()).orEmpty().toSortedSet()) }
    val macOriginal = remember { prefs.getStringSet("proxySharedBypassMacs", emptySet()).orEmpty().map { it.lowercase() }.sorted() }
    var macSaved by remember { mutableStateOf(macOriginal) }
    val macs = remember { hxEntries(macOriginal) }
    var macError by remember { mutableStateOf<String?>(null) }
    val macDirty = macs.values().map { it.lowercase() }.sorted() != macSaved

    LaunchedEffect(refresh) {
        loading = true
        snapshot = try {
            ProxySharedNetworkInspector.inspect(context)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            SharedNetworkSnapshot(error = error.message ?: "读取失败")
        }
        loading = false
    }

    fun changed(key: String) {
        ProxyRuntimeSettings.markDirty(prefs, key)
        vm.bumpSettings()
    }

    fun saveIfaces(next: Set<String>) {
        ifaceBypass = next.toSortedSet()
        prefs.edit().putStringSet("proxyBypassInterfaces", ifaceBypass).apply()
        changed("proxyBypassInterfaces")
    }

    fun saveMacs(values: List<String>): Boolean {
        val set = values.map { it.lowercase() }.toSortedSet()
        val bad = set.firstOrNull { !HxMacRegex.matches(it) || it == "00:00:00:00:00:00" || it == "ff:ff:ff:ff:ff:ff" }
        macError = when {
            set.size > 64 -> "最多 64 个 MAC"
            bad != null -> "MAC 格式无效：$bad"
            else -> null
        }
        if (macError != null) {
            haptics.perform(HetuHaptic.Reject)
            return false
        }
        prefs.edit().putStringSet("proxySharedBypassMacs", set).apply()
        changed("proxySharedBypassMacs")
        macSaved = set.toList()
        return true
    }

    HxPage(
        title = ht("共享网络"),
        largeTitle = false,
        subtitle = ht("热点、USB 与局域网转发流量"),
        onBack = onBack,
        refreshing = loading,
        onRefresh = { refresh++ },
        actions = {
            HxBarAction(Icons.Rounded.Refresh, "刷新", onClick = { refresh++ }, busy = loading)
            HxBarAction(Icons.Rounded.Check, "保存 MAC", onClick = {
                if (saveMacs(macs.values())) {
                    haptics.perform(HetuHaptic.Confirm)
                    vm.toast("MAC 直连规则已保存，重启代理后生效")
                }
            }, enabled = macDirty)
        },
    ) {
        item(key = "note") { HxNote("开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root PREROUTING / FORWARD 层生效，修改后重启代理。") }
        item(key = "master") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("启用共享网络"), enabled, {
                        enabled = it
                        prefs.edit().putBoolean("proxySharedNetwork", it).apply()
                        changed("proxySharedNetwork")
                    }, subtitle = ht("将进入 PREROUTING 的共享流量纳入透明代理"), icon = Icons.Rounded.WifiTethering)
                }
            }
        }
        item(key = "ifaces") {
            SettingsSection {
                SettingsGroup(title = ht("共享网络接口")) {
                    when {
                        loading && snapshot.interfaces.isEmpty() -> SettingsRow(ht("正在读取接口…"), icon = Icons.Rounded.Cable) { HxSpinner(18.dp) }
                        snapshot.interfaces.isEmpty() -> SettingsRow(snapshot.error.ifBlank { "没有读取到活动接口" }, icon = Icons.Rounded.Cable)
                        else -> snapshot.interfaces.forEachIndexed { index, item ->
                            if (index > 0) SettingsDivider()
                            val direct = item.name in ifaceBypass
                            SettingsSwitchRow(
                                item.name,
                                direct,
                                { on -> saveIfaces(if (on) ifaceBypass + item.name else ifaceBypass - item.name) },
                                subtitle = (if (direct) "直连 · " else "接管 · ") + "状态 " + item.state,
                                icon = Icons.Rounded.Router,
                            )
                        }
                    }
                }
            }
        }
        if (snapshot.clients.isNotEmpty()) {
            item(key = "clients") {
                SettingsSection {
                    SettingsGroup(title = ht("下游设备")) {
                        snapshot.clients.forEachIndexed { index, client ->
                            if (index > 0) SettingsDivider()
                            val direct = client.mac.lowercase() in macSaved
                            SettingsSwitchRow(
                                client.ip,
                                direct,
                                { on ->
                                    val next = if (on) macSaved + client.mac.lowercase() else macSaved - client.mac.lowercase()
                                    if (saveMacs(next)) {
                                        macs.clear()
                                        next.sorted().forEach { macs.add(HxEditEntry(it)) }
                                    }
                                },
                                subtitle = client.mac + " · " + client.iface + " · " + client.state,
                                icon = Icons.Rounded.Devices,
                            )
                        }
                    }
                }
            }
        }
        item(key = "macs") {
            SettingsSection {
                HxEditableList("MAC 列表", "MAC 地址", "aa:bb:cc:dd:ee:ff", macs, onEdited = { macError = null })
                AnimatedVisibility(macError != null) {
                    HxBanner(macError.orEmpty(), tone = HxTone.Bad, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  CNIP                                                                */
/* ------------------------------------------------------------------ */

@Composable
internal fun CnIpScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxyCnIpDirect", false)) }
    HxPage(title = ht("CNIP 设置"), subtitle = ht("中国大陆 IPv4 / IPv6 自动直连"), onBack = onBack, largeTitle = false) {
        item(key = "note") { HxNote("CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。") }
        item(key = "main") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("绕过 CNIP"), enabled, {
                        enabled = it
                        prefs.edit().putBoolean("proxyCnIpDirect", it).apply()
                        ProxyRuntimeSettings.markDirty(prefs, "proxyCnIpDirect")
                        vm.bumpSettings()
                    }, subtitle = ht("命中国内 IPv4 / IPv6 网段时直接连接"), icon = Icons.Rounded.Public)
                }
            }
        }
        item(key = "source") {
            SettingsSection {
                SettingsGroup {
                    SettingsRow(ht("数据源"), subtitle = ht("内置离线快照 + Mihomo provider 运行时更新"), icon = Icons.Rounded.Inventory2)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  运行核心                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun RuntimeCoreScreen(vm: HetuViewModel, onBack: () -> Unit, onOpenCores: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    HxPage(title = ht("运行核心"), subtitle = ht("选择负责 Root 代理运行的核心"), onBack = onBack, largeTitle = false) {
        item(key = "note") { HxNote("修改运行核心后，下次启动或重启代理生效。下载、更新与维护在「内核管理」。") }
        item(key = "cores") {
            HxSection {
                HxGroup {
                    listOf(
                        Triple(ProxyRuntimeProfile.Core.MIHOMO, "标准 Mihomo 运行核心", Icons.Rounded.Memory),
                        Triple(ProxyRuntimeProfile.Core.MIHOMO_SMART, "Smart 运行配置", Icons.Rounded.AutoAwesome),
                    ).forEachIndexed { index, (core, description, icon) ->
                        if (index > 0) HxDivider()
                        val installed = core == ProxyRuntimeProfile.Core.MIHOMO || ProxyCoreStore(context).installed(core)
                        HxRow(
                            core.label,
                            subtitle = if (installed) description else "$description · 尚未安装",
                            icon = icon,
                            enabled = installed,
                            onClick = {
                                prefs.edit().putString("proxyBaseCore", core.id).apply()
                                ProxyRuntimeSettings.markDirty(prefs, "proxyBaseCore")
                                vm.bumpSettings()
                                revision++
                            },
                        ) { HxSelectMark(profile.core == core) }
                    }
                }
            }
        }
        item(key = "manage") {
            HxSection {
                HxGroup {
                    HxNavRow(ht("内核管理"), subtitle = ht("下载、更新与维护核心文件"), icon = Icons.Rounded.SystemUpdateAlt, onClick = onOpenCores)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  文件管理                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun FileManagerScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val root = RuntimeFilesRepository.ROOT
    var path by rememberSaveable { mutableStateOf(root) }
    fun goUp() {
        val parent = File(path).parentFile?.path.orEmpty()
        path = parent.takeIf { it == root || it.startsWith("$root/") } ?: root
    }
    BackHandler(enabled = path != root) { goUp() }
    // Folders slide in from the right when going deeper and from the left when going up.
    AnimatedContent(
        targetState = path,
        transitionSpec = {
            val deeper = targetState.length > initialState.length
            (slideInHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> if (deeper) w / 3 else -w / 3 } + fadeIn(tween(HxMotion.Medium)))
                .togetherWith(slideOutHorizontally(tween(HxMotion.Long, easing = HxMotion.Emphasized)) { w -> if (deeper) -w / 6 else w / 6 } + fadeOut(tween(HxMotion.Short)))
        },
        label = "folder",
    ) { shown ->
        FileFolderPage(vm, shown, onOpen = { path = it }, onBack = { if (shown == root) onBack() else goUp() })
    }
}

@Composable
private fun FileFolderPage(vm: HetuViewModel, path: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    val repo = remember { RuntimeFilesRepository(context) }
    val root = RuntimeFilesRepository.ROOT
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var entries by remember { mutableStateOf<List<RuntimeFileEntry>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<RuntimeFileEntry?>(null) }
    var createFolder by remember { mutableStateOf<Boolean?>(null) }
    var renameFor by remember { mutableStateOf<RuntimeFileEntry?>(null) }
    var deleteFor by remember { mutableStateOf<RuntimeFileEntry?>(null) }
    var exportFor by remember { mutableStateOf<RuntimeFileEntry?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var downloadDialog by remember { mutableStateOf(false) }

    LaunchedEffect(refresh, path) {
        loading = true
        try {
            if (path == root) ProxyComposeController(context).ensureRuntimeFiles()
            entries = repo.list(path).sortedWith(compareBy<RuntimeFileEntry> { !it.directory }.thenBy { it.name.lowercase(Locale.ROOT) })
            error = ""
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取河图运行目录"
        } finally {
            loading = false
        }
    }

    fun run(done: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                vm.toast(done)
                refresh++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                vm.toast(failure.message ?: "操作失败")
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val display = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            val name = display?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty().ifBlank { "imported" }
            run("已导入") { repo.importFile(path, name, uri) }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val entry = exportFor
        exportFor = null
        if (uri != null && entry != null) run("已导出 ${entry.name}") { repo.export(entry, uri) }
    }

    fun openFile(entry: RuntimeFileEntry) {
        scope.launch {
            try {
                // Binary and archive payloads stay in the file manager instead of opening a
                // text editor that could not show them.
                val content = repo.read(entry.path)
                if (!content.editable) {
                    vm.toast("当前文件类型暂不支持编辑")
                    return@launch
                }
                context.startActivity(Intent(context, RuntimeFileEditorActivity::class.java).putExtra(EXTRA_RUNTIME_PATH, entry.path))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                vm.toast(failure.message ?: "无法打开文件")
            }
        }
    }

    val relative = path.removePrefix(root).trim('/')
    val visibleEntries = remember(entries, query) {
        if (query.isBlank()) entries else entries.filter { it.name.contains(query, true) }
    }
    val breadcrumb = remember(path) {
        buildList<Pair<String, String>> {
            add("hetu" to root)
            if (relative.isNotBlank()) {
                var current = root
                relative.split('/').filter { it.isNotBlank() }.forEach { segment ->
                    current = "$current/$segment"
                    add(segment to current)
                }
            }
        }
    }
    val showSearch = searching || path != root
    val marked = (menuFor ?: renameFor ?: deleteFor)?.path

    HxPage(
        title = ht("文件管理"),
        largeTitle = true,
        onBack = onBack,
        refreshing = loading && entries.isNotEmpty(),
        onRefresh = { refresh++ },
        actions = {
            HxBarAction(if (searching) PanelIcons.SearchX else ToolsIcons.Search, "搜索", onClick = {
                searching = !searching
                if (!searching && path == root) query = ""
            })
            HxBarAction(ToolsIcons.Ellipsis, "更多", onClick = { moreMenu = true }, anchorMenu = true)
        },
    ) {
        item(key = "breadcrumbs") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = HomeDims.gutter + 4.dp).padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumb.forEachIndexed { index, (label, target) ->
                    val active = index == breadcrumb.lastIndex
                    val source = remember { MutableInteractionSource() }
                    Box(
                        Modifier.heightIn(min = 34.dp).clip(HomeDims.pillShape)
                            .background(if (active) c.accentSoft else c.sunken)
                            .homeRowPressTint(source)
                            .clickable(interactionSource = source, indication = null, enabled = !active, role = Role.Button) { onOpen(target) }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (active) c.accent else c.t2, maxLines = 1,
                            style = HomeType.label.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium))
                    }
                    if (index < breadcrumb.lastIndex) {
                        Icon(HomeIcons.ChevronRight, null, Modifier.padding(horizontal = 3.dp).size(18.dp), tint = c.t3)
                    }
                }
            }
        }
        if (showSearch) {
            item(key = "search") {
                HxSearchField(
                    query, { query = it }, "搜索当前目录",
                    Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = 12.dp),
                    autoFocus = searching,
                )
            }
        }
        if (loading && entries.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(5) }
        } else if (error.isNotBlank()) {
            item(key = "error") { HxEmpty(HxIcons.FolderOpen, "无法读取目录", error) { HxButton("重试", onClick = { refresh++ }, icon = HomeIcons.RefreshCw) } }
        } else if (entries.isEmpty() && path == root) {
            item(key = "empty") { HxEmpty(HxIcons.FolderOpen, "此目录是空的", ht("点右上角菜单新建、导入或下载文件")) }
        } else {
            item(key = "hint") {
                Text(
                    "${visibleEntries.count { it.directory }} 个文件夹 · ${visibleEntries.count { !it.directory }} 个文件 · 长按更多操作",
                    Modifier.padding(start = HomeDims.gutter + 8.dp, end = HomeDims.gutter, bottom = 10.dp),
                    color = c.t2, style = HomeType.note.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                )
            }
            val folders = visibleEntries.filter { it.directory }
            val files = visibleEntries.filterNot { it.directory }
            val open: (RuntimeFileEntry) -> Unit = { entry -> if (entry.directory) onOpen(entry.path) else openFile(entry) }
            if (path != root) item(key = "subdirectory") {
                HxSection {
                    HxGroup {
                        HxRow("..", icon = HxIcons.FolderOpen, onClick = {
                            val parent = File(path).parentFile?.path.orEmpty()
                            onOpen(parent.takeIf { it == root || it.startsWith("$root/") } ?: root)
                        }, trailing = { Icon(HomeIcons.ChevronLeft, null, Modifier.size(20.dp), tint = c.t3) })
                        (folders + files).forEach { entry ->
                            FileEntryRow(entry, marked == entry.path, onClick = { open(entry) }, onLongClick = { menuFor = entry })
                        }
                    }
                }
            } else {
                listOf("folders" to folders, "files" to files).filter { it.second.isNotEmpty() }.forEach { (key, grouped) ->
                    item(key = key) {
                        HxSection {
                            HxGroup {
                                grouped.forEach { entry ->
                                    FileEntryRow(entry, marked == entry.path, onClick = { open(entry) }, onLongClick = { menuFor = entry })
                                }
                            }
                        }
                    }
                }
            }
            if (visibleEntries.isEmpty() && query.isNotBlank()) item(key = "no-matches") {
                HxEmpty(PanelIcons.SearchX, "没有匹配的文件", ht("试试其他文件名称"))
            }
        }
    }

    if (moreMenu) HxActionMenu(
        title = ht("文件管理"), onDismiss = { moreMenu = false },
        dimBehind = false,
        actions = listOf(
            HxMenuAction(ht("导入"), HxIcons.FileUp) { moreMenu = false; launchDocumentPicker(vm::toast) { importer.launch(arrayOf("*/*")) } },
            HxMenuAction(ht("下载"), ToolsIcons.Download) { moreMenu = false; downloadDialog = true },
            HxMenuAction(ht("新建文件"), HxIcons.FilePlus) { moreMenu = false; createFolder = false },
            HxMenuAction(ht("新建文件夹"), HxIcons.FolderPlus) { moreMenu = false; createFolder = true },
        ),
    )

    menuFor?.let { entry ->
        HxActionMenu(
            title = entry.name,
            entryMenu = if (entry.directory) HxFileEntryMenu.Folder else HxFileEntryMenu.File,
            headerIcon = fileGlyph(entry),
            referenceFileMenu = true, dimBehind = true, dimAmount = .3f, emphasizeAnchor = true,
            actions = buildList {
                if (entry.directory) add(HxMenuAction(ht("打开"), HxIcons.FolderOpen) { menuFor = null; onOpen(entry.path) })
                else add(HxMenuAction(ht("编辑"), ToolsIcons.Pencil) { menuFor = null; openFile(entry) })
                add(HxMenuAction(ht("重命名"), HxIcons.TextCursorInput) { menuFor = null; renameFor = entry })
                if (!entry.directory) add(HxMenuAction(ht("导出"), ToolsIcons.Share) { menuFor = null; exportFor = entry; launchDocumentPicker(vm::toast) { exporter.launch(entry.name) } })
                add(HxMenuAction(ht("删除"), ToolsIcons.Trash2, danger = true) { menuFor = null; deleteFor = entry })
            },
            onDismiss = { menuFor = null },
        )
    }
    createFolder?.let { folder ->
        HxFormDialog(
            title = if (folder) "新建文件夹" else "新建文件",
            fields = listOf(HxField("名称", placeholder = if (folder) "例如 backup" else "例如 config.yaml")),
            confirmLabel = "创建",
            validate = { v -> runCatching { RuntimeFilesRepository.child(path, v[0]) }.exceptionOrNull()?.message },
            onConfirm = { v ->
                createFolder = null
                run(if (folder) "已创建文件夹" else "已创建文件") { repo.create(path, v[0], folder) }
            },
            onDismiss = { createFolder = null },
        )
    }
    if (downloadDialog) {
        HxFormDialog(
            title = "下载到当前目录",
            message = "支持 HTTPS。文件名可留空，河图会从下载地址自动推断。",
            fields = listOf(
                HxField("下载地址", placeholder = "https://example.com/file.yaml"),
                HxField("文件名（可选）", placeholder = "config.yaml"),
            ),
            confirmLabel = "下载",
            errorField = { if (it == "请填写 HTTPS 下载地址") 0 else 1 },
            validate = { v ->
                val address = v[0]
                val downloadUri = runCatching { java.net.URI(address) }.getOrNull()
                val localHttp = downloadUri?.scheme == "http" && downloadUri.host in setOf("127.0.0.1", "localhost", "[::1]")
                if (!hxConfigHttpUrl(address) || (downloadUri?.scheme != "https" && !localHttp)) "请填写 HTTPS 下载地址" else {
                    val inferred = runCatching { Uri.parse(address).lastPathSegment.orEmpty().substringAfterLast('/') }.getOrDefault("")
                    val name = v[1].ifBlank { inferred }.ifBlank { "download" }
                    runCatching { RuntimeFilesRepository.child(path, name) }.exceptionOrNull()?.message
                }
            },
            onConfirm = { v ->
                downloadDialog = false
                val inferred = runCatching { Uri.parse(v[0]).lastPathSegment.orEmpty().substringAfterLast('/') }.getOrDefault("")
                val name = v[1].ifBlank { inferred }.ifBlank { "download" }
                run("已下载 $name") { repo.download(path, name, v[0], "Hetu-Android/${BuildConfig.VERSION_NAME}") }
            },
            onDismiss = { downloadDialog = false },
        )
    }
    renameFor?.let { entry ->
        HxFormDialog(
            title = "重命名",
            message = "原文件：${entry.name}", messageBelowFields = true,
            fields = listOf(HxField("新名称", entry.name)),
            confirmLabel = "重命名",
            validate = { v ->
                if (v[0] == entry.name) "名称没有变化"
                else runCatching { RuntimeFilesRepository.child(path, v[0]) }.exceptionOrNull()?.message
            },
            onConfirm = { v ->
                renameFor = null
                run("已重命名") { repo.rename(entry, v[0]) }
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { entry ->
        HxConfirmDialog(
            title = "删除${if (entry.directory) "文件夹" else "文件"}？",
            message = "「${entry.name}」将被永久删除" + if (entry.directory) "，包括其中的所有内容。" else "。",
            confirmLabel = "删除",
            danger = true,
            onConfirm = {
                deleteFor = null
                run("已删除") { repo.delete(entry) }
            },
            onDismiss = { deleteFor = null },
        )
    }
}

/** What a file is, as far as its name tells: scripts, configs and logs each get their own glyph. */
private fun fileGlyph(entry: RuntimeFileEntry) = when {
    entry.directory -> ToolsIcons.Folder
    else -> when (entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "sh", "bash" -> HxIcons.FileTerminal
        "yaml", "yml", "json", "toml", "conf", "ini" -> ToolsIcons.FileCog
        "log", "txt", "md" -> ToolsIcons.FileText
        else -> ToolsIcons.File
    }
}

/** One folder or file. While its menu or dialog is open the row stays marked. */
@Composable
private fun FileEntryRow(entry: RuntimeFileEntry, marked: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    HxRow(
        entry.name,
        subtitle = if (entry.directory) null else listOf(HxFormat.bytes(entry.size), if (entry.modified > 0) HxFormat.ago(entry.modified * 1000L) else "")
            .filter { it.isNotBlank() }.joinToString(" · "),
        icon = fileGlyph(entry),
        minimumHeight = 68.dp,
        onClick = onClick, onLongClick = onLongClick, selected = marked,
        trailing = if (entry.directory) ({ HxChevron() }) else null,
    )
}

/* ------------------------------------------------------------------ */
/*  诊断与维护                                                           */
/* ------------------------------------------------------------------ */

@Composable
internal fun DiagnosticsScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var busy by remember { mutableStateOf<String?>(null) }
    var preflight by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var sheet by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirmRecover by remember { mutableStateOf(false) }

    fun task(key: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = key
        scope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                vm.toast(failure.message ?: "操作失败")
            } finally {
                busy = null
            }
        }
    }

    HxPage(title = ht("诊断与维护"), subtitle = ht("预检、运行副本、诊断信息与紧急恢复"), onBack = onBack, largeTitle = false) {
        item(key = "preflight") {
            SettingsSection {
                SettingsGroup(title = ht("运行预检")) {
                    SettingsRow(
                        ht("开始预检"),
                        subtitle = ht("验证 Root / TPROXY / UID / IPv6 / 绕过规则是否可用"),
                        icon = ToolsFeatureIcons.ShieldPlus,
                        onClick = {
                            task("preflight") {
                                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val root = RootProxyManager(context)
                                    root.preflight(root.prepare(ProxyRuntimeProfile.load(vm.prefs)))
                                }
                                val ok = json.optBoolean("ok", false)
                                preflight = ok to if (ok) "预检通过：当前设备支持这组 Root 代理设置" else json.optString("message", "预检未通过")
                            }
                        },
                    ) { if (busy == "preflight") HxSpinner(18.dp) else HxChevron() }
                    // The verdict unfolds under the row that asked for it.
                    val result = preflight
                    HomeReveal(result != null) {
                        if (result != null) {
                            HxBanner(
                                result.second,
                                tone = if (result.first) HxTone.Good else HxTone.Warn,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "inspect") {
            SettingsSection {
                SettingsGroup(title = ht("查看")) {
                    DiagnosticRow("启动配置", "最终生成的运行副本，不修改源配置", ToolsIcons.FileText, busy, "startup") {
                        task("startup") { sheet = "启动配置" to vm.controller.startupConfig() }
                    }
                    SettingsDivider()
                    DiagnosticRow("消息与网络诊断", "微信连接、保活、分流与最近运行事件", ToolsFeatureIcons.Router, busy, "diag") {
                        task("diag") { sheet = "消息与网络诊断" to vm.controller.diagnostics() }
                    }
                    SettingsDivider()
                    DiagnosticRow("网络事件记录", "查看切网与错误编号；满额暂停，历史保留", HomeIcons.Clock, busy, "network-events") {
                        task("network-events") { sheet = "网络事件记录" to vm.controller.networkEvents() }
                    }
                    SettingsDivider()
                    DiagnosticRow("修复运行记录", "核对原会话基线、规则、DNS 与守护进程；保留现有连接", ToolsFeatureIcons.ShieldCheck, busy, "session-record") {
                        task("session-record") {
                            val result = try { vm.controller.repairSessionRecord() }
                                catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { error.message ?: "运行记录未修复，请查看网络诊断" }
                            sheet = "修复运行记录" to result
                        }
                    }
                }
            }
        }
        item(key = "recover") {
            SettingsSection {
                SettingsGroup(title = ht("紧急")) {
                    SettingsRow(
                        ht("恢复网络"),
                        subtitle = ht("停止代理并回滚河图添加的 iptables / 路由规则"),
                        icon = ToolsFeatureIcons.Siren,
                        iconTint = c.bad,
                        onClick = { confirmRecover = true },
                    ) { if (busy == "recover") HxSpinner(18.dp, c.bad) else HxChevron() }
                }
            }
        }
        item(key = "connectivity") {
            SettingsSection {
                SettingsGroup(title = ht("连通性")) {
                    SettingsRow(
                        ht("多平台网络测试"),
                        subtitle = ht("并发测试 Google、YouTube、GitHub、Telegram、ChatGPT 等平台"),
                        icon = PanelIcons.Gauge,
                        onClick = { nav.push(HxRoute.NetTest) },
                    ) { HxChevron() }
                }
            }
        }
    }

    sheet?.let { (title, text) -> HxTextSheet(title, text, onDismiss = { sheet = null }, showCopyLabel = true) }
    if (confirmRecover) {
        HxConfirmDialog(
            title = "恢复网络？",
            message = "将停止代理，并回滚河图添加的 iptables / 路由规则。用于网络异常时的紧急恢复。",
            confirmLabel = "恢复",
            danger = true,
            onConfirm = {
                confirmRecover = false
                task("recover") {
                    vm.controller.stop()
                    vm.toast("已停止代理并恢复网络")
                    vm.refreshNow()
                }
            },
            onDismiss = { confirmRecover = false },
        )
    }
}

/** One read-out of 查看: a spinner takes the chevron's place while its text is being collected. */
@Composable
private fun DiagnosticRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, busy: String?, key: String, onClick: () -> Unit) {
    SettingsRow(ht(title), subtitle = ht(subtitle), icon = icon, onClick = onClick) {
        if (busy == key) HxSpinner(18.dp) else HxChevron()
    }
}

/* ------------------------------------------------------------------ */
/*  状态通知                                                             */
/* ------------------------------------------------------------------ */

private val NotificationVariables = listOf(
    "{status}", "{uptime}", "{upload}", "{download}", "{upload_total}", "{download_total}",
    "{cpu}", "{memory}", "{connections}", "{config}", "{core}", "{mode}",
)

@Composable
internal fun NotificationSettingsScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val c = LocalHomeColors.current
    val haptics = rememberHetuHaptics()
    val initialEnabled = remember { prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false) }
    var enabled by rememberSaveable { mutableStateOf(initialEnabled) }
    val initialTitle = remember { prefs.getString(ProxyStatusNotificationService.PREF_TITLE_TEMPLATE, ProxyStatusNotificationService.DEFAULT_TITLE_TEMPLATE).orEmpty() }
    val initialTemplate = remember { prefs.getString(ProxyStatusNotificationService.PREF_TEMPLATE, ProxyStatusNotificationService.DEFAULT_TEMPLATE).orEmpty() }
    val initialRefresh = remember { prefs.getInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, 3).coerceIn(2, 60) }
    val initialTarget = remember { prefs.getString(ProxyStatusNotificationService.PREF_CLICK_TARGET, "Home").orEmpty() }
    val actionKeys = listOf(ProxyStatusNotificationService.PREF_ACTION_1, ProxyStatusNotificationService.PREF_ACTION_2, ProxyStatusNotificationService.PREF_ACTION_3)
    val labelKeys = listOf(ProxyStatusNotificationService.PREF_ACTION_LABEL_1, ProxyStatusNotificationService.PREF_ACTION_LABEL_2, ProxyStatusNotificationService.PREF_ACTION_LABEL_3)
    val defaults = listOf("reload", "restart", "stop")
    val initialActions = remember { actionKeys.mapIndexed { i, k -> prefs.getString(k, defaults[i]).orEmpty() } }
    val initialLabels = remember { labelKeys.map { prefs.getString(it, "").orEmpty() } }
    var titleTemplate by rememberSaveable { mutableStateOf(initialTitle) }
    var template by rememberSaveable { mutableStateOf(initialTemplate) }
    var refresh by rememberSaveable { mutableIntStateOf(initialRefresh) }
    var target by rememberSaveable { mutableStateOf(initialTarget) }
    val listSaver = remember { androidx.compose.runtime.saveable.listSaver<SnapshotStateList<String>, String>(
        save = { it.toList() }, restore = { values -> mutableStateListOf<String>().apply { addAll(values) } }) }
    val actions = rememberSaveable(saver = listSaver) { mutableStateListOf<String>().apply { addAll(initialActions) } }
    val labels = rememberSaveable(saver = listSaver) { mutableStateListOf<String>().apply { addAll(initialLabels) } }
    var picker by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val dirty = enabled != initialEnabled || titleTemplate != initialTitle || template != initialTemplate || refresh != initialRefresh || target != initialTarget ||
        actions.toList() != initialActions || labels.toList() != initialLabels

    val targetOptions = listOf(
        "Home" to "首页", "Panel" to "面板", "Strategy" to "策略",
        "PanelSheet" to "面板浮窗", "StrategySheet" to "策略浮窗", "Tools" to "工具", "Settings" to "设置",
        "Configs" to "配置页", "Providers" to "订阅页",
    )
    val actionOptions = listOf("reload" to "重载", "restart" to "重启", "stop" to "停止", "hide" to "隐藏通知", "none" to "无")
    val refreshOptions = listOf(2, 3, 5, 10, 30, 60)

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            enabled = true
        } else {
            vm.toast("没有通知权限，无法显示状态通知")
        }
    }

    fun save() {
        val edit = prefs.edit()
            .putString(ProxyStatusNotificationService.PREF_TITLE_TEMPLATE, titleTemplate.ifBlank { ProxyStatusNotificationService.DEFAULT_TITLE_TEMPLATE })
            .putString(ProxyStatusNotificationService.PREF_TEMPLATE, template.ifBlank { ProxyStatusNotificationService.DEFAULT_TEMPLATE })
            .putInt(ProxyStatusNotificationService.PREF_REFRESH_SECONDS, refresh)
            .putString(ProxyStatusNotificationService.PREF_CLICK_TARGET, target)
        actionKeys.forEachIndexed { i, k -> edit.putString(k, actions[i]) }
        labelKeys.forEachIndexed { i, k -> edit.putString(k, labels[i].take(12)) }
        edit.apply()
        ProxyStatusNotificationService.setEnabled(context, enabled)
        ProxyStatusNotificationService.refresh(context)
        haptics.perform(HetuHaptic.Confirm)
        vm.toast("通知设置已保存")
        onBack()
    }

    HxPage(
        title = ht("通知详细设置"),
        largeTitle = false,
        onBack = onBack,
        actions = { HxBarAction(HomeIcons.Check, "保存", onClick = ::save, enabled = dirty) },
    ) {
        item(key = "master") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("显示状态通知"), enabled, { on ->
                        if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            enabled = on
                        }
                    }, subtitle = ht("常驻显示运行状态、网速与快捷控制"), icon = HxIcons.BellRing)
                    SettingsDivider()
                    SettingsNavRow(ht("刷新频率"), icon = HomeIcons.RefreshCw, value = "$refresh 秒", dropdown = true) { picker = "refresh" to -1 }
                    SettingsDivider()
                    SettingsNavRow(ht("点击通知打开"), icon = HxIcons.AppWindow, value = ht(targetOptions.firstOrNull { it.first == target }?.second ?: target), dropdown = true) {
                        picker = "target" to -1
                    }
                }
            }
        }
        item(key = "title-template") {
            SettingsSection {
                SettingsGroup(title = ht("通知标题")) {
                    SettingsInput(
                        value = titleTemplate, onValueChange = { titleTemplate = it.take(96) }, label = "标题模板",
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                    )
                }
            }
        }
        item(key = "template") {
            SettingsSection {
                SettingsGroup(title = ht("通知内容")) {
                    Column(Modifier.padding(start = 18.dp, end = 18.dp)) {
                        SettingsInput(value = template, onValueChange = { template = it.take(320) }, label = "模板", singleLine = false)
                        Text(ht("可用变量：点一下，加到模板末尾"), Modifier.padding(top = 12.dp, bottom = 8.dp), color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.Medium))
                    }
                    // Every variable the service understands, one tap away from the template. One
                    // line that slides under the card's edges, so the buttons below stay close.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        NotificationVariables.forEach { variable ->
                            HomePill(
                                variable, tone = HomeTone.Neutral, height = 28.dp,
                                style = HomeType.badge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
                                onClick = { template = (if (template.isBlank() || template.endsWith(" ") || template.endsWith("\n")) template + variable else "$template $variable").take(320) },
                            )
                        }
                    }
                }
            }
        }
        (0 until 3).forEach { index ->
            item(key = "action$index") {
                SettingsSection {
                    SettingsGroup(title = "快捷按钮 ${index + 1}") {
                        SettingsNavRow(ht("动作"), icon = HxIcons.SquareCheckBig, value = ht(actionOptions.firstOrNull { it.first == actions[index] }?.second ?: actions[index]), dropdown = true) {
                            picker = "action" to index
                        }
                        SettingsInput(
                            value = labels[index], onValueChange = { labels[index] = it.take(12) },
                            label = "按钮文字（可空，最多 12 字）", placeholder = actionOptions.firstOrNull { it.first == actions[index] }?.second.orEmpty(),
                            modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 14.dp),
                        )
                    }
                }
            }
        }
    }

    picker?.let { (kind, index) ->
        when (kind) {
            "refresh" -> HxChoiceSheet(
                presentation = HxChoicePresentation.Notification,
                title = ht("刷新频率"),
                choices = refreshOptions.map { HxChoice(it.toString(), "$it 秒") },
                selected = refresh.toString(),
                onPick = { refresh = it.toInt(); picker = null },
                onDismiss = { picker = null },
            )
            "target" -> HxChoiceSheet(
                presentation = HxChoicePresentation.Notification,
                title = ht("点击通知打开"),
                choices = targetOptions.map { HxChoice(it.first, it.second) },
                maxVisibleChoices = 7,
                selected = target,
                onPick = { target = it; picker = null },
                onDismiss = { picker = null },
            )
            else -> HxChoiceSheet(
                presentation = HxChoicePresentation.Notification,
                title = "快捷按钮 ${index + 1}",
                choices = actionOptions.map { HxChoice(it.first, it.second) },
                selected = actions.getOrNull(index),
                onPick = { actions[index] = it; picker = null },
                onDismiss = { picker = null },
            )
        }
    }
}
