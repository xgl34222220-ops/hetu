package io.github.xgl34222220.hetu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    var editTargets by remember { mutableStateOf(false) }
    var confirmRecover by remember { mutableStateOf(false) }
    val recomposeTick = revision + vm.settingsRevision

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            ProxyStatusNotificationService.setEnabled(context, true)
            revision++
        } else vm.toast("没有通知权限，无法显示状态通知")
    }
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
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> restoreUri = uri }

    val profile = ProxyRuntimeProfile.load(prefs)

    fun open(type: Class<out android.app.Activity>) { context.startActivity(Intent(context, type)) }
    val c = Hx.colors

    val stagger = rememberHxStagger()
    HxPage(title = "设置", scrollToTopSignal = vm.reselect, subtitle = if (recomposeTick >= 0) null else "", bottomPadding = bottomPadding) {
        item(key = "proxy") {
            HxSection("代理与网络", modifier = Modifier.hxEnter(stagger, 0)) {
                HxGroup {
                    HxNavRow("网络与分流", subtitle = "运行模式、DNS、IPv6、UDP/QUIC、应用范围", icon = Icons.Rounded.Tune, value = "${profile.core.label} · ${profile.mode.label}") {
                        nav.push(HxRoute.Network)
                    }
                    HxDivider()
                    HxNavRow("高级代理配置", subtitle = "运行预检、Kill Switch、启动配置、恢复网络", icon = Icons.Rounded.HealthAndSafety, iconTint = c.good) {
                        open(ProxyAdvancedSettingsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("网络匹配", subtitle = "按 Wi‑Fi / SSID / 移动网络自动启停", icon = Icons.Rounded.Wifi) {
                        open(ProxyNetworkAutomationActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("共享网络", subtitle = "热点、USB 共享与下游设备 MAC 直连", icon = Icons.Rounded.WifiTethering, iconTint = c.good) {
                        open(ProxySharedNetworkSettingsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("绕过规则", subtitle = "排除指定网段与网络接口", icon = Icons.Rounded.AltRoute, iconTint = c.bad) {
                        open(ProxyBypassRulesActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("国内地址分流", subtitle = "国内 IPv4 / IPv6 自动直连", icon = Icons.Rounded.Public, iconTint = c.warn) {
                        open(ProxyCnIpSettingsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("端口细则", subtitle = "透明代理、DNS 与控制器端口", icon = Icons.Rounded.Cable, iconTint = c.textMuted,
                        value = "${MihomoStartupConfig.TPROXY_PORT} / ${MihomoStartupConfig.REDIRECT_PORT}") { choice = "ports" }
                }
            }
        }
        item(key = "data") {
            HxSection("订阅与数据", modifier = Modifier.hxEnter(stagger, 1)) {
                HxGroup {
                    HxNavRow("配置与订阅", subtitle = "导入 YAML、订阅链接、编辑配置", icon = Icons.Rounded.Description, iconTint = c.good,
                        value = vm.state.config) { nav.push(HxRoute.Configs) }
                    HxDivider()
                    HxNavRow("订阅工作台", subtitle = "订阅健康检查、YAML 大纲与完整编辑工具", icon = Icons.Rounded.CloudDownload) {
                        open(ProxySubscriptionActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("Sub-Store", subtitle = "订阅处理与配置导入", icon = Icons.Rounded.CloudSync) {
                        open(ProxySubStoreActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("广告过滤", subtitle = "规则源、黑白名单与拦截统计", icon = Icons.Rounded.Shield,
                        value = if (prefs.getBoolean("proxyAdblockChain", true)) "开启" else "关闭") { nav.push(HxRoute.Adblock) }
                    HxDivider()
                    HxNavRow("内核管理", subtitle = "下载、更新、导入与删除各核心", icon = Icons.Rounded.Memory, iconTint = c.textMuted,
                        value = vm.coreVersion.ifBlank { profile.core.label }) { nav.push(HxRoute.Cores) }
                }
            }
        }
        item(key = "panel") {
            HxSection("代理行为", modifier = Modifier.hxEnter(stagger, 2)) {
                HxGroup {
                    // Search/sort/layout/API/icon controls live on the Proxy page itself.
                    // Keep only preferences that are not duplicated there.
                    HxSwitchRow("切换节点后断开旧连接", prefs.getBoolean("proxySelectorDisconnectOnSelect", false), {
                        prefs.edit().putBoolean("proxySelectorDisconnectOnSelect", it).apply(); revision++
                    }, subtitle = "只关闭经过当前策略组的旧连接", icon = Icons.Rounded.SwapHoriz, iconTint = c.good)
                    HxDivider()
                    HxSwitchRow("启动后进入代理页", prefs.getBoolean("startOnPanel", false), {
                        prefs.edit().putBoolean("startOnPanel", it).apply(); revision++
                    }, icon = Icons.Rounded.Dashboard)
                    HxDivider()
                    HxNavRow("延迟自动刷新", subtitle = "首页站点延迟定时测速", icon = Icons.Rounded.Sync, iconTint = c.textMuted,
                        value = prefs.getInt("latencyAutoRefreshSeconds", 0).let { if (it == 30 || it == 60) "$it 秒" else "关闭" }) { choice = "latency" }
                    HxDivider()
                    HxNavRow("测速站点", subtitle = ProxyLatencyTargets.load(prefs).joinToString(" · ") { it.name }, icon = Icons.Rounded.Speed, iconTint = c.warn) {
                        editTargets = true
                    }
                }
            }
        }
        item(key = "services") {
            HxSection("服务与控制", modifier = Modifier.hxEnter(stagger, 3)) {
                HxGroup {
                    HxSwitchRow("开机自动启动", prefs.getBoolean("proxyRootAutoStart", false), {
                        prefs.edit().putBoolean("proxyRootAutoStart", it).apply(); revision++
                    }, subtitle = "开机后恢复上次运行中的代理", icon = Icons.Rounded.RestartAlt)
                    HxDivider()
                    HxSwitchRow("状态通知", prefs.getBoolean(ProxyStatusNotificationService.PREF_ENABLED, false), { on ->
                        if (on && Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            ProxyStatusNotificationService.setEnabled(context, on)
                            revision++
                        }
                    }, subtitle = "通知栏显示网速，并提供快捷按钮", icon = Icons.Rounded.Notifications, iconTint = c.warn)
                    HxDivider()
                    HxNavRow("通知与快捷控制", subtitle = "通知模板、按钮、刷新间隔与系统磁贴", icon = Icons.Rounded.Notifications, iconTint = c.warn) {
                        open(ProxyNotificationSettingsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("脚本", subtitle = "脚本管理与启动、停止钩子", icon = Icons.Rounded.Terminal, iconTint = c.textMuted) {
                        open(ProxyScriptsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("日志管理", subtitle = "筛选、搜索、暂停和导出运行日志", icon = Icons.Rounded.Article) {
                        open(ProxyLogViewerActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("启动配置", subtitle = "查看和校验当前运行配置", icon = Icons.Rounded.Description, iconTint = c.textMuted) {
                        open(ProxyStartupConfigActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("运行文件", subtitle = "浏览和编辑 Root 运行目录", icon = Icons.Rounded.Inventory2, iconTint = c.textMuted) {
                        open(ReferenceFileManagerActivity::class.java)
                    }
                }
            }
        }
        item(key = "panels") {
            HxSection("Web 面板", modifier = Modifier.hxEnter(stagger, 4)) {
                HxGroup {
                    HxNavRow("Web 面板管理", subtitle = "管理本地与远程面板", icon = Icons.Rounded.Web) {
                        open(ProxyWebPanelsActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("本地 WebUI", subtitle = "在应用内打开 Mihomo 管理面板", icon = Icons.Rounded.OpenInBrowser, enabled = vm.state.running) {
                        open(ProxyLocalWebUiActivity::class.java)
                    }
                    HxDivider()
                    HxNavRow("在浏览器打开 metacubexd", subtitle = "使用系统浏览器管理当前核心", icon = Icons.Rounded.OpenInNew, iconTint = c.textMuted, enabled = vm.state.running) {
                        val port = vm.state.controllerPort
                        val secret = Uri.encode(prefs.getString("proxyControllerSecret", "").orEmpty())
                        val url = "https://metacubex.github.io/metacubexd/#/setup?hostname=127.0.0.1&port=$port&secret=$secret"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            vm.toast("没有可用的浏览器")
                        }
                    }
                }
            }
        }
        item(key = "look") {
            HxSection("界面", modifier = Modifier.hxEnter(stagger, 5)) {
                HxGroup {
                    HxNavRow("主题", icon = Icons.Rounded.DarkMode, iconTint = c.textMuted, value = when (vm.appearance) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    }) { choice = "appearance" }
                    if (Build.VERSION.SDK_INT >= 31) {
                        HxDivider()
                        HxSwitchRow("壁纸取色", vm.dynamicColor, { vm.setDynamic(it) }, subtitle = "使用系统壁纸的主题色", icon = Icons.Rounded.Palette)
                    }
                    HxDivider()
                    HxSwitchRow("触感反馈", prefs.getBoolean(HetuHaptics.PREF_KEY, true), {
                        prefs.edit().putBoolean(HetuHaptics.PREF_KEY, it).apply(); revision++
                    }, subtitle = "点按、切换与操作结果的振动反馈", icon = Icons.Rounded.Vibration, iconTint = c.textMuted)
                    HxDivider()
                    HxSwitchRow("模糊效果", prefs.getBoolean("enableBlur", true), {
                        prefs.edit().putBoolean("enableBlur", it).apply(); vm.reloadAppearance(); revision++
                    }, subtitle = "底栏、顶栏与工具页面的毛玻璃效果", icon = Icons.Rounded.BlurOn, iconTint = c.textMuted)
                    HxDivider()
                    HxNavRow("更多主题选项", subtitle = "强调色、配色风格、纯黑深色、界面缩放、语言", icon = Icons.Rounded.ColorLens) {
                        open(ThemeSettingsActivity::class.java)
                    }
                }
            }
        }
        item(key = "backup") {
            HxSection("下载与备份", modifier = Modifier.hxEnter(stagger, 6)) {
                HxGroup {
                    HxNavRow("下载镜像", subtitle = "核心、规则等下载经由镜像前缀", icon = Icons.Rounded.Download, iconTint = c.good,
                        value = if (prefs.getBoolean("downloadMirrorEnabled", false)) prefs.getString("downloadMirrorPrefix", "").orEmpty().ifBlank { "已开启" } else "关闭") {
                        choice = "mirror"
                    }
                    HxDivider()
                    HxNavRow("导出备份", subtitle = "导出应用设置与配置库", icon = Icons.Rounded.UploadFile, iconTint = c.good) {
                        exporter.launch("Hetu-backup.json")
                    }
                    HxDivider()
                    HxNavRow("恢复备份", subtitle = "恢复前确认，不会卸载或清除数据", icon = Icons.Rounded.Restore, iconTint = c.good) {
                        importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }
                    HxDivider()
                    HxNavRow("恢复网络", subtitle = "停止代理并清理河图写入的网络规则", icon = Icons.Rounded.LinkOff, danger = true) {
                        confirmRecover = true
                    }
                }
            }
        }
        item(key = "about") {
            HxSection("关于", modifier = Modifier.hxEnter(stagger, 7)) {
                HxGroup {
                    HxNavRow("关于河图", icon = Icons.Rounded.Info, iconTint = c.textMuted, value = BuildConfig.VERSION_NAME) { nav.push(HxRoute.About) }
                    HxDivider()
                    HxNavRow("开源库", icon = Icons.Rounded.LibraryBooks, iconTint = c.textMuted) { open(ProxyAboutLibrariesActivity::class.java) }
                    HxDivider()
                    HxNavRow("赞助与支持", icon = Icons.Rounded.Favorite, iconTint = c.bad) { open(ProxyAboutSponsorshipActivity::class.java) }
                }
            }
        }
    }

    when (choice) {
        "api" -> ApiSettingsSheet(vm, onDismiss = { choice = null })
        "latency" -> HxChoiceSheet(
            title = "延迟自动刷新",
            choices = listOf(HxChoice("0", "关闭"), HxChoice("30", "每 30 秒"), HxChoice("60", "每 60 秒")),
            selected = prefs.getInt("latencyAutoRefreshSeconds", 0).let { if (it == 30 || it == 60) it.toString() else "0" },
            onPick = { prefs.edit().putInt("latencyAutoRefreshSeconds", it.toInt()).apply(); revision++; choice = null },
            onDismiss = { choice = null },
            footer = "只测试首页的三个站点，不会对全部节点测速。",
        )
        "ports" -> HxTextSheet(
            title = "端口细则",
            text = listOf(
                "TPROXY 透明代理    ${MihomoStartupConfig.TPROXY_PORT}",
                "Redirect 转发      ${MihomoStartupConfig.REDIRECT_PORT}",
                "DNS 监听           ${MihomoStartupConfig.DNS_PORT}",
                "控制器             127.0.0.1:${vm.state.controllerPort}",
                "出口探针           127.0.0.1:${MihomoStartupConfig.egressProbePort(vm.state.controllerPort)}",
                "",
                "以上端口仅供河图私有运行副本使用，订阅配置里的 mixed-port / socks-port 等不会被继承。",
            ).joinToString("\n"),
            onDismiss = { choice = null },
        )
        "mirror" -> HxFormDialog(
            title = "下载镜像",
            message = "填写镜像前缀，例如 https://ghfast.top/ ，留空表示关闭。仅使用你信任的镜像；不会附加控制器密钥。",
            fields = listOf(HxField("镜像前缀", prefs.getString("downloadMirrorPrefix", "").orEmpty(), placeholder = "https://")),
            validate = { v -> if (v[0].isNotBlank() && !v[0].startsWith("https://") && !v[0].startsWith("http://")) "请填写 http/https 地址" else null },
            onConfirm = { v ->
                prefs.edit().putBoolean("downloadMirrorEnabled", v[0].isNotBlank()).putString("downloadMirrorPrefix", v[0]).apply()
                revision++
                choice = null
            },
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
                        vm.bumpSettings()
                        revision++
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

    if (choice == "appearance") {
        HxChoiceSheet(
            title = "主题",
            choices = listOf(HxChoice("system", "跟随系统"), HxChoice("light", "浅色"), HxChoice("dark", "深色")),
            selected = vm.appearance,
            onPick = { vm.setAppearanceMode(it); choice = null },
            onDismiss = { choice = null },
        )
    }

    if (editTargets) {
        val targets = ProxyLatencyTargets.load(prefs)
        HxFormDialog(
            title = "测速站点",
            message = "首页的三个站点延迟，通过当前网络（含代理）访问测量。",
            fields = targets.flatMapIndexed { i, t ->
                listOf(HxField("站点 ${i + 1} 名称", t.name), HxField("站点 ${i + 1} 地址", t.url, placeholder = "https://"))
            },
            validate = { values ->
                values.chunked(2).map { ProxyLatencyTarget(it[0], it[1]) }.firstNotNullOfOrNull { ProxyLatencyTargets.validate(it) }
            },
            onConfirm = { values ->
                ProxyLatencyTargets.save(prefs, values.chunked(2).map { ProxyLatencyTarget(it[0], it[1]) })
                editTargets = false
                vm.toast("已保存")
            },
            onDismiss = { editTargets = false },
        )
    }

    if (confirmRecover) {
        HxConfirmDialog(
            title = "恢复网络？",
            message = "将停止代理，并回滚河图添加的 iptables / 路由规则。用于网络异常时的紧急恢复。",
            confirmLabel = "恢复",
            danger = true,
            onConfirm = {
                confirmRecover = false
                scope.launch {
                    try {
                        vm.controller.stop()
                        vm.toast("已停止代理并恢复网络")
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        vm.toast(error.message ?: "恢复失败")
                    }
                    vm.refreshNow()
                }
            },
            onDismiss = { confirmRecover = false },
        )
    }
}

/* ------------------------------------------------------------------ */
/*  Network & routing                                                   */
/* ------------------------------------------------------------------ */

@Composable
internal fun NetworkSettingsScreen(vm: HetuViewModel) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val prefs = vm.prefs
    var revision by remember { mutableIntStateOf(0) }
    var choice by remember { mutableStateOf<String?>(null) }
    var editCidrs by remember { mutableStateOf(false) }
    val recomposeTick = revision + vm.settingsRevision

    val profile = ProxyRuntimeProfile.load(prefs)
    fun changed(key: String) {
        ProxyRuntimeSettings.markDirty(prefs, key)
        revision++
        vm.bumpSettings()
    }
    fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply(); changed(key) }
    fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); changed(key) }

    val pending = vm.state.running && ProxyRuntimeSettings.pending(true, prefs)
    val capability = profile.capability()

    val stagger = rememberHxStagger()
    HxPage(title = "网络与分流", subtitle = if (recomposeTick >= 0) "修改后需重启代理生效" else null, onBack = { nav.pop() }) {
        if (pending) {
            item(key = "pending") {
                Column(Modifier.padding(horizontal = Hx.gutter).padding(bottom = 16.dp)) {
                    HxBanner(
                        if (vm.operation == HxRunOp.Restart) vm.operationText.ifBlank { "正在重启…" } else "设置已修改，重启代理后生效",
                        tone = HxTone.Warn,
                        actionLabel = if (vm.operation == null) "立即重启" else null,
                        onAction = vm::restart,
                    )
                }
            }
        }
        item(key = "engine") {
            HxSection("核心与模式", modifier = Modifier.hxEnter(stagger, 0)) {
                HxGroup {
                    HxNavRow("核心", icon = Icons.Rounded.Memory, value = profile.core.label) { choice = "core" }
                    HxDivider()
                    HxNavRow("透明代理模式", subtitle = modeDescription(profile.mode), icon = Icons.Rounded.Route, value = profile.mode.label) { choice = "mode" }
                }
            }
        }
        item(key = "dns") {
            HxSection("DNS 与协议", modifier = Modifier.hxEnter(stagger, 1)) {
                HxGroup {
                    HxNavRow("DNS 劫持", subtitle = "让系统 DNS 交给 Mihomo 解析，广告过滤依赖此项", icon = Icons.Rounded.Dns, value = when (profile.dnsHijack) {
                        ProxyRuntimeProfile.DnsHijack.OFF -> "关闭"
                        ProxyRuntimeProfile.DnsHijack.REDIRECT -> "Redirect"
                        else -> "自动"
                    }, enabled = capability.dnsHijack) { choice = "dns" }
                    HxDivider()
                    HxNavRow("IPv6", icon = Icons.Rounded.Public, iconTint = Hx.colors.good, value = when (profile.ipv6) {
                        ProxyRuntimeProfile.Ipv6.BYPASS -> "不进核心"
                        ProxyRuntimeProfile.Ipv6.STRICT -> "严格防泄漏"
                        ProxyRuntimeProfile.Ipv6.DISABLE -> "禁用"
                        else -> "启用"
                    }) { choice = "ipv6" }
                    HxDivider()
                    HxSwitchRow("TCP 接管", profile.tcp, { putBool("proxyTcp", it) }, icon = Icons.Rounded.SwapHoriz, enabled = capability.tcp)
                    HxDivider()
                    HxSwitchRow("UDP 接管", profile.udp, { putBool("proxyUdp", it) }, subtitle = "游戏、语音通话与 QUIC", icon = Icons.Rounded.Bolt, iconTint = Hx.colors.warn, enabled = capability.udp)
                    HxDivider()
                    HxSwitchRow("阻断 QUIC", profile.quicBlocked, { putBool("proxyQuicBlocked", it) }, subtitle = "拦截 UDP 443，迫使应用回落到 TCP", icon = Icons.Rounded.Speed, iconTint = Hx.colors.warn, enabled = capability.quicControl)
                }
            }
        }
        item(key = "routing") {
            HxSection("分流", modifier = Modifier.hxEnter(stagger, 2)) {
                HxGroup {
                    HxNavRow("应用范围", icon = Icons.Rounded.Apps, iconTint = Hx.colors.warn, value = when (profile.appScope) {
                        ProxyRuntimeProfile.AppScope.WHITELIST -> "仅所选应用代理"
                        ProxyRuntimeProfile.AppScope.BLACKLIST -> "所选应用直连"
                        else -> "不区分应用"
                    }, enabled = capability.appFilter) { choice = "scope" }
                    HxDivider()
                    HxNavRow("应用名单", icon = Icons.Rounded.Apps, iconTint = Hx.colors.warn,
                        value = "${prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().size} 个") { nav.push(HxRoute.Apps) }
                    HxDivider()
                    HxSwitchRow("中国 IP 直连", profile.cnIpDirect, { putBool("proxyCnIpDirect", it) }, subtitle = "内置 CN IP 库，国内地址不经过代理", icon = Icons.Rounded.Public)
                    HxDivider()
                    HxNavRow("绕过网段", subtitle = "这些 CIDR 不进入透明代理", icon = Icons.Rounded.Cable, iconTint = Hx.colors.textMuted,
                        value = "${prefs.getStringSet("proxyBypassCidrs", emptySet()).orEmpty().size} 条", enabled = capability.cidrBypass) { editCidrs = true }
                }
            }
        }
        item(key = "safety") {
            HxSection("共享与安全", modifier = Modifier.hxEnter(stagger, 3)) {
                HxGroup {
                    HxSwitchRow("接管热点/USB 共享", prefs.getBoolean("proxySharedNetwork", false), { putBool("proxySharedNetwork", it) },
                        subtitle = "让连接本机热点的设备也走代理", icon = Icons.Rounded.WifiTethering, iconTint = Hx.colors.good, enabled = capability.sharedNetwork)
                    HxDivider()
                    HxSwitchRow("Kill Switch", prefs.getBoolean("proxyKillSwitch", false), { putBool("proxyKillSwitch", it) },
                        subtitle = "核心异常退出时阻断流量，防止直连泄漏", icon = Icons.Rounded.GppGood, iconTint = Hx.colors.bad)
                }
            }
        }
        if (!capability.available && capability.reason.isNotBlank()) {
            item(key = "cap") {
                Column(Modifier.padding(horizontal = Hx.gutter)) { HxBanner(capability.reason, tone = HxTone.Warn) }
            }
        }
    }

    when (choice) {
        "core" -> HxChoiceSheet(
            title = "核心",
            choices = listOf(ProxyRuntimeProfile.Core.MIHOMO, ProxyRuntimeProfile.Core.MIHOMO_SMART).map { core ->
                val installed = core == ProxyRuntimeProfile.Core.MIHOMO || ProxyCoreStore(context).installed(core)
                HxChoice(core.id, core.label, if (installed) null else "尚未安装，请先在「核心管理」下载", enabled = installed)
            },
            selected = profile.core.id,
            onPick = { putString("proxyBaseCore", it); choice = null },
            onDismiss = { choice = null },
        )
        "mode" -> HxChoiceSheet(
            title = "透明代理模式",
            choices = ProxyRuntimeProfile.Mode.values()
                .filter { ProxyRuntimeProfile.capability(profile.core, it).available }
                .map { HxChoice(it.id, it.label, modeDescription(it)) },
            selected = profile.mode.id,
            onPick = { putString("proxyBaseMode", it); choice = null },
            onDismiss = { choice = null },
        )
        "dns" -> HxChoiceSheet(
            title = "DNS 劫持",
            choices = listOf(
                HxChoice("tproxy", "自动", "推荐。系统 DNS 由 Mihomo 解析"),
                HxChoice("redirect", "Redirect", "通过 REDIRECT 转发到本地 ${MihomoStartupConfig.DNS_PORT}"),
                HxChoice("off", "关闭", "不接管 DNS；广告过滤与域名分流可能失效"),
            ),
            selected = profile.dnsHijack.id,
            onPick = { putString("proxyDnsHijack", it); choice = null },
            onDismiss = { choice = null },
        )
        "ipv6" -> HxChoiceSheet(
            title = "IPv6",
            choices = listOf(
                HxChoice("enable", "启用", "IPv6 流量同样进入代理"),
                HxChoice("bypass", "不进核心", "IPv6 直连，不经过代理"),
                HxChoice("strict", "严格防泄漏", "仅用 IPv4，拦截 IPv6 防止绕过代理"),
                HxChoice("disable", "禁用", "关闭本机 IPv6"),
            ),
            selected = profile.ipv6.id,
            onPick = { putString("proxyBaseIpv6", it); choice = null },
            onDismiss = { choice = null },
        )
        "scope" -> HxChoiceSheet(
            title = "应用范围",
            choices = listOf(
                HxChoice("core", "不区分应用", "所有应用由配置规则决定"),
                HxChoice("blacklist", "所选应用直连", "名单中的应用绕过代理"),
                HxChoice("whitelist", "仅所选应用代理", "只有名单中的应用走代理"),
            ),
            selected = profile.appScope.id,
            onPick = { putString("proxyAppScope", it); choice = null },
            onDismiss = { choice = null },
        )
    }

    if (editCidrs) {
        val current = prefs.getStringSet("proxyBypassCidrs", emptySet()).orEmpty().sorted().joinToString("\n")
        HxFormDialog(
            title = "绕过网段",
            message = "每行一个 IPv4/IPv6 CIDR，例如 10.0.0.0/8、fd00::/8",
            fields = listOf(HxField("CIDR 列表", current, singleLine = false)),
            validate = { values ->
                val bad = values[0].lines().map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull { !looksLikeCidr(it) }
                if (bad != null) "格式不正确：$bad" else null
            },
            onConfirm = { values ->
                val set = values[0].lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                prefs.edit().putStringSet("proxyBypassCidrs", set).apply()
                changed("proxyBypassCidrs")
                editCidrs = false
            },
            onDismiss = { editCidrs = false },
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