package io.github.xgl34222220.hetu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.xgl34222220.hetu.ui.HetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(vm: HetuViewModel, bottomPadding: Dp) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val count = HetuSettingsBackup.export(context, uri)
                vm.toast("已备份 $count 项设置与配置")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "导出失败")
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> restoreUri = uri }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ProxyStatusNotificationService.setEnabled(context, true)
            revision++
        } else vm.toast("没有通知权限，无法显示状态通知")
    }

    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }
    fun bump() { revision++; vm.bumpSettings() }

    val c = Hx.colors
    val panelDefault = prefs.getString("defaultPanelSection", "proxies").orEmpty()
    val mirrorEnabled = prefs.getBoolean("downloadMirrorEnabled", false)
    val notifyEnabled = prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false)
    val showPanelDock = prefs.getBoolean("showPanelDock", true)
    val appLanguage = prefs.getString("appLanguage", "system").orEmpty().ifBlank { "system" }
    val startOnPanel = prefs.getBoolean("startOnPanel", false)

    HxPage(title = "设置", scrollToTopSignal = vm.reselect, bottomPadding = bottomPadding) {
        val rows = listOf(
            Triple("基础代理配置", "核心 · 模式 · IPv6 · 自动覆写", Icons.Rounded.Tune) to { nav.push(HxRoute.Network) },
            Triple("高级代理配置", "性能 · TCP/UDP · DNS · 资源限制", Icons.Rounded.AltRoute) to { nav.push(HxRoute.AdvancedNetwork) },
            Triple("语言与主题", "系统语言 · 外观主题 · 颜色", Icons.Rounded.Palette) to { nav.push(HxRoute.Theme) },
            Triple("备份与恢复", "配置备份 · 从备份恢复", Icons.Rounded.CloudSync) to { nav.push(HxRoute.Backup) },
            Triple("开机启动与下载", "开机自启 · 后台下载", Icons.Rounded.RestartAlt) to { nav.push(HxRoute.Startup) },
            Triple("通知设置", "状态通知 · 通知内容 · 常用操作", Icons.Rounded.Notifications) to { nav.push(HxRoute.Notifications) },
            Triple("默认面板", "启动后默认打开的功能页面", Icons.Rounded.GridView) to { nav.push(HxRoute.PanelPreferences) },
            Triple("关于", "版本信息 · 开源许可 · 致谢", Icons.Rounded.Info) to { nav.push(HxRoute.About) },
        )
        rows.forEach { (row, action) ->
            item(key = row.first) {
                HxSection {
                    HxGroup(Modifier.testTag("settings-${row.first}")) {
                        HxRow(row.first, subtitle = row.second, icon = row.third, onClick = action) { HxChevron() }
                    }
                }
            }
        }
    }

    when (choice) {
        "language" -> HxChoiceSheet(
            title = "语言",
            choices = listOf(
                HxChoice("system", "跟随系统"),
                HxChoice("zh-CN", "简体中文"),
                HxChoice("zh-TW", "繁體中文"),
                HxChoice("en", "English"),
                HxChoice("ru", "Русский"),
            ),
            selected = prefs.getString("appLanguage", "system").orEmpty(),
            onPick = { value -> prefs.edit().putString("appLanguage", value).apply(); vm.bumpSettings(); revision++; choice = null },
            onDismiss = { choice = null },
        )
        "backup" -> HxChoiceSheet(
            title = "备份与恢复",
            choices = listOf(
                HxChoice("export", "导出备份", "导出应用设置与配置库"),
                HxChoice("restore", "恢复备份", "从备份文件恢复应用数据"),
            ),
            selected = null,
            onPick = { value ->
                choice = null
                if (value == "export") exporter.launch("Hetu-backup.json")
                else importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
            onDismiss = { choice = null },
        )
        "mirror" -> HxFormDialog(
            title = "加速下载",
            message = "填写镜像前缀，例如 https://ghfast.top/ 。",
            fields = listOf(HxField("镜像前缀", prefs.getString("downloadMirrorPrefix", "").orEmpty(), placeholder = "https://")),
            validate = { values ->
                val v = values.firstOrNull().orEmpty().trim()
                if (v.isNotBlank() && !v.startsWith("https://") && !v.startsWith("http://")) "请填写 http/https 地址" else null
            },
            onConfirm = { values ->
                prefs.edit().putString("downloadMirrorPrefix", values.firstOrNull().orEmpty().trim()).apply()
                bump(); choice = null
            },
            onDismiss = { choice = null },
        )
        "defaultPanel" -> HxChoiceSheet(
            title = "默认面板页面",
            choices = listOf(
                HxChoice("proxies", "策略"),
                HxChoice("conn", "连接"),
                HxChoice("providers", "订阅"),
                HxChoice("rules", "规则"),
                HxChoice("sets", "规则集"),
            ),
            selected = panelDefault,
            onPick = { vm.setDefaultPanelSection(it); revision++; choice = null },
            onDismiss = { choice = null },
        )
    }

    restoreUri?.let { uri ->
        HxConfirmDialog(
            title = "恢复备份？",
            message = "备份内的设置与配置将写入当前应用，不会自动重启正在运行的代理。",
            confirmLabel = "恢复",
            onConfirm = {
                restoreUri = null
                scope.launch {
                    try {
                        val count = HetuSettingsBackup.restore(context, uri)
                        vm.toast("已恢复 $count 项，请检查设置后手动应用")
                        vm.reloadAppearance()
                        bump()
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        vm.toast(error.message ?: "恢复失败")
                    }
                }
            },
            onDismiss = { restoreUri = null },
        )
    }
}

