package io.github.xgl34222220.hetu

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePop
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowLayout
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.panel.PanelIcons
import io.github.xgl34222220.hetu.tools.ToolsIcons
import io.github.xgl34222220.hetu.ui.ht
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置: the root list and its three small sub-pages (备份与恢复, 开机启动与下载, 默认面板).
 * [initialSubPage] opens one of them directly when the page is pushed as a route of its own.
 */
@Composable
internal fun SettingsScreen(vm: HetuViewModel, bottomPadding: Dp, initialSubPage: String? = null, onBackOverride: (() -> Unit)? = null) {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    val c = LocalHomeColors.current
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
    @Suppress("UNUSED_VARIABLE")
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
    // Read so that a change made elsewhere (a restore, another page) redraws the values below.
    @Suppress("UNUSED_VARIABLE")
    val settingsTick = revision + vm.settingsRevision
    val panelDefault = prefs.getString("defaultPanelSection", "overview").orEmpty()
    val showPanelDock = prefs.getBoolean("showPanelDock", true)
    val startOnPanel = prefs.getBoolean("startOnPanel", false)

    when (subPage) {
        "backup" -> HxPage(
            title = ht("备份与恢复"),
            subtitle = ht("管理配置与偏好数据"),
            largeTitle = false,
            onBack = { closeSubPage() },
        ) {
            item(key = "create") {
                SettingsSection {
                    SettingsGroup {
                        SettingsRow(ht("创建备份"), subtitle = ht("导出当前配置与偏好设置"), icon = HxIcons.CloudUpload)
                        HxButton(
                            "导出备份", onClick = { launchDocumentPicker(vm::toast) { exporter.launch("Hetu-backup.json") } },
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
                        )
                    }
                }
            }
            item(key = "restore") {
                SettingsSection {
                    SettingsGroup {
                        SettingsRow(ht("从文件恢复"), subtitle = ht("选择河图备份文件，恢复前会再次确认"), icon = HxIcons.CloudDownload)
                        HxButton(
                            "选择文件", onClick = { launchDocumentPicker(vm::toast) { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } },
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp), filled = false,
                        )
                    }
                }
            }
            item(key = "contents") {
                SettingsSection {
                    SettingsGroup(title = ht("备份内容")) {
                        SettingsRow(ht("应用配置"), subtitle = ht("配置库与运行偏好"), icon = ToolsIcons.FileText)
                        SettingsDivider()
                        SettingsRow(ht("界面偏好"), subtitle = ht("主题、语言与显示设置"), icon = HxIcons.Palette)
                        SettingsDivider()
                        SettingsRow(ht("订阅与连接"), subtitle = ht("订阅链接与配置来源"), icon = ToolsIcons.Link)
                    }
                }
            }
            item(key = "warning") {
                HxBanner(
                    ht("备份文件可能包含配置中的订阅链接与认证信息，请保存至可信位置。"),
                    tone = HxTone.Warn,
                    modifier = Modifier.hxPageEnter().padding(horizontal = HomeDims.gutter),
                )
            }
        }

        "startupDownload" -> {
            val autoStart = prefs.getBoolean("proxyRootAutoStart", false) && prefs.getBoolean("proxyRootAutoStartInstalled", false)
            // Capture changing preferences before the memoized LazyList builder.
            // A failed setup leaves autoStart false, so it cannot invalidate that builder.
            val autoStartError = prefs.getString("proxyRootAutoStartError", "").orEmpty()
            val mirrorOn = prefs.getBoolean("downloadMirrorEnabled", false)
            val mirrorPrefix = prefs.getString("downloadMirrorPrefix", "").orEmpty()
            HxPage(
                title = ht("开机启动与下载"),
                largeTitle = false,
                onBack = { closeSubPage() },
            ) {
                item(key = "autostart") {
                    SettingsSection {
                        SettingsGroup {
                            SettingsSwitchRow(
                                ht("开机自启"),
                                autoStart,
                                { vm.setAutoStart(it) },
                                enabled = !vm.autoStartBusy && vm.operation == null,
                                subtitle = ht(if (vm.autoStartBusy) "正在设置开机脚本…" else if (autoStart) "开机脚本已安装，开机后自动启动服务" else "安装 Root 开机脚本，开机后自动启动服务"),
                                icon = HomeIcons.Power,
                            )
                        }
                    }
                }
                if (autoStartError.isNotBlank()) {
                    item(key = "autostart-error") {
                        HxBanner(
                            DiagnosticReport.redact(autoStartError, prefs.getString("proxyControllerSecret", "")).take(220),
                            tone = HxTone.Warn, modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap),
                        )
                    }
                }
                item(key = "download") {
                    SettingsSection {
                        SettingsGroup {
                            SettingsSwitchRow(
                                ht("加速下载"),
                                mirrorOn,
                                { prefs.edit().putBoolean("downloadMirrorEnabled", it).apply(); bump() },
                                subtitle = ht("通过已配置的镜像下载资源"),
                                icon = ToolsIcons.Download,
                            )
                            SettingsDivider()
                            SettingsNavRow(ht("加速地址"), icon = ToolsIcons.Link, onClick = { choice = "mirror" })
                            // The address itself, in the open: what the switch above will use.
                            Column(
                                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp).fillMaxWidth()
                                    .clip(HomeDims.controlShape).background(if (mirrorOn) c.accentSoft else c.sunken)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(ht("加速地址"), color = c.t2, style = HomeType.caption.copy(fontWeight = FontWeight.Medium))
                                Text(
                                    mirrorPrefix.ifBlank { ht("尚未设置") }, color = if (mirrorPrefix.isBlank()) c.t2 else c.t1,
                                    style = HomeType.body.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }

        "defaultPanel" -> HxPage(
            title = ht("默认面板"),
            largeTitle = false,
            onBack = { closeSubPage() },
        ) {
            item(key = "panel") {
                SettingsSection {
                    SettingsGroup {
                        SettingsNavRow(
                            ht("默认面板页面"),
                            icon = ToolsIcons.LayoutGrid,
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
                            icon = PanelIcons.Gauge,
                        )
                        SettingsDivider()
                        SettingsSwitchRow(
                            ht("显示底栏面板入口"),
                            showPanelDock,
                            { prefs.edit().putBoolean("showPanelDock", it).apply(); bump() },
                            icon = HxIcons.PanelBottom,
                        )
                    }
                }
            }
        }

        null -> HxPage(
            title = ht("设置"),
            scrollToTopSignal = vm.reselect,
            // Keep enough room below the last card for the first one to travel under the bar,
            // so the page can always be scrolled to its compact title, even on a tall screen.
            bottomPadding = bottomPadding + with(density) { firstGroupHeightPx.toDp() },
        ) {
            item(key = "proxy-config") {
                Box(Modifier.onSizeChanged { firstGroupHeightPx = it.height }) {
                    SettingsRootCard {
                        SettingsRootRow("基础代理配置", "核心、模式与当前配置", HomeIcons.SlidersHorizontal) { nav.push(HxRoute.Network) }
                        SettingsRootRow("高级代理配置", "性能、DNS 与资源限制", HomeIcons.Server) { open(ProxyAdvancedSettingsActivity::class.java) }
                    }
                }
            }
            item(key = "appearance-panel") {
                SettingsRootCard {
                    SettingsRootRow("语言与主题", "显示语言、主题与显示", HxIcons.Palette) { nav.push(HxRoute.Theme) }
                    SettingsRootRow("默认面板", "选择面板与显示偏好", ToolsIcons.LayoutGrid) { nav.push(HxRoute.DefaultPanelSettings) }
                }
            }
            item(key = "backup-startup-notify") {
                SettingsRootCard {
                    SettingsRootRow("备份与恢复", "导出与恢复应用设置", PanelIcons.Layers) { nav.push(HxRoute.BackupSettings) }
                    SettingsRootRow("开机启动与下载", "启动设置与资源下载", HomeIcons.Power) { nav.push(HxRoute.StartupDownloadSettings) }
                    SettingsRootRow("通知设置", "管理运行状态提醒", HxIcons.Bell) { nav.push(HxRoute.Notifications) }
                }
            }
            item(key = "about") {
                SettingsRootCard {
                    SettingsRootRow("关于", "版本与开源信息", HomeIcons.Info) { nav.push(HxRoute.About) }
                }
            }
        }
    }

    when (choice) {
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
            title = ht("恢复备份？"),
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
            Text(
                ht("备份内的设置与配置将写入当前应用。同名同内容配置会复用；同名不同内容配置将保留为副本。正在运行的代理不会自动重启。"),
                Modifier.fillMaxWidth(), color = c.t2, style = HomeType.body.copy(fontSize = 15.sp, lineHeight = 22.sp), textAlign = TextAlign.Center,
            )
        }
    }
}

/** A card of the root list. Its rows carry no hairlines: the icons already set them apart. */
@Composable
private fun SettingsRootCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    SettingsSection { SettingsGroup(content = content) }
}

@Composable
private fun SettingsRootRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    SettingsNavRow(ht(title), subtitle = ht(summary), icon = icon, onClick = onClick)
}

