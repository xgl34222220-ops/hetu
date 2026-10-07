package io.github.xgl34222220.hetu.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.home.HomeButton
import io.github.xgl34222220.hetu.home.HomeButtonKind
import io.github.xgl34222220.hetu.home.HomeDims
import io.github.xgl34222220.hetu.home.HomeDivider
import io.github.xgl34222220.hetu.home.HomeFlag
import io.github.xgl34222220.hetu.home.HomeFormat
import io.github.xgl34222220.hetu.home.HomeHaptic
import io.github.xgl34222220.hetu.home.HomeIcons
import io.github.xgl34222220.hetu.home.HomeNotice
import io.github.xgl34222220.hetu.home.HomeRegions
import io.github.xgl34222220.hetu.home.HomeReveal
import io.github.xgl34222220.hetu.home.HomeSegmented
import io.github.xgl34222220.hetu.home.HomeSheetContent
import io.github.xgl34222220.hetu.home.HomeSheetGroup
import io.github.xgl34222220.hetu.home.HomeSpinner
import io.github.xgl34222220.hetu.home.HomeTextField
import io.github.xgl34222220.hetu.home.HomeTone
import io.github.xgl34222220.hetu.home.HomeType
import io.github.xgl34222220.hetu.home.LocalHomeColors
import io.github.xgl34222220.hetu.home.LocalHomeHaptics
import io.github.xgl34222220.hetu.home.fill
import io.github.xgl34222220.hetu.home.goodText
import io.github.xgl34222220.hetu.home.homeTap
import io.github.xgl34222220.hetu.ui.ht

/* ------------------------------------------------------------------ */
/*  Menus                                                               */
/* ------------------------------------------------------------------ */

/**
 * Body of whichever menu [overlay] names. Every menu applies the pick and closes,
 * the check-box menu of 策略 › 筛选 included.
 */
@Composable
internal fun ColumnScope.PanelMenuContent(
    overlay: PanelOverlay,
    view: PanelViewState,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
) {
    fun pick(next: PanelViewState) { onView(next); onOverlay(null) }
    when (overlay) {
        PanelOverlay.GroupFilterMenu -> {
            val d = view.display
            fun set(next: PanelGroupDisplay) = pick(view.copy(display = next))
            PanelMenuItem("显示隐藏策略", { set(d.copy(showHidden = !d.showHidden)) }, checked = d.showHidden)
            PanelMenuItem("根据模式显示 GLOBAL", { set(d.copy(globalByMode = !d.globalByMode)) }, checked = d.globalByMode)
            PanelMenuItem("按订阅分组节点", { set(d.copy(groupByProvider = !d.groupByProvider)) }, checked = d.groupByProvider)
            PanelMenuItem("展开新策略时折叠上一个", { set(d.copy(collapsePrevious = !d.collapsePrevious)) }, checked = d.collapsePrevious)
            PanelMenuItem("切换节点后断开旧连接", { set(d.copy(disconnectOnSelect = !d.disconnectOnSelect)) }, checked = d.disconnectOnSelect)
        }
        PanelOverlay.RankModeMenu -> {
            PanelRankMode.entries.forEach { mode -> PanelMenuItem(mode.label, { pick(view.copy(rankMode = mode)) }, selected = view.rankMode == mode) }
            HomeDivider(Modifier.padding(top = 4.dp), inset = 12.dp)
            PanelMenuNote("排行只改变显示顺序，不影响代理行为。")
        }
        PanelOverlay.RankCountMenu -> {
            PanelMenuTitle("显示数量")
            PanelLogic.rankCounts.forEach { n -> PanelMenuItem(n.toString(), { pick(view.copy(rankCount = n)) }, selected = view.rankCount == n) }
        }
        PanelOverlay.ConnFilterMenu ->
            PanelConnFilter.entries.forEach { f -> PanelMenuItem(f.label, { pick(view.copy(connFilter = f)) }, selected = view.connFilter == f) }
        PanelOverlay.ConnSortMenu -> {
            PanelMenuTitle("排序方式")
            PanelConnSort.entries.forEach { s -> PanelMenuItem(s.label, { pick(view.copy(connSort = s)) }, selected = view.connSort == s) }
        }
        PanelOverlay.ConnMoreMenu -> {
            PanelMenuItem("按应用分组", { pick(view.copy(groupByApp = !view.groupByApp)) }, icon = PanelIcons.LayoutList, selected = view.groupByApp)
            HomeDivider(inset = 12.dp)
            PanelMenuItem("断开全部连接", { onOverlay(PanelOverlay.CloseAllDialog) }, icon = PanelIcons.Unlink, danger = true)
        }
        PanelOverlay.LogOrderMenu ->
            PanelLogOrder.entries.forEach { o -> PanelMenuItem(o.label, { pick(view.copy(logOrder = o)) }, selected = view.logOrder == o) }
        else -> Unit
    }
}

