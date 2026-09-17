from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "android-app/app/build.gradle.kts"
STARTUP = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/MihomoStartupConfig.java"
SCRIPT = ROOT / "android-app/app/src/main/assets/proxy-root-v3.sh"
ROOT_MANAGER = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/RootProxyManager.java"
CONTROLLER = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyComposeController.kt"
NETWORK_MATCH = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyNetworkMatchService.java"
INSPECTOR = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyRuntimeInspector.kt"
DASHBOARD = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyDashboardRepository.kt"
UI = ROOT / "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"test42: missing anchor: {label}")
    return text.replace(old, new, 1)

# ---------------------------------------------------------------------------
# Version
# ---------------------------------------------------------------------------
build = BUILD.read_text(encoding="utf-8")
build = replace_once(build, 'versionCode = 441', 'versionCode = 442', 'versionCode')
build = replace_once(build, 'versionName = "0.4.0-test.41"', 'versionName = "0.4.0-test.42"', 'versionName')
BUILD.write_text(build, encoding="utf-8")

# ---------------------------------------------------------------------------
# Runtime: do NOT use Mihomo routing-mark on Android.
# SO_MARK replaces Android's full fwmark/netId and can strand DIRECT/DoH sockets.
# Instead keep the kernel-selected Android network mark intact and exempt UID 0
# from every local OUTPUT interception chain. The Mihomo core runs as root.
# ---------------------------------------------------------------------------
startup = STARTUP.read_text(encoding="utf-8")
startup = replace_once(
    startup,
    '        override.append("routing-mark: ").append(OUTBOUND_ROUTING_MARK).append(\'\\n\');\n',
    '        // Android netd owns the socket fwmark/netId. Do not overwrite the full SO_MARK here;\n'
    '        // the Root controller exempts the root-owned Mihomo process from OUTPUT interception.\n',
    'routing-mark override',
)
STARTUP.write_text(startup, encoding="utf-8")

script = SCRIPT.read_text(encoding="utf-8")
replacements = [
    (
        '  iptables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns iptables mangle "$MOUT" "$S" "$UIDS" || return 1\n',
        '  iptables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1\n'
        '  iptables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1\n'
        '  iface_out iptables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns iptables mangle "$MOUT" "$S" "$UIDS" || return 1\n',
        'IPv4 root egress bypass',
    ),
    (
        '  ip6tables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns ip6tables mangle "$MOUT" "$S" "$UIDS" || return 1\n',
        '  ip6tables -t mangle -A "$MOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1\n'
        '  ip6tables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN || return 1\n'
        '  iface_out ip6tables mangle "$MOUT" "$IFACES" || return 1; blacklist_returns ip6tables mangle "$MOUT" "$S" "$UIDS" || return 1\n',
        'IPv6 root egress bypass',
    ),
    (
        '  iptables -t nat -N "$NOUT" || return 1; iptables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables nat "$NOUT" "$IFACES" || return 1;',
        '  iptables -t nat -N "$NOUT" || return 1; iptables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iptables -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out iptables nat "$NOUT" "$IFACES" || return 1;',
        'IPv4 redirect root bypass',
    ),
    (
        '  ip6tables -t nat -N "$NOUT" || return 1; ip6tables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables nat "$NOUT" "$IFACES" || return 1;',
        '  ip6tables -t nat -N "$NOUT" || return 1; ip6tables -t nat -A "$NOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; ip6tables -t nat -A "$NOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out ip6tables nat "$NOUT" "$IFACES" || return 1;',
        'IPv6 redirect root bypass',
    ),
    (
        '  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; iptables -t nat -N "$DNSOUT" || return 1; iptables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns iptables nat "$DNSOUT" "$S" "$UIDS" || return 1\n',
        '  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; iptables -t nat -N "$DNSOUT" || return 1; iptables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iptables -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out iptables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns iptables nat "$DNSOUT" "$S" "$UIDS" || return 1\n',
        'IPv4 DNS root bypass',
    ),
    (
        '  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; v6active || return 0; ip6tables -t nat -N "$DNSOUT" || return 1; ip6tables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns ip6tables nat "$DNSOUT" "$S" "$UIDS" || return 1\n',
        '  P="$1"; S="$2"; UIDS="$3"; SHARE="$4"; IFACES="$5"; v6active || return 0; ip6tables -t nat -N "$DNSOUT" || return 1; ip6tables -t nat -A "$DNSOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; ip6tables -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out ip6tables nat "$DNSOUT" "$IFACES" || return 1; blacklist_returns ip6tables nat "$DNSOUT" "$S" "$UIDS" || return 1\n',
        'IPv6 DNS root bypass',
    ),
    (
        '  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; iptables -t filter -N "$QUICOUT" || return 1; iptables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out iptables filter "$QUICOUT" "$IFACES" || return 1;',
        '  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; iptables -t filter -N "$QUICOUT" || return 1; iptables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iptables -t filter -A "$QUICOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out iptables filter "$QUICOUT" "$IFACES" || return 1;',
        'IPv4 QUIC root bypass',
    ),
    (
        '  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; v6active || return 0; ip6tables -t filter -N "$QUICOUT" || return 1; ip6tables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; iface_out ip6tables filter "$QUICOUT" "$IFACES" || return 1;',
        '  S="$1"; UIDS="$2"; SHARE="$3"; CIDRS="$4"; IFACES="$5"; v6active || return 0; ip6tables -t filter -N "$QUICOUT" || return 1; ip6tables -t filter -A "$QUICOUT" -m mark --mark "$BYPASS_MARK/$BYPASS_MASK" -j RETURN || return 1; ip6tables -t filter -A "$QUICOUT" -m owner --uid-owner 0 -j RETURN || return 1; iface_out ip6tables filter "$QUICOUT" "$IFACES" || return 1;',
        'IPv6 QUIC root bypass',
    ),
]
for old, new, label in replacements:
    script = replace_once(script, old, new, label)