@Composable
private fun MiuixSettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surfaceContainer,
        ),
        content = content,
    )
}

@Composable
private fun MiuixSettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun MiuixSettingsArrow(
    title: String,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String? = null,
    onClick: () -> Unit,
) {
    top.yukonga.miuix.kmp.preference.ArrowPreference(
        title = title,
        summary = summary,
        startAction = { MiuixSettingsIcon(icon) },
        endActions = {
            if (!value.isNullOrBlank()) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = value,
                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun MiuixSettingsSwitch(
    title: String,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    top.yukonga.miuix.kmp.preference.SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
        startAction = { MiuixSettingsIcon(icon) },
    )
}


/* ------------------------------------------------------------------ */
/*  Network & routing                                                   */
/* ------------------------------------------------------------------ */

@Composable
internal fun NetworkSettingsScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<List<ProxyConfigUi>>(emptyList()) }
    val profile = ProxyRuntimeProfile.load(prefs)
    LaunchedEffect(revision) { configs = runCatching { vm.controller.configLibrary() }.getOrDefault(emptyList()) }
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching { vm.controller.importConfig(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "config.yaml") }
                .onSuccess { vm.applyConfigChange("配置已导入并设为当前"); revision++ }
                .onFailure { vm.toast(it.message ?: "配置导入失败") }
        }
    }

    fun changed(key: String) {
        ProxyRuntimeSettings.markDirty(prefs, key)
        revision++
        vm.bumpSettings()
    }
    fun putString(key: String, value: String) {
        try {
            if (key == "proxyBaseCore") ProxyConfigLibrary.selectCore(prefs, value)
            else prefs.edit().putString(key, value).apply()
            changed(key)
        } catch (failure: java.io.IOException) {
            vm.toast(failure.message ?: "核心选择未改变，请稍后重试")
        }
    }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    val c = Hx.colors

    HxPage(
        title = "基础代理配置",
        onBack = { nav.pop() },
        largeTitle = false,
        canvasColor = c.canvas,
    ) {
        item(key = "core") {
            HxSection {
                HxGroup {
                    HxNavRow("代理核心", icon = Icons.Rounded.Memory, value = profile.core.label, dropdown = true) { choice = "core" }
                    HxDivider()
                    HxNavRow("运行模式", icon = Icons.Rounded.Route, value = profile.mode.label, dropdown = true) { choice = "mode" }
                    HxDivider()
                    HxNavRow(
                        "IPv6",
                        value = when (profile.ipv6) {
                            ProxyRuntimeProfile.Ipv6.BYPASS -> "不进核心"
                            ProxyRuntimeProfile.Ipv6.STRICT -> "严格防泄漏"
                            ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用系统 IPv6"
                            else -> "启用"
                        },
                        dropdown = true,
                    ) { choice = "ipv6" }
                    HxDivider()
                    HxSwitchRow(
                        "自动覆写",
                        profile.autoOverwrite,
                        { putBool("proxyBaseAutoOverwrite", it) },
                        subtitle = "启动时将必要的河图参数覆写到运行配置",
                    )
                }
            }
        }
        item(key = "startup") {
            HxSection {
                HxGroup {
                    HxNavRow("查看启动配置") { context.startActivity(Intent(context, ProxyStartupConfigActivity::class.java)) }
                }
            }
        }
        item(key = "config-source") {
            HxSection { HxGroup { HxRow("当前配置", subtitle = configs.firstOrNull { it.selected }?.name ?: "尚未选择配置", icon = Icons.Rounded.Description) } }
        }
        if (vm.state.running && ProxyRuntimeSettings.pending(true, prefs)) {
            item(key = "pending") {
                Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 12.dp)) {
                    HxBanner(
                        "设置已修改，重启代理后生效",
                        tone = HxTone.Warn,
                        actionLabel = if (vm.operation == null) "立即重启" else null,
                        onAction = vm::restart,
                    )
                }
            }
        }
    }

    when (choice) {
        "core" -> HxChoiceSheet(
            title = "核心选择",
            choices = listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART).map { core ->
                val installed = core == ProxyRuntimeProfile.Core.MIHOMO || ProxyCoreStore(context).installed(core)
                HxChoice(core.id, core.label, if (installed) null else "尚未安装，请先在核心管理下载", enabled = installed)
            },
            selected = profile.core.id,
            onPick = { putString("proxyBaseCore", it); choice = null },
            onDismiss = { choice = null },
        )
        "mode" -> HxChoiceSheet(
            title = "运行模式",
            choices = ProxyRuntimeProfile.Mode.values()
                .filter { ProxyRuntimeProfile.capability(profile.core, it).available }
                .map { HxChoice(it.id, it.label, modeDescription(it)) },
            selected = profile.mode.id,
            onPick = { putString("proxyBaseMode", it); choice = null },
            onDismiss = { choice = null },
        )
        "ipv6" -> HxChoiceSheet(
            title = "IPv6",
            choices = listOf(
                HxChoice("enable", "启用", "IPv6 流量同样进入代理"),
                HxChoice("bypass", "不进核心", "IPv6 直连，不经过代理"),
                HxChoice("strict", "严格防泄漏", "仅用 IPv4，拦截 IPv6"),
                HxChoice("disable", "禁用系统 IPv6", "关闭系统 IPv6 外联"),
            ),
            selected = profile.ipv6.id,
            onPick = { putString("proxyBaseIpv6", it); choice = null },
            onDismiss = { choice = null },
        )
    }
}

