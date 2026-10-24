package io.github.xgl34222220.hetu.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeCard
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeIconButton
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeRowDims
import io.github.xgl34222220.hetu.home.HomeRowDivider
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.homeAnimatedFraction
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.homeEnter
import io.github.xgl34222220.hetu.home.rememberHomeStagger
import io.github.xgl34222220.hetu.ui.ht

/* ------------------------------------------------------------------ */
/*  绕过规则                                                             */
/* ------------------------------------------------------------------ */

/**
 * 绕过规则 (工具 › 绕过规则). Stateless: edits go to the draft in [state].
 *
 * Two editable lists; “✓ 保存” in the bar lights up once the draft differs from what is saved.
 * Leaving with a draft shows [ToolsDiscardChangesDialogCard] (hosted by the route).
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
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "绕过规则",
        onBack = onBack,
        modifier = modifier,
        actions = { ToolsBarTextButton("保存", HomeIcons.Check, onSave, enabled = state.dirty && editable, loading = state.saving) },
    ) {
        ToolsLead("这些地址与接口在 Root 层直接放行，不进入 Mihomo。")
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading(cards = 2)
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.Split, "绕过规则读取失败", subtitle = load.message) {
                ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> {
                HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 0)) {
                    ToolsCardTitle("CIDR")
                    ToolsListEditor(
                        label = "CIDR", values = draft.cidrs, placeholder = "例如 10.0.0.0/8 或 fd00::/8", enabled = editable,
                        onChange = { index, value -> onDraftChange(draft.copy(cidrs = draft.cidrs.replaced(index, value))) },
                        onRemove = { index -> onDraftChange(draft.copy(cidrs = draft.cidrs.without(index))) },
                        onAdd = { onDraftChange(draft.copy(cidrs = draft.cidrs + "")) },
                    )
                }
                HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 1)) {
                    ToolsCardTitle("禁用接口")
                    ToolsListEditor(
                        label = "接口", values = draft.interfaces, placeholder = "例如 dummy0、tun+", enabled = editable,
                        onChange = { index, value -> onDraftChange(draft.copy(interfaces = draft.interfaces.replaced(index, value))) },
                        onRemove = { index -> onDraftChange(draft.copy(interfaces = draft.interfaces.without(index))) },
                        onAdd = { onDraftChange(draft.copy(interfaces = draft.interfaces + "")) },
                    )
                }
                ToolsNote("接口名不能填写 lo。修改后重启代理生效。", Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

internal fun List<String>.replaced(index: Int, value: String): List<String> = mapIndexed { at, old -> if (at == index) value else old }
internal fun List<String>.without(index: Int): List<String> = filterIndexed { at, _ -> at != index }

/** “放弃修改？” — asked when leaving 绕过规则 or 共享网络 with a draft. */
@Composable
internal fun ToolsDiscardChangesDialogCard(onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    ToolsDialogCard(
        title = "放弃修改？", text = "当前修改还没有保存。",
        confirmLabel = "放弃", confirmKind = ToolsConfirmKind.DangerSoft,
        icon = HomeIcons.TriangleAlert, iconTone = HomeTone.Warn,
        onConfirm = onConfirm, onCancel = onCancel, modifier = modifier,
    )
}

/* ------------------------------------------------------------------ */
/*  共享网络                                                             */
/* ------------------------------------------------------------------ */