SCRIPT.write_text(script, encoding="utf-8")

# ---------------------------------------------------------------------------
# Java health check: controller must actually be ready and DIRECT egress must work.
# This prevents a fake green "running" state when transparent routing has black-holed
# the core's own outbound sockets.
# ---------------------------------------------------------------------------
manager = ROOT_MANAGER.read_text(encoding="utf-8")
manager = replace_once(
    manager,
    '            new MihomoControllerClient(context).waitReady(4500);\n',
    '            MihomoControllerClient controller=new MihomoControllerClient(context);\n'
    '            if(!controller.waitReady(4500))throw new IOException("Mihomo 控制接口未在启动窗口内就绪");\n'
    '            try{controller.delay("DIRECT","https://connectivitycheck.platform.hicloud.com/generate_204","200-399");}\n'
    '            catch(Exception firstProbe){\n'
    '                try{controller.delay("DIRECT","https://www.gstatic.com/generate_204","200-399");}\n'
    '                catch(Exception secondProbe){throw new IOException("Mihomo 已启动，但 DIRECT 出站不可用；已回滚网络规则",secondProbe);}\n'
    '            }\n',
    'startup controller/direct health check',
)
ROOT_MANAGER.write_text(manager, encoding="utf-8")

# ---------------------------------------------------------------------------
# Manual session ownership: network automation may start/stop only sessions it owns.
# A manual Start must not be stopped later by an old SSID/BSSID automation callback.
# ---------------------------------------------------------------------------
controller = CONTROLLER.read_text(encoding="utf-8")
controller = replace_once(
    controller,
    '        root.start(profile) { onProgress(it) }\n    }\n\n    suspend fun stop(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) { root.stop { onProgress(it) } }\n',
    '        val previousOwner = prefs.getString("proxyRootSessionOwner", "").orEmpty()\n'
    '        prefs.edit().putString("proxyRootSessionOwner", "manual").apply()\n'
    '        try {\n'
    '            root.start(profile) { onProgress(it) }\n'
    '        } catch (error: Exception) {\n'
    '            if (previousOwner.isBlank()) prefs.edit().remove("proxyRootSessionOwner").apply()\n'
    '            else prefs.edit().putString("proxyRootSessionOwner", previousOwner).apply()\n'
    '            throw error\n'
    '        }\n'
    '    }\n\n'
    '    suspend fun stop(onProgress: (String) -> Unit = {}) = withContext(Dispatchers.IO) {\n'
    '        val result = root.stop { onProgress(it) }\n'
    '        prefs.edit().remove("proxyRootSessionOwner").apply()\n'
    '        result\n'
    '    }\n',
    'manual session ownership',
)
CONTROLLER.write_text(controller, encoding="utf-8")

