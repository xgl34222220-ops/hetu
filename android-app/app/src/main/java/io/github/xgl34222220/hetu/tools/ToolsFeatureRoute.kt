package io.github.xgl34222220.hetu.tools

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.xgl34222220.hetu.home.HomeModalSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The pushed page of [ToolsEntry], when it is one of pages 28–49. */
internal fun ToolsEntry.featureDestination(): ToolsDestination? = when (this) {
    ToolsEntry.Apps -> ToolsDestination.Apps
    ToolsEntry.Cores -> ToolsDestination.Cores
    ToolsEntry.Bypass -> ToolsDestination.Bypass
    ToolsEntry.Share -> ToolsDestination.Share
    ToolsEntry.CnIp -> ToolsDestination.CnIp
    ToolsEntry.Diag -> ToolsDestination.Diag
    ToolsEntry.Adblock -> ToolsDestination.Adblock
    else -> null
}

/**
 * Stateful hosts of pages 28–49, one per destination. `ToolsRoute` owns the navigation stack
 * and calls this for every destination that is not one of its own; each host loads through
 * [actions], keeps its page state while it is on screen, and shows its own dialogs and sheets.
 *
 * Hosts with a draft (绕过规则, 共享网络) or a transient mode (搜索, an open menu) install their
 * own back handler, which takes precedence over the route's plain “pop”.
 */
@Composable
internal fun ToolsFeaturePage(
    destination: ToolsDestination,
    actions: ToolsFeatureActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    appIcon: (@Composable (ToolsApp, Modifier) -> Unit)? = null,
) {
    when (destination) {
        ToolsDestination.Apps -> AppsHost(actions, onBack, modifier, appIcon)
        ToolsDestination.Cores -> CoresHost(actions, onBack, modifier)
        ToolsDestination.Bypass -> BypassHost(actions, onBack, modifier)
        ToolsDestination.Share -> ShareHost(actions, onBack, modifier)
        ToolsDestination.CnIp -> CnIpHost(actions, onBack, modifier)
        ToolsDestination.Diag -> DiagHost(actions, onBack, modifier)
        ToolsDestination.Adblock -> AdblockHost(actions, onBack, modifier)
        else -> Unit
    }
}

private fun Throwable.reason(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback

/** Launches [block]; anything it throws except cancellation goes to [onError]. */
private fun CoroutineScope.attempt(onError: (Exception) -> Unit, block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            onError(error)
        }
    }
}

/* ------------------------------ 应用管理 ------------------------------ */

@Composable
private fun AppsHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier, appIcon: (@Composable (ToolsApp, Modifier) -> Unit)?) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsAppsState()) }
    var menu by remember { mutableStateOf<ToolsAppsMenu?>(null) }

    fun load(refresh: Boolean) {
        val loader = actions.loadApps ?: return
        if (state.refreshing) return
        if (refresh) state = state.copy(refreshing = true)
        scope.attempt({ error ->
            state = if (state.load is ToolsLoad.Ready) state.copy(refreshing = false) else state.copy(load = ToolsLoad.Failed(error.reason("应用列表暂时无法读取")))
            if (refresh) actions.onMessage(error.reason("刷新失败"))
        }) {
            val snapshot = loader(refresh)
            state = state.copy(load = ToolsLoad.Ready, apps = snapshot.apps, scope = snapshot.scope, blacklist = snapshot.blacklist, whitelist = snapshot.whitelist, refreshing = false)
            if (refresh) actions.onMessage("应用列表已刷新")
        }
    }
    LaunchedEffect(Unit) { load(false) }

    fun commit(keys: Set<String>) {
        val target = state.scope
        if (target == ToolsAppScope.Core) return
        state = state.withSelected(keys)
        scope.attempt({ error -> actions.onMessage(error.reason("名单保存失败")); load(false) }) { actions.setAppList(target, keys) }
    }

    fun selectAll() {
        val visible = state.visible.map { it.key }.toSet()
        if (visible.isEmpty()) return
        val current = state.selected
        commit(if (current.containsAll(visible)) current - visible else current + visible)
    }

    BackHandler(enabled = state.searching || menu != null) {
        if (menu != null) menu = null else state = state.copy(searching = false, query = "")
    }

    ToolsAppsScreen(
        state = state,
        menu = menu,
        onBack = onBack,
        onToggleSearch = { state = state.copy(searching = !state.searching, query = "") },
        onQueryChange = { state = state.copy(query = it) },
        onScopeChange = { next ->
            if (next != state.scope) {
                state = state.copy(scope = next)
                scope.attempt({ error -> actions.onMessage(error.reason("应用范围保存失败")); load(false) }) { actions.setAppScope(next) }
            }
        },
        onToggleApp = { app -> commit(if (app.key in state.selected) state.selected - app.key else state.selected + app.key) },
        onSelectAll = { menu = null; selectAll() },
        onOpenMenu = { menu = it },
        onDismissMenu = { menu = null },
        onSortChange = { menu = null; state = state.copy(sort = it) },
        onToggleDescending = { menu = null; state = state.copy(descending = !state.descending) },
        onToggleSystem = { menu = null; state = state.copy(showSystem = !state.showSystem) },
        onClear = { menu = null; commit(emptySet()); actions.onMessage("名单已清空") },
        onRefresh = { menu = null; load(true) },
        onRetry = { state = state.copy(load = ToolsLoad.Loading); load(false) },
        modifier = modifier,
        appIcon = appIcon,
    )
}