/**
 * 共享网络 (工具 › 共享网络). Stateless: edits go to the draft in [state], ✓ saves it.
 *
 * Interface switches choose the tether ingress the Root rules are scoped to (stored apart from
 * 绕过规则; the upstream is never offered). MAC mode: blacklist (listed devices direct) or
 * whitelist (only listed devices proxied); device switches and the MAC list edit the list of the
 * current mode. With the master switch off the lower cards fade back and are inert.
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
    val stagger = rememberHomeStagger()
    ToolsPage(
        title = "共享网络",
        onBack = onBack,
        modifier = modifier,
        refreshing = state.refreshing,
        onRefresh = if (state.saving) null else onRefresh,
        subtitle = "热点、USB 与局域网转发流量",
        actions = {
            HomeIconButton(HomeIcons.RefreshCw, "刷新", onRefresh, enabled = ready && !state.refreshing, spinning = state.refreshing)
            HomeIconButton(HomeIcons.Check, "保存", onSave, enabled = state.dirty && editable, loading = state.saving, tint = if (state.dirty) c.accent else c.t3, glyph = 26.dp)
        },
    ) {
        when (val load = state.load) {
            ToolsLoad.Loading -> ToolsLoading(cards = 4)
            is ToolsLoad.Failed -> ToolsEmpty(ToolsIcons.RadioTower, "共享网络设置读取失败", subtitle = load.message) {
                ToolsButton("重新读取", onRetry, kind = HomeButtonKind.Primary, icon = HomeIcons.RefreshCw)
            }
            ToolsLoad.Ready -> {
                ToolsInfoCard("开启后河图只接管所选共享接口（热点 / USB / 以太网共享）进入的流量；上行网络不会被当作共享入口。接口与 MAC 名单在 Root 层生效，修改后重启代理。", Modifier.homeEnter(stagger, 0))
                HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 1)) {
                    ToolsSwitchRow("启用共享网络", draft.enabled, { onDraftChange(draft.copy(enabled = it)) }, icon = ToolsIcons.Wifi, subtitle = "将共享流量纳入透明代理", enabled = editable)
                }
                val lower = editable && draft.enabled
                val whitelist = draft.whitelist
                val listed = (if (whitelist) draft.onlyMacs else draft.macs).map { it.trim().lowercase() }
                fun setListed(next: List<String>) = onDraftChange(if (whitelist) draft.copy(onlyMacs = next) else draft.copy(macs = next))
                val presence = .5f + .5f * homeAnimatedFraction(if (draft.enabled) 1f else 0f, "tools-share-lower")
                Column(Modifier.alpha(presence).homeEnter(stagger, 2), verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("共享网络接口")
                        if (state.interfaces.isEmpty()) {
                            Text(state.note.ifBlank { ht("没有检测到正在共享的接口。") }, Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 16.dp), color = c.t2, style = HomeType.note)
                        } else if (state.note.isNotBlank()) {
                            Text(state.note, Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 10.dp), color = c.t2, style = HomeType.note)
                        }
                        state.interfaces.forEachIndexed { index, item ->
                            if (index > 0) HomeRowDivider(start = HomeRowDims.textStart)
                            val scoped = item.name in draft.sharedInterfaces
                            ToolsSwitchRow(
                                item.name, scoped,
                                { on -> onDraftChange(draft.copy(sharedInterfaces = if (on) draft.sharedInterfaces + item.name else draft.sharedInterfaces - item.name)) },
                                rawContent = true,
                                icon = ToolsIcons.Link,
                                subtitle = ht(if (item.upstream) "上行网络，不能作为共享入口" else if (scoped) "接管" else "不接管") +
                                    if (item.state.isBlank()) "" else " · ${ht("状态")} ${item.state}",
                                enabled = lower && (!item.upstream || scoped),
                            )
                        }
                        if (draft.sharedInterfaces.isEmpty()) {
                            Text(ht("未指定接口时兼容旧行为：接管所有转发入口。建议勾选实际的共享接口。"), Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, top = 6.dp, bottom = 14.dp), color = c.t2, style = HomeType.note)
                        }
                    }
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("MAC 名单模式")
                        ToolsSwitchRow(
                            "仅代理名单内设备", whitelist,
                            { on -> onDraftChange(draft.copy(macMode = if (on) "only" else "direct")) },
                            icon = ToolsFeatureIcons.ShieldCheck,
                            subtitle = if (whitelist) "白名单：名单外设备直连；名单为空时不代理任何共享设备" else "黑名单：名单内设备直连，其余设备走代理",
                            enabled = lower,
                        )
                    }
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle("下游设备")
                        if (state.clients.isEmpty()) {
                            Text(ht("没有检测到下游设备。"), Modifier.padding(start = HomeRowDims.start, end = HomeRowDims.start, bottom = 16.dp), color = c.t2, style = HomeType.note)
                        }
                        state.clients.forEachIndexed { index, client ->
                            if (index > 0) HomeRowDivider(start = HomeRowDims.textStart)
                            val mac = client.mac.lowercase()
                            ToolsSwitchRow(
                                client.hostname.ifBlank { client.ip }, mac in listed,
                                { on -> setListed(if (on) (if (whitelist) draft.onlyMacs else draft.macs) + mac else (if (whitelist) draft.onlyMacs else draft.macs).filter { it.trim().lowercase() != mac }) },
                                rawContent = true,
                                icon = ToolsFeatureIcons.Laptop,
                                subtitle = listOf(if (client.hostname.isBlank()) "" else client.ip, client.mac, client.iface, client.state).filter { it.isNotBlank() }.joinToString(" · "),
                                monospaceSubtitle = true,
                                enabled = lower,
                            )
                        }
                    }
                    HomeCard(Modifier.fillMaxWidth()) {
                        ToolsCardTitle(if (whitelist) "MAC 白名单" else "MAC 直连名单")
                        val values = if (whitelist) draft.onlyMacs else draft.macs
                        ToolsListEditor(
                            label = "MAC 地址", values = values, placeholder = "aa:bb:cc:dd:ee:ff", enabled = lower,
                            onChange = { index, value -> setListed(values.replaced(index, value)) },
                            onRemove = { index -> setListed(values.without(index)) },
                            onAdd = { setListed(values + "") },
                        )
                    }
                }
                ToolsNote(
                    ht(if (whitelist) "白名单设备在 TPROXY、Redirect、DNS、UDP 防泄漏、QUIC 与 Kill Switch 链中走代理，其余共享设备直连。" else "名单内设备在 TPROXY、Redirect、DNS、UDP 防泄漏与 QUIC 链中优先直连。") +
                        ht("最多 %d 个 MAC。").replace("%d", ToolsFeatureRules.MaxMacs.toString()),
                    Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/*  CNIP 设置                                                            */