network = NETWORK_MATCH.read_text(encoding="utf-8")
network = replace_once(
    network,
    'RootProxyManager root=new RootProxyManager(getApplicationContext());if("start".equals(action)){if(!root.status().optBoolean("running",false))root.start(ProxyRuntimeProfile.load(prefs));}else if("stop".equals(action)){if(root.status().optBoolean("running",false))root.stop();}',
    'RootProxyManager root=new RootProxyManager(getApplicationContext());boolean running=root.status().optBoolean("running",false);String owner=prefs.getString("proxyRootSessionOwner","");if("start".equals(action)){if(!running&&!"manual".equals(owner)){root.start(ProxyRuntimeProfile.load(prefs));prefs.edit().putString("proxyRootSessionOwner","automation").apply();}}else if("stop".equals(action)){if(running&&"automation".equals(owner)){root.stop();prefs.edit().remove("proxyRootSessionOwner").apply();}}',
    'network automation ownership',
)
NETWORK_MATCH.write_text(network, encoding="utf-8")

# ---------------------------------------------------------------------------
# Logs: include watchdog + last-crash so a future screenshot shows why it stopped.
# ---------------------------------------------------------------------------
inspector = INSPECTOR.read_text(encoding="utf-8")
inspector = replace_once(
    inspector,
    '        val command = "tail -n 160 /data/adb/bichen/proxy/run/core.log 2>&1 || true"\n',
    '        val command = "echo \'--- core.log ---\'; tail -n 140 /data/adb/bichen/proxy/run/core.log 2>&1 || true; echo \'--- watchdog.log ---\'; tail -n 30 /data/adb/bichen/proxy/run/watchdog.log 2>/dev/null || true; echo \'--- last-crash ---\'; cat /data/adb/bichen/proxy/run/last-crash 2>/dev/null || true"\n',
    'runtime log diagnostics',
)
INSPECTOR.write_text(inspector, encoding="utf-8")

# ---------------------------------------------------------------------------
# Dashboard repository: single-item refresh must surface errors, not silently swallow.
# ---------------------------------------------------------------------------
dashboard = DASHBOARD.read_text(encoding="utf-8")
dashboard = replace_once(
    dashboard,
    '''    suspend fun refreshProvider(name: String): DashboardProviderUi? = withContext(Dispatchers.IO) {
        try { api.updateProxyProvider(name) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { }
        remoteProviders(api.proxyProviders()).firstOrNull { it.name == name }
    }
''',
    '''    suspend fun refreshProvider(name: String): DashboardProviderUi? = withContext(Dispatchers.IO) {
        api.updateProxyProvider(name)
        remoteProviders(api.proxyProviders()).firstOrNull { it.name == name }
    }

    suspend fun refreshRuleSet(name: String): DashboardRuleSetUi? = withContext(Dispatchers.IO) {
        api.updateRuleProvider(name)
        parseRuleSets(api.ruleProviders()).firstOrNull { it.name == name }
    }
''',
    'single provider/ruleset refresh',
)
DASHBOARD.write_text(dashboard, encoding="utf-8")