/* ------------------------------ 核心管理 ------------------------------ */

@Composable
private fun CoresHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsCoresState()) }

    suspend fun read(network: Boolean) {
        val snapshot = (actions.loadCores ?: return)(network)
        state = state.copy(load = ToolsLoad.Ready, running = snapshot.running, cores = snapshot.cores)
    }

    fun check(announce: Boolean) {
        if (state.checking) return
        state = state.copy(checking = true)
        scope.attempt({ error ->
            state = if (state.load is ToolsLoad.Ready) state.copy(checking = false) else state.copy(load = ToolsLoad.Failed(error.reason("核心状态暂时无法读取")), checking = false)
            if (announce) actions.onMessage(error.reason("检查更新失败"))
        }) {
            read(network = true)
            state = state.copy(checking = false)
            if (announce) actions.onMessage(if (state.cores.any { it.latest.isNotBlank() }) "已检查发布源" else "未获取到远程版本")
        }
    }

    // Local state first so the cards appear at once, then the release check fills in “最新”.
    LaunchedEffect(Unit) {
        try {
            read(network = false)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (ignored: Exception) {
            // The network pass below reports the failure.
        }
        check(announce = false)
    }

    fun run(core: ToolsCore, done: String, failed: String, block: suspend () -> Unit) {
        if (state.busyId != null) return
        state = state.copy(busyId = core.id, progress = "")
        scope.launch {
            try {
                block()
                actions.onMessage(done)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                actions.onMessage(error.reason(failed))
            }
            state = state.copy(busyId = null, progress = "")
            check(announce = false)
        }
    }

    ToolsCoresScreen(
        state = state,
        onBack = onBack,
        onCheck = { check(announce = true) },
        onPrimary = { core, action ->
            when (action) {
                ToolsCoreAction.Update, ToolsCoreAction.Download ->
                    run(core, "${core.name} 已更新", "${core.name} 下载失败") {
                        // Progress arrives on the download thread; hop to the UI scope before touching state.
                        actions.downloadCore(core.id) { line -> scope.launch { state = state.copy(progress = line) } }
                    }
                ToolsCoreAction.Restore -> run(core, "${core.name} 已恢复内置版本", "恢复失败") { actions.removeCore(core.id) }
                ToolsCoreAction.Remove -> run(core, "${core.name} 下载核心已删除", "删除失败") { actions.removeCore(core.id) }
            }
        },
        onImport = { core ->
            if (state.busyId == null) {
                state = state.copy(busyId = core.id, progress = "等待选择核心文件")
                actions.importCore(core.id) { message ->
                    state = state.copy(busyId = null, progress = "")
                    if (message != null) actions.onMessage(message)
                    check(announce = false)
                }
            }
        },
        onRetry = { state = state.copy(load = ToolsLoad.Loading); check(announce = false) },
        modifier = modifier,
    )
}

/* ------------------------------ 绕过规则 ------------------------------ */

