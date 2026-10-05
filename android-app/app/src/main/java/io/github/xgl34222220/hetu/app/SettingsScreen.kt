package io.github.xgl34222220.hetu

import io.github.xgl34222220.hetu.ui.ht

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Layers
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
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.xgl34222220.hetu.ui.HetuHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(vm: HetuViewModel, bottomPadding: Dp, initialSubPage: String? = null, onBackOverride: (() -> Unit)? = null) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var subPage by remember { mutableStateOf(initialSubPage) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val density = LocalDensity.current
    var firstGroupHeightPx by remember { mutableIntStateOf(0) }

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

    fun closeSubPage() { if (onBackOverride != null) onBackOverride() else subPage = null }
    androidx.activity.compose.BackHandler(enabled = subPage != null) { closeSubPage() }
    val settingsTick = revision + vm.settingsRevision
    val c = Hx.colors
    val panelDefault = prefs.getString("defaultPanelSection", "overview").orEmpty()
    val mirrorEnabled = prefs.getBoolean("downloadMirrorEnabled", false)
    val notifyEnabled = prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false)
    val showPanelDock = prefs.getBoolean("showPanelDock", true)
    val appLanguage = prefs.getString("appLanguage", "system").orEmpty().ifBlank { "system" }
    val startOnPanel = prefs.getBoolean("startOnPanel", false)

    val miuixColors = if (c.dark) {
        top.yukonga.miuix.kmp.theme.darkColorScheme(
            primary = c.accent,
            primaryVariant = c.accent,
            background = c.canvas,
            surface = c.canvas,
            surfaceVariant = c.canvas,
            surfaceContainer = c.surface,
            onBackground = c.text,
            onSurface = c.text,
            onSurfaceContainer = c.text,
            onSurfaceVariantSummary = Color(0xFF55576A),
            onSurfaceVariantActions = Color(0xFF4A4C5E),
            outline = c.line,
            dividerLine = c.line,
        )
    } else {
        top.yukonga.miuix.kmp.theme.lightColorScheme(
            primary = c.accent,
            primaryVariant = c.accent,
            background = Color(0xFFEBEDFA),
            surface = Color(0xFFEBEDFA),
            surfaceVariant = Color(0xFFEBEDFA),
            surfaceContainer = Color(0xFFF9F8FE),
            onBackground = c.text,
            onSurface = c.text,
            onSurfaceContainer = c.text,
            onSurfaceVariantSummary = Color(0xFF55576A),
            onSurfaceVariantActions = Color(0xFF4A4C5E),
            outline = c.line,
            dividerLine = Color.Transparent,
        )
    }

    if (subPage == "backup") {
        HxPage(flatCanvas = true,
            title = ht("备份与恢复"),
            largeTitle = false,
            compactTitleFontSizeSp = 20f,
            onBack = { closeSubPage() },
        ) {
            item(key = "backup-subtitle") {
                Text("管理配置与偏好数据", style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Hx.gutter).padding(top = 8.dp, bottom = 10.dp))
            }
            item(key = "create") {
                SettingsSection {
                    SettingsGroup {
                        SettingsRow("创建备份", subtitle = "导出当前配置与偏好设置", icon = Icons.Rounded.CloudSync, iconTint = c.text)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            HxButton("导出备份", onClick = { launchDocumentPicker(vm::toast) { exporter.launch("Hetu-backup.json") } }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item(key = "restore") {
                SettingsSection {
                    SettingsGroup {
                        SettingsRow("从文件恢复", subtitle = "选择河图备份文件，恢复前会再次确认", icon = Icons.Rounded.Restore, iconTint = c.text)
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            HxButton("选择文件", onClick = { launchDocumentPicker(vm::toast) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } }, modifier = Modifier.fillMaxWidth(), filled = false)
                        }
                    }
                }
            }
            item(key = "contents") {
                SettingsSection {
                    SettingsGroup(title = "备份内容") {
                        SettingsRow("应用配置", subtitle = "配置库与运行偏好", icon = Icons.Rounded.Description, iconTint = c.text)
                        SettingsDivider()
                        SettingsRow("界面偏好", subtitle = "主题、语言与显示设置", icon = Icons.Rounded.Palette, iconTint = c.text)
                        SettingsDivider()
                        SettingsRow("订阅与连接", subtitle = "订阅链接与配置来源", icon = Icons.Rounded.LinkOff, iconTint = c.text)
                    }
                }
            }
            item(key = "warning") {
                HxBanner(
                    "备份文件可能包含配置中的订阅链接与认证信息，请保存至可信位置。",
                    tone = HxTone.Warn,
                    modifier = Modifier.padding(horizontal = Hx.gutter),
                )
            }
        }
    } else if (subPage == "startupDownload") {
        val autoStart = prefs.getBoolean("proxyRootAutoStart", false) && prefs.getBoolean("proxyRootAutoStartInstalled", false)
        // Capture changing preferences before the memoized LazyList builder.
        // A failed setup leaves autoStart false, so it cannot invalidate that builder.
        val autoStartError = prefs.getString("proxyRootAutoStartError", "").orEmpty()
        val mirrorOn = prefs.getBoolean("downloadMirrorEnabled", false)
        val mirrorPrefix = prefs.getString("downloadMirrorPrefix", "").orEmpty()
        HxPage(flatCanvas = true,
            title = ht("开机启动与下载"),
            largeTitle = false,
            compactTitleFontSizeSp = 20f,
            onBack = { closeSubPage() },
        ) {
            item(key = "autostart") {
                SettingsSection {
                    SettingsGroup {
                        SettingsSwitchRow(
                            "开机自启",
                            autoStart,
                            { vm.setAutoStart(it) },
                            enabled = !vm.autoStartBusy && vm.operation == null,
                            subtitle = if (vm.autoStartBusy) "正在设置开机脚本…" else if (autoStart) "开机脚本已安装，开机后自动启动服务" else "成功启动一次代理后开启，安装 Root 开机脚本",
                            icon = Icons.Rounded.RestartAlt,
                            iconTint = c.text,
                        )
                    }
                }
            }
            if (autoStartError.isNotBlank()) {
                item(key = "autostart-error") {
                    HxBanner(DiagnosticReport.redact(autoStartError, prefs.getString("proxyControllerSecret", "")).take(220),
                        tone = HxTone.Warn, modifier = Modifier.padding(horizontal = Hx.gutter))
                }
            }
            item(key = "download") {
                SettingsSection {
                    SettingsGroup {
                        SettingsSwitchRow(
                            ht("加速下载"),
                            mirrorOn,
                            { prefs.edit().putBoolean("downloadMirrorEnabled", it).apply(); bump() },
                            subtitle = "通过已配置的镜像下载资源",
                            icon = Icons.Rounded.Download,
                            iconTint = c.text,
                        )
                        SettingsDivider()
                        SettingsNavRow(
                            "加速地址",
                            icon = Icons.Rounded.LinkOff,
                            iconTint = c.text,
                            onClick = { choice = "mirror" },
                        )
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)
                            .fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (c.dark) c.surfaceMuted else Color(0xFFE8E5FF))
                            .padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("加速地址", color = c.textMuted, fontSize = 11.sp)
                            Text(mirrorPrefix.ifBlank { "尚未设置" }, color = c.textMuted, fontSize = 15.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
        }
    } else if (subPage == "defaultPanel") {
        HxPage(flatCanvas = true,
            title = ht("默认面板"),
            largeTitle = false,
            compactTitleFontSizeSp = 20f,
            onBack = { closeSubPage() },
        ) {
            item(key = "panel") {
                SettingsSection {
                    SettingsGroup {
                        SettingsNavRow(
                            ht("默认面板页面"),
                            icon = Icons.Rounded.GridView,
                            iconTint = c.text,
                            value = when (panelDefault) {
                                "overview" -> ht("概览"); "proxies" -> ht("策略"); "providers" -> ht("订阅"); "conn" -> ht("连接")
                                "rules" -> ht("规则"); "sets" -> ht("规则集"); "logs" -> ht("日志"); else -> ht("概览")
                            },
                            dropdown = true,
                            onClick = { choice = "defaultPanel" },
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            ht("启动时打开面板"),
                            startOnPanel,
                            { prefs.edit().putBoolean("startOnPanel", it).apply(); bump() },
                            icon = Icons.Rounded.Dashboard,
                            iconTint = c.text,
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            ht("显示底栏面板入口"),
                            showPanelDock,
                            { prefs.edit().putBoolean("showPanelDock", it).apply(); bump() },
                            icon = Icons.Rounded.GridView,
                            iconTint = c.text,
                        )
                    }
                }
            }
        }
    }

    if (subPage == null) {
    top.yukonga.miuix.kmp.theme.MiuixTheme(colors = miuixColors) {
        HxPage(flatCanvas = true,
            title = ht("设置"),
            scrollToTopSignal = vm.reselect,
            // The collapsed reference begins at the appearance group. Retain enough
            // measured trailing space to scroll the first group above the toolbar,
            // even on a tall viewport; do not shrink rows or hard-code a device height.
            bottomPadding = bottomPadding + with(density) { firstGroupHeightPx.toDp() },
            largeTitleStartPadding = 26.dp,
            largeTitleTopPadding = 16.dp,
            largeTitleFontSizeSp = 36f,
            largeTitleBottomPadding = 12.dp,
            canvasColor = c.canvas,
        ) {
            item(key = "proxy-config") {
                Box(Modifier.onSizeChanged { firstGroupHeightPx = it.height }) {
                    MiuixSettingsCard {
                        MiuixSettingsArrow(
                            title = ht("基础代理配置"),
                            summary = ht("核心、模式与当前配置"),
                            icon = Icons.Rounded.Tune,
                            onClick = { nav.push(HxRoute.Network) },
                        )
                        MiuixSettingsArrow(
                            title = ht("高级代理配置"),
                            summary = ht("性能、DNS 与资源限制"),
                            icon = Icons.Rounded.Dns,
                            onClick = { open(ProxyAdvancedSettingsActivity::class.java) },
                        )
                    }
                }
            }

            item(key = "appearance-panel") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        rowMinHeight = 74.dp,
                        title = ht("语言与主题"),
                        summary = ht("显示语言、主题与显示"),
                        icon = Icons.Rounded.Palette,
                        onClick = { nav.push(HxRoute.Theme) },
                    )
                    MiuixSettingsArrow(
                        rowMinHeight = 74.dp,
                        title = ht("默认面板"),
                        summary = ht("选择面板与显示偏好"),
                        icon = Icons.Rounded.GridView,
                        onClick = { nav.push(HxRoute.DefaultPanelSettings) },
                    )
                }
            }

            item(key = "backup-startup-notify") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        rowMinHeight = 70.dp,
                        title = ht("备份与恢复"),
                        summary = ht("导出与恢复应用设置"),
                        icon = Icons.Rounded.Layers,
                        onClick = { nav.push(HxRoute.BackupSettings) },
                    )
                    MiuixSettingsArrow(
                        rowMinHeight = 70.dp,
                        title = ht("开机启动与下载"),
                        summary = ht("启动设置与资源下载"),
                        icon = Icons.Rounded.RestartAlt,
                        onClick = { nav.push(HxRoute.StartupDownloadSettings) },
                    )
                    MiuixSettingsArrow(
                        rowMinHeight = 70.dp,
                        title = ht("通知设置"),
                        summary = ht("管理运行状态提醒"),
                        icon = Icons.Rounded.Notifications,
                        onClick = { nav.push(HxRoute.Notifications) },
                    )
                }
            }

            item(key = "about") {
                MiuixSettingsCard {
                    MiuixSettingsArrow(
                        title = ht("关于"),
                        summary = ht("版本与开源信息"),
                        icon = Icons.Rounded.Info,
                        onClick = { nav.push(HxRoute.About) },
                    )
                }
            }
        }
    }
    }

    when (choice) {
        "language" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Language,
            title = ht("语言"),
            choices = listOf(
                HxChoice("system", ht("跟随系统")),
                HxChoice("zh-CN", "简体中文"),
                HxChoice("zh-TW", "繁體中文"),
                HxChoice("en", "English"),
                HxChoice("ru", "Русский"),
            ),
            selected = prefs.getString("appLanguage", "system").orEmpty(),
            onPick = { value -> prefs.edit().putString("appLanguage", value).apply(); vm.bumpSettings(); revision++; choice = null },
            onDismiss = { choice = null },
        )
        "mirror" -> SettingsMirrorDialog(
            initial = prefs.getString("downloadMirrorPrefix", "").orEmpty(),
            onSave = { value ->
                prefs.edit().putString("downloadMirrorPrefix", value).apply()
                bump(); choice = null
            },
            onDismiss = { choice = null },
        )

        "defaultPanel" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("默认面板页面"),
            choices = listOf(
                HxChoice("overview", ht("概览")),
                HxChoice("proxies", ht("策略")),
                HxChoice("providers", ht("订阅")),
                HxChoice("conn", ht("连接")),
                HxChoice("rules", ht("规则")),
                HxChoice("sets", ht("规则集")),
                HxChoice("logs", ht("日志")),
            ),
            selected = panelDefault,
            onPick = { vm.setDefaultPanelSection(it); revision++; choice = null },
            onDismiss = { choice = null },
        )
    }

    restoreUri?.let { uri ->
        SettingsDialog(
            title = "恢复备份？",
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
        ) {
            Text("备份内的设置与配置将写入当前应用。同名同内容配置会复用；同名不同内容配置将保留为副本。正在运行的代理不会自动重启。",
                color = c.textMuted, fontSize = 14.sp, lineHeight = 21.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MiuixSettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SettingsSection { SettingsGroup(content = content) }
}

@Composable
private fun MiuixSettingsArrow(
    title: String,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String? = null,
    rowMinHeight: Dp = 78.dp,
    onClick: () -> Unit,
) {
    SettingsNavRow(title, subtitle = summary, icon = icon, value = value, rootReference = true, rowMinHeight = rowMinHeight, onClick = onClick)
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
    LaunchedEffect(revision, profile.core) { configs = runCatching { vm.controller.configLibrary() }.getOrDefault(emptyList()) }
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
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    val c = Hx.colors

    HxPage(flatCanvas = true,
        title = ht("基础代理配置"),
        onBack = { nav.pop() },
        largeTitle = false,
        compactTitleFontSizeSp = 20f,
        canvasColor = c.canvas,
    ) {
        item(key = "core") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow("代理核心", subtitle = "选择代理核心程序", icon = Icons.Rounded.Memory,
                        value = profile.core.label, dropdown = true) { choice = "core" }
                    SettingsDivider()
                    SettingsNavRow("运行模式", subtitle = "选择代理运行模式", icon = Icons.Rounded.AltRoute,
                        value = profile.mode.label, dropdown = true) { choice = "mode" }
                    SettingsDivider()
                    SettingsNavRow(
                        "IPv6",
                        subtitle = "启用或禁用 IPv6 支持",
                        icon = Icons.Rounded.Public,
                        value = when (profile.ipv6) {
                            ProxyRuntimeProfile.Ipv6.BYPASS -> "不进核心"
                            ProxyRuntimeProfile.Ipv6.STRICT -> "严格防泄漏"
                            ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用系统 IPv6"
                            else -> "启用"
                        },
                        dropdown = true,
                    ) { choice = "ipv6" }
                    SettingsDivider()
                    SettingsSwitchRow(
                        "自动覆写",
                        profile.autoOverwrite,
                        { putBool("proxyBaseAutoOverwrite", it) },
                        subtitle = "启动时将必要的河图参数覆写到运行配置",
                        icon = Icons.Rounded.Refresh,
                    )
                }
            }
        }
        item(key = "startup") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow("查看启动配置", subtitle = "查看当前生成的运行配置文件", icon = Icons.Rounded.Description) {
                        context.startActivity(Intent(context, ProxyStartupConfigActivity::class.java))
                    }
                }
            }
        }
        item(key = "config") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow("当前配置", subtitle = configs.firstOrNull { it.selected }?.name ?: "尚未选择配置",
                        icon = Icons.Rounded.Description, onClick = { choice = "config" })
                }
            }
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
        "config" -> HxSheet(title = "当前配置", onDismiss = { choice = null }) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(configs.size, key = { configs[it].name }) { index ->
                    val config = configs[index]
                    SettingsRow(config.name, onClick = {
                        choice = null
                        if (!config.selected) scope.launch {
                            try {
                                vm.controller.selectConfig(config.name)
                                vm.applyConfigChange("已切换到 ${config.name}")
                                revision++
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { vm.toast(error.message ?: "切换配置失败") }
                        }
                    }) {
                        if (config.selected) Icon(Icons.Rounded.Check, "当前配置", tint = c.accent, modifier = Modifier.size(22.dp))
                    }
                }
                item(key = "import-config") {
                    SettingsNavRow("导入配置", subtitle = "从文件导入 YAML 配置", icon = Icons.Rounded.UploadFile) {
                        choice = null
                        launchDocumentPicker(vm::toast) { importConfig.launch(arrayOf("*/*")) }
                    }
                }
            }
        }
        "core" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            menuWidthOverride = 174.dp,
            title = "代理核心",
            choices = listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART).map { core ->
                val installed = core == ProxyRuntimeProfile.Core.MIHOMO || ProxyCoreStore(context).installed(core)
                HxChoice(core.id, core.label, if (installed) null else "尚未安装，请先在核心管理下载", enabled = installed)
            },
            selected = profile.core.id,
            onPick = { putString("proxyBaseCore", it); choice = null },
            onDismiss = { choice = null },
        )
        "mode" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            menuWidthOverride = 184.dp,
            title = "运行模式",
            choices = ProxyRuntimeProfile.Mode.values()
                .filter { ProxyRuntimeProfile.capability(profile.core, it).available }
                .map { HxChoice(it.id, it.label, modeDescription(it)) },
            selected = profile.mode.id,
            onPick = { putString("proxyBaseMode", it); choice = null },
            onDismiss = { choice = null },
        )
        "ipv6" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            menuWidthOverride = 184.dp,
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
    SettingsSection {
        HxCard(onClick = onClick, padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painterResource(R.drawable.ic_hetu_concept),
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


/** The concept's eight accents keep their persisted IDs and use a two-by-four grid. */
@Composable
private fun HxAccentSwatches(vm: HetuViewModel) {
    SettingsAccentSwatches(vm.accentChoice, vm::setAccent)
}

@Composable
internal fun SettingsAccentSwatches(selectedHex: String, onSelect: (String) -> Unit) {
    val c = Hx.colors
    val accentLabel = ht("强调色")
    val swatches = listOf(
        "#2A62E8" to Color(0xFF0A62E8), "#42CAA3" to Color(0xFF42CAA3),
        "#36B9F3" to Color(0xFF36B9F3), "#6654FF" to Color(0xFF6654FF),
        "#9B5CFC" to Color(0xFF9B5CFC), "#F766AC" to Color(0xFFF766AC),
        "#FF5557" to Color(0xFFFF5557), "#FFAA06" to Color(0xFFFFAA06),
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(io.github.xgl34222220.hetu.ui.baiZeLineIcon(Icons.Rounded.Palette), null, tint = c.text, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(24.dp))
            Text(ht("强调色"), fontSize = 18.sp, lineHeight = 23.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = c.text)
        }
        Column(Modifier.selectableGroup().padding(start = 50.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (hex, color) ->
                        val selected = selectedHex.equals(hex, true)
                        val source = remember { MutableInteractionSource() }
                        Box(Modifier.size(48.dp).hxPressScale(source, .92f)
                            .semantics { contentDescription = "$accentLabel $hex" }
                            .selectable(selected = selected, role = Role.RadioButton, interactionSource = source, indication = null) { onSelect(hex) },
                            contentAlignment = Alignment.Center) {
                            Box(Modifier.size(46.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(21.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsThemeModeChoices(selected: String, onSelect: (String) -> Unit) {
    val c = Hx.colors
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            Triple("system", ht("跟随系统"), Icons.Rounded.AutoAwesome),
            Triple("light", ht("浅色模式"), Icons.Rounded.LightMode),
            Triple("dark", ht("深色模式"), Icons.Rounded.DarkMode),
        ).forEach { (value, label, icon) ->
            val active = selected == value
            val source = remember { MutableInteractionSource() }
            val background by animateColorAsState(if (active) c.accentSoft else c.surfaceMuted.copy(alpha = .42f), label = "settingsThemeChoice")
            Column(Modifier.weight(1f).heightIn(min = 108.dp).hxPressScale(source, .96f)
                .clip(RoundedCornerShape(12.dp))
                .then(if (active) Modifier.border(1.dp, c.accent, RoundedCornerShape(12.dp)) else Modifier)
                .background(background)
                .selectable(selected = active, role = Role.RadioButton, interactionSource = source, indication = null) { onSelect(value) }
                .padding(horizontal = 4.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                    if (active) Icon(Icons.Rounded.Check, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Icon(settingsLineIcon(icon), null, tint = c.text, modifier = Modifier.align(Alignment.Center).size(30.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text(label, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = c.text, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(when (value) { "system" -> ht("与系统设置保持一致"); "light" -> ht("始终使用浅色主题"); else -> ht("始终使用深色主题") },
                    fontSize = 10.sp, lineHeight = 14.sp, color = c.textMuted, maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
    val appLanguage = remember(tick) { prefs.getString("appLanguage", "system").orEmpty().ifBlank { "system" } }
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

    HxPage(flatCanvas = true,
        title = ht("主题设置"),
        subtitle = null,
        onBack = onBack,
        largeTitle = false,
        compactTitleFontSizeSp = 20f,
    ) {
        item(key = "language") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow(
                        ht("语言"),
                        subtitle = ht("切换应用语言"),
                        icon = Icons.Rounded.Public,
                        iconTint = c.text,
                        value = when (appLanguage) {
                            "zh-CN" -> "简体中文"
                            "zh-TW" -> "繁體中文"
                            "en" -> "English"
                            "ru" -> "Русский"
                            else -> ht("跟随系统")
                        },
                        onClick = { choice = "language" },
                    )
                }
            }
        }
        item(key = "theme") {
            SettingsSection {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(ht("主题模式"), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = c.text)
                        Spacer(Modifier.height(8.dp))
                        SettingsThemeModeChoices(vm.appearance) { value ->
                            vm.setAppearanceMode(value)
                            vm.bumpSettings()
                            revision++
                        }
                    }
                }
            }
        }
        item(key = "theme-colors") {
            SettingsSection {
                SettingsGroup {
                    if (Build.VERSION.SDK_INT >= 31) {
                        SettingsSwitchRow(ht("Monet 动态取色"), vm.dynamicColor, { vm.setDynamic(it); vm.bumpSettings(); revision++ }, subtitle = ht("使用系统壁纸提供的配色"), icon = Icons.Rounded.Palette)
                    }
                    SettingsDivider()
                    SettingsSwitchRow(ht("深色纯黑背景"), vm.pureBlack, { vm.updatePureBlack(it); vm.bumpSettings(); revision++ }, subtitle = ht("OLED 模式使用纯黑画布"), icon = Icons.Rounded.Contrast, iconTint = c.textMuted)
                    if (!vm.dynamicColor) {
                        SettingsDivider()
                        HxAccentSwatches(vm)
                    }
                }
            }
        }
        item(key = "glass") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("模糊效果"), blur, { setBool("enableBlur", it, reload = true) }, subtitle = ht("控制顶栏、底栏与浮层的实时模糊"), icon = Icons.Rounded.BlurOn, iconTint = c.textMuted)
                    SettingsDivider()
                    SettingsNavRow(ht("顶栏模糊样式"), subtitle = ht("选择顶栏磨砂的过渡方式"), icon = Icons.Rounded.Inventory2, iconTint = c.textMuted, value = if (topBlur == "gaussian") ht("高斯模糊") else ht("渐进式模糊"), dropdown = true) { choice = "topBlur" }
                    SettingsDivider()
                    SettingsSwitchRow(ht("悬浮底栏"), floating, { setBool("floatingBottomBar", it) }, subtitle = ht("关闭后底栏吸附屏幕底部"), icon = SettingsActionIcons.FloatingDock, iconTint = c.textMuted)
                    SettingsDivider()
                    SettingsSwitchRow(ht("底栏液态玻璃"), liquid, { setBool("liquidGlass", it) }, subtitle = ht("为底栏加入通透的折射与高光"), icon = SettingsActionIcons.LiquidGlass, iconTint = c.accent, enabled = blur)
                }
            }
        }
        item(key = "motion") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("预测性返回动画"), backAnim, { setBool("predictiveBackAnimation", it) }, subtitle = ht("返回手势让页面跟手缩放、位移并露出上一层"), icon = Icons.Rounded.Route, iconTint = c.textMuted)
                    AnimatedVisibility(backAnim) {
                        Column {
                            SettingsDivider()
                            SettingsSwitchRow(ht("动画方向跟随滑动边缘"), followEdge, { setBool("predictiveBackFollowEdge", it) }, subtitle = ht("从右边缘返回时方向同步反转"), icon = Icons.Rounded.SwapHoriz, iconTint = c.textMuted)
                        }
                    }
                    SettingsDivider()
                    SettingsNavRow(ht("界面缩放"), subtitle = ht("统一调整界面和文字大小"), icon = SettingsActionIcons.Scale, iconTint = c.textMuted, value = "${(uiScale * 100).toInt()}%", dropdown = true) { choice = "scale" }
                }
            }
        }
    }

    when (choice) {
        "language" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Language,
            title = ht("语言"),
            choices = listOf(
                HxChoice("system", ht("跟随系统")),
                HxChoice("zh-CN", "简体中文"),
                HxChoice("zh-TW", "繁體中文"),
                HxChoice("en", "English"),
                HxChoice("ru", "Русский"),
            ),
            selected = appLanguage,
            onPick = { value ->
                prefs.edit().putString("appLanguage", value).apply()
                vm.bumpSettings()
                revision++
                choice = null
            },
            onDismiss = { choice = null },
        )
        "appearance" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("主题模式"),
            choices = listOf(HxChoice("system", ht("跟随系统")), HxChoice("light", ht("浅色")), HxChoice("dark", ht("深色"))),
            selected = vm.appearance,
            onPick = { vm.setAppearanceMode(it); vm.bumpSettings(); revision++; choice = null },
            onDismiss = { choice = null },
        )
        "topBlur" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("顶栏模糊样式"),
            choices = listOf(
                HxChoice("progressive", ht("渐进式模糊"), "顶部更浓，向内容区域逐渐消散"),
                HxChoice("gaussian", ht("高斯模糊"), "整条顶栏使用均匀磨砂"),
            ),
            selected = topBlur,
            onPick = { setString("topBarBlurStyle", it); choice = null },
            onDismiss = { choice = null },
        )
        "scale" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Scale,
            title = ht("界面缩放"),
            choices = listOf(.8f, .9f, 1f, 1.1f, 1.2f).map { HxChoice(it.toString(), "${(it * 100).toInt()}%") },
            selected = uiScale.toString(),
            onPick = { value -> prefs.edit().putFloat("uiScale", value.toFloat()).apply(); vm.bumpSettings(); revision++; choice = null },
            onDismiss = { choice = null },
            footer = "调整后应用到所有页面与弹窗。",
        )
    }
}
