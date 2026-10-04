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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.home.LocalHomeColors

/* ------------------------------------------------------------------ */
/*  One preview per concept page: C01–C25 = 03A 工具上册 第 1–25 页.     */
/*  Dialogs, sheets and menus are drawn in-place over a scrim, because  */
/*  window-based overlays do not render in static previews.             */
/* ------------------------------------------------------------------ */

private const val W = 392
private const val H = 850

@Composable
private fun Frame(dark: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    HetuHomeTheme(dark = dark) {
        Box(Modifier.fillMaxSize().background(LocalHomeColors.current.bg), content = content)
    }
}

@Composable
private fun BoxScope.Scrim() {
    Box(Modifier.matchParentSize().background(LocalHomeColors.current.scrim))
}

@Composable
private fun BoxScope.DialogLayer(content: @Composable () -> Unit) {
    Scrim()
    Box(Modifier.align(Alignment.Center).padding(horizontal = 28.dp)) { content() }
}

@Composable
private fun BoxScope.SheetLayer(content: @Composable () -> Unit) {
    Scrim()
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(HomeDims.sheetShape).background(LocalHomeColors.current.surface)) { content() }
}

@Composable
private fun Root(state: ToolsRootState, scrolled: Int = 0) {
    ToolsScreen(state, onToggleSearch = {}, onQueryChange = {}, onOpen = {}, scroll = rememberScrollState(initial = scrolled))
}

@Composable
private fun Configs(state: ToolsConfigState = ToolsSamples.configState) {
    ToolsConfigScreen(
        state = state, menuFor = null, onBack = {}, onImport = {}, onSelectConfig = {}, onOpenMenu = {}, onDismissMenu = {},
        onMenuItem = { _, _ -> }, onEditYaml = {}, onAddSubscription = {}, onEditSubscription = {}, onDeleteSubscription = {}, onRetry = {},
    )
}

