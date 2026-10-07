package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeBarScaffold
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeNotice
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.HomeVerticalDivider
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.ui.ht

/**
 * 编辑配置 (pushed from 配置与订阅 › 编辑当前 YAML). Stateless: text and history live in [editor],
 * everything else in [state].
 *
 * - File row (amber “未保存” caption while dirty), tools (撤销 / 重做 / 大纲 / 校验), the code
 *   area, and accessory keys pinned above the keyboard. The save glyph in the bar turns accent
 *   once there is a draft.
 * - A failed validation unfolds a red notice with 关闭提示; the offending line is tinted.
 * - When the source could not be read, tools and keys are dimmed and the code area shows an
 *   empty state with 重新读取.
 * - The sheets and dialogs of this page are hosted by `ToolsRoute`; their contents are at the
 *   bottom of this file.
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
    HomeBarScaffold(
        title = "编辑配置",
        onBack = onBack,
        lifted = false,
        modifier = modifier,
        actions = {
            HomeIconButton(
                HomeIcons.Save, "保存", onSave,
                enabled = usable && state.dirty, loading = state.saving,
                tint = if (state.ready && state.dirty) c.accent else c.t3, glyph = 26.dp,
            )
        },
        footer = { SymbolBar(enabled = usable, onInsert = editor::insert) },
    ) { top, _ ->
        Column(
            Modifier.fillMaxSize().padding(start = HomeDims.gutter, end = HomeDims.gutter, top = top + 6.dp),
            verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            FileCard(state)
            ToolCard(state, usable, editor::undo, editor::redo, onOutline, onValidate)
            Column(Modifier.fillMaxWidth().weight(1f)) {
                // The notice brings its own gap, so it can unfold without making the editor jump.
                HomeReveal(state.banner != null) {
                    HomeNotice(
                        state.banner.orEmpty(), HomeIcons.CircleAlert, Modifier.padding(bottom = HomeDims.gap),
                        tone = HomeTone.Bad, actionLabel = "关闭提示", onAction = onDismissBanner,
                    )
                }
                HomeCard(Modifier.fillMaxWidth().weight(1f)) {
                    when (val load = state.load) {
                        ToolsLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { HomeSpinner(size = 22.dp, color = c.t3) }
                        is ToolsLoad.Failed -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                            ToolsEmpty(ToolsIcons.FileWarning, "配置读取失败", subtitle = load.message, topPadding = 24.dp) {
                                ToolsButton("重新读取", onReload, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
                            }
                        }
                        ToolsLoad.Ready -> codeArea(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(state: ToolsEditorState) {
    val c = LocalHomeColors.current
    val draft = state.ready && state.dirty
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsRow(
            title = AnnotatedString(state.title),
            icon = if (state.failed) ToolsIcons.FileWarning else ToolsIcons.FileText,
            iconTint = if (state.failed) c.t3 else c.t1,
            subtitle = ht(state.caption),
            subtitleColor = if (draft) c.warnText else c.t2,
            subtitleMaxLines = 1,
        )
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
    HomeCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.Undo2, "撤销", onUndo, enabled = usable && state.canUndo) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.Redo2, "重做", onRedo, enabled = usable && state.canRedo) }
            HomeVerticalDivider(Modifier.height(22.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { HomeIconButton(ToolsIcons.List, "语法大纲", onOutline, enabled = usable) }
            HomeVerticalDivider(Modifier.height(22.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                HomeIconButton(HomeIcons.CircleCheck, "语法校验", onValidate, enabled = usable && !state.validating, loading = state.validating)
            }
        }
    }
}

/** Accessory keys above the keyboard (or the navigation bar when the keyboard is closed). */
@Composable
private fun SymbolBar(enabled: Boolean, onInsert: (String) -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    HomeCard(Modifier.fillMaxWidth().alpha(if (enabled) 1f else .45f)) {
        Row(Modifier.fillMaxWidth().padding(7.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ToolsYaml.symbols.forEach { symbol ->
                val label = ToolsYaml.symbolLabel(symbol)
                val spoken = if (symbol == "  ") ht("插入缩进") else ht("插入") + " " + label
                Box(
                    Modifier
                        .weight(1f)
                        .height(42.dp)
                        .homeTap(enabled = enabled, role = Role.Button) { haptics(HomeHaptic.Tick); onInsert(symbol) }
                        .clip(ToolsDims.keyShape)
                        .background(if (c.dark) c.sunken else c.bg)
                        .semantics { contentDescription = spoken },
                    contentAlignment = Alignment.Center,
                ) { Text(label, color = c.t1, style = ToolsTypography.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1) }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets                                                              */
/* ------------------------------------------------------------------ */

/** Top-level keys of the document with their line numbers; nested names are indented. */
@Composable
internal fun ToolsOutlineSheetContent(items: List<ToolsOutlineItem>, onJump: (ToolsOutlineItem) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    ToolsSheetContent(title = "语法大纲", modifier = modifier, onClose = onClose) {
        if (items.isEmpty()) {
            Text(ht("没有可识别的顶层字段。"), Modifier.padding(horizontal = 6.dp, vertical = 8.dp), color = c.t2, style = HomeType.note)
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .homeTap(role = Role.Button) { haptics(HomeHaptic.Tap); onJump(item) }
                            .clip(HomeDims.controlShape)
                            .background(if (c.dark) c.sunken else c.bg)
                            .padding(start = 16.dp + 22.dp * item.level, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(item.title, Modifier.weight(1f), color = c.accent, style = HomeType.label.copy(fontWeight = if (item.level == 0) FontWeight.Bold else FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.line.toString(), color = c.t2, style = HomeType.delay)
                        ToolsChevron()
                    }
                }
            }
        }
    }
}

/** Another config was selected while this one was being edited. */
@Composable
internal fun ToolsSaveConflictSheetContent(current: String, onKeepDraft: () -> Unit, onReload: () -> Unit, modifier: Modifier = Modifier) {
    DraftConflictSheet(
        title = "保存遇到冲突",
        text = "当前配置已发生变化，无法保存。\n当前选择为「$current」。你的草稿仍保留，重新读取会打开当前选择。",
        onKeepDraft = onKeepDraft, onReload = onReload, modifier = modifier,
    )
}

/** The source file changed on disk while it was being edited. */
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
    ToolsSheetContent(
        title = title, modifier = modifier, onClose = onKeepDraft,
        footer = {
            ToolsButton("保留草稿", onKeepDraft, Modifier.fillMaxWidth(), kind = HomeButtonKind.Soft)
            ToolsButton("重新读取", onReload, Modifier.fillMaxWidth(), kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
        },
    ) { Text(text, Modifier.padding(horizontal = 6.dp), color = c.t2, style = HomeType.body) }
}

/* ------------------------------------------------------------------ */
/*  Dialog cards                                                        */
/* ------------------------------------------------------------------ */

/** Reached from 重新读取 on either sheet while there is a draft. */
@Composable
internal fun ToolsReloadDraftDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, loading: Boolean = false) {
    ToolsDialogCard(
        title = "放弃草稿并重新读取？",
        text = "重新读取成功后，本页未保存的修改和撤销记录会被替换为当前配置的最新内容。\n读取失败会继续保留草稿。",
        confirmLabel = "放弃并读取", confirmKind = ToolsConfirmKind.DangerSoft, confirmLoading = loading,
        icon = HomeIcons.TriangleAlert, iconTone = HomeTone.Warn, stacked = true,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

/** Shown when leaving the editor with a draft. */
@Composable
internal fun ToolsDiscardEditsDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        title = "放弃修改？", text = "当前修改还没有保存。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.DangerSoft,
        icon = HomeIcons.TriangleAlert, iconTone = HomeTone.Warn,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