/* ------------------------------------------------------------------ */
/*  基础代理配置                                                         */
/* ------------------------------------------------------------------ */

@Composable
internal fun NetworkSettingsScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val prefs = vm.prefs
    val scope = rememberCoroutineScope()
    val c = LocalHomeColors.current
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<List<ProxyConfigUi>>(emptyList()) }
    val profile = ProxyRuntimeProfile.load(prefs)
    LaunchedEffect(revision, profile.core) { configs = runCatching { vm.controller.configLibrary() }.getOrDefault(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    if (importing) {
        // File, link or clipboard: validated by Mihomo before it is stored, rolled back if the running core rejects it.
        ConfigImportPage(vm, onBack = { importing = false }) { message ->
            importing = false
            revision++
            vm.toast(message)
        }
        return
    }

    fun changed(key: String) {
        ProxyRuntimeSettings.markDirty(prefs, key)
        revision++
        vm.bumpSettings()
    }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }
    val pending = vm.state.running && ProxyRuntimeSettings.pending(true, prefs)

    HxPage(
        title = ht("基础代理配置"),
        onBack = { nav.pop() },
        largeTitle = false,
    ) {
        item(key = "core") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow(ht("代理核心"), subtitle = ht("选择代理核心程序"), icon = ToolsIcons.Cpu,
                        value = profile.core.label, dropdown = true) { choice = "core" }
                    SettingsDivider()
                    SettingsNavRow(ht("运行模式"), subtitle = ht("选择代理运行模式"), icon = PanelIcons.Route,
                        value = profile.mode.label, dropdown = true) { choice = "mode" }
                    SettingsDivider()
                    SettingsNavRow(
                        "IPv6",
                        subtitle = ht("启用或禁用 IPv6 支持"),
                        icon = PanelIcons.Globe,
                        value = ht(when (profile.ipv6) {
                            ProxyRuntimeProfile.Ipv6.BYPASS -> "不进核心"
                            ProxyRuntimeProfile.Ipv6.STRICT -> "严格防泄漏"
                            ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用系统 IPv6"
                            else -> "启用"
                        }),
                        dropdown = true,
                    ) { choice = "ipv6" }
                    SettingsDivider()
                    SettingsSwitchRow(
                        ht("自动覆写"),
                        profile.autoOverwrite,
                        { putBool("proxyBaseAutoOverwrite", it) },
                        subtitle = ht("启动时将必要的河图参数覆写到运行配置"),
                        icon = HomeIcons.RefreshCw,
                    )
                }
            }
        }
        item(key = "startup") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow(ht("查看启动配置"), subtitle = ht("查看当前生成的运行配置文件"), icon = ToolsIcons.FileCog) {
                        context.startActivity(Intent(context, ProxyStartupConfigActivity::class.java))
                    }
                }
            }
        }
        item(key = "config") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow(ht("当前配置"), subtitle = configs.firstOrNull { it.selected }?.name ?: ht("尚未选择配置"),
                        icon = ToolsIcons.FileText, onClick = { choice = "config" })
                    SettingsDivider()
                    SettingsNavRow(ht("编辑当前配置"), subtitle = ht("保存前校验，应用失败自动回滚"), icon = HxIcons.SquarePen) {
                        nav.push(HxRoute.ConfigEditor)
                    }
                    SettingsDivider()
                    SettingsNavRow(ht("导入配置"), subtitle = ht("文件、链接或剪贴板，校验后导入"), icon = HxIcons.Upload) { importing = true }
                }
            }
        }
        if (pending) {
            item(key = "pending") {
                HxBanner(
                    ht("设置已修改，重启代理后生效"),
                    tone = HxTone.Warn,
                    modifier = Modifier.padding(horizontal = HomeDims.gutter).padding(bottom = HomeDims.gap),
                    actionLabel = if (vm.operation == null) "立即重启" else null,
                    onAction = vm::restart,
                )
            }
        }
    }

    when (choice) {
        "config" -> HxSheet(title = ht("当前配置"), onDismiss = { choice = null }) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                items(configs.size, key = { configs[it].name }) { index ->
                    val config = configs[index]
                    SettingsRow(config.name, icon = ToolsIcons.FileText, compact = true, onClick = {
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
                        if (config.selected) Icon(HomeIcons.Check, ht("当前配置"), Modifier.size(22.dp), tint = c.accent)
                    }
                }
                item(key = "import-config") {
                    SettingsNavRow(ht("导入配置"), subtitle = ht("文件、链接或剪贴板，校验后导入"), icon = HxIcons.Upload) {
                        choice = null
                        importing = true
                    }
                }
            }
        }
        "core" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("代理核心"),
            choices = listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART).map { core ->
                val installed = core == ProxyRuntimeProfile.Core.MIHOMO || ProxyCoreStore(context).installed(core)
                HxChoice(core.id, core.label, if (installed) null else ht("尚未安装，请先在核心管理下载"), enabled = installed)
            },
            selected = profile.core.id,
            onPick = { putString("proxyBaseCore", it); choice = null },
            onDismiss = { choice = null },
        )
        "mode" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("运行模式"),
            choices = ProxyRuntimeProfile.Mode.values()
                .filter { ProxyRuntimeProfile.capability(profile.core, it).available }
                .map { HxChoice(it.id, it.label, ht(modeDescription(it))) },
            selected = profile.mode.id,
            onPick = { putString("proxyBaseMode", it); choice = null },
            onDismiss = { choice = null },
        )
        "ipv6" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = "IPv6",
            choices = listOf(
                HxChoice("enable", ht("启用"), ht("IPv6 流量同样进入代理")),
                HxChoice("bypass", ht("不进核心"), ht("IPv6 直连，不经过代理")),
                HxChoice("strict", ht("严格防泄漏"), ht("仅用 IPv4，拦截 IPv6")),
                HxChoice("disable", ht("禁用系统 IPv6"), ht("关闭系统 IPv6 外联")),
            ),
            selected = profile.ipv6.id,
            onPick = { putString("proxyBaseIpv6", it); choice = null },
            onDismiss = { choice = null },
        )
    }
}

