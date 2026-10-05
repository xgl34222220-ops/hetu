package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeBanner
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsHairline as HomeDivider
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.tools.ToolsSheetContent as HomeSheetContent
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.tools.ToolsTopBar as HomeTopBar
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.HomeVerticalDivider
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics

/**
 * 编辑配置 (pushed from 配置与订阅 › 编辑当前 YAML). Stateless: text and history live in [editor],
 * everything else in [state].
 *
 * - Page 25: file card (amber “未保存” caption while dirty), tool card (撤销 / 重做 / 大纲 / 校验),
 *   code area, accessory keys. The save glyph in the bar turns accent once there is a draft.
 * - Page 15: red banner under the tool card with 关闭提示; the offending line is tinted.
 * - Page 16: the source could not be read; tools and keys are dimmed, the code area shows
 *   an empty state with 重新读取.
 * - Pages 13, 14, 17 (sheets) and 18, 19 (dialogs) are hosted by `ToolsRoute`; their contents
 *   are at the bottom of this file.
 *
 * @param codeArea the editing widget. Defaults to the Compose [ToolsYamlEditor]; a host that
 *   needs to open very large configs can supply another widget driven by the same [editor].
 */
@Composable
internal fun ToolsEditorScreen(
    state: ToolsEditorState,
    editor: ToolsYamlEditorState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onOutline: () -> Unit,
    onValidate: () -> Unit,
    onDismissBanner: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
    codeArea: @Composable (Modifier) -> Unit = { ToolsYamlEditor(editor, it, errorLine = state.errorLine, enabled = !state.saving) },
) {
    val c = LocalHomeColors.current
    val usable = state.ready && !state.saving
    Column(modifier.fillMaxSize().background(c.bg).imePadding()) {
        HomeTopBar(title = "编辑配置", onBack = onBack) {
            HomeIconButton(
                HomeIcons.Save, "保存", onSave,
                enabled = usable && state.dirty, loading = state.saving,
                tint = if (state.ready && state.dirty) c.accent else c.t3,
            )
        }
        Column(
            Modifier.weight(1f).padding(horizontal = HomeDims.gutter),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            FileCard(state)
            ToolCard(state, usable, editor::undo, editor::redo, onOutline, onValidate)
            if (state.banner != null) {
                HomeBanner(state.banner, HomeIcons.CircleAlert, tone = HomeTone.Bad, actionLabel = ht("关闭提示"), onAction = onDismissBanner)
            }
            HomeCard(Modifier.fillMaxWidth().weight(1f)) {
                when (val load = state.load) {
                    ToolsLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HomeSpinner(size = 20.dp, color = c.t3) }
                    is ToolsLoad.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ToolsEmpty(ToolsIcons.FileWarning, "配置读取失败", subtitle = load.message, iconSize = 60.dp) {
                            HomeButton("重新读取", onReload, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
                        }
                    }
                    ToolsLoad.Ready -> codeArea(Modifier.fillMaxSize())
                }
            }
        }
        SymbolBar(enabled = usable, onInsert = editor::insert)
    }
}

@Composable
private fun FileCard(state: ToolsEditorState) {
    val c = LocalHomeColors.current
    val draft = state.ready && state.dirty
    HomeCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(if (state.failed) ToolsIcons.FileWarning else ToolsIcons.FileText, null, Modifier.size(20.dp), tint = if (state.failed) c.t3 else c.t2)
            Column(Modifier.weight(1f)) {
                Text(state.title, color = c.t1, style = HomeType.rowTitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(ht(state.caption), color = if (draft) c.warn else c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ToolCard(
    state: ToolsEditorState,
    usable: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onOutline: () -> Unit,
    onValidate: () -> Unit,
) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(HomeDims.rowMinHeightSmall), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.Undo2, "撤销", onUndo, enabled = usable && state.canUndo, tint = c.t1) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.Redo2, "重做", onRedo, enabled = usable && state.canRedo, tint = c.t1) }
            HomeVerticalDivider(Modifier.height(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.List, "语法大纲", onOutline, enabled = usable, tint = c.t1) }
            HomeVerticalDivider(Modifier.height(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HomeIconButton(HomeIcons.CircleCheck, "语法校验", onValidate, enabled = usable && !state.validating, loading = state.validating, tint = c.t1)
            }
        }
    }
}

