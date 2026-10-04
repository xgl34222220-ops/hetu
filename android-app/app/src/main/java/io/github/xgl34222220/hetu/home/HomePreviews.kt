package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/*
 * One preview per prototype state, numbered like the artifact's «01 首页» list (A01–A14),
 * so each can be compared side by side with https://claude.ai/artifact/XSBd1r6mzew5zEZeVkfVKP.
 * Sheets are drawn inline (scrim + bottom-aligned surface) because a real ModalBottomSheet
 * lives in its own window and does not render in a static preview.
 */

private const val W = 390
private const val H = 844

@Composable
private fun Frame(dark: Boolean = false, accent: HomeAccent = HomeAccent.Default, pureBlack: Boolean = false, content: @Composable () -> Unit) {
    HetuHomeTheme(dark = dark, accent = accent, pureBlack = pureBlack) {
        Box(Modifier.fillMaxSize().background(LocalHomeColors.current.bg)) { content() }
    }
}

@Composable
private fun Main(state: HomeUiState) {
    HomeScreen(state, HomeActions(), onOpenIpDetail = {}, onOpenTargets = {}, onOpenResource = {}, onOpenSpeedSource = {})
}

@Composable
private fun WithSheet(page: @Composable () -> Unit, sheet: @Composable () -> Unit) {
    val c = LocalHomeColors.current
    Box(Modifier.fillMaxSize()) {
        page()
        Box(Modifier.fillMaxSize().background(c.scrim))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(HomeDims.sheetShape).background(c.surface)
                .border(1.dp, c.line, HomeDims.sheetShape),
        ) { sheet() }
    }
}

@Preview(name = "A01 首页 · 运行中", widthDp = W, heightDp = H)
@Composable
private fun PreviewA01Running() = Frame { Main(HomeSamples.running) }

@Preview(name = "A02 首页 · 运行控制与模式切换", widthDp = W, heightDp = H)
@Composable
private fun PreviewA02ModeSwitch() = Frame { Main(HomeSamples.modeSwitch) }

@Preview(name = "A03 首页 · 未运行", widthDp = W, heightDp = H)
@Composable
private fun PreviewA03NotRunning() = Frame { Main(HomeSamples.notRunning) }

@Preview(name = "A04 首页 · 待重启与 LAN", widthDp = W, heightDp = H)
@Composable
private fun PreviewA04PendingRestartLan() = Frame { Main(HomeSamples.pendingRestartLan) }

@Preview(name = "A05 首页 · 网速数据来源", widthDp = W, heightDp = H)
@Composable
private fun PreviewA05SpeedSource() = Frame {
    WithSheet(page = { Main(HomeSamples.running) }) {
        HomeSpeedSourceSheetContent(HomeSpeedSource.Api, onSelect = {}, onClose = {})
    }
}

@Preview(name = "A06 首页 · 正在启动", widthDp = W, heightDp = H)
@Composable
private fun PreviewA06Starting() = Frame { Main(HomeSamples.starting) }

@Preview(name = "A07 首页 · 启动失败", widthDp = W, heightDp = H)
@Composable
private fun PreviewA07StartFailed() = Frame {
    WithSheet(page = { Main(HomeSamples.startFailed) }) {
        HomeStartFailedSheetContent((HomeSamples.startFailed.status as HomeStatus.StartFailed).detail, onCopy = {}, onViewConfig = {}, onRetry = {})
    }
}

@Preview(name = "A08 首页 · 正在重启", widthDp = W, heightDp = H)
@Composable
private fun PreviewA08Restarting() = Frame { Main(HomeSamples.restarting) }

@Preview(name = "A09 公网 IP 详情 · 公网", widthDp = W, heightDp = H)
@Composable
private fun PreviewA09IpWan() = Frame {
    HomeIpDetailScreen(HomeSamples.running, HomeNetSide.Wan, onSideChange = {}, onBack = {}, onRefresh = {}, onCopy = { _, _ -> })
}

@Preview(name = "A10 公网 IP 详情 · 局域网", widthDp = W, heightDp = H)
@Composable
private fun PreviewA10IpLan() = Frame {
    HomeIpDetailScreen(HomeSamples.running, HomeNetSide.Lan, onSideChange = {}, onBack = {}, onRefresh = {}, onCopy = { _, _ -> })
}

@Composable
private fun Targets(targets: List<HomeTarget>, feedback: HomeTargetsFeedback?) {
    HomeLatencyTargetsScreen(
        HomeTargetsConfig(targets), feedback,
        onTargetChange = { _, _ -> }, onAutoRefreshChange = {}, onReset = {}, onSave = {}, onBack = {},
    )
}

@Preview(name = "A11 本机直测目标 · 已保存", widthDp = W, heightDp = H)
@Composable
private fun PreviewA11TargetsSaved() = Frame { Targets(HomeSamples.exampleTargets, HomeTargetsFeedback.Saved()) }

@Preview(name = "A12 本机直测目标 · 校验错误", widthDp = W, heightDp = H)
@Composable
private fun PreviewA12TargetsInvalid() = Frame {
    Targets(HomeSamples.duplicateTargets, HomeTargetsFeedback.Invalid(HomeTargets.validate(HomeSamples.duplicateTargets).orEmpty()))
}

@Preview(name = "A13 资源占用 · 运行与缺测", widthDp = W, heightDp = H)
@Composable
private fun PreviewA13ResourceRunning() = Frame { HomeResourceScreen(HomeSamples.running, onBack = {}) }

@Preview(name = "A14 资源占用 · 等待采样", widthDp = W, heightDp = H)
@Composable
private fun PreviewA14ResourceWaiting() = Frame { HomeResourceScreen(HomeSamples.notRunning, onBack = {}) }

/* ---- Extras beyond the 14 concept states ---- */

@Preview(name = "X 首页 · 正在停止", widthDp = W, heightDp = H)
@Composable
private fun PreviewStopping() = Frame { Main(HomeSamples.stopping) }

@Preview(name = "X 首页 · 深色", widthDp = W, heightDp = H)
@Composable
private fun PreviewDark() = Frame(dark = true) { Main(HomeSamples.running) }

@Preview(name = "X 首页 · 纯黑 + 青色", widthDp = W, heightDp = H)
@Composable
private fun PreviewPureBlackJade() = Frame(dark = true, accent = HomeAccent.Jade, pureBlack = true) { Main(HomeSamples.running) }

@Preview(name = "X 河图点阵 · 三种模式 × 八档强调色", widthDp = W, heightDp = 420)
@Composable
private fun PreviewDotMatrix() = Frame {
    val c = LocalHomeColors.current
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            HeTuDotMode.entries.forEach { mode ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    HeTuDotMatrix(mode, animate = false)
                    Text(mode.name, color = c.t2, style = HomeType.caption)
                }
            }
        }
        HomeAccent.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { accent ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        HeTuDotMatrix(HeTuDotMode.Running, size = 72.dp, color = accent.light, animate = false)
                        Text(accent.label, color = c.t2, style = HomeType.caption)
                    }
                }
            }
        }
    }
}
