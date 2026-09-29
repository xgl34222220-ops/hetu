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
                vm.toast("已导出 $count 项设置")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "导出失败")
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val count = HetuSettingsBackup.restore(context, uri)
                vm.toast("已恢复 $count 项设置")
                vm.setAppearanceMode(prefs.getString("appearance", "system") ?: "system")
                vm.bumpSettings()
                revision++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                vm.toast(error.message ?: "恢复失败")
            }
        }
    }

    val profile = ProxyRuntimeProfile.load(prefs)

    HxPage(title = "设置", subtitle = if (recomposeTick >= 0) null else "", bottomPadding = bottomPadding) {
        item(key = "proxy") {
            HxSection("代理") {
                HxGroup {
                    HxNavRow("网络与分流", subtitle = "运行模式、DNS、IPv6、UDP/QUIC 与绕过", icon = Icons.Rounded.Tune, value = "${profile.core.label} · ${profile.mode.label}") {
                        nav.push(HxRoute.Network)
                    }
                    HxDivider()
                    HxNavRow("应用名单", subtitle = "按应用决定走代理还是直连", icon = Icons.Rounded.Apps, iconTint = Hx.colors.warn,
                        value = "${prefs.getStringSet("proxyAppPackages", emptySet()).orEmpty().size} 个") { nav.push(HxRoute.Apps) }
                    HxDivider()
                    HxNavRow("配置与订阅", subtitle = "导入 YAML、订阅链接、编辑配置", icon = Icons.Rounded.Description, iconTint = Hx.colors.good,
                        value = vm.state.config) { nav.push(HxRoute.Configs) }
                    HxDivider()
                    HxNavRow("广告过滤", subtitle = "规则源、黑白名单与拦截统计", icon = Icons.Rounded.Shield,
                        value = if (prefs.getBoolean("proxyAdblockChain", true)) "开启" else "关闭") { nav.push(HxRoute.Adblock) }
                    HxDivider()
                    HxNavRow("核心管理", subtitle = "查看版本、在线更新或导入 Mihomo", icon = Icons.Rounded.Memory, iconTint = Hx.colors.textMuted,
                        value = vm.coreVersion.ifBlank { profile.core.label }) { nav.push(HxRoute.Cores) }
                }
            }
        }
        item(key = "behavior") {
            HxSection("启动与通知") {
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
                    }, subtitle = "在通知栏显示网速，并提供重载/重启/停止按钮", icon = Icons.Rounded.Notifications, iconTint = Hx.colors.warn)
                    HxDivider()
                    HxSwitchRow("切换节点时断开旧连接", prefs.getBoolean("hetuCloseOnSwitch", true), {
                        prefs.edit().putBoolean("hetuCloseOnSwitch", it).apply(); revision++
                    }, subtitle = "让正在使用的应用立即走新节点", icon = Icons.Rounded.SwapHoriz, iconTint = Hx.colors.good)
                }
            }
        }
        item(key = "look") {
            HxSection("外观") {
                HxGroup {
                    HxNavRow("主题", icon = Icons.Rounded.DarkMode, iconTint = Hx.colors.textMuted, value = when (vm.appearance) {
                        "light" -> "浅色"
                        "dark" -> "深色"
                        else -> "跟随系统"
                    }) { choice = "appearance" }
                    if (Build.VERSION.SDK_INT >= 31) {
                        HxDivider()
                        HxSwitchRow("壁纸取色", vm.dynamicColor, { vm.setDynamic(it) }, subtitle = "使用系统壁纸的主题色", icon = Icons.Rounded.Palette)
                    }
                }
            }
        }
        item(key = "tools") {
            HxSection("工具") {
                HxGroup {
                    HxNavRow("Web 面板", subtitle = "在浏览器打开 metacubexd 管理当前核心", icon = Icons.Rounded.Web, enabled = vm.state.running) {
                        val port = vm.state.controllerPort
                        val secret = Uri.encode(prefs.getString("proxyControllerSecret", "").orEmpty())
                        val url = "https://metacubex.github.io/metacubexd/#/setup?hostname=127.0.0.1&port=$port&secret=$secret"
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (_: Exception) {
                            vm.toast("没有可用的浏览器")
                        }
                    }
                    HxDivider()
                    HxNavRow("测速站点", subtitle = ProxyLatencyTargets.load(prefs).joinToString(" · ") { it.name }, icon = Icons.Rounded.Speed, iconTint = Hx.colors.warn) {
                        editTargets = true
                    }
                    HxDivider()
                    HxNavRow("导出设置", subtitle = "备份代理、订阅选择与界面设置", icon = Icons.Rounded.Download, iconTint = Hx.colors.good) {
                        exporter.launch("hetu-settings.json")
                    }
                    HxDivider()
                    HxNavRow("导入设置", subtitle = "从备份文件恢复", icon = Icons.Rounded.UploadFile, iconTint = Hx.colors.good) {
                        importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                    HxDivider()
                    HxNavRow("恢复网络", subtitle = "停止代理并清理河图写入的网络规则", icon = Icons.Rounded.Restore, danger = true) {
                        confirmRecover = true
                    }
                }
            }
        }
        item(key = "about") {
            HxSection {
                HxGroup {
                    HxNavRow("关于河图", icon = Icons.Rounded.Info, iconTint = Hx.colors.textMuted, value = BuildConfig.VERSION_NAME) { nav.push(HxRoute.About) }
                }
            }
        }
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
            HxSection("核心与模式") {
                HxGroup {
                    HxNavRow("核心", icon = Icons.Rounded.Memory, value = profile.core.label) { choice = "core" }
                    HxDivider()
                    HxNavRow("透明代理模式", subtitle = modeDescription(profile.mode), icon = Icons.Rounded.Route, value = profile.mode.label) { choice = "mode" }
                }
            }
        }
        item(key = "dns") {
            HxSection("DNS 与协议") {
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
            HxSection("分流") {
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
            HxSection("共享与安全") {
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