/** Accessory keys above the keyboard (or the navigation bar when the keyboard is closed). */
@Composable
private fun SymbolBar(enabled: Boolean, onInsert: (String) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = HomeDims.gutter, end = HomeDims.gutter, top = HomeDims.gap, bottom = 8.dp)) {
        HomeCard(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)) {
            Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolsYaml.symbols.forEach { symbol ->
                    val label = ToolsYaml.symbolLabel(symbol)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(ToolsDims.keyShape)
                            .background(c.sunken)
                            .clickable(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tick); onInsert(symbol) }
                            .semantics { contentDescription = if (symbol == "  ") "插入缩进" else "插入 $label" },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = c.t1, style = HomeType.mono, maxLines = 1) }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets (pages 13, 14, 17)                                           */
/* ------------------------------------------------------------------ */

/** Page 13. Top-level keys in accent monospace with their line number; nested names are indented. */
@Composable
internal fun ToolsOutlineSheetContent(items: List<ToolsOutlineItem>, onJump: (ToolsOutlineItem) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeSheetContent(title = "语法大纲", modifier = modifier, subtitle = ht("点击跳转到对应区段"), onClose = onClose) {
        if (items.isEmpty()) {
            Text(ht("没有可识别的顶层字段。"), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), color = c.t2, style = HomeType.note)
        } else {
            HomeCard(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) HomeDivider()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 32.dp)
                                .clickable(role = Role.Button) { haptics(HomeHaptic.Tap); onJump(item) }
                                .padding(start = 16.dp + 20.dp * item.level, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(item.title, Modifier.weight(1f), color = c.accent, style = HomeType.mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.line.toString(), color = c.t3, style = HomeType.caption.copy(fontFeatureSettings = "tnum"))
                            ToolsChevron()
                        }
                    }
                }
            }
        }
    }
}

/** Page 14: another config was selected while this one was being edited. */
@Composable
internal fun ToolsSaveConflictSheetContent(current: String, onKeepDraft: () -> Unit, onReload: () -> Unit, modifier: Modifier = Modifier) {
    DraftConflictSheet(
        title = "保存遇到冲突",
        text = "当前配置已发生变化，无法保存。\n当前选择为「$current」。你的草稿仍保留，重新读取会打开当前选择。",
        onKeepDraft = onKeepDraft, onReload = onReload, modifier = modifier,
    )
}

/** Page 17: the source file changed on disk while it was being edited. */
@Composable
internal fun ToolsSourceChangedSheetContent(onKeepDraft: () -> Unit, onReload: () -> Unit, modifier: Modifier = Modifier) {
    DraftConflictSheet(
        title = "文件已在其他位置修改",
        text = "源文件已发生变化，无法保存。\n你的草稿仍保留。可以继续编辑，或明确放弃草稿后读取最新内容。",
        onKeepDraft = onKeepDraft, onReload = onReload, modifier = modifier,
    )
}

@Composable
private fun DraftConflictSheet(title: String, text: String, onKeepDraft: () -> Unit, onReload: () -> Unit, modifier: Modifier) {
    val c = LocalHomeColors.current
    HomeSheetContent(
        title = title, modifier = modifier, onClose = onKeepDraft,
        footer = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeButton("保留草稿", onKeepDraft, Modifier.fillMaxWidth(), kind = HomeButtonKind.Soft)
                HomeButton("重新读取", onReload, Modifier.fillMaxWidth(), kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
        },
    ) { Text(text, Modifier.padding(horizontal = 4.dp), color = c.t2, style = ToolsType.dialogText) }
}

/* ------------------------------------------------------------------ */
/*  Dialog cards (pages 18, 19)                                         */
/* ------------------------------------------------------------------ */

/** Page 18. Reached from 重新读取 on either sheet while there is a draft. */
@Composable
internal fun ToolsReloadDraftDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    ToolsDialogCard(
        actions = ToolsDialogActions.Stacked,
        title = "放弃草稿并重新读取？",
        text = "重新读取成功后，本页未保存的修改和撤销记录会被替换为当前配置的最新内容。\n读取失败会继续保留草稿。",
        confirmLabel = "放弃并读取", confirmKind = ToolsConfirmKind.DangerSoft, confirmLoading = loading,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

/** Page 19. Shown when leaving the editor with a draft. */
@Composable
internal fun ToolsDiscardEditsDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        actions = ToolsDialogActions.Stacked,
        title = "放弃修改？", text = "当前修改还没有保存。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.DangerSoft,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