@Composable
private fun BypassHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsBypassState()) }
    var confirmLeave by remember { mutableStateOf(false) }

    fun load() {
        val loader = actions.loadBypass ?: return
        scope.attempt({ error -> state = state.copy(load = ToolsLoad.Failed(error.reason("绕过规则暂时无法读取"))) }) {
            val rules = loader()
            state = ToolsBypassState(ToolsLoad.Ready, rules, rules)
        }
    }
    LaunchedEffect(Unit) { load() }

    fun save() {
        if (state.saving || !state.dirty) return
        val rules = state.draft.normalized()
        val problem = ToolsFeatureRules.bypassError(rules)
        if (problem != null) { actions.onMessage(problem); return }
        state = state.copy(saving = true)
        scope.attempt({ error -> state = state.copy(saving = false); actions.onMessage(error.reason("保存失败")) }) {
            actions.saveBypass(rules)
            state = state.copy(saved = rules, draft = rules, saving = false)
            actions.onMessage("已保存；重启代理后生效")
        }
    }

    fun leave() { if (state.saving) return; if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty || state.saving) { leave() }

    ToolsBypassScreen(state, onBack = ::leave, onSave = ::save, onDraftChange = { state = state.copy(draft = it) }, onRetry = { state = ToolsBypassState(); load() }, modifier = modifier)

    if (confirmLeave) {
        ToolsDialog(onDismiss = { confirmLeave = false }) {
            ToolsDiscardChangesDialogCard(onConfirm = { confirmLeave = false; onBack() }, onCancel = { confirmLeave = false })
        }
    }
}

/* ------------------------------ 共享网络 ------------------------------ */

@Composable
private fun ShareHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsShareState()) }
    var confirmLeave by remember { mutableStateOf(false) }

    /** [keepDraft]: a refresh only renews what the device reports, never the unsaved edits. */
    fun load(keepDraft: Boolean) {
        val loader = actions.loadShare ?: return
        if (state.refreshing) return
        if (keepDraft) state = state.copy(refreshing = true)
        scope.attempt({ error ->
            state = if (keepDraft) state.copy(refreshing = false) else state.copy(load = ToolsLoad.Failed(error.reason("共享网络设置暂时无法读取")))
            if (keepDraft) actions.onMessage(error.reason("刷新失败"))
        }) {
            val snapshot = loader()
            state = if (keepDraft) {
                state.copy(interfaces = snapshot.interfaces, clients = snapshot.clients, note = snapshot.note, refreshing = false)
            } else {
                ToolsShareState(ToolsLoad.Ready, snapshot.settings, snapshot.settings, snapshot.interfaces, snapshot.clients, snapshot.note)
            }
            if (keepDraft) actions.onMessage("共享网络状态已刷新")
        }
    }
    LaunchedEffect(Unit) { load(keepDraft = false) }

    fun save() {
        if (state.saving || !state.dirty) return
        val settings = state.draft.normalized()
        val problem = ToolsFeatureRules.shareError(settings)
        if (problem != null) { actions.onMessage(problem); return }
        state = state.copy(saving = true)
        scope.attempt({ error -> state = state.copy(saving = false); actions.onMessage(error.reason("保存失败")) }) {
            actions.saveShare(settings)
            state = state.copy(saved = settings, draft = settings, saving = false)
            actions.onMessage("已保存；重启代理后生效")
        }
    }

    fun leave() { if (state.saving) return; if (state.dirty) confirmLeave = true else onBack() }
    BackHandler(enabled = state.dirty || state.saving) { leave() }

    ToolsShareScreen(
        state, onBack = ::leave, onRefresh = { load(keepDraft = true) }, onSave = ::save,
        onDraftChange = { state = state.copy(draft = it) }, onRetry = { state = ToolsShareState(); load(keepDraft = false) }, modifier = modifier,
    )

    if (confirmLeave) {
        ToolsDialog(onDismiss = { confirmLeave = false }) {
            ToolsDiscardChangesDialogCard(onConfirm = { confirmLeave = false; onBack() }, onCancel = { confirmLeave = false })
        }
    }
}

/* ------------------------------ CNIP ------------------------------ */

@Composable
private fun CnIpHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsCnIpState()) }
    LaunchedEffect(Unit) {
        val loader = actions.loadCnIp ?: return@LaunchedEffect
        state = try {
            ToolsCnIpState(ToolsLoad.Ready, loader())
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            ToolsCnIpState(ToolsLoad.Failed(error.reason("设置暂时无法读取")))
        }
    }
    ToolsCnIpScreen(
        state, onBack,
        onEnabledChange = { next ->
            val previous = state.enabled
            state = state.copy(enabled = next)
            scope.attempt({ error -> state = state.copy(enabled = previous); actions.onMessage(error.reason("保存失败")) }) {
                actions.setCnIp(next)
                actions.onMessage("已保存；重启代理后生效")
            }
        },
        modifier = modifier,
    )
}

/* ------------------------------ 诊断与维护 ------------------------------ */

