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
/*  One preview per concept page: C26–C49 = 03A 工具上册 第 26–49 页.    */
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
private fun Apps(state: ToolsAppsState, menu: ToolsAppsMenu? = null) {
    ToolsAppsScreen(
        state = state, menu = menu, onBack = {}, onToggleSearch = {}, onQueryChange = {}, onScopeChange = {}, onToggleApp = {}, onSelectAll = {},
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
private fun Adblock(state: ToolsAdblockState = ToolsFeatureSamples.adblock.copy(modeKnown = true, ruleMode = true), scrolled: Int = 0) {
    ToolsAdblockScreen(
        state = state, onBack = {}, onHelp = {}, onRefresh = {}, onEnabledChange = {}, onSwitchToRuleMode = null, onPickRecent = {}, onLevelChange = {},
        onUpdate = {}, onSourceChange = { _, _ -> }, onAddDomain = {}, onRemoveDomain = { _, _ -> }, onStandaloneDnsChange = {}, onCnameChange = {}, onRetry = {},
        scroll = rememberScrollState(initial = scrolled),
    )
}

private const val RulesScroll = 100_000

/* ------------------------------ 订阅 / 导入（沿用 Part 1 屏幕） ------------------------------ */

@Composable
internal fun Pdf85C26EditSubscription() = Frame {
    ToolsSubscriptionScreen(ToolsSubscriptionForm.edit(ToolsSamples.subscriptions[0]), onNameChange = {}, onUrlChange = {}, onSave = {}, onBack = {})
}

@Composable
internal fun Pdf85C27ImportFile() = Frame {
    ToolsImportScreen(
        ToolsImportForm(tab = ToolsImportTab.File), pickedFile = remember { ToolsPickedFile("旅行.yaml", "18 KB") },
        onTabChange = {}, onUrlChange = {}, onNameChange = {}, onPickFile = {}, onImport = {}, onBack = {},
    )
}

/* ------------------------------ 应用管理 ------------------------------ */

@Composable
internal fun Pdf85C28AppsBlacklist() = Frame { Apps(ToolsFeatureSamples.appsBlacklist) }

@Composable
internal fun Pdf85C29AppsSortMenu() = Frame {
    Apps(ToolsFeatureSamples.appsBlacklist, ToolsAppsMenu.Sort)
}

@Composable
internal fun Pdf85C30AppsMoreMenu() = Frame {
    Apps(ToolsFeatureSamples.appsBlacklist, ToolsAppsMenu.More)
}

@Composable
internal fun Pdf85C31AppsWhitelist() = Frame { Apps(ToolsFeatureSamples.appsWhitelist) }

@Composable
internal fun Pdf85C32AppsCore() = Frame { Apps(ToolsFeatureSamples.appsCore) }

@Composable
internal fun Pdf85C33AppsSearch() = Frame { Apps(ToolsFeatureSamples.appsSearch) }

/* ------------------------------ 核心管理 ------------------------------ */

@Composable
internal fun Pdf85C34Cores() = Frame { Cores(ToolsFeatureSamples.coresIdle) }

@Composable
internal fun Pdf85C35CoresDownloading() = Frame { Cores(ToolsFeatureSamples.coresDownloading) }

/* ------------------------------ 绕过规则 / 共享网络 / CNIP ------------------------------ */

@Composable
internal fun Pdf85C36Bypass() = Frame { Bypass(ToolsFeatureSamples.bypass) }

@Composable
internal fun Pdf85C37BypassDiscard() = Frame {
    Bypass(ToolsFeatureSamples.bypassDirty)
    DialogLayer { ToolsDiscardChangesDialogCard(onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C38Share() = Frame {
    ToolsShareScreen(ToolsFeatureSamples.share, onBack = {}, onRefresh = {}, onSave = {}, onDraftChange = {}, onRetry = {})
}

@Composable
internal fun Pdf85C39CnIp() = Frame { ToolsCnIpScreen(ToolsFeatureSamples.cnIp, onBack = {}, onEnabledChange = {}) }

/* ------------------------------ 诊断与维护 ------------------------------ */

@Composable
internal fun Pdf85C40Diag() = Frame { Diag() }

@Composable
internal fun Pdf85C41StartupConfig() = Frame {
    Diag()
    SheetLayer { ToolsStartupConfigSheetContent(ToolsDiagText(loading = false, text = ToolsFeatureSamples.startupConfig), onCopy = {}) }
}

@Composable
internal fun Pdf85C42RestoreNetwork() = Frame {
    Diag()
    DialogLayer { ToolsRestoreNetworkDialogCard(onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C43Report() = Frame {
    Diag()
    SheetLayer { ToolsReportSheetContent(ToolsDiagText(loading = false, text = ToolsFeatureSamples.report), onCopy = {}) }
}

/* ------------------------------ 广告过滤 ------------------------------ */

@Composable
// The mdpi 7cf page45 capture places the LevelCard at y765, below the y78 toolbar.
internal fun Pdf85C44AdblockRules() = Frame { Adblock(scrolled = 687) }

@Composable
internal fun Pdf85C45AdblockStatus() = Frame { Adblock() }

@Composable
internal fun Pdf85C46AdblockHelp() = Frame {
    Adblock()
    SheetLayer { ToolsAdblockHelpSheetContent(onClose = {}) }
}

@Composable
internal fun Pdf85C47AddAllow() = Frame {
    Adblock(scrolled = RulesScroll)
    DialogLayer { ToolsAddDomainDialogCard(ToolsAdOverlay.AddDomain(allow = true, draft = "example.com"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C48AddBlock() = Frame {
    Adblock(scrolled = RulesScroll)
    DialogLayer { ToolsAddDomainDialogCard(ToolsAdOverlay.AddDomain(allow = false, draft = "ads.example.net"), onDraftChange = {}, onConfirm = {}, onCancel = {}) }
}

@Composable
internal fun Pdf85C49ConfirmAllow() = Frame {
    Adblock()
    DialogLayer { ToolsConfirmAllowDialogCard(ToolsAdOverlay.ConfirmAllow("ads.example.com"), onConfirm = {}, onCancel = {}) }
}
