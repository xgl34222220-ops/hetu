package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.tools.ToolsButton as HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.tools.ToolsSurfaceCard as HomeCard
import io.github.xgl34222220.hetu.tools.ToolsDesignDims as HomeDims
import io.github.xgl34222220.hetu.tools.ToolsHairline as HomeDivider
import io.github.xgl34222220.hetu.tools.ToolsIconButton as HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.tools.ToolsTypography as HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors

/* ------------------------------------------------------------------ */
/*  绕过规则 (pages 36, 37)                                              */
/* ------------------------------------------------------------------ */

/**
 * 绕过规则 (工具 › 绕过规则). Stateless: edits go to the draft in [state].
 *
 * - Page 36: two editable lists; “✓ 保存” lights up once the draft differs from what is saved.
 * - Page 37: leaving with a draft shows [ToolsDiscardChangesDialogCard] (hosted by the route).
 */
@Composable
internal fun ToolsBypassScreen(
    state: ToolsBypassState,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDraftChange: (ToolsBypassRules) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    val editable = state.load is ToolsLoad.Ready && !state.saving
    ToolsWidePage(
        title = "绕过规则",
        onBack = onBack,
        modifier = modifier,
        actions = { ToolsBarTextButton("保存", HomeIcons.Check, onSave, enabled = state.dirty && editable, loading = state.saving) },
    ) {
        ToolsLead("这些地址与接口在 Root 层直接放行，不进入 Mihomo。")
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading()
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.Split, "绕过规则读取失败", subtitle = load.message) {
                HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> Column(verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                HomeCard(Modifier.fillMaxWidth()) {
                    ToolsCardTitle("CIDR")
                    ToolsListEditor(
                        label = "CIDR", values = draft.cidrs, placeholder = "例如 10.0.0.0/8 或 fd00::/8", enabled = editable,
                        onChange = { index, value -> onDraftChange(draft.copy(cidrs = draft.cidrs.replaced(index, value))) },
                        onRemove = { index -> onDraftChange(draft.copy(cidrs = draft.cidrs.without(index))) },
                        onAdd = { onDraftChange(draft.copy(cidrs = draft.cidrs + "")) },
                    )
                }
                HomeCard(Modifier.fillMaxWidth()) {
                    ToolsCardTitle("禁用接口")
                    ToolsListEditor(
                        label = "接口", values = draft.interfaces, placeholder = "例如 dummy0、tun+", enabled = editable,
                        onChange = { index, value -> onDraftChange(draft.copy(interfaces = draft.interfaces.replaced(index, value))) },
                        onRemove = { index -> onDraftChange(draft.copy(interfaces = draft.interfaces.without(index))) },
                        onAdd = { onDraftChange(draft.copy(interfaces = draft.interfaces + "")) },
                    )
                }
                ToolsNote("接口名不能填写 lo。修改后重启代理生效。", Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
        }
    }
}

internal fun List<String>.replaced(index: Int, value: String): List<String> = mapIndexed { at, old -> if (at == index) value else old }
internal fun List<String>.without(index: Int): List<String> = filterIndexed { at, _ -> at != index }

/** Page 37, and the same question on 共享网络: “放弃修改？”. */
@Composable
internal fun ToolsDiscardChangesDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        title = "放弃修改？", text = "当前修改还没有保存。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.DangerSoft,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

/* ------------------------------------------------------------------ */
/*  共享网络 (page 38)                                                   */
/* ------------------------------------------------------------------ */

/**
 * 共享网络 (工具 › 共享网络). Stateless: edits go to the draft in [state], ✓ saves it.
 *
 * Every switch below the master switch means “direct”: an interface or a downstream device
 * that is switched on bypasses the proxy. Device switches and the MAC list edit the same set.
 * With the master switch off the three lower cards are dimmed and inert.
 */
@Composable
internal fun ToolsShareScreen(
    state: ToolsShareState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onDraftChange: (ToolsShareSettings) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomeColors.current
    val draft = state.draft
    val ready = state.load is ToolsLoad.Ready
    val editable = ready && !state.saving
    val macs = draft.macs.map { it.trim().lowercase() }
    ToolsPage(
        title = "共享网络",
        onBack = onBack,
        modifier = modifier.imePadding(),
        refreshing = state.refreshing,
        onRefresh = if (state.saving) null else onRefresh,
        subtitle = "热点、USB 与局域网转发流量",
        actions = {
            HomeIconButton(HomeIcons.RefreshCw, "刷新", onRefresh, enabled = ready, loading = state.refreshing)
            HomeIconButton(HomeIcons.Check, "保存", onSave, enabled = state.dirty && editable, loading = state.saving, tint = if (state.dirty) c.accent else c.t3)
        },
    ) {
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading()
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.RadioTower, "共享网络设置读取失败", subtitle = load.message) {
                HomeButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> Column(verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                ToolsInfoCard("开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root 层生效，修改后重启代理。")
                HomeCard(Modifier.fillMaxWidth()) {
                    ToolsSwitchRow("启用共享网络", draft.enabled, { onDraftChange(draft.copy(enabled = it)) }, icon = ToolsIcons.Wifi, subtitle = "将共享流量纳入透明代理", enabled = editable)
                }
                val lower = editable && draft.enabled
                Column(Modifier.alpha(if (draft.enabled) 1f else .5f), verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("共享网络接口")
                        if (state.interfaces.isEmpty()) {
                            Text(state.note.ifBlank { "没有检测到正在共享的接口。" }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color = c.t2, style = HomeType.note)
                        }
                        state.interfaces.forEachIndexed { index, item ->
                            if (index > 0) HomeDivider(Modifier.padding(horizontal = 16.dp))
                            val direct = item.name in draft.directInterfaces
                            ToolsSwitchRow(
                                item.name, direct,
                                { on -> onDraftChange(draft.copy(directInterfaces = if (on) draft.directInterfaces + item.name else draft.directInterfaces - item.name)) },
                                icon = ToolsIcons.Link,
                                subtitle = (if (direct) "直连" else "接管") + if (item.state.isBlank()) "" else " · 状态 ${item.state}",
                                enabled = lower,
                            )
                        }
                    }
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("下游设备")
                        if (state.clients.isEmpty()) {
                            Text("没有检测到下游设备。", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), color = c.t2, style = HomeType.note)
                        }
                        state.clients.forEachIndexed { index, client ->
                            if (index > 0) HomeDivider(Modifier.padding(horizontal = 16.dp))
                            val mac = client.mac.lowercase()
                            ToolsSwitchRow(
                                client.ip, mac in macs,
                                { on -> onDraftChange(draft.copy(macs = if (on) draft.macs + mac else draft.macs.filter { it.trim().lowercase() != mac })) },
                                icon = ToolsFeatureIcons.Laptop,
                                subtitle = listOf(client.mac, client.iface, client.state).filter { it.isNotBlank() }.joinToString(" · "),
                                monospaceSubtitle = true,
                                enabled = lower,
                            )
                        }
                    }
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("MAC 列表")
                        ToolsListEditor(
                            label = "MAC 地址", values = draft.macs, placeholder = "aa:bb:cc:dd:ee:ff", enabled = lower,
                            onChange = { index, value -> onDraftChange(draft.copy(macs = draft.macs.replaced(index, value))) },
                            onRemove = { index -> onDraftChange(draft.copy(macs = draft.macs.without(index))) },
                            onAdd = { onDraftChange(draft.copy(macs = draft.macs + "")) },
                        )
                    }
                }
                ToolsNote("名单内设备在 TPROXY、Redirect、DNS、UDP 防泄漏与 QUIC 链中优先直连。最多 ${ToolsFeatureRules.MaxMacs} 个 MAC。", Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  CNIP 设置 (page 39)                                                  */
/* ------------------------------------------------------------------ */

/** CNIP 设置 (工具 › CNIP). One switch that saves immediately, and a read-only source row. */
@Composable
internal fun ToolsCnIpScreen(
    state: ToolsCnIpState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalHomeColors.current
    ToolsPage(title = "CNIP 设置", onBack = onBack, modifier = modifier, subtitle = "中国大陆 IPv4 / IPv6 自动直连") {
        ToolsInfoCard("CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。")
        Spacer(Modifier.height(HomeDims.gap))
        HomeCard(Modifier.fillMaxWidth()) {
            ToolsSwitchRow(
                "绕过 CNIP", state.enabled, onEnabledChange,
                icon = ToolsFeatureIcons.Globe, subtitle = "命中国内 IPv4 / IPv6 网段时直接连接", enabled = state.load is ToolsLoad.Ready,
            )
        }
        Spacer(Modifier.height(HomeDims.gap))
        HomeCard(Modifier.fillMaxWidth()) {
            ToolsRow(AnnotatedString("数据源"), icon = ToolsIcons.Database, iconTint = c.t1, subtitle = "内置离线快照 + Mihomo provider 运行时更新")
        }
    }
}
