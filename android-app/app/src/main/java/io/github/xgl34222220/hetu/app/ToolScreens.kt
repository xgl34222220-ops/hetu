package io.github.xgl34222220.hetu

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/* ------------------------------------------------------------------ */
/*  Shared: editable list section                                       */
/* ------------------------------------------------------------------ */

/**
 * A titled card of filled inputs, one per entry, each with a delete button, followed by an
 * "添加一项" line. Entries are plain state objects so the caller decides when to save.
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
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
    HxGroup(modifier = modifier, title = title) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            entries.forEach { entry ->
                key(entry.id) {
                    Row(Modifier.clip(Hx.rowShape).background(c.surfaceMuted), verticalAlignment = Alignment.CenterVertically) {
                        HxTextField(
                            value = entry.text,
                            onValueChange = { entry.text = it; onEdited() },
                            label = { Text(fieldLabel) },
                            placeholder = { Text(placeholder, color = c.textFaint) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            haptics.perform(HetuHaptic.Tick)
                            entries.remove(entry)
                            onEdited()
                        }) {
                            Icon(Icons.Rounded.DeleteOutline, "删除", tint = c.textMuted)
                        }
                    }
                }
            }
            if (entries.isEmpty()) {
                Text("还没有条目", style = MaterialTheme.typography.bodySmall, color = c.textFaint, modifier = Modifier.padding(4.dp))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp).padding(bottom = 10.dp)
                .clip(RoundedCornerShape(14.dp)).background(c.surfaceMuted)
                .clickable {
                    haptics.perform(HetuHaptic.Tap)
                    entries.add(HxEditEntry(""))
                    onEdited()
                }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("添加一项", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = c.accent)
        }
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
    SettingsSection {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hx.colors.accentSoft)
            .padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, null, tint = Hx.colors.accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(text, color = Hx.colors.textMuted, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
        }
    }
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

    HxPage(flatCanvas = true,
        title = "绕过规则",
        largeTitle = false,
        subtitle = "这些地址与接口在 Root 层直接放行，不进入 Mihomo",
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
            androidx.compose.animation.AnimatedVisibility(error != null) {
                HxBanner(error.orEmpty(), tone = HxTone.Bad, modifier = Modifier.padding(horizontal = Hx.gutter))
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
    val c = Hx.colors
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

    HxPage(flatCanvas = true,
        title = "共享网络",
        largeTitle = false,
        subtitle = "热点、USB 与局域网转发流量",
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
                    SettingsSwitchRow("启用共享网络", enabled, {
                        enabled = it
                        prefs.edit().putBoolean("proxySharedNetwork", it).apply()
                        changed("proxySharedNetwork")
                    }, subtitle = "将进入 PREROUTING 的共享流量纳入透明代理", icon = Icons.Rounded.WifiTethering, iconTint = c.textMuted)
                }
            }
        }
        item(key = "ifaces") {
            SettingsSection {
                SettingsGroup(title = "共享网络接口") {
                    when {
                        loading && snapshot.interfaces.isEmpty() -> SettingsRow("正在读取接口…", icon = Icons.Rounded.Cable, iconTint = c.textMuted) { HxSpinner(16.dp) }
                        snapshot.interfaces.isEmpty() -> SettingsRow(snapshot.error.ifBlank { "没有读取到活动接口" }, icon = Icons.Rounded.Cable, iconTint = c.textMuted)
                        else -> snapshot.interfaces.forEachIndexed { index, item ->
                            if (index > 0) SettingsDivider()
                            val direct = item.name in ifaceBypass
                            SettingsSwitchRow(
                                item.name,
                                direct,
                                { on -> saveIfaces(if (on) ifaceBypass + item.name else ifaceBypass - item.name) },
                                subtitle = (if (direct) "直连 · " else "接管 · ") + "状态 " + item.state,
                                icon = Icons.Rounded.Router,
                                iconTint = c.textMuted,
                            )
                        }
                    }
                }
            }
        }
        if (snapshot.clients.isNotEmpty()) {
            item(key = "clients") {
                SettingsSection {
                    SettingsGroup(title = "下游设备") {
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
                                iconTint = c.textMuted,
                            )
                        }
                    }
                }
            }
        }
        item(key = "macs") {
            SettingsSection {
                HxEditableList("MAC 列表", "MAC 地址", "aa:bb:cc:dd:ee:ff", macs, onEdited = { macError = null })
                androidx.compose.animation.AnimatedVisibility(macError != null) {
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
    val c = Hx.colors
    var enabled by remember { mutableStateOf(prefs.getBoolean("proxyCnIpDirect", false)) }
    HxPage(flatCanvas = true, title = "CNIP 设置", subtitle = "中国大陆 IPv4 / IPv6 自动直连", onBack = onBack, largeTitle = false) {
        item(key = "note") { HxNote("CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。") }
        item(key = "main") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow("绕过 CNIP", enabled, {
                        enabled = it
                        prefs.edit().putBoolean("proxyCnIpDirect", it).apply()
                        ProxyRuntimeSettings.markDirty(prefs, "proxyCnIpDirect")
                        vm.bumpSettings()
                    }, subtitle = "命中国内 IPv4 / IPv6 网段时直接连接", icon = Icons.Rounded.Public, iconTint = c.textMuted)
                }
            }
        }
        item(key = "source") {
            SettingsSection {
                SettingsGroup {
                    SettingsRow("数据源", subtitle = "内置离线快照 + Mihomo provider 运行时更新", icon = Icons.Rounded.Inventory2, iconTint = c.textMuted)
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
    val c = Hx.colors
    var revision by remember { mutableIntStateOf(0) }
    val profile = remember(revision) { ProxyRuntimeProfile.load(prefs) }
    HxPage(flatCanvas = true, title = "运行核心", subtitle = "选择负责 Root 代理运行的核心", onBack = onBack, largeTitle = false) {
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
                            iconTint = c.textMuted,
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
                    HxNavRow("内核管理", subtitle = "下载、更新与维护核心文件", icon = Icons.Rounded.SystemUpdateAlt, iconTint = c.textMuted, onClick = onOpenCores)
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
    val c = Hx.colors
    val haptics = rememberHetuHaptics()
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
                // Match the reference: unsupported binary/archive payloads stay in the file manager
                // instead of opening a fake text editor.
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

    HxPage(flatCanvas = true,
        title = "文件管理",
        largeTitle = true,
        largeTitleFontSizeSp = 36f,
        largeTitleStartPadding = 26.dp,
        largeTitleTopPadding = 6.dp,
        onBack = onBack,
        refreshing = loading && entries.isNotEmpty(),
        onRefresh = { refresh++ },
        actions = {
            HxBarAction(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "搜索", onClick = {
                searching = !searching
                if (!searching && path == root) query = ""
            })
            HxBarAction(Icons.Rounded.MoreHoriz, "更多", onClick = { moreMenu = true }, anchorMenu = true)
        },
    ) {
        item(key = "breadcrumbs") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = Hx.gutter).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                breadcrumb.forEachIndexed { index, (label, target) ->
                    val active = index == breadcrumb.lastIndex
                    Box(
                        Modifier.clip(Hx.pillShape)
                            .background(if (active) c.accentSoft else c.surfaceMuted)
                            .clickable(enabled = !active) { onOpen(target) }
                            .widthIn(min = if (index == 0) 68.dp else 0.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp, lineHeight = 20.sp),
                            color = if (active) c.accent else c.textMuted,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                    }
                    if (index < breadcrumb.lastIndex) {
                        Text("›", color = c.textFaint, modifier = Modifier.padding(horizontal = 5.dp))
                    }
                }
            }
        }
        if (showSearch) {
            item(key = "search") {
                HxSearchField(
                    query, { query = it }, "搜索当前目录",
                    Modifier.padding(horizontal = Hx.gutter).padding(bottom = 10.dp),
                    autoFocus = searching,
                )
            }
        }
        if (loading && entries.isEmpty()) {
            item(key = "loading") { HxSkeletonRows(6) }
        } else if (error.isNotBlank()) {
            item(key = "error") { HxEmpty(Icons.Rounded.FolderOpen, "无法读取目录", error) { HxButton("重试", onClick = { refresh++ }, icon = Icons.Rounded.Refresh) } }
        } else if (entries.isEmpty() && path == root) {
            item(key = "empty") { HxEmpty(Icons.Rounded.FolderOpen, "此目录是空的", "点右上角菜单新建、导入或下载文件") }
        } else {
            item(key = "hint") {
                Text(
                    "${visibleEntries.count { it.directory }} 个文件夹 · ${visibleEntries.count { !it.directory }} 个文件 · 长按更多操作",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 16.sp, lineHeight = 21.sp),
                    color = c.textMuted,
                    modifier = Modifier.padding(start = Hx.gutter + 4.dp, bottom = 8.dp),
                )
            }
            val folders = visibleEntries.filter { it.directory }
            val files = visibleEntries.filterNot { it.directory }
            if (path != root) item(key = "subdirectory") {
                HxSection {
                    HxGroup {
                        HxRow("..", icon = Icons.AutoMirrored.Rounded.ArrowBack, referenceRow = true,
                            referenceTextSizeSp = 18f, minimumHeight = 68.dp, onClick = {
                                val parent = File(path).parentFile?.path.orEmpty()
                                onOpen(parent.takeIf { it == root || it.startsWith("$root/") } ?: root)
                            })
                        (folders + files).forEach { entry ->
                            FileReferenceEntryRow(entry, (menuFor ?: renameFor ?: deleteFor)?.path == entry.path,
                                onClick = { haptics.perform(HetuHaptic.Tap); if (entry.directory) onOpen(entry.path) else openFile(entry) },
                                onLongClick = { menuFor = entry })
                        }
                    }
                }
            } else {
                listOf("folders" to folders, "files" to files).filter { it.second.isNotEmpty() }.forEach { (key, grouped) ->
                    item(key = key) {
                        HxSection {
                            HxGroup {
                                grouped.forEach { entry ->
                                    FileReferenceEntryRow(entry, (menuFor ?: renameFor ?: deleteFor)?.path == entry.path,
                                        onClick = { haptics.perform(HetuHaptic.Tap); if (entry.directory) onOpen(entry.path) else openFile(entry) },
                                        onLongClick = { menuFor = entry })
                                }
                            }
                        }
                    }
                }
            }
            if (visibleEntries.isEmpty() && query.isNotBlank()) item(key = "no-matches") {
                HxEmpty(Icons.Rounded.SearchOff, "没有匹配的文件", "试试其他文件名称")
            }

        }
    }

    if (moreMenu) HxActionMenu(
        title = "文件管理", onDismiss = { moreMenu = false },
        dimBehind = false,
        actions = listOf(
            HxMenuAction("导入", Icons.Rounded.FileUpload) { moreMenu = false; launchDocumentPicker(vm::toast) { importer.launch(arrayOf("*/*")) } },
            HxMenuAction("下载", Icons.Rounded.SystemUpdateAlt) { moreMenu = false; downloadDialog = true },
            HxMenuAction("新建文件", Icons.Rounded.NoteAdd) { moreMenu = false; createFolder = false },
            HxMenuAction("新建文件夹", Icons.Rounded.CreateNewFolder) { moreMenu = false; createFolder = true },
        ),
    )

    menuFor?.let { entry ->
        HxActionMenu(
            title = entry.name,
            entryMenu = if (entry.directory) HxFileEntryMenu.Folder else HxFileEntryMenu.File,
            headerIcon = if (entry.directory) Icons.Outlined.Folder else null,
            referenceFileMenu = true, dimBehind = true, dimAmount = if (entry.directory) .38f else .45f, emphasizeAnchor = true,
            actions = buildList {
                if (entry.directory) add(HxMenuAction("打开", Icons.Rounded.FolderOpen) { menuFor = null; onOpen(entry.path) })
                else add(HxMenuAction("编辑", Icons.Outlined.Edit) { menuFor = null; openFile(entry) })
                add(HxMenuAction("重命名", FileRenameGlyph) { menuFor = null; renameFor = entry })
                if (!entry.directory) add(HxMenuAction("导出", Icons.Rounded.IosShare) { menuFor = null; exportFor = entry; launchDocumentPicker(vm::toast) { exporter.launch(entry.name) } })
                add(HxMenuAction("删除", Icons.Rounded.DeleteOutline, danger = true) { menuFor = null; deleteFor = entry })
            },
            onDismiss = { menuFor = null },
        )
    }
    createFolder?.let { folder ->
        HxFormDialog(
            title = if (folder) "新建文件夹" else "新建文件",
            widthFraction = .84f,
            titleFontSize = if (folder) 20.sp else 26.sp,
            titleLineHeight = if (folder) 26.sp else 32.sp,
            titleTextAlign = if (folder) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start,
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
            widthFraction = .87f, titleFontSize = 22.sp, hideMessageOnError = true, highlightError = false, plainFields = true,
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

private val FileRenameGlyph by lazy {
    ImageVector.Builder("FileRename", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(2f, 6f); lineTo(13f, 6f); moveTo(7.5f, 6f); lineTo(7.5f, 19f)
            moveTo(18f, 3f); lineTo(18f, 21f); moveTo(15f, 3f); lineTo(21f, 3f)
            moveTo(15f, 21f); lineTo(21f, 21f)
        }
    }.build()
}

@Composable
private fun FileReferenceEntryRow(entry: RuntimeFileEntry, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = Hx.colors
    HxRow(entry.name, referenceRow = true, referenceTextSizeSp = 18f,
        modifier = Modifier.drawBehind {
            if (selected) {
                val insetX = if (entry.directory) 4.dp.toPx() else 0f
                val insetY = if (entry.directory) 6.dp.toPx() else 0f
                drawRoundRect(c.accentSoft, topLeft = Offset(insetX, insetY),
                    size = Size(size.width - 2 * insetX, size.height - 2 * insetY), cornerRadius = CornerRadius(12.dp.toPx()))
            }
        },
        minimumHeight = if (entry.directory) 68.dp else 74.dp,
        subtitle = if (entry.directory) null else listOf(HxFormat.bytes(entry.size), if (entry.modified > 0) HxFormat.ago(entry.modified * 1000L) else "")
            .filter { it.isNotBlank() }.joinToString(" · "),
        icon = if (entry.directory) Icons.Rounded.Folder else Icons.Rounded.Description,
        onClick = onClick, onLongClick = onLongClick,
        trailing = if (entry.directory) ({ HxChevron() }) else null)
}

/* ------------------------------------------------------------------ */
/*  诊断与维护                                                           */
/* ------------------------------------------------------------------ */

@Composable
internal fun DiagnosticsScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Hx.colors
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

    HxPage(flatCanvas = true, title = "诊断与维护", subtitle = "预检、运行副本、诊断信息与紧急恢复", onBack = onBack, largeTitle = false) {
        item(key = "preflight") {
            SettingsSection {
                SettingsGroup(title = "运行预检") {
                    SettingsRow(
                        "开始预检",
                        subtitle = "验证 Root / TPROXY / UID / IPv6 / 绕过规则是否可用",
                        icon = Icons.Rounded.HealthAndSafety,
                        iconTint = c.textMuted,
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
                    ) { if (busy == "preflight") HxSpinner(16.dp) else HxChevron() }
                    androidx.compose.animation.AnimatedVisibility(preflight != null) {
                        val result = preflight
                        if (result != null) {
                            HxBanner(
                                result.second,
                                tone = if (result.first) HxTone.Good else HxTone.Warn,
                                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        item(key = "inspect") {
            SettingsSection {
                SettingsGroup(title = "查看") {
                    SettingsRow(
                        "启动配置",
                        subtitle = "最终生成的运行副本，不修改源配置",
                        icon = Icons.Rounded.Description,
                        iconTint = c.textMuted,
                        onClick = { task("startup") { sheet = "启动配置" to vm.controller.startupConfig() } },
                    ) { if (busy == "startup") HxSpinner(16.dp) else HxChevron() }
                    SettingsDivider()
                    SettingsRow(
                        "消息与网络诊断",
                        subtitle = "微信连接、保活、分流与最近运行事件",
                        icon = Icons.Rounded.Router,
                        iconTint = c.textMuted,
                        onClick = { task("diag") { sheet = "消息与网络诊断" to vm.controller.diagnostics() } },
                    ) { if (busy == "diag") HxSpinner(16.dp) else HxChevron() }
                    SettingsDivider()
                    SettingsRow(
                        "网络事件记录",
                        subtitle = "查看切网与错误编号；满额暂停，历史保留",
                        icon = Icons.Rounded.History,
                        iconTint = c.textMuted,
                        onClick = { task("network-events") { sheet = "网络事件记录" to vm.controller.networkEvents() } },
                    ) { if (busy == "network-events") HxSpinner(16.dp) else HxChevron() }
                    SettingsDivider()
                    SettingsRow(
                        "修复运行记录",
                        subtitle = "核对原会话基线、规则、DNS 与守护进程；保留现有连接",
                        icon = Icons.Rounded.HealthAndSafety,
                        iconTint = c.textMuted,
                        onClick = {
                            task("session-record") {
                                val result = try { vm.controller.repairSessionRecord() }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (error: Exception) { error.message ?: "运行记录未修复，请查看网络诊断" }
                                sheet = "修复运行记录" to result
                            }
                        },
                    ) { if (busy == "session-record") HxSpinner(16.dp) else HxChevron() }
                }
            }
        }
        item(key = "recover") {
            SettingsSection {
                SettingsGroup(title = "紧急") {
                    SettingsRow(
                        "恢复网络",
                        subtitle = "停止代理并回滚河图添加的 iptables / 路由规则",
                        icon = Icons.Rounded.Refresh,
                        iconTint = c.bad,
                        onClick = { confirmRecover = true },
                    ) { if (busy == "recover") HxSpinner(16.dp, c.bad) }
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

/* ------------------------------------------------------------------ */
/*  状态通知                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun NotificationSettingsScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = vm.prefs
    val c = Hx.colors
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
        "Home" to "首页", "Panel" to "面板", "Strategy" to "策略", "Configs" to "配置页",
        "Providers" to "订阅页", "Tools" to "工具", "Settings" to "设置",
        "PanelSheet" to "面板浮窗", "StrategySheet" to "策略浮窗",
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

    HxPage(flatCanvas = true,
        title = "通知详细设置",
        largeTitle = false,
        compactTitleFontSizeSp = 20f,
        subtitle = null,
        onBack = onBack,
        actions = { HxBarAction(Icons.Rounded.Check, "保存", onClick = ::save, enabled = dirty) },
    ) {
        item(key = "master") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow("显示状态通知", enabled, { on ->
                        if (on && android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            enabled = on
                        }
                    }, subtitle = "常驻显示运行状态、网速与快捷控制", icon = Icons.Rounded.Router, iconTint = c.textMuted)
                    SettingsDivider()
                    SettingsNavRow("刷新频率", icon = Icons.Rounded.Refresh, iconTint = c.textMuted, value = "$refresh 秒", dropdown = true) { picker = "refresh" to -1 }
                    SettingsDivider()
                    SettingsNavRow("点击通知打开", icon = Icons.Rounded.Inventory2, iconTint = c.textMuted, value = targetOptions.firstOrNull { it.first == target }?.second ?: target, dropdown = true) {
                        picker = "target" to -1
                    }
                }
            }
        }
        item(key = "title-template") {
            SettingsSection {
                SettingsGroup(title = "通知标题") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SettingsInput(value = titleTemplate, onValueChange = { titleTemplate = it.take(96) }, label = "标题模板")
                    }
                }
            }
        }
        item(key = "template") {
            SettingsSection {
                SettingsGroup(title = "通知内容") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        SettingsInput(value = template, onValueChange = { template = it.take(320) }, label = "模板", singleLine = false)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "可用变量：{status} {uptime} {upload} {download} {upload_total} {download_total} {cpu} {memory} {connections} {config} {core} {mode}",
                            fontSize = 12.sp, lineHeight = 17.sp,
                            color = c.textMuted,
                        )
                    }
                }
            }
        }
        (0 until 3).forEach { index ->
            item(key = "action$index") {
                SettingsSection {
                    SettingsGroup(title = "快捷按钮 ${index + 1}") {
                        SettingsNavRow("动作", icon = Icons.Rounded.Check, iconTint = c.textMuted, value = actionOptions.firstOrNull { it.first == actions[index] }?.second ?: actions[index], dropdown = true) {
                            picker = "action" to index
                        }
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                            SettingsInput(value = labels[index], onValueChange = { labels[index] = it.take(12) },
                                label = "按钮文字（可空，最多 12 字）", placeholder = actionOptions.firstOrNull { it.first == actions[index] }?.second.orEmpty())
                        }
                    }
                }
            }
        }
    }

    picker?.let { (kind, index) ->
        when (kind) {
            "refresh" -> HxChoiceSheet(
                presentation = HxChoicePresentation.Notification,
                title = "刷新频率",
                choices = refreshOptions.map { HxChoice(it.toString(), "$it 秒") },
                selected = refresh.toString(),
                onPick = { refresh = it.toInt(); picker = null },
                onDismiss = { picker = null },
            )
            "target" -> HxChoiceSheet(
                presentation = HxChoicePresentation.Notification,
                title = "点击通知打开",
                choices = targetOptions.map { HxChoice(it.first, it.second) },
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