@Composable
private fun DiagHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsDiagState()) }
    var overlay by remember { mutableStateOf<ToolsDiagOverlay?>(null) }

    fun preflight() {
        val runner = actions.runPreflight ?: return
        if (state.preflight is ToolsPreflight.Running) return
        state = state.copy(preflight = ToolsPreflight.Running)
        scope.attempt({ error -> state = state.copy(preflight = ToolsPreflight.Failed(error.reason("预检执行失败"))) }) {
            state = state.copy(preflight = runner())
        }
    }

    /** Each opening has its own identity; closed or reopened sheets ignore that request's late result. */
    fun open(start: ToolsDiagOverlay, read: suspend () -> String, wrap: (ToolsDiagText) -> ToolsDiagOverlay) {
        overlay = start
        scope.launch {
            val content = try {
                ToolsDiagText(loading = false, text = read())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                ToolsDiagText(loading = false, error = error.reason("读取失败"))
            }
            if (overlay === start) overlay = wrap(content)
        }
    }

    ToolsDiagScreen(
        state = state,
        onBack = onBack,
        onPreflight = ::preflight,
        onStartupConfig = { open(ToolsDiagOverlay.StartupConfig(), actions.startupConfig) { ToolsDiagOverlay.StartupConfig(it) } },
        onReport = { open(ToolsDiagOverlay.Report(), actions.diagnostics) { ToolsDiagOverlay.Report(it) } },
        onRestore = { overlay = ToolsDiagOverlay.ConfirmRestore() },
        onOpenDiagnosticsDetails = actions.onOpenDiagnosticsDetails,
        onOpenNetworkTest = actions.onOpenNetworkTest,
        modifier = modifier,
    )

    when (val current = overlay) {
        is ToolsDiagOverlay.StartupConfig -> HomeModalSheet(onDismiss = { overlay = null }) {
            ToolsStartupConfigSheetContent(current.content, onCopy = { actions.onCopy("启动配置", it) })
        }
        is ToolsDiagOverlay.Report -> HomeModalSheet(onDismiss = { overlay = null }) {
            ToolsReportSheetContent(current.content, onCopy = { actions.onCopy("诊断信息", it) })
        }
        is ToolsDiagOverlay.ConfirmRestore -> ToolsDialog(onDismiss = { if (!current.running) overlay = null }) {
            ToolsRestoreNetworkDialogCard(
                loading = current.running,
                onConfirm = {
                    overlay = ToolsDiagOverlay.ConfirmRestore(running = true)
                    scope.attempt({ error -> overlay = null; actions.onMessage(error.reason("恢复网络失败")) }) {
                        val note = actions.restoreNetwork()
                        overlay = null
                        actions.onMessage(note.ifBlank { "已停止代理并回滚网络规则" })
                    }
                },
                onCancel = { overlay = null },
            )
        }
        null -> Unit
    }
}

/* ------------------------------ 广告过滤 ------------------------------ */

