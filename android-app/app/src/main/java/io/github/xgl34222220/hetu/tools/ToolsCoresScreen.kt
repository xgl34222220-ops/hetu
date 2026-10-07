package io.github.xgl34222220.hetu.tools

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeMotion
import io.github.xgl34222220.hetu.home.HomePill
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeRowSubStyle
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeMotionEnabled
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.ht

/**
 * 核心管理 (工具 › 核心管理). Stateless.
 *
 * One card per core: glyph tile, name with a status capsule, version line, two buttons. While
 * a core is being downloaded, imported or removed its card unfolds a progress line and shows
 * “处理中”; every other button on the page is disabled.
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
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "核心管理",
        onBack = onBack,
        modifier = modifier,
        refreshing = state.checking,
        onRefresh = if (idle) onCheck else null,
        subtitle = state.running.takeIf { it.isNotBlank() }?.let { ht("运行中") + " · " + it },
        actions = { HomeIconButton(HomeIcons.RefreshCw, "检查更新", onCheck, enabled = idle && state.load is ToolsLoad.Ready && !state.checking, spinning = state.checking) },
    ) {
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading()
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.Cpu, "核心状态读取失败", subtitle = load.message) {
                ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> {
                state.cores.forEachIndexed { index, core ->
                    CoreCard(
                        core, busy = state.busyId == core.id, enabled = idle, progress = state.progress,
                        onPrimary = { onPrimary(core, it) }, onImport = { onImport(core) }, modifier = Modifier.homeEnter(stagger, index),
                    )
                }
                ToolsNote(
                    "核心按设备 ABI 从发布源直接拉取。内置核心未下载更新时使用 App 自带版本；标注「仅下载管理」的核心只做下载与版本管理。",
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
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
private fun CoreCard(
    core: ToolsCore,
    busy: Boolean,
    enabled: Boolean,
    progress: String,
    onPrimary: (ToolsCoreAction) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier,
) {
    val c = LocalHomeColors.current
    val action = core.primaryAction()
    val tile by animateColorAsState(if (core.runnable) c.accentSoft else c.sunken, HomeMotion.fade(LocalHomeMotionEnabled.current), label = "tools-core-tile")
    HomeCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(HomeDims.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolsGlyphTile(core.glyph(), tint = c.t1, background = tile, size = 62.dp, glyph = 40.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            core.name, Modifier.weight(1f, fill = false), color = c.t1,
                            style = HomeType.sheetTitle.copy(fontSize = 23.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            !core.runnable -> HomePill(ht("仅下载管理"), tone = HomeTone.Neutral)
                            core.updateAvailable -> HomePill(ht("有更新"), tone = HomeTone.Accent)
                            core.latest.isNotBlank() -> HomePill(ht("已是最新"), tone = HomeTone.Good)
                        }
                    }
                    Text(core.versionLine, color = c.t2, style = HomeRowSubStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HomeReveal(busy) {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeSpinner(size = 18.dp, color = c.accent)
                    Text(progress.ifBlank { ht("处理中…") }, color = c.t2, style = HomeType.delay, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    busy -> ToolsButton("处理中", {}, Modifier.weight(1f), kind = HomeButtonKind.Soft, neutral = true, enabled = false, loading = true)
                    action != null -> ToolsButton(
                        action.label, { onPrimary(action) }, Modifier.weight(1f),
                        kind = when (action) {
                            ToolsCoreAction.Update -> HomeButtonKind.Primary
                            ToolsCoreAction.Download -> HomeButtonKind.Soft
                            ToolsCoreAction.Restore, ToolsCoreAction.Remove -> HomeButtonKind.Secondary
                        },
                        enabled = enabled,
                    )
                }
                ToolsButton("导入", onImport, Modifier.weight(1f), kind = HomeButtonKind.Soft, enabled = enabled && !busy)
            }
        }
    }
}
