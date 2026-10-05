package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.LocalHomeColors

/* ------------------------------------------------------------------ */
/*  One preview per concept page: C01–C25 = 03A 工具上册 第 1–25 页.     */
/*  Dialogs, sheets and menus use their production windows;  */
/*  fixtures are test-only and do not change runtime data.             */
/* ------------------------------------------------------------------ */

private const val W = 392
private const val H = 850

@Composable
private fun Frame(dark: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    io.github.xgl34222220.hetu.HetuAppTheme(appearance = if (dark) "dark" else "light", dynamic = false) {
        ToolsConceptTheme {
            Box(Modifier.fillMaxSize().background(LocalHomeColors.current.bg), content = content)
        }
    }
}

@Composable
private fun BoxScope.Scrim() {
    Box(Modifier.matchParentSize().background(LocalHomeColors.current.scrim))
}

@Composable
private fun BoxScope.DialogLayer(content: @Composable () -> Unit) { ToolsDialog(onDismiss = {}) { content() } }

@Composable
private fun BoxScope.SheetLayer(content: @Composable () -> Unit) { io.github.xgl34222220.hetu.home.HomeModalSheet(onDismiss = {}) { content() } }

@Composable
private fun Root(state: ToolsRootState, scrolled: Int = 0) {
    ToolsScreen(state, onToggleSearch = {}, onQueryChange = {}, onOpen = {}, scroll = rememberScrollState(initial = scrolled))
}

@Composable
private fun Configs(state: ToolsConfigState = ToolsSamples.configState, menuFor: String? = null) {
    ToolsConfigScreen(
        state = state, menuFor = menuFor, onBack = {}, onImport = {}, onSelectConfig = {}, onOpenMenu = {}, onDismissMenu = {},
        onMenuItem = { _, _ -> }, onEditYaml = {}, onAddSubscription = {}, onEditSubscription = {}, onDeleteSubscription = {}, onRetry = {},
    )
}


@Composable
private fun Subscription(form: ToolsSubscriptionForm) {
    ToolsSubscriptionScreen(form, onNameChange = {}, onUrlChange = {}, onSave = {}, onBack = {})
}

@Composable
private fun Import(form: ToolsImportForm) {
    ToolsImportScreen(form, pickedFile = null, onTabChange = {}, onUrlChange = {}, onNameChange = {}, onPickFile = {}, onImport = {}, onBack = {})
}

@Composable
private fun Editor(state: ToolsEditorState = ToolsSamples.editorDraft, text: String = ToolsSamples.yaml) {
    val buffer = remember(text) { ToolsYamlEditorState(text) }
    ToolsEditorScreen(state, buffer, onBack = {}, onSave = {}, onOutline = {}, onValidate = {}, onDismissBanner = {}, onReload = {})
}

/* ------------------------------ 工具 ------------------------------ */

@Composable
internal fun Pdf85C01ToolsExpanded() = Frame { Root(ToolsRootState()) }

@Composable
internal fun Pdf85C02ToolsCollapsed() = Frame { Root(ToolsRootState(), scrolled = 600) }

@Composable
internal fun Pdf85C03ToolsSearch() = Frame { Root(ToolsSamples.rootSearch) }

/* ------------------------------ 配置与订阅 ------------------------------ */

@Composable
internal fun Pdf85C04Configs() = Frame { Configs() }

@Composable
internal fun Pdf85C05AddSubscription() = Frame { Subscription(ToolsSamples.addSubscription) }

@Composable
internal fun Pdf85C06MenuCurrent() = Frame { Configs(menuFor = ToolsSamples.configs[0].name) }

@Composable
internal fun Pdf85C07MenuOther() = Frame { Configs(menuFor = ToolsSamples.configs[1].name) }

@Composable
internal fun Pdf85C08MenuBundled() = Frame { Configs(menuFor = ToolsSamples.configs[2].name) }

@Composable
internal fun Pdf85C09Rename() = Frame {
    Configs()
    DialogLayer { ToolsRenameConfigDialogCard(ToolsConfigOverlay.Rename("工作.yaml", "工作专用.yaml"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C10DeleteConfig() = Frame {
    Configs()
    DialogLayer { ToolsDeleteConfigDialogCard(ToolsConfigOverlay.DeleteConfig("日常.yaml", isCurrent = true), onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C11DeleteSubscription() = Frame {
    Configs()
    DialogLayer { ToolsDeleteSubscriptionDialogCard(ToolsConfigOverlay.DeleteSubscription("日常.yaml", "主订阅"), onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C12ImportDiscard() = Frame {
    Import(ToolsSamples.importLink)
    DialogLayer { ToolsDiscardFormDialogCard(onConfirm = {}, onCancel = {}) }
}

/* ------------------------------ 配置编辑 ------------------------------ */

@Composable
internal fun Pdf85C13Outline() = Frame {
    Editor()
    SheetLayer { ToolsOutlineSheetContent(ToolsYaml.outline(ToolsSamples.yaml), onJump = {}, onClose = {}) }
}

@Composable
internal fun Pdf85C14SaveConflict() = Frame {
    Editor()
    SheetLayer { ToolsSaveConflictSheetContent("工作.yaml", onKeepDraft = {}, onReload = {}) }
}

@Composable
internal fun Pdf85C15Invalid() = Frame { Editor(ToolsSamples.editorInvalid, ToolsSamples.yamlInvalid) }

@Composable
internal fun Pdf85C16LoadFailed() = Frame { Editor(ToolsSamples.editorLoadFailed, "") }

@Composable
internal fun Pdf85C17SourceChanged() = Frame {
    Editor()
    SheetLayer { ToolsSourceChangedSheetContent(onKeepDraft = {}, onReload = {}) }
}

@Composable
internal fun Pdf85C18ConfirmReload() = Frame {
    Editor()
    DialogLayer { ToolsReloadDraftDialogCard(onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C19ConfirmDiscard() = Frame {
    Editor()
    DialogLayer { ToolsDiscardEditsDialogCard(onConfirm = {}, onCancel = {}) }
}

/* ------------------------------ 表单校验 ------------------------------ */

@Composable
internal fun Pdf85C20ImportUrlInvalid() = Frame { Import(ToolsSamples.importLinkInvalid) }

@Composable
internal fun Pdf85C21AddNameEmpty() = Frame { Subscription(ToolsSamples.addSubscriptionNameEmpty) }

@Composable
internal fun Pdf85C22AddUrlInvalid() = Frame { Subscription(ToolsSamples.addSubscriptionUrlInvalid) }

@Composable
internal fun Pdf85C23EditUrlInvalid() = Frame { Subscription(ToolsSamples.editSubscriptionUrlInvalid) }

@Composable
internal fun Pdf85C24ImportLink() = Frame { Import(ToolsSamples.importLink) }

@Composable
internal fun Pdf85C25Editor() = Frame { Editor() }

