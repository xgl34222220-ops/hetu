package io.github.xgl34222220.hetu.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.github.xgl34222220.hetu.home.HetuHomeTheme
import io.github.xgl34222220.hetu.home.HomeAccent
import io.github.xgl34222220.hetu.home.LocalHomeColors

/*
 * One preview per prototype state, numbered like the artifact's «02 面板» list (B01–B29),
 * so each can be compared side by side with https://claude.ai/artifact/XSBd1r6mzew5zEZeVkfVKP.
 * Menus, sheets and the dialog are drawn inline (popups = false): popup windows do not render
 * in a static preview. Inline menu positions are approximate; at runtime they anchor to their button.
 */

private const val W = 390
private const val H = 844

@Composable
private fun Scene(id: String, dark: Boolean = false, accent: HomeAccent = HomeAccent.Default) {
    val scene = PanelSamples.scene(id)
    HetuHomeTheme(dark = dark, accent = accent) {
        Box(Modifier.fillMaxSize().background(LocalHomeColors.current.bg)) {
            PanelScreen(
                data = scene.data, view = scene.view, overlay = scene.overlay, actions = PanelActions(),
                onView = {}, onOverlay = {},
                listState = rememberLazyListState(initialFirstVisibleItemIndex = scene.scrollToItem),
                popups = false,
            )
        }
    }
}

@Preview(name = "B01 策略 · 紧凑策略组", widthDp = W, heightDp = H) @Composable private fun PreviewB01() = Scene("B01")
@Preview(name = "B02 策略 · 展开节点", widthDp = W, heightDp = H) @Composable private fun PreviewB02() = Scene("B02")
@Preview(name = "B03 概览 · 顶部", widthDp = W, heightDp = H) @Composable private fun PreviewB03() = Scene("B03")
@Preview(name = "B04 概览 · 趋势与排行（收起顶栏）", widthDp = W, heightDp = H) @Composable private fun PreviewB04() = Scene("B04")
@Preview(name = "B05 订阅", widthDp = W, heightDp = H) @Composable private fun PreviewB05() = Scene("B05")
@Preview(name = "B06 连接", widthDp = W, heightDp = H) @Composable private fun PreviewB06() = Scene("B06")
@Preview(name = "B07 规则", widthDp = W, heightDp = H) @Composable private fun PreviewB07() = Scene("B07")
@Preview(name = "B08 规则集", widthDp = W, heightDp = H) @Composable private fun PreviewB08() = Scene("B08")
@Preview(name = "B09 日志", widthDp = W, heightDp = H) @Composable private fun PreviewB09() = Scene("B09")
@Preview(name = "B10 策略 · 筛选浮层", widthDp = W, heightDp = H) @Composable private fun PreviewB10() = Scene("B10")
@Preview(name = "B11 策略 · 排序与布局", widthDp = W, heightDp = H) @Composable private fun PreviewB11() = Scene("B11")
@Preview(name = "B12 策略 · 测速与 API（已配置）", widthDp = W, heightDp = H) @Composable private fun PreviewB12() = Scene("B12")
@Preview(name = "B13 策略 · 节点信息", widthDp = W, heightDp = H) @Composable private fun PreviewB13() = Scene("B13")
@Preview(name = "B14 连接 · 详情浮层", widthDp = W, heightDp = H) @Composable private fun PreviewB14() = Scene("B14")
@Preview(name = "B15 连接 · 断开全部确认", widthDp = W, heightDp = H) @Composable private fun PreviewB15() = Scene("B15")
@Preview(name = "B16 概览 · 显示数量", widthDp = W, heightDp = H) @Composable private fun PreviewB16() = Scene("B16")
@Preview(name = "B17 连接 · 排序", widthDp = W, heightDp = H) @Composable private fun PreviewB17() = Scene("B17")
@Preview(name = "B18 连接 · 显示菜单", widthDp = W, heightDp = H) @Composable private fun PreviewB18() = Scene("B18")
@Preview(name = "B19 策略 · 搜索结果", widthDp = W, heightDp = H) @Composable private fun PreviewB19() = Scene("B19")
@Preview(name = "B20 概览 · 排行方式", widthDp = W, heightDp = H) @Composable private fun PreviewB20() = Scene("B20")
@Preview(name = "B21 连接 · 筛选", widthDp = W, heightDp = H) @Composable private fun PreviewB21() = Scene("B21")
@Preview(name = "B22 订阅 · 搜索与更新状态", widthDp = W, heightDp = H) @Composable private fun PreviewB22() = Scene("B22")
@Preview(name = "B23 规则 · 搜索无结果", widthDp = W, heightDp = H) @Composable private fun PreviewB23() = Scene("B23")
@Preview(name = "B24 连接 · 搜索与应用展开", widthDp = W, heightDp = H) @Composable private fun PreviewB24() = Scene("B24")
@Preview(name = "B25 规则集 · 搜索与更新", widthDp = W, heightDp = H) @Composable private fun PreviewB25() = Scene("B25")
@Preview(name = "B26 日志 · 搜索与展开", widthDp = W, heightDp = H) @Composable private fun PreviewB26() = Scene("B26")
@Preview(name = "B27 策略 · 测速与 API（默认）", widthDp = W, heightDp = H) @Composable private fun PreviewB27() = Scene("B27")
@Preview(name = "B28 策略 · 测速中与超时", widthDp = W, heightDp = H) @Composable private fun PreviewB28() = Scene("B28")
@Preview(name = "B29 面板 · 代理未运行", widthDp = W, heightDp = H) @Composable private fun PreviewB29() = Scene("B29")

/* ---- Extras beyond the 29 concept states ---- */

@Preview(name = "X 策略 · 深色", widthDp = W, heightDp = H) @Composable private fun PreviewDarkGroups() = Scene("B02", dark = true)
@Preview(name = "X 概览 · 深色 + 青色", widthDp = W, heightDp = H) @Composable private fun PreviewDarkOverview() = Scene("B03", dark = true, accent = HomeAccent.Jade)