private fun modeDescription(mode: ProxyRuntimeProfile.Mode): String = when (mode) {
    ProxyRuntimeProfile.Mode.TPROXY -> "推荐。TCP/UDP 全接管，性能最好"
    ProxyRuntimeProfile.Mode.REDIRECT -> "仅 TCP，兼容性最好"
    ProxyRuntimeProfile.Mode.ENHANCE -> "TCP 走 Redirect，UDP 走 TPROXY"
    ProxyRuntimeProfile.Mode.TUN -> "Root 下的 TUN 虚拟网卡"
    ProxyRuntimeProfile.Mode.EBPF -> "eBPF 重定向到 TUN"
    ProxyRuntimeProfile.Mode.MIXED -> "暂不可用"
}

/* ------------------------------------------------------------------ */
/*  主题设置: accent, theme mode                                        */
/* ------------------------------------------------------------------ */

/**
 * The eight accents, two rows of four. Each is a radio button named by its colour; the chosen
 * one grows a ring in its own colour and a check pops in.
 */
@Composable
internal fun SettingsAccentSwatches(selectedHex: String, onSelect: (String) -> Unit) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val haptics = LocalHomeHaptics.current
    val accentLabel = ht("强调色")
    val swatches = listOf(
        "#2A62E8" to Color(0xFF0A62E8), "#42CAA3" to Color(0xFF42CAA3),
        "#36B9F3" to Color(0xFF36B9F3), "#6654FF" to Color(0xFF6654FF),
        "#9B5CFC" to Color(0xFF9B5CFC), "#F766AC" to Color(0xFFF766AC),
        "#FF5557" to Color(0xFFFF5557), "#FFAA06" to Color(0xFFFFAA06),
    )
    Column {
        HomeRowLayout(AnnotatedString(accentLabel), icon = HxIcons.Palette, minHeight = HomeRowDims.compact)
        Column(
            Modifier.selectableGroup().padding(start = HomeRowDims.textStart - 4.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            swatches.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (hex, color) ->
                        val selected = selectedHex.equals(hex, true)
                        val source = remember { MutableInteractionSource() }
                        val ring by animateFloatAsState(if (selected) 1f else 0f, HomeMotion.pop(motion), label = "accent-ring")
                        Box(
                            Modifier
                                .size(56.dp)
                                .hxPressScale(source, .9f)
                                .semantics { contentDescription = "$accentLabel $hex" }
                                .selectable(selected = selected, role = Role.RadioButton, interactionSource = source, indication = null) {
                                    haptics(HomeHaptic.Tick)
                                    onSelect(hex)
                                }
                                .drawBehind {
                                    // The disc gives way a little to its ring as the ring arrives.
                                    val full = size.minDimension / 2f
                                    if (ring > 0f) drawCircle(color.copy(alpha = ring.coerceIn(0f, 1f)), full - 1.25.dp.toPx(), style = Stroke(2.5.dp.toPx()))
                                    drawCircle(color, full - (2.dp + 5.dp * ring.coerceIn(0f, 1.2f)).toPx())
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            HomePop(selected) { Icon(HomeIcons.Check, null, Modifier.size(22.dp), tint = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 跟随系统 / 浅色模式 / 深色模式 as three tiles, each with a small picture of the app in that
 * mode, so the choice can be made by eye.
 */
@Composable
internal fun SettingsThemeModeChoices(selected: String, onSelect: (String) -> Unit) {
    val c = LocalHomeColors.current
    val motion = LocalHomeMotionEnabled.current
    val haptics = LocalHomeHaptics.current
    val tileShape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("system", ht("跟随系统"), ht("与系统设置保持一致")),
            Triple("light", ht("浅色模式"), ht("始终使用浅色主题")),
            Triple("dark", ht("深色模式"), ht("始终使用深色主题")),
        ).forEach { (value, label, note) ->
            val active = selected == value
            val source = remember { MutableInteractionSource() }
            val background by animateColorAsState(if (active) c.accentSoft else c.sunken, HomeMotion.fade(motion), label = "theme-tile")
            val outline by animateColorAsState(if (active) c.accent else Color.Transparent, HomeMotion.fade(motion), label = "theme-tile-outline")
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 148.dp)
                    .hxPressScale(source, .96f)
                    .clip(tileShape)
                    .background(background)
                    .border(1.5.dp, outline, tileShape)
                    .selectable(selected = active, role = Role.RadioButton, interactionSource = source, indication = null) {
                        haptics(HomeHaptic.Tick)
                        onSelect(value)
                    }
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth().height(58.dp)) {
                    val accent = c.accent
                    Box(Modifier.fillMaxWidth().height(58.dp).drawBehind { drawThemePreview(value, accent) })
                    HomePop(active, Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                        Box(Modifier.size(20.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(HomeIcons.Check, null, Modifier.size(13.dp), tint = c.onAccent)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(label, color = c.t1, style = HomeType.noteStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), maxLines = 2, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
                Text(note, color = c.t2, style = HomeType.badge.copy(fontWeight = FontWeight.Medium), maxLines = 2, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A thumbnail of the app in one theme: canvas, a bar, a card with two lines and the accent. */
private fun DrawScope.drawThemePreview(mode: String, accent: Color) {
    val radius = CornerRadius(12.dp.toPx())
    fun half(dark: Boolean) {
        val canvas = if (dark) Color(0xFF0E0F13) else Color(0xFFECEEFB)
        val card = if (dark) Color(0xFF20232B) else Color(0xFFFFFFFF)
        val ink = if (dark) Color(0xFFEBEDF2) else Color(0xFF12161A)
        drawRoundRect(canvas, cornerRadius = radius)
        val pad = 7.dp.toPx()
        drawRoundRect(ink.copy(alpha = .78f), Offset(pad, pad), Size(size.width * .34f, 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        val top = pad + 10.dp.toPx()
        drawRoundRect(card, Offset(pad, top), Size(size.width - pad * 2f, size.height - top - pad), CornerRadius(7.dp.toPx()))
        val line = top + 8.dp.toPx()
        drawCircle(accent, 4.dp.toPx(), Offset(pad + 10.dp.toPx(), line + 5.dp.toPx()))
        drawRoundRect(ink.copy(alpha = .62f), Offset(pad + 19.dp.toPx(), line), Size(size.width * .36f, 3.5.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawRoundRect(ink.copy(alpha = .26f), Offset(pad + 19.dp.toPx(), line + 7.dp.toPx()), Size(size.width * .24f, 3.5.dp.toPx()), CornerRadius(2.dp.toPx()))
    }
    when (mode) {
        "light" -> half(false)
        "dark" -> half(true)
        else -> {
            // Both at once, split down the middle: whichever the system is showing.
            clipRect(right = size.width / 2f) { half(false) }
            clipRect(left = size.width / 2f) { half(true) }
        }
    }
    drawRoundRect(Color.Black.copy(alpha = .07f), cornerRadius = radius, style = Stroke(1.dp.toPx()))
}

/* ------------------------------------------------------------------ */
/*  主题设置                                                             */
/* ------------------------------------------------------------------ */

@Composable
internal fun HxThemeLabScreen(vm: HetuViewModel, onBack: () -> Unit) {
    val prefs = vm.prefs
    val c = LocalHomeColors.current
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
        title = ht("主题设置"),
        onBack = onBack,
        largeTitle = false,
    ) {
        item(key = "language") {
            SettingsSection {
                SettingsGroup {
                    SettingsNavRow(
                        ht("语言"),
                        subtitle = ht("切换应用语言"),
                        icon = HxIcons.Languages,
                        value = when (appLanguage) {
                            "zh-CN" -> "简体中文"
                            "zh-TW" -> "繁體中文"
                            "en" -> "English"
                            "ru" -> "Русский"
                            else -> ht("跟随系统")
                        },
                        dropdown = true,
                        onClick = { choice = "language" },
                    )
                }
            }
        }
        item(key = "theme") {
            SettingsSection {
                SettingsGroup(title = ht("主题模式")) {
                    Box(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp)) {
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
                        SettingsSwitchRow(ht("Monet 动态取色"), vm.dynamicColor, { vm.setDynamic(it); vm.bumpSettings(); revision++ }, subtitle = ht("使用系统壁纸提供的配色"), icon = HxIcons.Blend)
                        SettingsDivider()
                    }
                    SettingsSwitchRow(ht("深色纯黑背景"), vm.pureBlack, { vm.updatePureBlack(it); vm.bumpSettings(); revision++ }, subtitle = ht("OLED 模式使用纯黑画布"), icon = HxIcons.Contrast)
                    // With Monet on the accent comes from the wallpaper, so the swatches fold away.
                    HomeReveal(!vm.dynamicColor) {
                        Column {
                            SettingsDivider()
                            SettingsAccentSwatches(vm.accentChoice, vm::setAccent)
                        }
                    }
                }
            }
        }
        item(key = "glass") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("模糊效果"), blur, { setBool("enableBlur", it, reload = true) }, subtitle = ht("控制顶栏、底栏与浮层的实时模糊"), icon = HxIcons.Droplet)
                    SettingsDivider()
                    SettingsNavRow(ht("顶栏模糊样式"), subtitle = ht("选择顶栏磨砂的过渡方式"), icon = HxIcons.LayoutPanelTop, value = if (topBlur == "gaussian") ht("高斯模糊") else ht("渐进式模糊"), dropdown = true) { choice = "topBlur" }
                    SettingsDivider()
                    SettingsSwitchRow(ht("悬浮底栏"), floating, { setBool("floatingBottomBar", it) }, subtitle = ht("关闭后底栏吸附屏幕底部"), icon = HxIcons.PanelBottom)
                    SettingsDivider()
                    SettingsSwitchRow(ht("底栏液态玻璃"), liquid, { setBool("liquidGlass", it) }, subtitle = ht("为底栏加入通透的折射与高光"), icon = HxIcons.Sparkles, enabled = blur)
                }
            }
        }
        item(key = "motion") {
            SettingsSection {
                SettingsGroup {
                    SettingsSwitchRow(ht("预测性返回动画"), backAnim, { setBool("predictiveBackAnimation", it) }, subtitle = ht("返回手势让页面跟手缩放、位移并露出上一层"), icon = ToolsIcons.Undo2)
                    HomeReveal(backAnim) {
                        Column {
                            SettingsDivider()
                            SettingsSwitchRow(ht("动画方向跟随滑动边缘"), followEdge, { setBool("predictiveBackFollowEdge", it) }, subtitle = ht("从右边缘返回时方向同步反转"), icon = HxIcons.MoveHorizontal)
                        }
                    }
                    SettingsDivider()
                    SettingsNavRow(ht("界面缩放"), subtitle = ht("统一调整界面和文字大小"), icon = HxIcons.Scaling, value = "${(uiScale * 100).toInt()}%", dropdown = true) { choice = "scale" }
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
        "topBlur" -> HxChoiceSheet(
            presentation = HxChoicePresentation.Settings,
            title = ht("顶栏模糊样式"),
            choices = listOf(
                HxChoice("progressive", ht("渐进式模糊"), ht("顶部更浓，向内容区域逐渐消散")),
                HxChoice("gaussian", ht("高斯模糊"), ht("整条顶栏使用均匀磨砂")),
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
            footer = ht("调整后应用到所有页面与弹窗。"),
        )
    }
}