# ---------------------------------------------------------------------------
# Compose UI: instant desired-state paint + immediate real refresh, dedicated latency
# hit target, per-item spinners, capsule feedback, and bottom-dock visual language.
# ---------------------------------------------------------------------------
ui = UI.read_text(encoding="utf-8")
ui = replace_once(
    ui,
    '    var state by remember { mutableStateOf(ProxyComposeState()) }\n',
    '    var state by remember { mutableStateOf(ProxyComposeState(running = prefs.getBoolean("proxyRootWanted", false))) }\n',
    'optimistic persisted runtime state',
)
ui = replace_once(
    ui,
    '''    LaunchedEffect(Unit) {
        runCatching { repo.ensureIcons() }
        refresh()
        while (true) {
            delay(2200)
            refresh()
        }
    }
''',
    '''    LaunchedEffect(Unit) {
        // Runtime state is more important than decorative icon downloads. Reopening the app
        // must not show a false "已停止" card while network icon assets are being fetched.
        refresh()
        launch { runCatching { repo.ensureIcons() } }
        while (true) {
            delay(2200)
            refresh()
        }
    }
''',
    'refresh-before-icons',
)

# RefPanel local loading/toast state.
panel_start = ui.index('private fun RefPanel(')
panel_end = ui.index('\n@Composable\nprivate fun RefGroupDetailPage', panel_start)
panel = ui[panel_start:panel_end]
panel = replace_once(
    panel,
    '    val testing = remember { mutableStateMapOf<String, Boolean>() }\n    var error by remember { mutableStateOf("") }\n',
    '    val testing = remember { mutableStateMapOf<String, Boolean>() }\n'
    '    val providerRefreshing = remember { mutableStateMapOf<String, Boolean>() }\n'
    '    val ruleSetRefreshing = remember { mutableStateMapOf<String, Boolean>() }\n'
    '    var capsuleText by remember { mutableStateOf("") }\n'
    '    var capsuleError by remember { mutableStateOf(false) }\n'
    '    var error by remember { mutableStateOf("") }\n',
    'panel loading states',
)
panel = replace_once(
    panel,
    '    DisposableEffect(Unit) {\n',
    '    LaunchedEffect(capsuleText) {\n'
    '        if (capsuleText.isNotBlank()) {\n'
    '            delay(2500)\n'
    '            capsuleText = ""\n'
    '            capsuleError = false\n'
    '        }\n'
    '    }\n\n'
    '    DisposableEffect(Unit) {\n',
    'capsule timer',
)

# Policy-group latency badge becomes its own hit target, so it never expands the card.
panel = replace_once(
    panel,
    '''                                        delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name
                                        },
''',
    '''                                        delay = delays[selected] ?: group.nodes.firstOrNull { it.name == selected }?.lastDelay,
                                        testing = selected.isNotBlank() && testing[selected] == true,
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                            selectedGroupName = if (selectedGroupName == group.name) null else group.name
                                        },
                                        onDelay = {
                                            if (selected.isNotBlank() && testing[selected] != true) scope.launch {
                                                testing[selected] = true
                                                try {
                                                    delays[selected] = repo.delay(selected)
                                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                                } catch (_: Exception) {
                                                    delays[selected] = -1L
                                                } finally {
                                                    testing.remove(selected)
                                                }
                                            }
                                        },
''',
    'group-card latency action',
)

