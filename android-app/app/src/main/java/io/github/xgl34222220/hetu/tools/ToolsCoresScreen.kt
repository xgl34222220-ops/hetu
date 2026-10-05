package io.github.xgl34222220.hetu.tools

import io.github.xgl34222220.hetu.ui.ht
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeBadge
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/**
 * 核心管理 (工具 › 核心管理). Stateless.
 *
 * - Page 34: one card per core: glyph tile, name with a badge, version line, two buttons.
 * - Page 35: the busy card shows a progress line and “处理中”; every other button is disabled.
 */
@Composable
internal fun ToolsCoresScreen(
    state: ToolsCoresState,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onPrimary: (ToolsCore, ToolsCoreAction) -> Unit,
    onImport: (ToolsCore) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = state.busyId == null
    ToolsPage(
        title = "核心管理",
        onBack = onBack,
        modifier = modifier,
        refreshing = state.checking,
        onRefresh = if (idle) onCheck else null,
        subtitle = state.running.takeIf { it.isNotBlank() }?.let { "运行中：$it" },
        actions = { HomeIconButton(HomeIcons.RefreshCw, "检查更新", onCheck, enabled = idle && state.load is ToolsLoad.Ready, loading = state.checking) },
    ) {
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading()
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.Cpu, "核心状态读取失败", subtitle = load.message) {
                HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> Column(verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                state.cores.forEach { core ->
                    CoreCard(core, busy = state.busyId == core.id, enabled = idle, progress = state.progress, onPrimary = { onPrimary(core, it) }, onImport = { onImport(core) })
                }
                ToolsNote(
                    "核心按设备 ABI 从发布源直接拉取。内置核心未下载更新时使用 App 自带版本；标注「仅下载管理」的核心只做下载与版本管理。",
                    Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

internal fun ToolsCore.glyph(): ImageVector = when {
    id.startsWith("mihomo") -> ToolsIcons.CoreMihomoPdf34
    id.startsWith("sing-box") || id.startsWith("singbox") -> ToolsIcons.CoreSingBoxPdf34
    id.startsWith("xray") -> ToolsIcons.CoreXrayPdf34
    else -> ToolsIcons.Cpu
}

@Composable
private fun CoreCard(core: ToolsCore, busy: Boolean, enabled: Boolean, progress: String, onPrimary: (ToolsCoreAction) -> Unit, onImport: () -> Unit) {
    val c = LocalHomeColors.current
    val action = core.primaryAction()
    val softButtonColors = if (c.dark) c else c.copy(
        accentSoft = if (enabled) Color(0xFFEEF0FC) else coreButtonColorBeforeDisabledAlpha(Color(0xFFEAEDF6), c.surface),
        accent = if (enabled) c.accent else coreButtonColorBeforeDisabledAlpha(Color(0xFFBBBDCA), c.surface),
    )
    val processingColors = if (c.dark) c else c.copy(
        surface = coreButtonColorBeforeDisabledAlpha(Color(0xFFC8CAD6), c.surface),
        t1 = Color.White,
        line2 = Color.Transparent,
    )
    HomeCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(HomeDims.cardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (core.runnable) c.accentSoft else c.sunken),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(core.glyph(), null, Modifier.size(40.dp), tint = c.t1)
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(core.name, Modifier.weight(1f, fill = core.runnable), color = c.t1,
                            style = HomeType.rowTitle.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            !core.runnable -> HomeBadge(ht("仅下载管理"))
                            core.updateAvailable -> HomeBadge(ht("有更新"), tone = HomeTone.Accent)
                            core.latest.isNotBlank() -> HomeBadge(ht("已是最新"), tone = HomeTone.Good)
                        }
                    }
                    Text(core.versionLine, color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeSpinner(size = 16.dp, color = c.accent)
                    Text(progress.ifBlank { "处理中…" }, color = c.t2, style = HomeType.rowSub.copy(fontFeatureSettings = "tnum"), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    busy -> CompositionLocalProvider(LocalHomeColors provides processingColors) {
                        HomeButton("处理中", {}, Modifier.weight(1f), enabled = false, loading = true)
                    }
                    action != null -> CompositionLocalProvider(LocalHomeColors provides if (action == ToolsCoreAction.Download) softButtonColors else c) {
                        HomeButton(
                            action.label, { onPrimary(action) }, Modifier.weight(1f),
                            kind = when (action) {
                                ToolsCoreAction.Update -> HomeButtonKind.Primary
                                ToolsCoreAction.Download -> HomeButtonKind.Soft
                                ToolsCoreAction.Restore, ToolsCoreAction.Remove -> HomeButtonKind.Secondary
                            },
                            enabled = enabled,
                        )
                    }
                }
                CompositionLocalProvider(LocalHomeColors provides softButtonColors) {
                    HomeButton("导入", onImport, Modifier.weight(1f), kind = HomeButtonKind.Soft, enabled = enabled && !busy)
                }
            }
        }
    }
}

/** Keep the shared button's disabled alpha while matching the measured light-mode colors. */
private fun coreButtonColorBeforeDisabledAlpha(fill: Color, surface: Color): Color {
    val alpha = .45f
    return fill.copy(
        red = ((fill.red - surface.red * (1f - alpha)) / alpha).coerceIn(0f, 1f),
        green = ((fill.green - surface.green * (1f - alpha)) / alpha).coerceIn(0f, 1f),
        blue = ((fill.blue - surface.blue * (1f - alpha)) / alpha).coerceIn(0f, 1f),
    )
}