/* ------------------------------------------------------------------ */
/*  Sheets                                                              */
/* ------------------------------------------------------------------ */

/** Body of whichever bottom sheet [overlay] names. Wrap it in HomeModalSheet at runtime. */
@Composable
internal fun PanelSheetBody(
    overlay: PanelOverlay,
    data: PanelData,
    view: PanelViewState,
    actions: PanelActions,
    onView: (PanelViewState) -> Unit,
    onOverlay: (PanelOverlay?) -> Unit,
) {
    when (overlay) {
        PanelOverlay.LayoutSheet -> LayoutSheet(data, view, onView, onOverlay, actions)
        PanelOverlay.ApiSheet, PanelOverlay.ApiReadErrorSheet -> ApiSheet(view.api, onCancel = {
            onOverlay(if (overlay == PanelOverlay.ApiReadErrorSheet) null else PanelOverlay.LayoutSheet)
        }) { saved ->
            onView(view.copy(api = saved)); actions.onSaveApi(saved); onOverlay(null)
        }
        is PanelOverlay.NodeInfo -> NodeInfoSheet(overlay.node, data, onClose = { onOverlay(null) }, onTest = { actions.onTestNode(overlay.node) }, onCopy = { actions.onCopy("节点名称", overlay.node) })
        is PanelOverlay.ConnectionDetail -> {
            val conn = data.connections.firstOrNull { it.id == overlay.id }
            if (conn != null) ConnectionSheet(conn, data, onClose = { onOverlay(null) }) { actions.onCloseConnection(conn.id); onOverlay(null) }
            else HomeSheetContent(title = "连接已结束", onClose = { onOverlay(null) }) {
                Text(ht("这个连接已经关闭，列表会在下次刷新时更新。"), Modifier.padding(horizontal = 6.dp), color = LocalHomeColors.current.t2, style = HomeType.body)
            }
        }
        else -> Unit
    }
}

@Composable
private fun SheetRow(title: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ht(title), Modifier.weight(1f), color = LocalHomeColors.current.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        control()
    }
}

@Composable
private fun SheetNavRow(icon: ImageVector, title: String, subtitle: String, busy: Boolean = false, onClick: () -> Unit) {
    val c = LocalHomeColors.current
    val haptics = LocalHomeHaptics.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeight).homeTap(enabled = !busy, role = Role.Button) { haptics(HomeHaptic.Tap); onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.t1)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ht(title), color = c.t1, style = HomeType.rowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ht(subtitle), color = c.t2, style = HomeType.rowSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) HomeSpinner(size = 20.dp) else Icon(HomeIcons.ChevronRight, null, Modifier.size(20.dp), tint = c.t3)
    }
}

/** 排序与布局: every control applies immediately, so the list behind the sheet changes live. */
@Composable
private fun LayoutSheet(data: PanelData, view: PanelViewState, onView: (PanelViewState) -> Unit, onOverlay: (PanelOverlay?) -> Unit, actions: PanelActions) {
    val layout = view.layout
    fun set(next: PanelGroupLayout) = onView(view.copy(layout = next))
    val wide = Modifier.width(204.dp)
    val narrow = Modifier.width(204.dp)
    val columns = listOf(1 to "1 列", 2 to "2 列")
    HomeSheetContent(title = "排序与布局", onClose = { onOverlay(null) }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeSheetGroup {
                SheetRow("排序方式") { HomeSegmented(PanelNodeSort.entries.map { it to it.label }, layout.sort, { set(layout.copy(sort = it)) }, wide, height = 38.dp, corner = 11.dp) }
                HomeDivider(inset = 16.dp)
                PanelSwitchRow("倒序", layout.descending, { set(layout.copy(descending = it)) })
            }
            HomeSheetGroup {
                SheetRow("策略列数") { HomeSegmented(columns, layout.groupColumns, { set(layout.copy(groupColumns = it)) }, narrow, height = 38.dp, corner = 11.dp) }
                HomeDivider(inset = 16.dp)
                PanelSwitchRow("紧凑策略卡", layout.compactGroups, { set(layout.copy(compactGroups = it)) })
            }
            HomeSheetGroup {
                SheetRow("节点列数") { HomeSegmented(columns, layout.nodeColumns, { set(layout.copy(nodeColumns = it)) }, narrow, height = 38.dp, corner = 11.dp) }
                HomeDivider(inset = 16.dp)
                PanelSwitchRow("紧凑节点卡", layout.compactNodes, { set(layout.copy(compactNodes = it)) })
                HomeDivider(inset = 16.dp)
                SheetRow("名称显示") { HomeSegmented(listOf(false to "单行截断", true to "自动换行"), layout.wrapNames, { set(layout.copy(wrapNames = it)) }, wide, height = 38.dp, corner = 11.dp) }
            }
            HomeSheetGroup {
                SheetNavRow(HomeIcons.SlidersHorizontal, "测速与 API", "设置测速引擎与 API 相关选项") { onOverlay(PanelOverlay.ApiSheet) }
                HomeDivider(inset = 16.dp)
                SheetNavRow(PanelIcons.Gauge, "测试全部节点", if (data.testingAll) "正在测试，完成后会提示可用节点数" else "对当前配置的所有节点测一次延迟", busy = data.testingAll) { actions.onTestAll() }
                HomeDivider(inset = 16.dp)
                SheetNavRow(PanelIcons.Image, "策略图标", "覆盖策略组图标，不修改 YAML") { onOverlay(null); actions.onOpenPolicyIcons() }
            }
        }
    }
}

