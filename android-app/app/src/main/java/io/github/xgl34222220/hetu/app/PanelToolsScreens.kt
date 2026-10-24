package io.github.xgl34222220.hetu

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.ui.HetuHaptic
import io.github.xgl34222220.hetu.ui.ht
import io.github.xgl34222220.hetu.ui.rememberHetuHaptics

/* ------------------------------------------------------------------ */
/*  面板: every live Mihomo view in one swipeable page                   */
/* ------------------------------------------------------------------ */

/**
 * The pre-V20.87 panel host and its legacy section screens. The four-tab shell draws the panel/
 * module's [io.github.xgl34222220.hetu.panel.NewUiPanel] instead, so this host has no route in
 * the running app; it is kept, unchanged, because nothing is deleted and tests still render it.
 */
@Composable
internal fun PanelScreen(vm: HetuViewModel, bottomPadding: Dp) {
    HxTabbedPage(
        title = ht("面板"),
        tabs = HxPanelSections.map { (key, label) -> HxPageTab(key, ht(label)) },
        selected = vm.panelSection,
        onSelect = vm::openPanel,
        showSubtitle = false,
        minimalHeader = true,
        panelReferenceStyle = true,
        bottomPadding = bottomPadding,
        scrollToTopSignal = vm.reselect,
        pageComposable = { key ->
            when (key) {
                "overview" -> PanelOverviewScreen(vm, bottomPadding)
                "proxies" -> ProxiesScreen(vm, bottomPadding)
                "conn", "logs" -> ConnectionsScreen(vm, bottomPadding, forcedSection = key)
                "providers", "rules", "sets" -> RulesScreen(vm, bottomPadding, forcedSection = key)
                else -> PanelOverviewScreen(vm, bottomPadding)
            }
        },
    )
}

/* ------------------------------------------------------------------ */
/*  工具: every management tool, one tap away                           */
/* ------------------------------------------------------------------ */

@Composable
internal fun ToolsScreen(
    vm: HetuViewModel,
    bottomPadding: Dp,
    onSubPageVisibleChanged: (Boolean) -> Unit = {},
) {
    val nav = LocalNav.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    io.github.xgl34222220.hetu.tools.HetuToolsV2(
        onLog = vm::toast,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            bottom = maxOf(bottomPadding + 24.dp, HomeDims.dockClearance)
        ),
        onSubPageVisibleChanged = onSubPageVisibleChanged,
        onConfigChanged = { scope.launch { vm.refreshNow() } },
        // A running proxy picks the new config up (hot reload, or the restart notice), like 设置 › 配置.
        onConfigSelected = { name -> vm.applyConfigChange("已切换到 $name") },
        onOpenConfigEditor = { nav.push(HxRoute.ConfigEditor) },
        requestedEntry = vm.toolsRequest,
        onRequestConsumed = { vm.toolsRequest = null; vm.toolsImportRequest = false },
        requestedImport = vm.toolsImportRequest,
        onOpenExternalEntry = { entry ->
            when (entry) {
                io.github.xgl34222220.hetu.tools.ToolsEntry.Files -> { nav.push(HxRoute.Files); true }
                io.github.xgl34222220.hetu.tools.ToolsEntry.Logs -> { nav.push(HxRoute.Logs); true }
                io.github.xgl34222220.hetu.tools.ToolsEntry.NetMatch -> { nav.push(HxRoute.NetMatch); true }
                io.github.xgl34222220.hetu.tools.ToolsEntry.NetTest -> { nav.push(HxRoute.NetTest); true }
                else -> false
            }
        },
    )
}

internal class HxEditEntry(initial: String) {
    val id: Long = nextId++
    var text by mutableStateOf(initial)

    private companion object {
        var nextId = 0L
    }
}

internal fun hxLooksLikeCidr(value: String): Boolean {
    val parts = value.split('/')
    if (parts.size != 2) return false
    val bits = parts[1].toIntOrNull() ?: return false
    val ip = parts[0]
    return if (ip.contains(':')) bits in 0..128 && ip.all { it.isLetterOrDigit() || it == ':' }
    else bits in 0..32 && ip.split('.').let { octets -> octets.size == 4 && octets.all { o -> o.toIntOrNull()?.let { it in 0..255 } == true } }
}