panel = replace_once(
    panel,
    '''                    RefProviderRow(
                        item = item,
                        onRefresh = {
                            scope.launch {
                                repo.refreshProvider(item.name)?.let { updated ->
                                    providers = providers.map { if (it.name == updated.name) updated else it }
                                }
                            }
                        },
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    )
''',
    '''                    RefProviderRow(
                        item = item,
                        refreshing = providerRefreshing[item.name] == true,
                        onRefresh = {
                            if (providerRefreshing[item.name] != true) scope.launch {
                                providerRefreshing[item.name] = true
                                try {
                                    val updated = repo.refreshProvider(item.name)
                                    if (updated != null) providers = providers.map { if (it.name == updated.name) updated else it }
                                    capsuleError = false
                                    capsuleText = "订阅更新成功"
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                } catch (e: Exception) {
                                    capsuleError = true
                                    capsuleText = e.message ?: "订阅更新失败，请检查网络"
                                } finally {
                                    providerRefreshing.remove(item.name)
                                }
                            }
                        },
                        onClick = { context.startActivity(Intent(context, ProxySubscriptionActivity::class.java)) },
                    )
''',
    'provider per-item loading',
)
panel = replace_once(
    panel,
    '                RefPanelTab.RuleSets -> items(ruleSets, key = { it.name }) { item ->\n                    RefRuleSetRow(item) { scope.launch { ruleSets = repo.refreshRuleSets() } }\n                }\n',
    '                RefPanelTab.RuleSets -> items(ruleSets, key = { it.name }) { item ->\n'
    '                    RefRuleSetRow(item, refreshing = ruleSetRefreshing[item.name] == true) {\n'
    '                        if (ruleSetRefreshing[item.name] != true) scope.launch {\n'
    '                            ruleSetRefreshing[item.name] = true\n'
    '                            try {\n'
    '                                val updated = repo.refreshRuleSet(item.name)\n'
    '                                if (updated != null) ruleSets = ruleSets.map { if (it.name == updated.name) updated else it }\n'
    '                                capsuleError = false\n'
    '                                capsuleText = "规则集更新完成"\n'
    '                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)\n'
    '                            } catch (e: Exception) {\n'
    '                                capsuleError = true\n'
    '                                capsuleText = e.message ?: "规则集更新失败，请检查网络"\n'
    '                            } finally {\n'
    '                                ruleSetRefreshing.remove(item.name)\n'
    '                            }\n'
    '                        }\n'
    '                    }\n'
    '                }\n',
    'ruleset per-item loading',
)

# Fixed floating capsule above panel content.
panel = replace_once(
    panel,
    '    PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {\n        LazyColumn(\n',
    '    Box(Modifier.fillMaxSize()) {\n'
    '        PullToRefreshBox(isRefreshing = refreshing, onRefresh = ::refresh, modifier = Modifier.fillMaxSize()) {\n'
    '            LazyColumn(\n',
    'panel capsule box open',
)
# The RefPanel tail is unique inside the sliced section.
tail = '''            }
        }
    }


}'''
replacement_tail = '''            }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = capsuleText.isNotBlank(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 10.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(190)) { -it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(170)) { -it / 3 },
        ) {
            RefFloatingCapsule(capsuleText, capsuleError)
        }
    }


}'''
panel = replace_once(panel, tail, replacement_tail, 'panel capsule box close')
ui = ui[:panel_start] + panel + ui[panel_end:]

# Group card signature + delay badge.
ui = replace_once(
    ui,
    'private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, modifier: Modifier, onClick: () -> Unit) {',
    'private fun RefGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, delay: Long?, testing: Boolean, modifier: Modifier, onClick: () -> Unit, onDelay: () -> Unit) {',
    'group card signature',
)
old_group_badge = '''            Surface(shape = RoundedCornerShape(999.dp), color = Color(0xFFEFF6FF)) {
                Text(
                    refDelay(delay),
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Color(0xFF2563EB),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
'''
ui = replace_once(ui, old_group_badge, '            RefDelayBadge(delay, testing, onDelay)\n', 'group delay nested click')

# Crystal inset tray styling.
ui = replace_once(ui, '    val shape = RoundedCornerShape(20.dp)\n    val trayBrush = if (dark) {\n        Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f), t.elevatedCardBackground, t.elevatedCardBackground))\n    } else {\n        Brush.verticalGradient(listOf(Color(0xFFEFF4F8), Color(0xFFF8FAFC), Color(0xFFF8FAFC)))\n    }\n',
'''    val shape = RoundedCornerShape(22.dp)
    val trayBrush = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .065f), t.elevatedCardBackground, t.cardBackground))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE2E8F0).copy(alpha = .46f), Color(0xFFF8FAFC).copy(alpha = .94f), Color.White.copy(alpha = .86f)))
    }
''', 'inline crystal tray brush')
ui = replace_once(ui,
    '        border = BorderStroke(1.dp, if (dark) t.outline.copy(alpha = .44f) else Color(0xFFE2E8F0)),\n        shadowElevation = 0.dp,\n',
    '        border = BorderStroke(.8.dp, if (dark) Color.White.copy(alpha = .10f) else Color.White.copy(alpha = .88f)),\n        shadowElevation = 1.dp,\n',
    'inline tray border',
)

