package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeCheckMark
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.home.warnText
import io.github.xgl34222220.hetu.ui.ht

/**
 * 配置与订阅 (pushed from 工具 › 配置管理). Stateless.
 *
 * - 配置管理 card: one row per config; the current one carries the accent highlight and a check
 *   that pops in when the selection moves. Tapping a row selects it; «⋯» opens the row menu,
 *   a popup anchored to the button ([menuFor] names the row whose menu is open). The menu's
 *   items depend on the row (current / other / bundled template).
 * - 订阅管理 card: the proxy-providers of the current config.
 * - Rename, delete config and delete subscription are dialogs hosted by `ToolsRoute`; their
 *   cards are at the bottom of this file.
 */
@Composable
internal fun ToolsConfigScreen(
    state: ToolsConfigState,
    menuFor: String?,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onSelectConfig: (ToolsConfig) -> Unit,
    onOpenMenu: (ToolsConfig) -> Unit,
    onDismissMenu: () -> Unit,
    onMenuItem: (ToolsConfig, ToolsConfigMenuItem) -> Unit,
    onEditYaml: () -> Unit,
    onAddSubscription: () -> Unit,
    onEditSubscription: (ToolsSubscription) -> Unit,
    onDeleteSubscription: (ToolsSubscription) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    val ready = state.load is ToolsLoad.Ready
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "配置与订阅",
        onBack = onBack,
        modifier = modifier,
        refreshing = state.refreshing,
        onRefresh = if (state.busy) null else onRefresh,
        actions = { HomeIconButton(ToolsIcons.Plus, "导入配置", onImport, enabled = ready && !state.busy, glyph = 26.dp) },
    ) {
        ToolsLead("管理源配置与当前配置中的订阅链接。")
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading()
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.FileWarning, "配置读取失败", subtitle = load.message) {
                ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> {
                ConfigCard(state, menuFor, onSelectConfig, onOpenMenu, onDismissMenu, onMenuItem, onEditYaml, Modifier.homeEnter(stagger, 0))
                SubscriptionCard(state, onAddSubscription, onEditSubscription, onDeleteSubscription, Modifier.homeEnter(stagger, 1))
            }
        }
    }
}

@Composable
private fun ConfigCard(
    state: ToolsConfigState,
    menuFor: String?,
    onSelectConfig: (ToolsConfig) -> Unit,
    onOpenMenu: (ToolsConfig) -> Unit,
    onDismissMenu: () -> Unit,
    onMenuItem: (ToolsConfig, ToolsConfigMenuItem) -> Unit,
    onEditYaml: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalHomeColors.current
    val currentLabel = ht("当前配置")
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardHeader(ToolsIcons.Folder, "配置管理")
        state.configs.forEach { config ->
            ToolsRow(
                title = AnnotatedString(config.name),
                icon = ToolsIcons.File,
                subtitle = ht(config.caption),
                subtitleMaxLines = 1,
                selected = config.current,
                enabled = !state.busy,
                endPadding = 4.dp,
                onClick = { onSelectConfig(config) },
                trailing = {
                    // Always laid out, so the check can pop in and out as the selection moves.
                    HomeCheckMark(config.current, Modifier.semantics { if (config.current) contentDescription = currentLabel }, size = 26.dp)
                    Box {
                        HomeIconButton(ToolsIcons.Ellipsis, "更多操作", { onOpenMenu(config) }, enabled = !state.busy, tint = c.t2)
                        if (menuFor == config.name) {
                            ToolsMenuPopup(onDismiss = onDismissMenu) {
                                ToolsConfigMenuCard(config) { item -> onMenuItem(config, item) }
                            }
                        }
                    }
                },
            )
        }
        HomeRowDivider(Modifier.padding(top = 4.dp))
        ToolsRow(
            title = AnnotatedString(ht("编辑当前 YAML")),
            icon = ToolsIcons.Pencil,
            titleColor = c.accent,
            iconTint = c.accent,
            enabled = !state.busy && state.current != null,
            onClick = onEditYaml,
            trailing = { ToolsChevron() },
        )
    }
}