/**
 * 测速与 API. Edits a local draft; 保存 validates and commits, 取消 returns to 排序与布局.
 * Each switch reveals its fields only while it is on.
 */
@Composable
private fun ApiSheet(saved: PanelApiSettings, onCancel: () -> Unit, onSave: (PanelApiSettings) -> Unit) {
    val haptics = LocalHomeHaptics.current
    var draft by remember(saved) { mutableStateOf(saved) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun edit(next: PanelApiSettings) { draft = next; errors = emptyMap() }
    HomeSheetContent(
        title = "测速与 API",
        subtitle = "测速、历史采集与控制器连接",
        footer = {
            HomeButton("取消", onCancel, Modifier.weight(1f), kind = HomeButtonKind.Soft)
            HomeButton("保存", {
                val found = PanelLogic.validateApi(draft)
                if (found.isEmpty()) onSave(draft.copy(testUrl = draft.testUrl.trim(), host = draft.host.trim(), port = draft.port.trim()))
                else { haptics(HomeHaptic.Reject); errors = found }
            }, Modifier.weight(1f), kind = HomeButtonKind.Primary)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeSheetGroup {
                PanelSwitchRow("测速地址", draft.customTestUrl, { edit(draft.copy(customTestUrl = it)) }, subtitle = if (draft.customTestUrl) "正在使用自定义测速 URL" else "跟随订阅或策略组自带地址")
                HomeReveal(draft.customTestUrl) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp)) {
                        HomeTextField("测速 URL", draft.testUrl, { edit(draft.copy(testUrl = it)) }, isError = "testUrl" in errors, keyboardType = KeyboardType.Uri)
                        FieldError(errors["testUrl"])
                    }
                }
            }
            HomeSheetGroup {
                PanelSwitchRow("流量与连接历史", draft.history, { edit(draft.copy(history = it)) }, subtitle = "保存最近 24 小时排行所需的数据")
            }
            HomeSheetGroup {
                PanelSwitchRow("外部 Clash API", draft.externalApi, { edit(draft.copy(externalApi = it)) }, subtitle = if (draft.externalApi) "使用自定义控制器" else "使用河图本机核心")
                HomeReveal(draft.externalApi) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(1f)) {
                                HomeTextField("主机", draft.host, { edit(draft.copy(host = it)) }, isError = "host" in errors)
                                FieldError(errors["host"])
                            }
                            Column(Modifier.width(112.dp)) {
                                HomeTextField("端口", draft.port, { edit(draft.copy(port = it)) }, isError = "port" in errors, keyboardType = KeyboardType.Number)
                                FieldError(errors["port"])
                            }
                        }
                        Column {
                            HomeTextField("密钥", draft.secret, { edit(draft.copy(secret = it)) }, isError = "secret" in errors, placeholder = "未设置")
                            FieldError(errors["secret"])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldError(message: String?) {
    if (message == null) return
    HomeNotice(ht(message), HomeIcons.CircleAlert, Modifier.padding(top = 4.dp), tone = HomeTone.Bad, filled = false)
}

@Composable
private fun DetailRow(label: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = HomeDims.rowMinHeightSmall).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(ht(label), color = LocalHomeColors.current.t2, style = HomeType.label, maxLines = 1)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

@Composable
private fun DetailText(text: String, mono: Boolean = false) {
    Text(
        text, color = LocalHomeColors.current.t1,
        style = if (mono) PanelType.monoSmall else HomeType.value.copy(fontWeight = FontWeight.Medium),
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/** 节点信息 (long-press a node): protocol, provider, UDP, latency; footer 测速 / 复制名称. */
@Composable
private fun NodeInfoSheet(name: String, data: PanelData, onClose: () -> Unit, onTest: () -> Unit, onCopy: () -> Unit) {
    val c = LocalHomeColors.current
    val node = data.groups.firstNotNullOfOrNull { g -> g.nodes.firstOrNull { it.name == name } }
    HomeSheetContent(
        title = "节点信息",
        onClose = onClose,
        footer = {
            HomeButton("测速", onTest, Modifier.weight(1f), icon = PanelIcons.Gauge, loading = data.delayOf(name) == PanelDelay.Testing)
            HomeButton("复制名称", onCopy, Modifier.weight(1f), kind = HomeButtonKind.Soft, icon = HomeIcons.Copy)
        },
    ) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (node?.kind == PanelNodeKind.Proxy) HomeFlag(HomeRegions.codeOf(name), height = 22.dp)
            Text(HomeRegions.withoutFlag(name), color = c.t1, style = PanelType.nodeTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HomeSheetGroup {
            DetailRow("协议") { DetailText(node?.protocol?.ifBlank { null } ?: HomeFormat.Dash) }
            HomeDivider(inset = 16.dp)
            DetailRow("提供商") { DetailText(node?.provider?.ifBlank { null } ?: HomeFormat.Dash) }
            HomeDivider(inset = 16.dp)
            DetailRow("支持") { DetailText(if (node?.udp == true) "UDP" else ht("仅 TCP")) }
            HomeDivider(inset = 16.dp)
            DetailRow("延迟") { PanelDelayLabel(data.delayOf(name), onCard = true) }
        }
    }
}

/** 连接详情: who (app, package, rule), where (chain, network), how much (totals, rates); footer 断开此连接. */
@Composable
private fun ConnectionSheet(conn: PanelConnection, data: PanelData, onClose: () -> Unit, onDisconnect: () -> Unit) {
    val c = LocalHomeColors.current
    HomeSheetContent(
        title = conn.host,
        verbatimTitle = true,
        subtitle = conn.timeLabel?.let { ht("%s 建立").fill(it) },
        onClose = onClose,
        footer = { PanelDangerButton("断开此连接", onDisconnect, Modifier.weight(1f), soft = true, icon = PanelIcons.Unlink) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeSheetGroup {
                DetailRow("应用") {
                    if (conn.app.isBlank()) Text(ht(PanelLogic.UnattributedApp), color = c.t3, style = HomeType.label)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PanelAvatar(conn.app, conn.packageName, size = 22.dp)
                        DetailText(conn.app)
                    }
                }
                if (conn.packageName.isNotBlank()) { HomeDivider(inset = 16.dp); DetailRow("包名") { DetailText(conn.packageName, mono = true) } }
                HomeDivider(inset = 16.dp)
                DetailRow("规则") { DetailText(conn.rule.ifBlank { HomeFormat.Dash }) }
            }
            HomeSheetGroup {
                DetailRow("链路") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val slot = LocalPanelGroupIcon.current
                        val head = data.groups.firstOrNull { it.name == conn.chain.firstOrNull() }
                        if (slot != null && head != null) slot(head, Modifier.size(18.dp))
                        DetailText(conn.chainText.ifEmpty { "DIRECT" })
                    }
                }
                HomeDivider(inset = 16.dp)
                DetailRow("网络") { DetailText(listOf(conn.network, conn.inbound, conn.kind).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { HomeFormat.Dash }) }
            }
            HomeSheetGroup {
                DetailRow("流量") { FlowPair(HomeFormat.bytes(conn.downloadTotalBytes), HomeFormat.bytes(conn.uploadTotalBytes)) }
                HomeDivider(inset = 16.dp)
                DetailRow("速率") { FlowPair(HomeFormat.speed(conn.downloadBytesPerSecond), HomeFormat.speed(conn.uploadBytesPerSecond)) }
            }
        }
    }
}

/** “↓ 30 MB   ↑ 2 MB” with the arrows in the download and upload colours. */
@Composable
private fun FlowPair(down: String, up: String) {
    val c = LocalHomeColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(PanelIcons.ArrowDown, null, Modifier.size(18.dp), tint = c.accent)
        Box(Modifier.padding(start = 6.dp, end = 16.dp)) { DetailText(down) }
        Icon(PanelIcons.ArrowUp, null, Modifier.size(18.dp), tint = c.goodText)
        Box(Modifier.padding(start = 6.dp)) { DetailText(up) }
    }
}

/* ------------------------------------------------------------------ */
/*  Dialog                                                              */
/* ------------------------------------------------------------------ */

@Composable
internal fun PanelCloseAllDialogCard(onConfirm: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    PanelDialogCard(
        title = "断开全部连接？",
        message = "所有应用会立即重新建立连接，正在进行的下载或通话可能中断。",
        confirm = "断开全部",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
    )
}