/** The row menu of the config at [index], placed where the popup would hang. */
@Composable
private fun BoxScope.ConfigMenu(index: Int) {
    Box(Modifier.align(Alignment.TopEnd).padding(top = 188.dp + 57.dp * index, end = 20.dp)) {
        ToolsConfigMenuCard(ToolsSamples.configs[index]) {}
    }
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

@Preview(name = "C01 工具 · 展开", widthDp = W, heightDp = H)
@Composable
private fun C01ToolsExpanded() = Frame { Root(ToolsRootState()) }

@Preview(name = "C02 工具 · 折叠顶栏", widthDp = W, heightDp = 640)
@Composable
private fun C02ToolsCollapsed() = Frame { Root(ToolsRootState(), scrolled = 600) }

@Preview(name = "C03 工具 · 搜索", widthDp = W, heightDp = H)
@Composable
private fun C03ToolsSearch() = Frame { Root(ToolsSamples.rootSearch) }

/* ------------------------------ 配置与订阅 ------------------------------ */

@Preview(name = "C04 配置与订阅 · 列表", widthDp = W, heightDp = H)
@Composable
private fun C04Configs() = Frame { Configs() }

@Preview(name = "C05 添加订阅", widthDp = W, heightDp = H)
@Composable
private fun C05AddSubscription() = Frame { Subscription(ToolsSamples.addSubscription) }

@Preview(name = "C06 配置菜单 · 当前配置", widthDp = W, heightDp = H)
@Composable
private fun C06MenuCurrent() = Frame { Configs(); ConfigMenu(0) }

@Preview(name = "C07 配置菜单 · 其他配置", widthDp = W, heightDp = H)
@Composable
private fun C07MenuOther() = Frame { Configs(); ConfigMenu(1) }

@Preview(name = "C08 配置菜单 · 内置模板", widthDp = W, heightDp = H)
@Composable
private fun C08MenuBundled() = Frame { Configs(); ConfigMenu(2) }

@Preview(name = "C09 配置 · 重命名", widthDp = W, heightDp = H)
@Composable
private fun C09Rename() = Frame {
    Configs()
    DialogLayer { ToolsRenameConfigDialogCard(ToolsConfigOverlay.Rename("工作.yaml", "工作专用.yaml"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C10 配置 · 删除确认", widthDp = W, heightDp = H)
@Composable
private fun C10DeleteConfig() = Frame {
    Configs()
    DialogLayer { ToolsDeleteConfigDialogCard(ToolsConfigOverlay.DeleteConfig("日常.yaml", isCurrent = true), onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C11 订阅 · 删除确认", widthDp = W, heightDp = H)
@Composable
private fun C11DeleteSubscription() = Frame {
    Configs()
    DialogLayer { ToolsDeleteSubscriptionDialogCard(ToolsConfigOverlay.DeleteSubscription("日常.yaml", "主订阅"), onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C12 导入配置 · 放弃填写", widthDp = W, heightDp = H)
@Composable
private fun C12ImportDiscard() = Frame {
    Import(ToolsSamples.importLink)
    DialogLayer { ToolsDiscardFormDialogCard(onConfirm = {}, onCancel = {}) }
}

/* ------------------------------ 配置编辑 ------------------------------ */

@Preview(name = "C13 配置编辑 · 语法大纲", widthDp = W, heightDp = H)
@Composable
private fun C13Outline() = Frame {
    Editor()
    SheetLayer { ToolsOutlineSheetContent(ToolsYaml.outline(ToolsSamples.yaml), onJump = {}, onClose = {}) }
}

@Preview(name = "C14 配置编辑 · 选择变更冲突", widthDp = W, heightDp = H)
@Composable
private fun C14SaveConflict() = Frame {
    Editor()
    SheetLayer { ToolsSaveConflictSheetContent("工作.yaml", onKeepDraft = {}, onReload = {}) }
}

@Preview(name = "C15 配置编辑 · 校验失败", widthDp = W, heightDp = H)
@Composable
private fun C15Invalid() = Frame { Editor(ToolsSamples.editorInvalid, ToolsSamples.yamlInvalid) }

@Preview(name = "C16 配置编辑 · 读取失败", widthDp = W, heightDp = H)
@Composable
private fun C16LoadFailed() = Frame { Editor(ToolsSamples.editorLoadFailed, "") }

@Preview(name = "C17 配置编辑 · 文件外部修改", widthDp = W, heightDp = H)
@Composable
private fun C17SourceChanged() = Frame {
    Editor()
    SheetLayer { ToolsSourceChangedSheetContent(onKeepDraft = {}, onReload = {}) }
}

@Preview(name = "C18 配置编辑 · 放弃并重新读取", widthDp = W, heightDp = H)
@Composable
private fun C18ConfirmReload() = Frame {
    Editor()
    DialogLayer { ToolsReloadDraftDialogCard(onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C19 配置编辑 · 放弃修改", widthDp = W, heightDp = H)
@Composable
private fun C19ConfirmDiscard() = Frame {
    Editor()
    DialogLayer { ToolsDiscardEditsDialogCard(onConfirm = {}, onCancel = {}) }
}

/* ------------------------------ 表单校验 ------------------------------ */

@Preview(name = "C20 导入配置 · 链接错误", widthDp = W, heightDp = H)
@Composable
private fun C20ImportUrlInvalid() = Frame { Import(ToolsSamples.importLinkInvalid) }

@Preview(name = "C21 添加订阅 · 名称为空", widthDp = W, heightDp = H)
@Composable
private fun C21AddNameEmpty() = Frame { Subscription(ToolsSamples.addSubscriptionNameEmpty) }

@Preview(name = "C22 添加订阅 · 链接错误", widthDp = W, heightDp = H)
@Composable
private fun C22AddUrlInvalid() = Frame { Subscription(ToolsSamples.addSubscriptionUrlInvalid) }

@Preview(name = "C23 编辑订阅 · 链接错误", widthDp = W, heightDp = H)
@Composable
private fun C23EditUrlInvalid() = Frame { Subscription(ToolsSamples.editSubscriptionUrlInvalid) }

@Preview(name = "C24 导入配置 · 链接", widthDp = W, heightDp = H)
@Composable
private fun C24ImportLink() = Frame { Import(ToolsSamples.importLink) }

@Preview(name = "C25 配置编辑 · YAML", widthDp = W, heightDp = H)
@Composable
private fun C25Editor() = Frame { Editor() }

/* ------------------------------ 补充 ------------------------------ */

@Preview(name = "X1 工具 · 搜索无结果", widthDp = W, heightDp = H)
@Composable
private fun X1SearchEmpty() = Frame { Root(ToolsSamples.rootSearchEmpty) }

@Preview(name = "X2 配置与订阅 · 无订阅", widthDp = W, heightDp = H)
@Composable
private fun X2NoSubscriptions() = Frame { Configs(ToolsSamples.configStateNoSubscriptions) }

@Preview(name = "X3 配置与订阅 · 读取失败", widthDp = W, heightDp = H)
@Composable
private fun X3ConfigsFailed() = Frame { Configs(ToolsConfigState(ToolsLoad.Failed("配置库暂时无法读取"))) }

@Preview(name = "X4 配置与订阅 · 深色", widthDp = W, heightDp = H)
@Composable
private fun X4ConfigsDark() = Frame(dark = true) { Configs() }

@Preview(name = "X5 配置编辑 · 深色", widthDp = W, heightDp = H)
@Composable
private fun X5EditorDark() = Frame(dark = true) { Editor() }

/** The whole flow on sample data: run it in interactive mode to walk pages 1–25. */
@Preview(name = "X6 交互流程（示例数据）", widthDp = W, heightDp = H)
@Composable
private fun X6Flow() = Frame {
    val actions = remember {
        ToolsActions(
            loadConfigs = { ToolsConfigSnapshot(ToolsSamples.configs, ToolsSamples.subscriptions) },
            readConfig = { ToolsConfigDocument("日常.yaml", ToolsSamples.yaml) },
        )
    }
    ToolsRoute(actions)
}
