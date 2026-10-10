package io.github.xgl34222220.hetu.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.HomeModalSheet
import io.github.xgl34222220.hetu.home.HomeMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pages of the tools module. The first five are hosted here; the rest (pages 28–49) by
 * `ToolsFeaturePage`. Entries without a page leave through `ToolsActions.onOpenEntry`.
 */
internal enum class ToolsDestination { Root, Configs, Subscription, Import, Editor, Apps, Cores, Bypass, Share, CnIp, Diag, Adblock }

/**
 * Stateful host of the tools tab: in-module navigation, form and editor logic, dialogs,
 * sheets and the back key. All I/O goes through [actions]; nothing in here touches Android
 * or the existing sources, so the whole flow runs in a preview with the default no-op actions.
 *
 * @param features host callbacks of pages 28–49; an entry whose loader is missing (or all of
 *   them, when null) is handed to `ToolsActions.onOpenEntry` instead.
 * @param appIcon draws an app's launcher icon in 应用管理; a letter tile is used when null.
 * @param onDestinationChanged the page on top changed (the host keeps per-page flags with it).
 * @param pickedFile the document the user chose after `ToolsActions.onPickImportFile`.
 * @param contentPadding padding of the root list; its bottom must clear the floating dock.
 * @param onSubPageVisibleChanged true while a pushed page is on top, so the host can hide the dock.
 */
