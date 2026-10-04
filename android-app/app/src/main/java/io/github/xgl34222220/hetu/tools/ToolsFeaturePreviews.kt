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
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.LocalHomeColors

/* ------------------------------------------------------------------ */
/*  One preview per concept page: C26–C49 = 03A 工具上册 第 26–49 页.    */
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

/** A bar menu, placed where the popup would hang: [end] is the distance from the right edge. */
@Composable
private fun BoxScope.MenuLayer(end: Int, content: @Composable () -> Unit) {
    Scrim()
    Box(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = end.dp)) { content() }
}

@Composable
private fun Apps(state: ToolsAppsState) {
    ToolsAppsScreen(
        state = state, menu = null, onBack = {}, onToggleSearch = {}, onQueryChange = {}, onScopeChange = {}, onToggleApp = {}, onSelectAll = {},
        onOpenMenu = {}, onDismissMenu = {}, onSortChange = {}, onToggleDescending = {}, onToggleSystem = {}, onClear = {}, onRefresh = {}, onRetry = {},
    )
}

@Composable
private fun Cores(state: ToolsCoresState) {
    ToolsCoresScreen(state, onBack = {}, onCheck = {}, onPrimary = { _, _ -> }, onImport = {}, onRetry = {})
}

@Composable
private fun Bypass(state: ToolsBypassState) {
    ToolsBypassScreen(state, onBack = {}, onSave = {}, onDraftChange = {}, onRetry = {})
}

@Composable
private fun Diag() {
    ToolsDiagScreen(ToolsFeatureSamples.diag, onBack = {}, onPreflight = {}, onStartupConfig = {}, onReport = {}, onRestore = {})
}

/** [scrolled] > 0 shows the lower half of the page (规则 section), as on concept page 44. */
@Composable
private fun Adblock(state: ToolsAdblockState = ToolsFeatureSamples.adblock, scrolled: Int = 0) {
    ToolsAdblockScreen(
        state = state, onBack = {}, onHelp = {}, onRefresh = {}, onEnabledChange = {}, onSwitchToRuleMode = null, onPickRecent = {}, onLevelChange = {},
        onUpdate = {}, onSourceChange = { _, _ -> }, onAddDomain = {}, onRemoveDomain = { _, _ -> }, onStandaloneDnsChange = {}, onCnameChange = {}, onRetry = {},
        scroll = rememberScrollState(initial = scrolled),
    )
}

private const val RulesScroll = 100_000

/* ------------------------------ 订阅 / 导入（沿用 Part 1 屏幕） ------------------------------ */

@Preview(name = "C26 订阅 · 编辑", widthDp = W, heightDp = H)
@Composable
private fun C26EditSubscription() = Frame {
    ToolsSubscriptionScreen(ToolsSubscriptionForm.edit(ToolsSamples.subscriptions[0]), onNameChange = {}, onUrlChange = {}, onSave = {}, onBack = {})
}

@Preview(name = "C27 导入配置 · 文件", widthDp = W, heightDp = H)
@Composable
private fun C27ImportFile() = Frame {
    ToolsImportScreen(
        ToolsImportForm(tab = ToolsImportTab.File), pickedFile = remember { ToolsPickedFile("旅行.yaml", "18 KB") },
        onTabChange = {}, onUrlChange = {}, onNameChange = {}, onPickFile = {}, onImport = {}, onBack = {},
    )
}

/* ------------------------------ 应用管理 ------------------------------ */

@Preview(name = "C28 应用管理 · 黑名单", widthDp = W, heightDp = H)
@Composable
private fun C28AppsBlacklist() = Frame { Apps(ToolsFeatureSamples.appsBlacklist) }

@Preview(name = "C29 应用 · 排序菜单", widthDp = W, heightDp = H)
@Composable
private fun C29AppsSortMenu() = Frame {
    Apps(ToolsFeatureSamples.appsBlacklist)
    MenuLayer(end = 96) { ToolsAppSortMenuCard(ToolsFeatureSamples.appsBlacklist, onSortChange = {}, onToggleDescending = {}) }
}

@Preview(name = "C30 应用 · 更多菜单", widthDp = W, heightDp = H)
@Composable
private fun C30AppsMoreMenu() = Frame {
    Apps(ToolsFeatureSamples.appsBlacklist)
    MenuLayer(end = 8) { ToolsAppMoreMenuCard(ToolsFeatureSamples.appsBlacklist, onToggleSystem = {}, onSelectAll = {}, onClear = {}, onRefresh = {}) }
}

@Preview(name = "C31 应用管理 · 白名单", widthDp = W, heightDp = H)
@Composable
private fun C31AppsWhitelist() = Frame { Apps(ToolsFeatureSamples.appsWhitelist) }