@Composable
private fun AdblockHost(actions: ToolsFeatureActions, onBack: () -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ToolsAdblockState()) }
    var overlay by remember { mutableStateOf<ToolsAdOverlay?>(null) }

    suspend fun read() {
        val snapshot = (actions.loadAdblock ?: return)()
        // A re-read keeps the last 实测 result; it is the user's evidence, not part of the snapshot.
        state = snapshot.state.copy(load = ToolsLoad.Ready, busy = false, updating = false, refreshing = false, probe = state.probe, probing = state.probing)
    }

    fun reload(announce: String? = null) {
        if (state.refreshing) return
        state = state.copy(refreshing = true)
        scope.attempt({ error ->
            state = if (state.load is ToolsLoad.Ready) state.copy(busy = false, updating = false, refreshing = false) else state.copy(load = ToolsLoad.Failed(error.reason("广告过滤状态暂时无法读取")), refreshing = false)
        }) {
            read()
            if (announce != null) actions.onMessage(announce)
        }
    }
    LaunchedEffect(Unit) { reload() }

    /** Shows [optimistic] at once, applies the change, reports its note, then re-reads the truth. */
    fun change(optimistic: ToolsAdblockState, failed: String, block: suspend () -> String) {
        if (state.busy) return
        state = optimistic.copy(busy = true)
        scope.launch {
            try {
                val note = block()
                if (note.isNotBlank()) actions.onMessage(note)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                actions.onMessage(error.reason(failed))
            }
            reload()
        }
    }

    fun addDomain(dialog: ToolsAdOverlay.AddDomain) {
        if (dialog.saving) return
        val problem = ToolsFeatureRules.domainError(dialog.draft, if (dialog.allow) state.allow else state.block)
        if (problem != null) { overlay = dialog.copy(error = problem); return }
        val domain = ToolsFeatureRules.cleanDomain(dialog.draft)
        overlay = dialog.copy(error = null, saving = true)
        scope.attempt({ error -> overlay = dialog.copy(error = error.reason("添加失败"), saving = false) }) {
            val note = actions.changeAdDomain(domain, dialog.allow, true)
            overlay = null
            actions.onMessage(note.ifBlank { if (dialog.allow) "已加入白名单" else "已加入黑名单" })
            read()
        }
    }

    ToolsAdblockScreen(
        state = state,
        onBack = onBack,
        onHelp = { overlay = ToolsAdOverlay.Help },
        onRefresh = { state = state.copy(busy = true); reload("运行链已重新检测") },
        onEnabledChange = { on -> change(state.copy(enabled = on), "开关保存失败") { actions.setAdblockEnabled(on) } },
        onSwitchToRuleMode = actions.switchToRuleMode?.let { switch -> { change(state, "模式切换失败") { switch(); "" } } },
        onPickRecent = { overlay = ToolsAdOverlay.ConfirmAllow(it) },
        onLevelChange = { level -> change(state.copy(level = level, levelNote = null), "强度切换失败") { actions.setAdLevel(level) } },
        onUpdate = {
            if (!state.updating && !state.busy) {
                state = state.copy(updating = true, busy = true)
                scope.launch {
                    try {
                        actions.onMessage(actions.updateAdRules().ifBlank { "规则源已是最新" })
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        actions.onMessage(error.reason("规则更新失败"))
                    }
                    reload()
                }
            }
        },
        onSourceChange = { source, on ->
            change(state.copy(sources = state.sources.map { if (it.id == source.id) it.copy(enabled = on) else it }), "规则源修改失败") { actions.setAdSource(source.id, on) }
        },
        onAddDomain = { allow -> overlay = ToolsAdOverlay.AddDomain(allow) },
        onRemoveDomain = { domain, allow ->
            change(if (allow) state.copy(allow = state.allow - domain) else state.copy(block = state.block - domain), "移除失败") { actions.changeAdDomain(domain, allow, false) }
        },
        onStandaloneDnsChange = { on -> change(state.copy(standaloneDns = on), "独立 DNS 过滤切换失败") { actions.setStandaloneDns(on) } },
        onCnameChange = { on -> change(state.copy(cnameProtection = on), "保存失败") { actions.setCnameProtection(on); "" } },
        onRetry = { state = ToolsAdblockState(); reload() },
        modifier = modifier,
        onProbe = actions.probeAdblock?.let { probeOnce ->
            {
                if (!state.probing) {
                    state = state.copy(probing = true)
                    scope.launch {
                        val result = try { probeOnce() } catch (cancel: CancellationException) { throw cancel }
                            catch (error: Exception) { ToolsAdProbe(null, error.reason("实测失败")) }
                        state = state.copy(probing = false, probe = result)
                        reload()
                    }
                }
            }
        },
    )

    when (val current = overlay) {
        ToolsAdOverlay.Help -> HomeModalSheet(onDismiss = { overlay = null }) { ToolsAdblockHelpSheetContent(onClose = { overlay = null }) }
        is ToolsAdOverlay.AddDomain -> ToolsDialog(onDismiss = { if (!current.saving) overlay = null }) {
            ToolsAddDomainDialogCard(
                current,
                onDraftChange = { overlay = current.copy(draft = it, error = null) },
                onConfirm = { addDomain(current) },
                onCancel = { overlay = null },
            )
        }
        is ToolsAdOverlay.ConfirmAllow -> ToolsDialog(onDismiss = { if (!current.saving) overlay = null }) {
            ToolsConfirmAllowDialogCard(
                current,
                onConfirm = {
                    overlay = current.copy(saving = true)
                    scope.attempt({ error -> overlay = null; actions.onMessage(error.reason("加入白名单失败")) }) {
                        val note = if (current.domain in state.allow) "" else actions.changeAdDomain(current.domain, true, true)
                        overlay = null
                        actions.onMessage(note.ifBlank { "已加入白名单" })
                        read()
                    }
                },
                onCancel = { overlay = null },
            )
        }
        null -> Unit
    }
}
