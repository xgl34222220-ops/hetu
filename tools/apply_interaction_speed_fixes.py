from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"pattern not found: {label}")
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# 1) Controller: expose async VPN startup state/failure and make app list instant.
# -----------------------------------------------------------------------------
p = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenComposeController.kt")
s = p.read_text()

s = replace_once(
    s,
    "    val vpnRunning: Boolean = false,\n    val proxyRunning: Boolean = false,",
    "    val vpnRunning: Boolean = false,\n    val vpnWanted: Boolean = false,\n    val proxyRunning: Boolean = false,",
    "HomeSnapshot vpnWanted",
)

s = replace_once(
    s,
    '    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)\n',
    '    private val prefs = app.getSharedPreferences("bichen", Context.MODE_PRIVATE)\n'
    '    @Volatile private var appCache: List<AppItem>? = null\n'
    '    private val iconCache = android.util.LruCache<String, Bitmap>(128)\n',
    "controller caches",
)

s = replace_once(
    s,
    '''            vpnRunning = DnsVpnService.running,
            proxyRunning = rootProxyRunning || MihomoVpnService.engaged || prefs.getBoolean("proxyWanted", false),
            message = status.optString("error", status.optString("message", "")),''',
    '''            vpnRunning = DnsVpnService.running,
            vpnWanted = prefs.getBoolean("vpnWanted", false),
            proxyRunning = rootProxyRunning || MihomoVpnService.engaged || prefs.getBoolean("proxyWanted", false),
            message = prefs.getString("vpnError", "")?.takeIf {
                preferredVpnMode() && !DnsVpnService.running && it.isNotBlank()
            } ?: status.optString("error", status.optString("message", "")),''',
    "home vpn state",
)

s = replace_once(
    s,
    '''    fun startVpn() {
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    fun stopVpn() {
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        app.startService(intent)
    }''',
    '''    fun startVpn() {
        prefs.edit().putBoolean("vpnWanted", true).remove("vpnError").apply()
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
    }

    fun stopVpn() {
        prefs.edit().putBoolean("vpnWanted", false).apply()
        val intent = Intent(app, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)
        app.startService(intent)
    }''',
    "vpn desired state",
)

load_start = s.index("    suspend fun loadApps(): List<AppItem> = withContext(Dispatchers.IO) {")
load_end = s.index("    private fun loadIcon(", load_start)
new_load = '''    fun cachedApps(): List<AppItem> = appCache ?: emptyList()

    suspend fun preloadApps() {
        loadApps(forceRefresh = false)
    }

    suspend fun loadApps(forceRefresh: Boolean = false): List<AppItem> = withContext(Dispatchers.IO) {
        if (!forceRefresh) appCache?.let { return@withContext it }
        val pm = app.packageManager
        val items = pm.getInstalledApplications(0).asSequence()
            .filter { it.packageName != app.packageName }
            .map { info ->
                // Do not decode every installed app icon before showing the list. Icons are loaded
                // lazily for visible rows by appIcon(), so the page can appear immediately.
                AppItem(
                    label = pm.getApplicationLabel(info).toString(),
                    packageName = info.packageName,
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = null,
                )
            }.toMutableList()
        val collator = Collator.getInstance(Locale.CHINA)
        items.sortWith { a, b -> collator.compare(a.label, b.label) }
        items.toList().also { appCache = it }
    }

    suspend fun appIcon(packageName: String): Bitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { return@withContext it }
        try {
            val info = app.packageManager.getApplicationInfo(packageName, 0)
            loadIcon(info)?.also { iconCache.put(packageName, it) }
        } catch (_: Exception) {
            null
        }
    }

'''
s = s[:load_start] + new_load + s[load_end:]
p.write_text(s)


# -----------------------------------------------------------------------------
# 2) Launcher UI: real feedback, no swallowed cancellation, app prewarm/lazy icons,
#    rule update feedback, and a less empty/sparse home hierarchy.
# -----------------------------------------------------------------------------
p = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/CompactMainActivity.kt")
s = p.read_text()

if "import kotlinx.coroutines.CancellationException\n" not in s:
    s = replace_once(
        s,
        "import kotlinx.coroutines.delay\n",
        "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.delay\n",
        "CancellationException import",
    )

s = replace_once(
    s,
    '''    val controller = remember { BichenComposeController(context) }
    var page by rememberSaveable { mutableStateOf(CompactPage.Home) }''',
    '''    val controller = remember { BichenComposeController(context) }
    LaunchedEffect(controller) { controller.preloadApps() }
    var page by rememberSaveable { mutableStateOf(CompactPage.Home) }''',
    "app prewarm",
)