/* ------------------------------------------------------------------ */

/** CNIP 设置 (工具 › CNIP). One switch that saves immediately, and a read-only source row. */
@Composable
internal fun ToolsCnIpScreen(
    state: ToolsCnIpState,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onKernelChange: ((v4: Boolean, v6: Boolean) -> Unit)? = null,
    onTcpOnlyChange: ((Boolean) -> Unit)? = null,
    onUdpDirectChange: ((Boolean) -> Unit)? = null,
) {
    val stagger = rememberHomeStagger()
    ToolsPage(title = "CNIP 设置", onBack = onBack, modifier = modifier, subtitle = "中国大陆 IPv4 / IPv6 自动直连") {
        ToolsInfoCard("CNIP 只补充 IP 级直连，不替代 YAML 中已有的域名规则。修改后重启代理生效。", Modifier.homeEnter(stagger, 0))
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 1)) {
            ToolsSwitchRow(
                "绕过 CNIP", state.enabled || state.kernelV4 || state.kernelV6, onEnabledChange,
                icon = ToolsFeatureIcons.Globe, subtitle = if (state.kernelV4 || state.kernelV6) "内核绕过开启时，核心内规则同时保留作回退"
                    else "命中国内 IPv4 / IPv6 网段时直接连接",
                enabled = state.load is ToolsLoad.Ready && !state.kernelV4 && !state.kernelV6,
            )
        }
        if (onKernelChange != null) {
            // Kernel-level bypass: the same glass card family as the rows above.
            HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 2)) {
                if (onUdpDirectChange != null) {
                    // 6.0.0, default on: calls and games to CN addresses skip the core's userspace UDP relay.
                    val full = state.kernelV4 || state.kernelV6
                    ToolsSwitchRow(
                        "国内 UDP 内核直连", state.udpDirect || full, onUdpDirectChange,
                        icon = ToolsFeatureIcons.ShieldCheck,
                        subtitle = if (full) "已开启内核绕过：国内 TCP 和 UDP 都不经过核心"
                            else "视频 / 语音通话、游戏发往国内 IP 的 UDP 不经过核心，减少卡顿；TCP 仍经核心规则。默认开启",
                        enabled = state.load is ToolsLoad.Ready && !full,
                    )
                    HomeRowDivider(start = HomeRowDims.textStart)
                }
                ToolsSwitchRow(
                    "内核绕过 IPv4", state.kernelV4, { onKernelChange(it, state.kernelV6) },
                    icon = ToolsFeatureIcons.ShieldCheck, subtitle = "国内 IPv4 直接放行，不经过核心（ipset）",
                    enabled = state.load is ToolsLoad.Ready,
                )
                HomeRowDivider(start = HomeRowDims.textStart)
                ToolsSwitchRow(
                    "内核绕过 IPv6", state.kernelV6, { onKernelChange(state.kernelV4, it) },
                    icon = ToolsFeatureIcons.ShieldCheck, subtitle = "仅 IPv6「启用 / 严格」模式生效",
                    enabled = state.load is ToolsLoad.Ready,
                )
                HomeRowDivider(start = HomeRowDims.textStart)
                if (onTcpOnlyChange != null) {
                    // Ad blocking: CN-resolved ad hosts bypass the core entirely; keeping UDP/QUIC in
                    // the core lets its rules still see those flows. Default off.
                    ToolsSwitchRow(
                        "仅 TCP 旁路", state.kernelTcpOnly, onTcpOnlyChange,
                        icon = ToolsFeatureIcons.Shield, subtitle = "只让国内 TCP 连接绕过核心，UDP / QUIC 仍经核心规则（含去广告）",
                        enabled = state.load is ToolsLoad.Ready && (state.kernelV4 || state.kernelV6),
                    )
                    HomeRowDivider(start = HomeRowDims.textStart)
                }
                val kernelStatusText = buildList { for (part in cnIpKernelStatusParts(state)) add(ht(part.key).fill(*part.args.toTypedArray())) }.joinToString(" · ")
                ToolsRow(AnnotatedString(ht("内核状态")), icon = ToolsIcons.Database, subtitle = kernelStatusText)
            }
        }
        HomeCard(Modifier.fillMaxWidth().homeEnter(stagger, 3)) {
            ToolsRow(AnnotatedString(ht("数据源")), icon = ToolsIcons.Database,
                subtitle = ht("gaoyifan/china-operator-ip（MIT）· 内置离线快照 + 核心每日更新，内核集合随之热更新"))
        }
    }
}