@Composable
private fun SubscriptionCard(
    state: ToolsConfigState,
    onAdd: () -> Unit,
    onEdit: (ToolsSubscription) -> Unit,
    onDelete: (ToolsSubscription) -> Unit,
    modifier: Modifier,
) {
    val c = LocalHomeColors.current
    val deleteLabel = "删除订阅"
    HomeCard(modifier.fillMaxWidth()) {
        ToolsCardHeader(
            ToolsIcons.Link, "订阅管理", caption = "当前配置的 proxy-providers。",
            trailing = { HomeIconButton(ToolsIcons.Plus, "添加订阅", onAdd, enabled = !state.busy && state.current != null, glyph = 26.dp) },
        )
        if (state.subscriptions.isEmpty()) {
            Text(
                ht("当前配置没有 proxy-providers。可以添加订阅，或直接编辑 YAML。"),
                Modifier.padding(horizontal = HomeRowDims.start, vertical = 12.dp), color = c.t2, style = HomeType.note,
            )
        }
        state.subscriptions.forEachIndexed { index, item ->
            if (index > 0) HomeRowDivider(start = HomeRowDims.textStart)
            ToolsRow(
                title = AnnotatedString(item.name),
                icon = ToolsIcons.Link,
                subtitle = if (item.placeholder) ht("尚未填写订阅链接") else item.url,
                subtitleMaxLines = 1,
                subtitleStyle = if (item.placeholder) HomeRowSubStyle else ToolsType.url,
                subtitleColor = if (item.placeholder) c.warnText else c.t2,
                enabled = !state.busy,
                endPadding = 4.dp,
                onClick = { onEdit(item) },
                trailing = {
                    ToolsChevron()
                    HomeIconButton(ToolsIcons.Trash2, deleteLabel, { onDelete(item) }, enabled = !state.busy, tint = c.bad, glyph = 22.dp)
                },
            )
        }
        ToolsNote("配置切换后，下次启动或重启代理时生效。", Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, top = 8.dp, bottom = 16.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Row menu                                                            */
/* ------------------------------------------------------------------ */

/** Menu of one config row. Items come from [menuItems]; the caption line is the config name. */
@Composable
internal fun ToolsConfigMenuCard(config: ToolsConfig, modifier: Modifier = Modifier, onItem: (ToolsConfigMenuItem) -> Unit) {
    ToolsMenuCard(
        entries = config.menuItems().map { item ->
            ToolsMenuEntry(
                label = item.label,
                icon = when (item) {
                    ToolsConfigMenuItem.SetCurrent -> HomeIcons.CircleCheck
                    ToolsConfigMenuItem.Export -> ToolsIcons.Share
                    ToolsConfigMenuItem.Rename -> ToolsIcons.Pencil
                    ToolsConfigMenuItem.Delete -> ToolsIcons.Trash2
                },
                danger = item.danger,
                onClick = { onItem(item) },
            )
        },
        modifier = modifier,
        title = config.name,
    )
}

/* ------------------------------------------------------------------ */
/*  Dialog cards                                                        */
/* ------------------------------------------------------------------ */

/** The error line unfolds under the field after a failed 保存. */
@Composable
internal fun ToolsRenameConfigDialogCard(
    overlay: ToolsConfigOverlay.Rename,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        title = "重命名配置", confirmLabel = "保存", onConfirm = onConfirm, onCancel = onCancel,
        modifier = modifier, confirmLoading = overlay.saving,
    ) { ToolsField("名称", overlay.draft, onDraftChange, error = overlay.error, placeholder = "例如 日常.yaml", enabled = !overlay.saving) }
}

@Composable
internal fun ToolsDeleteConfigDialogCard(
    overlay: ToolsConfigOverlay.DeleteConfig,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        title = "删除配置？",
        text = "「${overlay.config}」将被永久删除。" + if (overlay.isCurrent) "\n删除后会切换回内置模板。" else "",
        confirmLabel = "删除", confirmKind = ToolsConfirmKind.Danger,
        icon = ToolsIcons.Trash2, iconTone = HomeTone.Bad,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

@Composable
internal fun ToolsDeleteSubscriptionDialogCard(
    overlay: ToolsConfigOverlay.DeleteSubscription,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        title = "删除订阅？",
        text = "从「${overlay.config}」中移除「${overlay.subscription}」\n及其在策略组中的引用。",
        confirmLabel = "删除", confirmKind = ToolsConfirmKind.Danger,
        icon = ToolsIcons.Trash2, iconTone = HomeTone.Bad,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