s = replace_once(
    s,
    '''    val snapshot by produceState(initialValue = HomeSnapshot(), refresh) {
        value = runCatching { controller.homeSnapshot() }.getOrElse { HomeSnapshot(message = it.message ?: "状态读取失败") }
    }''',
    '''    val snapshot by produceState(initialValue = HomeSnapshot(), refresh) {
        while (true) {
            value = try {
                controller.homeSnapshot()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                HomeSnapshot(message = error.message ?: "状态读取失败")
            }
            delay(if (value.vpnWanted && !value.vpnRunning) 350 else 1000)
        }
    }''',
    "live home snapshot",
)

s = replace_once(
    s,
    '''    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var showModePicker by rememberSaveable { mutableStateOf(false) }''',
    '''    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var showModePicker by rememberSaveable { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }''',
    "home action busy",
)

home_toggle_start = s.index("    fun toggleProtection() {")
home_list_start = s.index("    LazyColumn(", home_toggle_start)
s = s[:home_toggle_start] + '''    fun toggleProtection() {
        if (actionBusy || (snapshot.vpnWanted && !snapshot.vpnRunning)) return
        if (controller.preferredVpnMode()) {
            if (snapshot.vpnRunning) {
                controller.stopVpn()
            } else {
                val permission = controller.prepareVpn()
                if (permission == null) controller.startVpn() else launcher.launch(permission)
            }
            refresh++
        } else {
            scope.launch {
                actionBusy = true
                try {
                    controller.toggleModuleProtection(snapshot.moduleEnabled)
                } catch (cancel: CancellationException) {
                    throw cancel
                } finally {
                    actionBusy = false
                    refresh++
                }
            }
        }
    }

''' + s[home_list_start:]

s = replace_once(
    s,
    '''                        Button(
                            onClick = ::toggleProtection,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PowerSettingsNew, null, Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(if (active) "暂停保护" else "立即开启")
                        }''',
    '''                        val starting = vpnMode && snapshot.vpnWanted && !snapshot.vpnRunning
                        Button(
                            onClick = ::toggleProtection,
                            enabled = !actionBusy && !starting,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            if (actionBusy || starting) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(if (active) Icons.Rounded.Pause else Icons.Rounded.PowerSettingsNew, null, Modifier.size(19.dp))
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(when {
                                starting -> "开启中"
                                actionBusy -> "处理中"
                                active -> "暂停保护"
                                else -> "立即开启"
                            })
                        }''',
    "protection button feedback",
)

# Replace the oversized Bento metric block with one compact dashboard strip.
metrics_start = s.index('        item("metrics") {')
metrics_end = s.index('        if (snapshot.message', metrics_start)
s = s[:metrics_start] + '''        item("metrics") {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardBackground,
                border = BorderStroke(1.dp, tokens.outline),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CompactMetric(counters.blocked.toString(), "累计拦截", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(36.dp), color = tokens.outline)
                    CompactMetric(snapshot.ruleCount.toString(), "有效规则", Modifier.weight(1f))
                    VerticalDivider(Modifier.height(36.dp), color = tokens.outline)
                    CompactMetric("${counters.blockRate}%", "拦截率", Modifier.weight(1f))
                }
            }
        }

''' + s[metrics_end:]

s = replace_once(
    s,
    '''    val apps by produceState(initialValue = emptyList<AppItem>(), reload) { value = runCatching { controller.loadApps() }.getOrElse { emptyList() } }''',
    '''    val apps by produceState(initialValue = controller.cachedApps(), reload) {
        value = try {
            controller.loadApps(forceRefresh = reload > 0)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            controller.cachedApps()
        }
    }''',
    "cached app state",
)

apps_items_marker = '''        items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected'''
s = replace_once(
    s,
    apps_items_marker,
    '''        if (apps.isEmpty()) item("apps-loading") {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                Text("正在读取本机应用…", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected
            val icon by produceState(initialValue = app.icon, app.packageName) {
                value = controller.appIcon(app.packageName)
            }''',
    "app loading/lazy icon",
)

# Restrict this replacement to the apps section only.
apps_section_start = s.index("private fun CompactAppsPage")
rules_section_start = s.index("private fun CompactRulesPage", apps_section_start)
apps_section = s[apps_section_start:rules_section_start]
apps_section = replace_once(
    apps_section,
    '''                shape = RoundedCornerShape(18.dp),
                color = tokens.cardBackground,
                shadowElevation = 0.dp,''',
    '''                shape = RoundedCornerShape(14.dp),
                color = if (checked) tokens.selectionBackground else Color.Transparent,
                shadowElevation = 0.dp,''',
    "flat app row",
)
apps_section = apps_section.replace("if (app.icon != null) {", "if (icon != null) {", 1)
apps_section = apps_section.replace("bitmap = app.icon.asImageBitmap(),", "bitmap = icon!!.asImageBitmap(),", 1)
s = s[:apps_section_start] + apps_section + s[rules_section_start:]