private fun looksLikeCidr(value: String): Boolean {
    val parts = value.split('/')
    if (parts.size != 2) return false
    val bits = parts[1].toIntOrNull() ?: return false
    val ip = parts[0]
    return if (ip.contains(':')) bits in 0..128 && ip.all { it.isLetterOrDigit() || it == ':' }
    else bits in 0..32 && ip.split('.').let { octets -> octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true } }
}

private fun modeDescription(mode: ProxyRuntimeProfile.Mode): String = when (mode) {
    ProxyRuntimeProfile.Mode.TPROXY -> "推荐。TCP/UDP 全接管，性能最好"
    ProxyRuntimeProfile.Mode.REDIRECT -> "仅 TCP，兼容性最好"
    ProxyRuntimeProfile.Mode.ENHANCE -> "TCP 走 Redirect，UDP 走 TPROXY"
    ProxyRuntimeProfile.Mode.TUN -> "Root 下的 TUN 虚拟网卡"
    ProxyRuntimeProfile.Mode.EBPF -> "eBPF 重定向到 TUN"
    ProxyRuntimeProfile.Mode.MIXED -> "暂不可用"
}
@Composable
private fun SettingsIdentityCard(vm: HetuViewModel, onClick: () -> Unit) {
    val c = Hx.colors
    val running = vm.state.running
    HxSection {
        HxCard(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_hetu_official),
                    null,
                    Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("河图", style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(
                        "v" + BuildConfig.VERSION_NAME + " · " + vm.coreVersion.ifBlank { "Mihomo" },
                        style = MaterialTheme.typography.bodySmall.merge(HxNumberStyle),
                        color = c.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HxPill(if (running) "运行中" else "未运行", if (running) HxTone.Good else HxTone.Neutral)
                Spacer(Modifier.width(4.dp))
                HxChevron()
            }
        }
    }
}


