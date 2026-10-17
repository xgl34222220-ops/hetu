package io.github.xgl34222220.hetu.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.ht

private class IpRow(val label: String, val value: String?, val copyable: Boolean = true)

/**
 * 公网 / 局域网 IP 详情 (full page, pushed from the WAN / LAN card).
 * Rows whose value is unknown render “未知” in tertiary text and lose their copy button.
 * Switching sides slides the list in the direction of the chosen segment.
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
    val motion = LocalHomeMotionEnabled.current
    val live = state.status.isLive
    HomeSubPage(
        title = if (side == HomeNetSide.Wan) "公网 IP 详情" else "局域网 IP 详情",
        onBack = onBack,
        modifier = modifier,
        actions = { HomeIconButton(HomeIcons.RefreshCw, "刷新", { if (!state.ipRefreshing) onRefresh() }, spinning = state.ipRefreshing, tint = if (state.ipRefreshing) c.accent else c.t1) },
    ) {
        HomeSegmented(
            options = listOf(HomeNetSide.Wan to "公网 IP", HomeNetSide.Lan to "局域网 IP"),
            selected = side,
            onSelect = onSideChange,
            track = c.surface,
            height = 46.dp,
            corner = 23.dp,
            textStyle = HomeType.button,
        )
        AnimatedContent(
            targetState = side,
            transitionSpec = {
                if (!motion) EnterTransition.None togetherWith ExitTransition.None
                else {
                    val forward = targetState.ordinal > initialState.ordinal
                    (slideInHorizontally(tween(HomeMotion.PageMs, easing = HomeMotion.Emphasized)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(220)))
                        .togetherWith(slideOutHorizontally(tween(200, easing = HomeMotion.Emphasized)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(120)))
                        .using(SizeTransform(clip = false))
                }
            },
            label = "home-ip-side",
        ) { shown ->
            val wan = shown == HomeNetSide.Wan
            val rows = if (wan) listOf(
                IpRow("IP 地址", state.wan.ip),
                IpRow("地理位置", state.wan.region),
                IpRow("网络运营商", state.wan.isp),
                IpRow("ASN", state.wan.asn),
                IpRow("城市", state.wan.city),
                IpRow("组织", state.wan.organization),
                IpRow("IP 类型", state.wan.ipType),
                IpRow("时区", state.wan.timezone),
                IpRow("经纬度", state.wan.coordinates),
            ) else listOf(IpRow("IP 地址", state.lan.ip), IpRow("网络接口", state.lan.iface))
            // A failed refresh with an earlier answer keeps that answer (labelled below); only
            // a lookup that has nothing to show is reported as a failure.
            val failure = state.wan.error?.takeIf { wan && state.wan.ip == null && state.wan.state == HomeWanState.Failed && it.isNotBlank() }
            val kept = wan && state.wan.ip != null && (state.wan.state == HomeWanState.Stale || state.wan.state == HomeWanState.Failed)
            val path = if (live) ht("经代理查询，与普通应用看到的出口相同。") else ht("代理未运行，显示本机直连的公网出口。")
            val note = when {
                !wan -> null
                kept -> ht("%s；最新查询未成功，下拉首页或点右上角可重新查询。").fill(homeCheckedText(state.wan.checkedAt)) + " " + path
                else -> path + " " + ht("「未知」表示查询源未返回该字段。")
            }
            val loading = wan && state.wan.ip == null && (state.wan.state == HomeWanState.Loading || state.wan.state == HomeWanState.Idle)
            Column {
                if (failure != null) HomeNotice(ht("公网信息查询失败：%s").fill(ht(failure)), HomeIcons.CircleAlert, Modifier.padding(bottom = HomeDims.gap), tone = HomeTone.Bad)
                HomeCard(Modifier.fillMaxWidth().alpha(if (state.ipRefreshing || kept) .6f else 1f)) {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) HomeDivider(inset = HomeDims.cardPadding)
                        val value = row.value?.takeIf { it.isNotBlank() && it != HomeFormat.Dash }
                        val placeholder = if (loading) ht("正在查询") else ht("未知")
                        HomeKeyValueRow(
                            label = ht(row.label),
                            value = value ?: placeholder,
                            labelWidth = 104.dp,
                            unknown = value == null,
                            trailing = if (value != null && row.copyable) {
                                { HomeIconButton(HomeIcons.Copy, ht("复制") + ht(row.label), { onCopy(row.label, value) }, tint = c.t1, glyph = 22.dp) }
                            } else null,
                        )
                    }
                }
                if (note != null) Text(note, Modifier.padding(start = 6.dp, end = 6.dp, top = 10.dp), color = c.t3, style = HomeType.caption)
            }
        }
    }
}