# Home controls: lighter floating glass pill treatment.
ui = replace_once(ui, '        danger -> Color(0xFFFFF1F2).copy(alpha = .94f)\n        dark -> Color.White.copy(alpha = .075f)\n        else -> Color.White.copy(alpha = .92f)\n',
    '        danger -> Color(0xFFFFF1F2).copy(alpha = .82f)\n        dark -> Color.White.copy(alpha = .075f)\n        else -> Color.White.copy(alpha = .76f)\n', 'home pill translucency')
ui = replace_once(ui, '            .height(40.dp)\n', '            .height(42.dp)\n', 'home pill height')
ui = replace_once(ui, '            .shadow(5.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)\n',
    '            .shadow(7.dp, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)\n', 'home pill shadow')

# Provider row: spinner and Glacier-blue remaining block.
ui = replace_once(ui,
    'private fun RefProviderRow(item: DashboardProviderUi, onRefresh: () -> Unit, onClick: () -> Unit) {\n    val t = LocalBichenTokens.current\n    val scheme = MaterialTheme.colorScheme\n',
    'private fun RefProviderRow(item: DashboardProviderUi, refreshing: Boolean, onRefresh: () -> Unit, onClick: () -> Unit) {\n'
    '    val t = LocalBichenTokens.current\n'
    '    val scheme = MaterialTheme.colorScheme\n'
    '    val spinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "providerRefreshSpin${item.name}")\n'
    '    val spin by spinTransition.animateFloat(\n'
    '        initialValue = 0f, targetValue = 360f,\n'
    '        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing)),\n'
    '        label = "providerRefreshRotation${item.name}",\n'
    '    )\n',
    'provider signature/spin',
)
ui = replace_once(ui,
    '                Surface(onClick = onRefresh, shape = RoundedCornerShape(999.dp), color = Color(0xFFEBF3FF)) {\n',
    '                Surface(onClick = { if (!refreshing) onRefresh() }, shape = RoundedCornerShape(999.dp), color = Color(0xFFEFF6FF), border = BorderStroke(1.dp, Color(0xFFDBEAFE))) {\n',
    'provider refresh capsule',
)
ui = replace_once(ui,
    '                        Icon(Icons.Rounded.Sync, "同步更新", tint = scheme.primary, modifier = Modifier.size(14.dp))\n',
    '                        Icon(Icons.Rounded.Sync, "同步更新", tint = scheme.primary, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f })\n',
    'provider refresh spin icon',
)
ui = replace_once(ui,
    '                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xFFEDF4FF)) {\n',
    '                    Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = Color(0xFFEFF6FF), border = BorderStroke(1.dp, Color(0xFFDBEAFE))) {\n',
    'provider remaining block',
)
ui = replace_once(ui, '                            Text(refBytes(item.remaining), color = scheme.primary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)\n                            Text("剩余流量", color = scheme.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)\n',
    '                            Text(refBytes(item.remaining), color = Color(0xFF2563EB), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)\n                            Text("剩余流量", color = Color(0xFF2563EB), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)\n', 'provider remaining colors')