/** The three appearance modes share one selection group; large text gets full-width cards. */
@Composable
private fun HxAppearanceModes(vm: HetuViewModel) {
    val c = Hx.colors
    val modes = listOf(
        Triple("system", "跟随系统", Icons.Rounded.BrightnessAuto),
        Triple("light", "浅色模式", Icons.Rounded.LightMode),
        Triple("dark", "深色模式", Icons.Rounded.DarkMode),
    )
    val captions = listOf("与系统设置\n保持一致", "始终使用\n浅色主题", "始终使用\n深色主题")
    val fontScale = LocalDensity.current.fontScale
    @Composable fun Mode(index: Int, modifier: Modifier = Modifier) {
        val (value, label, icon) = modes[index]
        val selected = vm.appearance == value
        val motion = LocalHxMotionEnabled.current
        val fillColor by androidx.compose.animation.animateColorAsState(
            if (selected) c.accentSoft else c.surface,
            if (motion) HxMotion.enter<Color>(HxMotion.Short) else androidx.compose.animation.core.snap(), label = "theme-mode-fill-$value")
        val borderColor by androidx.compose.animation.animateColorAsState(
            if (selected) c.accent else c.line,
            if (motion) HxMotion.enter<Color>(HxMotion.Short) else androidx.compose.animation.core.snap(), label = "theme-mode-border-$value")
        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        androidx.compose.material3.Surface(
            modifier = modifier.testTag("theme-mode-$value")
                .hxPressScale(source)
                .selectable(selected, role = Role.RadioButton, interactionSource = source,
                    indication = androidx.compose.foundation.LocalIndication.current) { vm.setAppearanceMode(value) },
            shape = RoundedCornerShape(20.dp),
            color = fillColor,
            border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else .8.dp, borderColor),
        ) {
            Column(Modifier.padding(12.dp).heightIn(min = 124.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HxIconBadge(icon, if (selected) c.accent else c.textMuted)
                    Spacer(Modifier.weight(1f))
                    if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = c.accent)
                }
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.titleSmall, color = c.text)
                Spacer(Modifier.height(5.dp))
                Text(captions[index], style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
        }
    }
    HxSection("主题模式") {
        BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
            if (fontScale > 1.3f || maxWidth < 310.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.indices.forEach { Mode(it, Modifier.fillMaxWidth()) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.indices.forEach { Mode(it, Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Keep all existing accents, with separate 48 dp targets instead of crowded tiny circles. */
@Composable
private fun HxAccentSwatches(vm: HetuViewModel) {
    val c = Hx.colors
    val haptics = io.github.xgl34222220.hetu.ui.rememberHetuHaptics()
    val swatches = listOf("#2A62E8" to "冰蓝", "#12806F" to "墨绿", "#0EA5E9" to "湖蓝", "#4F46E5" to "靛蓝",
        "#8B5CF6" to "紫色", "#EC4899" to "粉色", "#EF4444" to "红色", "#F59E0B" to "琥珀")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).selectableGroup()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HxIconBadge(Icons.Rounded.ColorLens, c.textMuted)
            Spacer(Modifier.width(10.dp))
            Text("强调色", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text)
        }
        Spacer(Modifier.height(8.dp))
        swatches.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                row.forEach { (hex, name) ->
                    val color = Color(android.graphics.Color.parseColor(hex))
                    val selected = vm.accentChoice.equals(hex, true)
                    val scale by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else .82f,
                        if (LocalHxMotionEnabled.current) HxMotion.pop<Float>() else androidx.compose.animation.core.snap(), label = "swatch")
                    Box(Modifier.size(48.dp).testTag("theme-color-$hex")
                        .semantics { contentDescription = "强调色：$name" }
                        .selectable(selected, role = Role.RadioButton) {
                            haptics.perform(io.github.xgl34222220.hetu.ui.HetuHaptic.Tick)
                            vm.setAccent(hex)
                        }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(34.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(CircleShape).background(color)
                            .then(if (selected) Modifier.border(3.dp, c.surface, CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}


/* ------------------------------------------------------------------ */
/*  Theme lab — BoxProxy interaction model, Hetu rendering primitives  */
/* ------------------------------------------------------------------ */

@Composable
internal fun HxThemeLabScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    val tick = revision + vm.settingsRevision
    val blur = remember(tick) { prefs.getBoolean("enableBlur", true) }
    val floating = remember(tick) { prefs.getBoolean("floatingBottomBar", true) }
    val liquid = remember(tick) { prefs.getBoolean("liquidGlass", true) }
    val backAnim = remember(tick) { prefs.getBoolean("predictiveBackAnimation", true) }
    val followEdge = remember(tick) { prefs.getBoolean("predictiveBackFollowEdge", true) }
    val topBlur = remember(tick) { prefs.getString("topBarBlurStyle", "progressive").orEmpty().ifBlank { "progressive" } }
    val uiScale = remember(tick) { prefs.getFloat("uiScale", 1f).coerceIn(.8f, 1.2f) }
    val c = Hx.colors

    fun setBool(key: String, value: Boolean, reload: Boolean = false) {
        prefs.edit().putBoolean(key, value).apply()
        if (reload) vm.reloadAppearance()
        vm.bumpSettings()
        revision++
    }
    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
        vm.bumpSettings()
        revision++
    }

    HxPage(
        title = "主题设置",
        subtitle = null,
        onBack = onBack,
        largeTitle = false,
    ) {
        item(key = "language") {
            HxSection { HxGroup { HxNavRow("语言", icon = Icons.Rounded.Public, value = when (prefs.getString("appLanguage", "system")) { "zh-CN" -> "简体中文"; "zh-TW" -> "繁體中文"; "en" -> "English"; "ru" -> "Русский"; else -> "跟随系统" }) { choice = "language" } } }
        }
        item(key = "appearance-modes") { HxAppearanceModes(vm) }
        item(key = "theme") {
            HxSection {
                HxGroup {
                    if (Build.VERSION.SDK_INT >= 31) {
                        HxSwitchRow("Monet 动态取色", vm.dynamicColor, { vm.setDynamic(it); vm.bumpSettings(); revision++ }, subtitle = "使用系统壁纸的配色", icon = Icons.Rounded.Palette)
                        HxDivider()
                    }
                    HxSwitchRow("深色纯黑背景", vm.pureBlack, { vm.updatePureBlack(it); vm.bumpSettings(); revision++ }, subtitle = "OLED 模式使用纯黑画布", icon = Icons.Rounded.Contrast, iconTint = c.textMuted)
                    if (!vm.dynamicColor) {
                        HxDivider()
                        HxAccentSwatches(vm)
                    }
                }
            }
        }
        item(key = "glass") {
            HxSection {
                HxGroup {
                    HxSwitchRow("模糊效果", blur, { setBool("enableBlur", it, reload = true) }, subtitle = "控制顶栏、底栏与浮层的实时模糊", icon = Icons.Rounded.BlurOn, iconTint = c.textMuted)
                    HxDivider()
                    HxNavRow("顶栏模糊样式", subtitle = "选择顶栏磨砂的过渡方式", icon = Icons.Rounded.Tune, iconTint = c.textMuted, value = if (topBlur == "gaussian") "高斯模糊" else "渐进式模糊", dropdown = true) { choice = "topBlur" }
                    HxDivider()
                    HxSwitchRow("悬浮底栏", floating, { setBool("floatingBottomBar", it) }, subtitle = "关闭后底栏吸附屏幕底部", icon = Icons.Rounded.Dashboard, iconTint = c.textMuted)
                    HxDivider()
                    HxSwitchRow("底栏液态玻璃", liquid, { setBool("liquidGlass", it) }, subtitle = "为底栏加入通透的折射与高光", icon = Icons.Rounded.BlurOn, iconTint = c.accent, enabled = blur)
                }
            }
        }
        item(key = "motion") {
            HxSection {
                HxGroup {
                    HxSwitchRow("预测性返回动画", backAnim, { setBool("predictiveBackAnimation", it) }, subtitle = "返回手势让页面跟手缩放、位移并露出上一层", icon = Icons.Rounded.Route, iconTint = c.textMuted)
                    AnimatedVisibility(backAnim) {
                        Column {
                            HxDivider()
                            HxSwitchRow("动画方向跟随滑动边缘", followEdge, { setBool("predictiveBackFollowEdge", it) }, subtitle = "从右边缘返回时方向同步反转", icon = Icons.Rounded.SwapHoriz, iconTint = c.textMuted)
                        }
                    }
                    HxDivider()
                    HxNavRow("界面缩放", subtitle = "统一调整界面和文字大小", icon = Icons.Rounded.GridView, iconTint = c.textMuted, value = "${(uiScale * 100).toInt()}%", dropdown = true) { choice = "scale" }
                }
            }
        }
    }

    when (choice) {
        "language" -> HxChoiceSheet("语言", listOf(HxChoice("system", "跟随系统"), HxChoice("zh-CN", "简体中文"), HxChoice("zh-TW", "繁體中文"), HxChoice("en", "English"), HxChoice("ru", "Русский")), prefs.getString("appLanguage", "system"), onPick = { setString("appLanguage", it); choice = null }, onDismiss = { choice = null })

        "topBlur" -> HxChoiceSheet(
            title = "顶栏模糊样式",
            choices = listOf(
                HxChoice("progressive", "渐进式模糊", "顶部更浓，向内容区域逐渐消散"),
                HxChoice("gaussian", "高斯模糊", "整条顶栏使用均匀磨砂"),
            ),
            selected = topBlur,
            onPick = { setString("topBarBlurStyle", it); choice = null },
            onDismiss = { choice = null },
        )
        "scale" -> HxChoiceSheet(
            title = "界面缩放",
            choices = listOf(.8f, .9f, 1f, 1.1f, 1.2f).map { HxChoice(it.toString(), "${(it * 100).toInt()}%") },
            selected = uiScale.toString(),
            onPick = { value -> prefs.edit().putFloat("uiScale", value.toFloat()).apply(); vm.bumpSettings(); revision++; choice = null },
            onDismiss = { choice = null },
            footer = "调整后应用到所有页面与弹窗。",
        )
    }
}