@Preview(name = "C32 应用管理 · 核心模式", widthDp = W, heightDp = H)
@Composable
private fun C32AppsCore() = Frame { Apps(ToolsFeatureSamples.appsCore) }

@Preview(name = "C33 应用管理 · 搜索", widthDp = W, heightDp = H)
@Composable
private fun C33AppsSearch() = Frame { Apps(ToolsFeatureSamples.appsSearch) }

/* ------------------------------ 核心管理 ------------------------------ */

@Preview(name = "C34 核心管理", widthDp = W, heightDp = H)
@Composable
private fun C34Cores() = Frame { Cores(ToolsFeatureSamples.coresIdle) }

@Preview(name = "C35 核心管理 · 下载中", widthDp = W, heightDp = H)
@Composable
private fun C35CoresDownloading() = Frame { Cores(ToolsFeatureSamples.coresDownloading) }

/* ------------------------------ 绕过规则 / 共享网络 / CNIP ------------------------------ */

@Preview(name = "C36 绕过规则", widthDp = W, heightDp = H)
@Composable
private fun C36Bypass() = Frame { Bypass(ToolsFeatureSamples.bypass) }

@Preview(name = "C37 绕过规则 · 放弃修改", widthDp = W, heightDp = H)
@Composable
private fun C37BypassDiscard() = Frame {
    Bypass(ToolsFeatureSamples.bypassDirty)
    DialogLayer { ToolsDiscardChangesDialogCard(onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C38 共享网络", widthDp = W, heightDp = 980)
@Composable
private fun C38Share() = Frame {
    ToolsShareScreen(ToolsFeatureSamples.share, onBack = {}, onRefresh = {}, onSave = {}, onDraftChange = {}, onRetry = {})
}

@Preview(name = "C39 CNIP 设置", widthDp = W, heightDp = H)
@Composable
private fun C39CnIp() = Frame { ToolsCnIpScreen(ToolsFeatureSamples.cnIp, onBack = {}, onEnabledChange = {}) }

/* ------------------------------ 诊断与维护 ------------------------------ */

@Preview(name = "C40 诊断与维护", widthDp = W, heightDp = H)
@Composable
private fun C40Diag() = Frame { Diag() }

@Preview(name = "C41 诊断 · 启动配置浮层", widthDp = W, heightDp = H)
@Composable
private fun C41StartupConfig() = Frame {
    Diag()
    SheetLayer { ToolsStartupConfigSheetContent(ToolsDiagText(loading = false, text = ToolsFeatureSamples.startupConfig), onCopy = {}) }
}

@Preview(name = "C42 诊断 · 恢复网络确认", widthDp = W, heightDp = H)
@Composable
private fun C42RestoreNetwork() = Frame {
    Diag()
    DialogLayer { ToolsRestoreNetworkDialogCard(onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C43 诊断 · 消息与网络", widthDp = W, heightDp = H)
@Composable
private fun C43Report() = Frame {
    Diag()
    SheetLayer { ToolsReportSheetContent(ToolsDiagText(loading = false, text = ToolsFeatureSamples.report), onCopy = {}) }
}

/* ------------------------------ 广告过滤 ------------------------------ */

@Preview(name = "C44 广告过滤 · 规则", widthDp = W, heightDp = H)
@Composable
private fun C44AdblockRules() = Frame { Adblock(scrolled = RulesScroll) }

@Preview(name = "C45 广告过滤 · 状态", widthDp = W, heightDp = H)
@Composable
private fun C45AdblockStatus() = Frame { Adblock() }

@Preview(name = "C46 广告过滤 · 说明", widthDp = W, heightDp = H)
@Composable
private fun C46AdblockHelp() = Frame {
    Adblock()
    SheetLayer { ToolsAdblockHelpSheetContent(onClose = {}) }
}

@Preview(name = "C47 广告过滤 · 添加白名单", widthDp = W, heightDp = H)
@Composable
private fun C47AddAllow() = Frame {
    Adblock(scrolled = RulesScroll)
    DialogLayer { ToolsAddDomainDialogCard(ToolsAdOverlay.AddDomain(allow = true, draft = "example.com"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C48 广告过滤 · 添加黑名单", widthDp = W, heightDp = H)
@Composable
private fun C48AddBlock() = Frame {
    Adblock(scrolled = RulesScroll)
    DialogLayer { ToolsAddDomainDialogCard(ToolsAdOverlay.AddDomain(allow = false, draft = "ads.example.net"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Preview(name = "C49 广告过滤 · 加入白名单确认", widthDp = W, heightDp = H)
@Composable
private fun C49ConfirmAllow() = Frame {
    Adblock()
    DialogLayer { ToolsConfirmAllowDialogCard(ToolsAdOverlay.ConfirmAllow("ads.example.com"), onConfirm = {}, onCancel = {}) }
}
