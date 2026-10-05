package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsHairline as HomeDivider
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/**
 * 配置与订阅 (pushed from 工具 › 配置管理). Stateless.
 *
 * - Page 4: 配置管理 card (config rows, 编辑当前 YAML) and 订阅管理 card (proxy-providers of the
 *   current config). Tapping a config row selects it; «⋯» opens the row menu.
 * - Pages 6–8: the menu, whose items depend on the row (current / other / bundled template).
 *   It is a popup anchored to the «⋯» button; [menuFor] names the row whose menu is open.
 * - Pages 9–11 (rename, delete config, delete subscription) are dialogs hosted by `ToolsRoute`;
 *   their cards are at the bottom of this file.
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
    val c = LocalHomeColors.current
    val ready = state.load is ToolsLoad.Ready
    ToolsPage(
        title = "配置与订阅",
        onBack = onBack,
        modifier = modifier,
        refreshing = state.refreshing,
        onRefresh = if (state.busy) null else onRefresh,
        actions = { HomeIconButton(ToolsIcons.Plus, "导入配置", onImport, enabled = ready && !state.busy) },
    ) {
        ToolsLead("管理源配置与当前配置中的订阅链接。")
        when (val load = state.load) {
            ToolsLoad.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { HomeSpinner(size = 20.dp, color = c.t3) }
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.FileWarning, "配置读取失败", subtitle = load.message) {
                HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> {
                ConfigCard(state, menuFor, onSelectConfig, onOpenMenu, onDismissMenu, onMenuItem, onEditYaml)
                Spacer(Modifier.height(HomeDims.gap))
                SubscriptionCard(state, onAddSubscription, onEditSubscription, onDeleteSubscription)
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
) {
    val c = LocalHomeColors.current
    val menuOffset = with(LocalDensity.current) { 40.dp.roundToPx() }
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardHeader(ToolsIcons.Folder, "配置管理")
        state.configs.forEach { config ->
            ToolsRow(
                title = AnnotatedString(config.name),
                modifier = Modifier.padding(horizontal = 10.dp),
                icon = ToolsIcons.File,
                subtitle = ht(config.caption),
                selected = config.current,
                enabled = !state.busy,
                endPadding = 4.dp,
                onClick = { onSelectConfig(config) },
                trailing = {
                    if (config.current) ToolsCheckBadge()
                    Box {
                        HomeIconButton(ToolsIcons.Ellipsis, "更多操作", { onOpenMenu(config) }, enabled = !state.busy, tint = c.t2)
                        if (menuFor == config.name) {
                            ToolsMenuPopup(onDismiss = onDismissMenu, offsetY = menuOffset) {
                                ToolsConfigMenuCard(config) { item -> onMenuItem(config, item) }
                            }
                        }
                    }
                },
            )
        }
        ToolsRow(
            title = AnnotatedString(ht("编辑当前 YAML")),
            icon = ToolsIcons.Pencil,
            titleColor = c.accent,
            iconTint = c.accent,
            compact = true,
            enabled = !state.busy && state.current != null,
            onClick = onEditYaml,
        )
    }
}

@Composable
private fun SubscriptionCard(
    state: ToolsConfigState,
    onAdd: () -> Unit,
    onEdit: (ToolsSubscription) -> Unit,
    onDelete: (ToolsSubscription) -> Unit,
) {
    val c = LocalHomeColors.current
    HomeCard(Modifier.fillMaxWidth()) {
        ToolsCardHeader(
            ToolsIcons.Link, "订阅管理", caption = "当前配置的 proxy-providers。",
            trailing = { HomeIconButton(ToolsIcons.Plus, "添加订阅", onAdd, enabled = !state.busy && state.current != null) },
        )
        if (state.subscriptions.isEmpty()) {
            Text(
                ht("当前配置没有 proxy-providers。可以添加订阅，或直接编辑 YAML。"),
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = c.t2, style = HomeType.note,
            )
        }
        state.subscriptions.forEach { item ->
            ToolsRow(
                title = AnnotatedString(item.name),
                modifier = Modifier.padding(horizontal = 10.dp),
                icon = ToolsIcons.Link,
                subtitle = if (item.placeholder) ht("尚未填写订阅链接") else item.url,
                subtitleMaxLines = 1,
                subtitleStyle = if (item.placeholder) HomeType.rowSub else ToolsType.url,
                subtitleColor = if (item.placeholder) c.warn else c.t3,
                enabled = !state.busy,
                endPadding = 4.dp,
                onClick = { onEdit(item) },
                trailing = {
                    ToolsChevron()
                    HomeIconButton(ToolsIcons.Trash2, "删除订阅", { onDelete(item) }, enabled = !state.busy, tint = c.bad)
                },
            )
        }
        ToolsNote("配置切换后，下次启动或重启代理时生效。", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/* ------------------------------------------------------------------ */
/*  Row menu (pages 6–8)                                                */
/* ------------------------------------------------------------------ */

/** Menu of one config row. Items come from [menuItems]: the caption line is the config name. */
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
/*  Dialog cards (pages 9–11)                                           */
/* ------------------------------------------------------------------ */

/** Page 9. The error line appears under the field after a failed 保存. */
@Composable
internal fun ToolsRenameConfigDialogCard(
    overlay: ToolsConfigOverlay.Rename,
    onDraftChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        actions = ToolsDialogActions.ConfigGrid,
        title = "重命名配置", confirmLabel = "保存", onConfirm = onConfirm, onCancel = onCancel,
        modifier = modifier, confirmLoading = overlay.saving,
    ) { ToolsField("名称", overlay.draft, onDraftChange, error = overlay.error, placeholder = "例如 日常.yaml", compact = true) }
}

/** Page 10. */
@Composable
internal fun ToolsDeleteConfigDialogCard(
    overlay: ToolsConfigOverlay.DeleteConfig,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        actions = ToolsDialogActions.ConfigGrid,
        title = "删除配置？",
        text = "「${overlay.config}」将被永久删除。" + if (overlay.isCurrent) "\n删除后会切换回内置模板。" else "",
        confirmLabel = "删除", confirmKind = ToolsConfirmKind.Danger,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

/** Page 11. */
@Composable
internal fun ToolsDeleteSubscriptionDialogCard(
    overlay: ToolsConfigOverlay.DeleteSubscription,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolsDialogCard(
        actions = ToolsDialogActions.ConfigGrid,
        title = "删除订阅？",
        text = "从「${overlay.config}」中移除「${overlay.subscription}」\n及其在策略组中的引用。",
        confirmLabel = "删除", confirmKind = ToolsConfirmKind.DangerSoft,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}