# Rules update feedback.
s = replace_once(
    s,
    '''    val scope = rememberCoroutineScope()
    var addMode by remember { mutableStateOf<Boolean?>(null) }''',
    '''    val scope = rememberCoroutineScope()
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }
    var addMode by remember { mutableStateOf<Boolean?>(null) }''',
    "rules feedback state",
)

s = replace_once(
    s,
    '''                    Button(
                        onClick = { scope.launch { runCatching { controller.updateRules() }; reload++ } },
                        Modifier.fillMaxWidth().heightIn(min = 50.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("更新订阅")
                    }''',
    '''                    Button(
                        onClick = {
                            if (updating) return@Button
                            scope.launch {
                                updating = true
                                updateMessage = "正在更新规则…"
                                try {
                                    updateMessage = controller.updateRules()
                                    reload++
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Exception) {
                                    updateMessage = error.message ?: "规则更新失败"
                                } finally {
                                    updating = false
                                }
                            }
                        },
                        enabled = !updating,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (updating) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Rounded.Sync, null, Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (updating) "更新中" else "更新规则")
                    }
                    if (updateMessage.isNotBlank()) {
                        Text(updateMessage, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }''',
    "rules update feedback",
)

p.write_text(s)


# -----------------------------------------------------------------------------
# 3) Proxy homepage: quick currently-selected-node test. Full all-node test remains
#    available on the Nodes panel.
# -----------------------------------------------------------------------------
p = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyDashboardRepository.kt")
s = p.read_text()
if "suspend fun quickDelay()" not in s:
    global_idx = s.index("suspend fun globalDelay(): Map<String, Long>")
    comment_idx = s.rfind("/**", 0, global_idx)
    quick = '''    /** Fast homepage probe: test only the currently selected nodes from strategy groups. */
    suspend fun quickDelay(): Map<String, Long> = withContext(Dispatchers.IO) {
        val targets = controller.state().groups.map { it.now }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return@withContext emptyMap()
        coroutineScope {
            targets.map { node ->
                async {
                    val value = try {
                        delay(node)
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        -1L
                    }
                    node to value
                }
            }.awaitAll().toMap()
        }
    }

'''
    s = s[:comment_idx] + quick + s[comment_idx:]
p.write_text(s)

p = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/LuoShuProxyV3Activity.kt")
s = p.read_text()
s = replace_once(
    s,
    '''            testingAll = true
            try { delays.putAll(repo.globalDelay()) }
            catch (e: Exception) { message = e.message ?: "测速失败" }
            finally { testingAll = false }''',
    '''            testingAll = true
            try {
                val result = repo.quickDelay()
                delays.putAll(result)
                if (result.isEmpty()) message = "没有可测速的当前节点"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                message = e.message ?: "测速失败"
            } finally {
                testingAll = false
            }''',
    "homepage quick delay",
)

s = replace_once(
    s,
    '''                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = state.running, onClick = onTestAll),
                            contentAlignment = Alignment.CenterStart,
                        ) { V3LatencyMetric(avg, testingAll, Modifier.fillMaxWidth()) }''',
    '''                        Surface(
                            onClick = onTestAll,
                            enabled = state.running && !testingAll,
                            modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = t.elevatedCardBackground,
                        ) {
                            V3LatencyMetric(
                                avg,
                                testingAll,
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }''',
    "latency control surface",
)

lat_start = s.index("private fun V3LatencyMetric")
lat_end = s.index("@Composable\nprivate fun V3SmallAction", lat_start)
lat = s[lat_start:lat_end].replace("正在测试全部节点", "正在测试当前节点")
s = s[:lat_start] + lat + s[lat_end:]
p.write_text(s)


# -----------------------------------------------------------------------------
# 4) Dock: reduce the oversized floating slab seen in the recording.
# -----------------------------------------------------------------------------
p = Path("android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenGlassDock.kt")
s = p.read_text()
s = replace_once(s, "val shape = RoundedCornerShape(28.dp)", "val shape = RoundedCornerShape(25.dp)", "dock radius")
s = replace_once(
    s,
    '''            .padding(horizontal = 20.dp)
            .padding(bottom = bottomInset + 12.dp)
            .fillMaxWidth()
            .height(70.dp),''',
    '''            .padding(horizontal = 18.dp)
            .padding(bottom = bottomInset + 10.dp)
            .fillMaxWidth()
            .height(64.dp),''',
    "dock shell size",
)
s = replace_once(s, "val indicatorShape = RoundedCornerShape(21.dp)", "val indicatorShape = RoundedCornerShape(18.dp)", "dock indicator radius")
s = s.replace(".height(58.dp)", ".height(52.dp)")
s = replace_once(s, "fontSize = 12.sp,", "fontSize = 11.5.sp,", "dock label size")
p.write_text(s)

print("patched runtime interaction, speed and UI issues")