# Rule-set row: local per-item spin.
ui = replace_once(ui,
    'private fun RefRuleSetRow(item: DashboardRuleSetUi, onRefresh: () -> Unit) {\n    val t = LocalBichenTokens.current\n    val scheme = MaterialTheme.colorScheme\n',
    'private fun RefRuleSetRow(item: DashboardRuleSetUi, refreshing: Boolean, onRefresh: () -> Unit) {\n'
    '    val t = LocalBichenTokens.current\n'
    '    val scheme = MaterialTheme.colorScheme\n'
    '    val spinTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "ruleSetRefreshSpin${item.name}")\n'
    '    val spin by spinTransition.animateFloat(\n'
    '        initialValue = 0f, targetValue = 360f,\n'
    '        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(760, easing = androidx.compose.animation.core.LinearEasing)),\n'
    '        label = "ruleSetRefreshRotation${item.name}",\n'
    '    )\n',
    'ruleset signature/spin',
)
ui = replace_once(ui,
    '            IconButton(onClick = onRefresh, modifier = Modifier.size(38.dp)) {\n                Icon(Icons.Rounded.Download, "远端更新", tint = scheme.primary, modifier = Modifier.size(20.dp))\n            }\n',
    '            IconButton(onClick = { if (!refreshing) onRefresh() }, modifier = Modifier.size(38.dp)) {\n'
    '                Icon(Icons.Rounded.Download, "远端更新", tint = scheme.primary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (refreshing) spin else 0f })\n'
    '            }\n',
    'ruleset refresh spin icon',
)

# Floating capsule helper, matching the glass dock language.
insert_anchor = '\n@Composable\nprivate fun RefGroupDetailPage('
helper = '''
@Composable
private fun RefFloatingCapsule(text: String, error: Boolean) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "capsulePulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = .58f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(720),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "capsulePulseAlpha",
    )
    Surface(
        shape = CircleShape,
        color = Color(0xFF0F172A).copy(alpha = .88f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(7.dp).graphicsLayer { alpha = dotAlpha }
                    .background(if (error) Color(0xFFFB7185) else Color(0xFF34D399), CircleShape),
            )
            Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

'''
if insert_anchor not in ui:
    raise SystemExit('test42: floating capsule insert anchor missing')
ui = ui.replace(insert_anchor, '\n' + helper + '@Composable\nprivate fun RefGroupDetailPage(', 1)

UI.write_text(ui, encoding="utf-8")

# ---------------------------------------------------------------------------
# Hard audit.
# ---------------------------------------------------------------------------
checks = {
    BUILD: ['versionCode = 442', 'versionName = "0.4.0-test.42"'],
    STARTUP: ['Do not overwrite the full SO_MARK', 'bind-address: \'*\''],
    SCRIPT: [
        'P=14500; while [ "$P" -le 14949 ]',
        'iptables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN',
        'ip6tables -t mangle -A "$MOUT" -m owner --uid-owner 0 -j RETURN',
        'iptables -t nat -A "$DNSOUT" -m owner --uid-owner 0 -j RETURN',
    ],
    ROOT_MANAGER: ['DIRECT 出站不可用', 'controller.waitReady(4500)'],
    CONTROLLER: ['proxyRootSessionOwner', '"manual"'],
    NETWORK_MATCH: ['"automation".equals(owner)', '!"manual".equals(owner)'],
    INSPECTOR: ['--- watchdog.log ---', '--- last-crash ---'],
    DASHBOARD: ['suspend fun refreshRuleSet(name: String)', 'api.updateProxyProvider(name)'],
    UI: [
        'ProxyComposeState(running = prefs.getBoolean("proxyRootWanted", false))',
        'refresh()\n        launch { runCatching { repo.ensureIcons() } }',
        'RefDelayBadge(delay, testing, onDelay)',
        '订阅更新成功', '规则集更新完成',
        'private fun RefFloatingCapsule',
        'Color(0xFFDBEAFE)',
        'providerRefreshRotation', 'ruleSetRefreshRotation',
    ],
}
for path, needles in checks.items():
    body = path.read_text(encoding="utf-8")
    for needle in needles:
        if needle not in body:
            raise SystemExit(f"test42: missing invariant {needle} in {path}")

# Explicitly reject the Android-breaking routing-mark line.
if 'override.append("routing-mark: ")' in STARTUP.read_text(encoding="utf-8"):
    raise SystemExit('test42: Mihomo routing-mark override still present')

print("test.42 runtime stability + instant status + interaction/UI polish applied")
