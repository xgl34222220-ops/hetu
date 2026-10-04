package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

private class IpRow(val label: String, val value: String?, val copyable: Boolean = true)

/**
 * 公网 / 局域网 IP 详情 (full page, pushed from the WAN / LAN card).
 * Rows whose value is unknown render “未知” in tertiary text and lose their copy button.
 */
@Composable
internal fun HomeIpDetailScreen(
    state: HomeUiState,
    side: HomeNetSide,
    onSideChange: (HomeNetSide) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCopy: (label: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomeColors.current
    val live = state.status.isLive
    val wan = side == HomeNetSide.Wan
    val rows = if (wan) {
        if (live) listOf(
            IpRow("IP 地址", state.wan.ip),
            IpRow("地理位置", state.wan.region),
            IpRow("网络运营商", state.wan.isp),
            IpRow("ASN", state.wan.asn),
            IpRow("城市", state.wan.city),
            IpRow("组织", state.wan.organization),
            IpRow("IP 类型", state.wan.ipType),
            IpRow("时区", state.wan.timezone),
            IpRow("经纬度", state.wan.coordinates),
        ) else listOf(IpRow("IP 地址", null, copyable = false), IpRow("地理位置", null, copyable = false))
    } else listOf(IpRow("IP 地址", state.lan.ip), IpRow("网络接口", state.lan.iface))
    val note = when {
        !wan -> null
        !live -> "代理未运行，公网出口信息需要启动后经核心查询。"
        else -> "出口信息经代理核心查询，「未知」表示查询源未返回该字段。"
    }

    Column(modifier.fillMaxSize().background(c.bg)) {
        HomeTopBar(
            title = if (wan) "公网 IP 详情" else "局域网 IP 详情",
            onBack = onBack,
            actions = { HomeIconButton(HomeIcons.RefreshCw, "刷新", onRefresh, loading = state.ipRefreshing) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = HomeDims.gutter, end = HomeDims.gutter, top = 0.dp, bottom = 32.dp),
        ) {
            HomeSegmented(
                options = listOf(HomeNetSide.Wan to "公网 IP", HomeNetSide.Lan to "局域网 IP"),
                selected = side,
                onSelect = onSideChange,
            )
            Spacer(Modifier.height(16.dp))
            HomeCard(Modifier.fillMaxWidth().alpha(if (state.ipRefreshing) .45f else 1f)) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HomeDivider()
                    val value = row.value?.takeIf { it.isNotBlank() && it != HomeFormat.Dash }
                    val placeholder = if (wan && !live) HomeFormat.Dash else "未知"
                    HomeKeyValueRow(
                        label = row.label,
                        value = value ?: placeholder,
                        labelWidth = 72.dp,
                        unknown = value == null,
                        trailing = if (value != null && row.copyable) {
                            { HomeIconButton(HomeIcons.Copy, "复制${row.label}", { onCopy(row.label, value) }, tint = c.t3) }
                        } else null,
                    )
                }
            }
            if (note != null) Text(note, Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp), color = c.t3, style = HomeType.caption)
        }
    }
}