/** One translatable table key of the 内核状态 line plus its %s values. */
internal data class CnIpStatusPart(val key: String, val args: List<Any> = emptyList())

/**
 * 内核状态 of the running session: ipset or the iptables tree (ROMs without an ipset binary, such as
 * stock ColorOS) both keep CN destinations out of the core; degraded names why the core RULE-SET
 * does the job instead. Each part is a table key so every language gets the whole line.
 */
internal fun cnIpKernelStatusParts(state: ToolsCnIpState): List<CnIpStatusPart> {
    val reason = state.kernelReason
    val active = state.kernelStatus == "ipset" || state.kernelStatus == "iptables" || state.kernelStatus == "ebpf"
    return when {
        // 6.0.0: the default UDP-only bypass while the full bypass is off; a kernel without it runs without it.
        !state.kernelV4 && !state.kernelV6 && state.udpDirect -> when {
            active && state.kernelUdpOnly -> buildList {
                add(CnIpStatusPart(if (state.kernelStatus == "ebpf") "国内 UDP 内核直连生效（eBPF）" else "国内 UDP 内核直连生效（ipset）"))
                if (state.kernelV4Entries > 0) add(CnIpStatusPart("IPv4 %s 段", listOf(state.kernelV4Entries)))
                if (state.kernelV6Entries > 0) add(CnIpStatusPart("IPv6 %s 段", listOf(state.kernelV6Entries)))
            }
            state.kernelStatus == "off" && reason.isNotBlank() -> listOf(CnIpStatusPart("本机内核不支持国内 UDP 直连，UDP 仍经核心（%s）", listOf(reason)))
            else -> listOf(CnIpStatusPart("重启代理后生效"))
        }
        !state.kernelV4 && !state.kernelV6 -> listOf(CnIpStatusPart("未开启"))
        active -> buildList {
            add(CnIpStatusPart(when (state.kernelStatus) {
                "ipset" -> "内核 ipset 生效"
                "ebpf" -> "内核 eBPF 生效（本机没有 ipset）"
                else -> "内核 iptables 规则生效（本机没有 ipset）"
            }))
            if (state.kernelV4Entries > 0) add(CnIpStatusPart("IPv4 %s 段", listOf(state.kernelV4Entries)))
            if (state.kernelV6Entries > 0) add(CnIpStatusPart("IPv6 %s 段", listOf(state.kernelV6Entries)))
            if (state.kernelV6 && reason.contains("v6-mode-")) add(CnIpStatusPart("IPv6 未生效：需要 IPv6「启用 / 严格」模式"))
        }
        state.kernelStatus == "degraded" -> listOf(when {
            reason.contains("chain-missing") || reason.contains("pins-missing") -> CnIpStatusPart("内核绕过规则已丢失，已降级为核心内规则；重启代理可恢复")
            reason.contains("bpf-") -> CnIpStatusPart("设备没有 ipset，eBPF 也不可用，已降级为核心内规则（%s）", listOf(reason))
            reason.contains("xt-connmark-unavailable") -> CnIpStatusPart("设备没有 ipset，也不支持 iptables 连接标记，已降级为核心内规则")
            reason.contains("mode-tun") || reason.contains("mode-ebpf") -> CnIpStatusPart("当前代理模式不支持内核绕过，已降级为核心内规则")
            reason.contains("iptables-failed") || reason.contains("restore-missing") -> CnIpStatusPart("内核 iptables 规则安装失败，已降级为核心内规则（%s）", listOf(reason))
            reason.contains("data-invalid") || reason.contains("data-missing") -> CnIpStatusPart("中国 IP 数据缺失或无效，已降级为核心内规则（%s）", listOf(reason))
            // No ipset binary (e.g. stock ColorOS): the iptables tree is opt-in only since 4.4.4.
            reason.contains("ipset-unavailable") -> CnIpStatusPart("设备不支持 ipset，已降级为核心内规则")
            reason.contains("v6-mode-") -> CnIpStatusPart("IPv6 未生效：需要 IPv6「启用 / 严格」模式")
            reason.isBlank() -> CnIpStatusPart("内核绕过未生效，已降级为核心内规则")
            else -> CnIpStatusPart("内核绕过未生效，已降级为核心内规则（%s）", listOf(reason))
        })
        else -> listOf(CnIpStatusPart("重启代理后生效"))
    }
}

/** Plain Chinese 内核状态 line (diagnostics text, tests). */
internal fun cnIpKernelStatusText(state: ToolsCnIpState): String =
    cnIpKernelStatusParts(state).joinToString(" · ") { it.key.fill(*it.args.toTypedArray()) }