@Composable
internal fun ToolsRoute(
    actions: ToolsActions,
    modifier: Modifier = Modifier,
    pickedFile: ToolsPickedFile? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = HomeDims.dockClearance),
    onSubPageVisibleChanged: (Boolean) -> Unit = {},
    features: ToolsFeatureActions? = null,
    appIcon: (@Composable (ToolsApp, Modifier) -> Unit)? = null,
    onDestinationChanged: (ToolsDestination) -> Unit = {},
    requestedEntry: ToolsEntry? = null,
    onRequestConsumed: () -> Unit = {},
) {
    val act by rememberUpdatedState(actions)
    val scope = rememberCoroutineScope()
    val motion = io.github.xgl34222220.hetu.homeMotionAvailable(io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current)

    var stackNames by rememberSaveable { mutableStateOf(listOf(ToolsDestination.Root.name)) }
    val stack = stackNames.map(ToolsDestination::valueOf)
    val top = stack.last()
    fun push(destination: ToolsDestination) { stackNames = stackNames + destination.name }
    fun pop() { if (stack.size > 1) stackNames = stackNames.dropLast(1) }

    var root by remember { mutableStateOf(ToolsRootState()) }
    var configs by remember { mutableStateOf(ToolsConfigState()) }
    var configOverlay by remember { mutableStateOf<ToolsConfigOverlay?>(null) }
    var subscription by remember { mutableStateOf(ToolsSubscriptionForm.add()) }
    var import by remember { mutableStateOf(ToolsImportForm()) }
    var discardForm by remember { mutableStateOf(false) }

    val buffer = remember { ToolsYamlEditorState() }
    var document by remember { mutableStateOf<ToolsConfigDocument?>(null) }
    var editor by remember { mutableStateOf(ToolsEditorState()) }
    var editorOverlay by remember { mutableStateOf<ToolsEditorOverlay?>(null) }
    var reloading by remember { mutableStateOf(false) }

    val notifySubPage by rememberUpdatedState(onSubPageVisibleChanged)
    LaunchedEffect(stack.size > 1) { notifySubPage(stack.size > 1) }
    val notifyDestination by rememberUpdatedState(onDestinationChanged)
    LaunchedEffect(top) { notifyDestination(top) }

    /* ------------------------------ 配置与订阅 ------------------------------ */

    fun refreshConfigs(showSpinner: Boolean = false) {
        if (configs.refreshing) return
        configs = if (configs.load is ToolsLoad.Ready) configs.copy(refreshing = showSpinner) else configs.copy(load = ToolsLoad.Loading, refreshing = true)
        scope.launch {
            try {
                val snapshot = act.loadConfigs()
                configs = ToolsConfigState(ToolsLoad.Ready, snapshot.configs, snapshot.subscriptions)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                configs = configs.copy(load = ToolsLoad.Failed(error.reason("配置库暂时无法读取")), busy = false, refreshing = false)
            }
        }
    }

    // The launcher saves this route while the established Sora editor or another Hx page covers it.
    // Its live snapshots are intentionally read again when the module returns.
    LaunchedEffect(top) {
        if (top == ToolsDestination.Configs && configs.load !is ToolsLoad.Ready) refreshConfigs(showSpinner = true)
    }

    /** Runs a mutation of the config library: rows are inert meanwhile, the list reloads afterwards. */
    fun mutateConfigs(done: String?, failed: String, block: suspend () -> Unit) {
        if (configs.busy) return
        configs = configs.copy(busy = true)
        scope.launch {
            try {
                block()
                if (done != null) act.onMessage(done)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                act.onMessage(error.reason(failed))
            }
            configs = configs.copy(busy = false)
            refreshConfigs()
        }
    }

    fun selectConfig(config: ToolsConfig) {
        configOverlay = null
        if (config.current) return
        mutateConfigs("已切换到 ${config.name}", "切换配置失败") { act.selectConfig(config.name) }
    }

    fun confirmRename(overlay: ToolsConfigOverlay.Rename) {
        if (overlay.saving) return
        val name = overlay.draft.trim()
        if (name == overlay.config) { configOverlay = null; return }
        val error = ToolsRules.configNameError(name, overlay.config, configs.configs.map { it.name })
        if (error != null) { configOverlay = overlay.copy(error = error); return }
        configOverlay = overlay.copy(error = null, saving = true)
        scope.launch {
            try {
                act.renameConfig(overlay.config, name)
                configOverlay = null
                act.onMessage("已重命名")
                refreshConfigs()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                configOverlay = overlay.copy(error = error.reason("重命名失败"), saving = false)
            }
        }
    }

    /* ------------------------------ 表单 ------------------------------ */

    fun saveSubscription() {
        val form = subscription
        if (form.saving) return
        val adding = form.mode is ToolsSubscriptionMode.Add
        val nameError = if (adding) ToolsRules.subscriptionNameError(form.name, configs.subscriptions.map { it.name }) else null
        val urlError = ToolsRules.urlError(form.url)
        if (nameError != null || urlError != null) { subscription = form.copy(nameError = nameError, urlError = urlError); return }
        subscription = form.copy(nameError = null, urlError = null, saving = true)
        scope.launch {
            try {
                if (adding) act.addSubscription(form.name.trim(), form.url.trim()) else act.updateSubscription(form.name, form.url.trim())
                pop()
                act.onMessage("订阅已保存；重启代理后生效")
                refreshConfigs()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                subscription = subscription.copy(saving = false)
                act.onMessage(error.reason("订阅保存失败"))
            }
        }
    }

    fun runImport() {
        val form = import
        if (form.importing) return
        val file = pickedFile
        if (form.tab == ToolsImportTab.Link) {
            val urlError = ToolsRules.urlError(form.url)
            if (urlError != null) { import = form.copy(urlError = urlError); return }
        } else if (file == null) {
            return
        }
        import = form.copy(urlError = null, importing = true)
        scope.launch {
            try {
                val saved = if (form.tab == ToolsImportTab.Link) {
                    act.importFromUrl(form.url.trim(), ToolsRules.importName(form.url, form.name))
                } else {
                    act.importFile(checkNotNull(file))
                }
                act.onClearPickedFile()
                pop()
                act.onMessage("已导入并选中 $saved；原配置未覆盖")
                refreshConfigs()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                import = import.copy(importing = false)
                act.onMessage(error.reason("导入配置失败"))
            }
        }
    }

    fun leaveForm() {
        if (subscription.saving || import.importing) return
        val dirty = when (top) {
            ToolsDestination.Subscription -> subscription.dirty
            ToolsDestination.Import -> import.dirty(pickedFile)
            else -> false
        }
        if (dirty) discardForm = true else { act.onClearPickedFile(); pop() }
    }

    /* ------------------------------ 配置编辑 ------------------------------ */

    val draft = document.let { it != null && buffer.text != it.text }

    /** First read, and 重新读取 from the failed state: there is no draft to protect. */
    fun loadDocument() {
        editor = ToolsEditorState(ToolsLoad.Loading, fileName = editor.fileName)
        scope.launch {
            try {
                val source = act.readConfig()
                document = source
                buffer.reset(source.text)
                editor = ToolsEditorState(ToolsLoad.Ready, source.name)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                document = null
                editor = ToolsEditorState(ToolsLoad.Failed(error.reason("源文件暂时无法读取")), editor.fileName)
            }
        }
    }

    /** 重新读取 with a draft on screen: the draft survives a failed read. */
    fun reloadDocument() {
        if (reloading) return
        reloading = true
        scope.launch {
            try {
                val source = act.readConfig()
                document = source
                buffer.reset(source.text)
                editor = ToolsEditorState(ToolsLoad.Ready, source.name)
                editorOverlay = null
                act.onMessage("已读取最新内容")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                editorOverlay = null
                act.onMessage("读取失败，草稿已保留：${error.reason("源文件暂时无法读取")}")
            }
            reloading = false
        }
    }

    fun requestReload() {
        if (draft) editorOverlay = ToolsEditorOverlay.ConfirmReload else reloadDocument()
    }

    fun showIssue(message: String, line: Int?) {
        editor = editor.copy(banner = "配置无效：$message", errorLine = line, saving = false, validating = false)
        if (line != null) buffer.jumpToLine(line)
    }

    /** Quick local lint; returns true when it found something (and reported it). */
    fun lintFails(): Boolean {
        val issue = ToolsYaml.lint(buffer.text) ?: return false
        showIssue(issue.message, issue.line)
        return true
    }

    fun validate() {
        if (!editor.ready || editor.validating || editor.saving || lintFails()) return
        val text = buffer.text
        editor = editor.copy(validating = true)
        scope.launch {
            try {
                act.validateConfig(text)
                editor = editor.copy(banner = null, errorLine = null, validating = false)
                act.onMessage("校验通过")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = error.reason("校验失败")
                showIssue(reason, ToolsYaml.errorLine(reason))
            }
        }
    }

    fun save() {
        val source = document ?: return
        if (!editor.ready || editor.saving || !draft || lintFails()) return
        val text = buffer.text
        editor = editor.copy(saving = true)
        scope.launch {
            try {
                when (val result = act.saveConfig(source, text)) {
                    ToolsSaveResult.Saved -> {
                        document = source.copy(text = text)
                        editor = editor.copy(banner = null, errorLine = null, saving = false)
                        act.onMessage("YAML 已保存；重启代理后生效")
                    }
                    is ToolsSaveResult.Invalid -> showIssue(result.message, ToolsYaml.errorLine(result.message))
                    is ToolsSaveResult.SelectionChanged -> { editor = editor.copy(saving = false); editorOverlay = ToolsEditorOverlay.SaveConflict(result.current) }
                    ToolsSaveResult.SourceChanged -> { editor = editor.copy(saving = false); editorOverlay = ToolsEditorOverlay.SourceChanged }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                val reason = error.reason("保存失败")
                showIssue(reason, ToolsYaml.errorLine(reason))
            }
        }
    }

    fun leaveEditor() {
        if (editor.saving) return
        if (draft) editorOverlay = ToolsEditorOverlay.ConfirmDiscard else pop()
    }

    /* ------------------------------ 返回键 ------------------------------ */

    BackHandler(enabled = stack.size > 1 || root.searching) {
        when (top) {
            ToolsDestination.Root -> root = ToolsRootState(groups = root.groups)
            ToolsDestination.Configs -> if (configOverlay != null) configOverlay = null else pop()
            ToolsDestination.Subscription, ToolsDestination.Import -> leaveForm()
            ToolsDestination.Editor -> leaveEditor()
            else -> pop()
        }
    }

    // Another tab asked for one of these pages: open it on top of the root, once.
    val consumeRequest by rememberUpdatedState(onRequestConsumed)
    LaunchedEffect(requestedEntry) {
        val entry = requestedEntry ?: return@LaunchedEffect
        val feature = entry.featureDestination()
        when {
            entry == ToolsEntry.Configs -> {
                if (top != ToolsDestination.Configs) {
                    stackNames = listOf(ToolsDestination.Root.name, ToolsDestination.Configs.name)
                }
                refreshConfigs(showSpinner = configs.load !is ToolsLoad.Ready)
            }
            feature != null && features?.hosts(entry) == true -> stackNames = listOf(ToolsDestination.Root.name, feature.name)
            else -> act.onOpenEntry(entry)
        }
        consumeRequest()
    }

    /* ------------------------------ 页面 ------------------------------ */

    AnimatedContent(
        targetState = top,
        modifier = modifier,
        transitionSpec = {
            val forward = targetState.ordinal > initialState.ordinal
            val enter = slideInHorizontally(tween(HomeMotion.PageMs, easing = HomeMotion.Emphasized)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(HomeMotion.PageMs))
            val exit = slideOutHorizontally(tween(HomeMotion.PageMs, easing = HomeMotion.Emphasized)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(HomeMotion.SwitchMs))
            if (motion) enter togetherWith exit else androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
        },
        label = "tools-page",
    ) { page ->
        when (page) {
            ToolsDestination.Root -> ToolsScreen(
                state = root,
                onToggleSearch = { root = root.copy(searching = !root.searching, query = "") },
                onQueryChange = { root = root.copy(query = it) },
                onOpen = { entry ->
                    val feature = entry.featureDestination()
                    if (entry == ToolsEntry.Configs) {
                        refreshConfigs(showSpinner = configs.load !is ToolsLoad.Ready)
                        push(ToolsDestination.Configs)
                    } else if (feature != null && features?.hosts(entry) == true) {
                        push(feature)
                    } else {
                        act.onOpenEntry(entry)
                    }
                },
                contentPadding = contentPadding,
            )

            ToolsDestination.Configs -> ToolsConfigScreen(
                state = configs,
                menuFor = (configOverlay as? ToolsConfigOverlay.Menu)?.config,
                onBack = { configOverlay = null; pop() },
                onImport = { import = ToolsImportForm(); act.onClearPickedFile(); push(ToolsDestination.Import) },
                onSelectConfig = ::selectConfig,
                onOpenMenu = { configOverlay = ToolsConfigOverlay.Menu(it.name) },
                onDismissMenu = { if (configOverlay is ToolsConfigOverlay.Menu) configOverlay = null },
                onMenuItem = { config, item ->
                    when (item) {
                        ToolsConfigMenuItem.SetCurrent -> selectConfig(config)
                        ToolsConfigMenuItem.Export -> { configOverlay = null; act.exportConfig(config.name) }
                        ToolsConfigMenuItem.Rename -> configOverlay = ToolsConfigOverlay.Rename(config.name, config.name)
                        ToolsConfigMenuItem.Delete -> configOverlay = ToolsConfigOverlay.DeleteConfig(config.name, config.current)
                    }
                },
                onEditYaml = {
                    val externalEditor = act.onOpenEditor
                    if (externalEditor != null) externalEditor()
                    else { editorOverlay = null; loadDocument(); push(ToolsDestination.Editor) }
                },
                onAddSubscription = { subscription = ToolsSubscriptionForm.add(); push(ToolsDestination.Subscription) },
                onEditSubscription = { subscription = ToolsSubscriptionForm.edit(it); push(ToolsDestination.Subscription) },
                onDeleteSubscription = { configOverlay = ToolsConfigOverlay.DeleteSubscription(configs.current?.name.orEmpty(), it.name) },
                onRetry = { refreshConfigs(showSpinner = true) },
                onRefresh = { refreshConfigs(showSpinner = true) },
            )

            ToolsDestination.Subscription -> ToolsSubscriptionScreen(
                form = subscription,
                onNameChange = { subscription = subscription.copy(name = it, nameError = null) },
                onUrlChange = { subscription = subscription.copy(url = it, urlError = null) },
                onSave = ::saveSubscription,
                onBack = ::leaveForm,
            )

            ToolsDestination.Import -> ToolsImportScreen(
                form = import,
                pickedFile = pickedFile,
                onTabChange = { import = import.copy(tab = it, urlError = null) },
                onUrlChange = { import = import.copy(url = it, urlError = null) },
                onNameChange = { import = import.copy(name = it) },
                onPickFile = { act.onPickImportFile() },
                onImport = ::runImport,
                onBack = ::leaveForm,
            )

            ToolsDestination.Editor -> ToolsEditorScreen(
                state = editor.copy(dirty = draft, canUndo = buffer.canUndo, canRedo = buffer.canRedo),
                editor = buffer,
                onBack = ::leaveEditor,
                onSave = ::save,
                onOutline = { editorOverlay = ToolsEditorOverlay.Outline(ToolsYaml.outline(buffer.text)) },
                onValidate = ::validate,
                onDismissBanner = { editor = editor.copy(banner = null, errorLine = null) },
                onReload = ::loadDocument,
            )

            else -> if (features != null) ToolsFeaturePage(page, features, onBack = ::pop, appIcon = appIcon)
        }
    }

    /* ------------------------------ 浮层 ------------------------------ */

    when (val overlay = configOverlay) {
        is ToolsConfigOverlay.Rename -> ToolsDialog(onDismiss = { if (!overlay.saving) configOverlay = null }) {
            ToolsRenameConfigDialogCard(
                overlay,
                onDraftChange = { configOverlay = overlay.copy(draft = it, error = null) },
                onConfirm = { confirmRename(overlay) },
                onCancel = { configOverlay = null },
            )
        }
        is ToolsConfigOverlay.DeleteConfig -> ToolsDialog(onDismiss = { configOverlay = null }) {
            ToolsDeleteConfigDialogCard(
                overlay,
                onConfirm = {
                    configOverlay = null
                    mutateConfigs("已删除 ${overlay.config}", "删除配置失败") { act.deleteConfig(overlay.config) }
                },
                onCancel = { configOverlay = null },
            )
        }
        is ToolsConfigOverlay.DeleteSubscription -> ToolsDialog(onDismiss = { configOverlay = null }) {
            ToolsDeleteSubscriptionDialogCard(
                overlay,
                onConfirm = {
                    configOverlay = null
                    mutateConfigs("已删除 ${overlay.subscription}；重启代理后生效", "删除订阅失败") { act.deleteSubscription(overlay.subscription) }
                },
                onCancel = { configOverlay = null },
            )
        }
        is ToolsConfigOverlay.Menu, null -> Unit
    }

    if (discardForm) {
        ToolsDialog(onDismiss = { discardForm = false }) {
            ToolsDiscardFormDialogCard(
                onConfirm = { discardForm = false; act.onClearPickedFile(); pop() },
                onCancel = { discardForm = false },
            )
        }
    }

    when (val overlay = editorOverlay) {
        is ToolsEditorOverlay.Outline -> HomeModalSheet(onDismiss = { editorOverlay = null }) {
            ToolsOutlineSheetContent(
                overlay.items,
                onJump = { editorOverlay = null; buffer.jumpToLine(it.line) },
                onClose = { editorOverlay = null },
            )
        }
        is ToolsEditorOverlay.SaveConflict -> HomeModalSheet(onDismiss = { editorOverlay = null }) {
            ToolsSaveConflictSheetContent(overlay.current, onKeepDraft = { editorOverlay = null }, onReload = ::requestReload)
        }
        ToolsEditorOverlay.SourceChanged -> HomeModalSheet(onDismiss = { editorOverlay = null }) {
            ToolsSourceChangedSheetContent(onKeepDraft = { editorOverlay = null }, onReload = ::requestReload)
        }
        ToolsEditorOverlay.ConfirmReload -> ToolsDialog(onDismiss = { if (!reloading) editorOverlay = null }) {
            ToolsReloadDraftDialogCard(onConfirm = ::reloadDocument, onCancel = { editorOverlay = null }, loading = reloading)
        }
        ToolsEditorOverlay.ConfirmDiscard -> ToolsDialog(onDismiss = { editorOverlay = null }) {
            ToolsDiscardEditsDialogCard(onConfirm = { editorOverlay = null; pop() }, onCancel = { editorOverlay = null })
        }
        null -> Unit
    }
}

private fun Throwable.reason(fallback: String): String = message?.takeIf { it.isNotBlank() } ?: fallback
