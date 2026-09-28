package io.github.xgl34222220.hetu

import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.launch

@Composable
internal fun PanelToolbar11(searchOpen: Boolean, options: PanelOptions11,
    onSearch: () -> Unit, onOptions: (PanelOptions11) -> Unit, onSettings: () -> Unit) {
    val t = LocalHetuTokens.current
    var menu by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp)
        .testTag("panel11-toolbar"), verticalAlignment = Alignment.CenterVertically) {
        Text("面板", Modifier.weight(1f), color = t.textPrimary, fontSize = 22.sp,
            lineHeight = 29.sp, fontWeight = FontWeight.Bold)
        PanelTool11(Icons.Rounded.Search, "搜索策略与节点", "panel11-search-toggle",
            active = searchOpen, onClick = onSearch)
        Box {
            PanelTool11(Icons.Rounded.FilterList, "筛选策略", "panel11-filter", menu == "filter") {
                menu = if (menu == "filter") "" else "filter"
            }
            DropdownMenu(expanded = menu == "filter", onDismissRequest = { menu = "" },
                shape = RoundedCornerShape(20.dp), containerColor = t.cardBackground,
                modifier = Modifier.widthIn(min = 260.dp).testTag("panel11-filter-menu")) {
                PanelToggleMenu11("显示隐藏策略", "panel11-hidden", options.showHidden) { onOptions(options.copy(showHidden = !options.showHidden)) }
                PanelToggleMenu11("根据模式显示 GLOBAL", "panel11-global", options.globalByMode) { onOptions(options.copy(globalByMode = !options.globalByMode)) }
                PanelToggleMenu11("按提供商分组", "panel11-provider", options.providers) { onOptions(options.copy(providers = !options.providers)) }
                PanelToggleMenu11("展开时折叠上一个", "panel11-exclusive", options.collapsePrevious) { onOptions(options.copy(collapsePrevious = !options.collapsePrevious)) }
            }
        }
        Box {
            PanelTool11(Icons.Rounded.Tune, "排序与布局", "panel11-layout", menu == "layout") {
                menu = if (menu == "layout") "" else "layout"
            }
            DropdownMenu(expanded = menu == "layout", onDismissRequest = { menu = "" },
                shape = RoundedCornerShape(20.dp), containerColor = t.cardBackground,
                modifier = Modifier.widthIn(min = 230.dp).testTag("panel11-layout-menu")) {
                listOf("config" to "配置顺序", "name" to "名称排序", "latency" to "延迟排序").forEach { (key, label) ->
                    DropdownMenuItem(text = { Text(label, fontSize = 13.sp) },
                        modifier = Modifier.testTag("panel11-sort-$key"),
                        trailingIcon = { if (options.sort == key || key == "latency" && options.sort == "delay") Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                        onClick = { onOptions(options.copy(sort = key)) })
                }
                PanelToggleMenu11("倒序", "panel11-descending", options.descending, options.sort != "config") { onOptions(options.copy(descending = !options.descending)) }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = t.textSecondary.copy(alpha = .08f))
                PanelToggleMenu11("双列节点", "panel11-columns", options.columns == 2) { onOptions(options.copy(columns = if (options.columns == 2) 1 else 2)) }
                PanelToggleMenu11("紧凑密度", "panel11-density", options.compact) { onOptions(options.copy(compact = !options.compact)) }
            }
        }
        PanelTool11(Icons.Rounded.Settings, "API 与测速配置", "panel11-settings", onClick = onSettings)
    }
}

@Composable
private fun PanelToggleMenu11(title: String, tag: String, checked: Boolean, enabled: Boolean = true, click: () -> Unit) {
    DropdownMenuItem(text = { Text(title, fontSize = 13.sp) }, enabled = enabled,
        modifier = Modifier.testTag(tag).semantics { toggleableState = if (checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
        trailingIcon = { if (checked) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color(0xFF2563EB)) }, onClick = click)
}

@Composable
internal fun PanelTool11(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String,
    tag: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val t = LocalHetuTokens.current
    Box(Modifier.size(48.dp).testTag(tag).clip(CircleShape)
        .nativePress(enabled = enabled, label = description, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(21.dp), tint = if (active) Color(0xFF2563EB) else t.textSecondary)
    }
}

@Composable
internal fun PanelSubTabs11(selected: RefPanelTab, change: (RefPanelTab) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)
        .testTag("panel11-subtabs"), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        listOf(RefPanelTab.Overview, RefPanelTab.Groups, RefPanelTab.Subscriptions,
            RefPanelTab.Connections, RefPanelTab.Rules, RefPanelTab.RuleSets).forEach { tab ->
            val label = if (tab == RefPanelTab.Groups) "策略" else tab.label
            val active = tab == selected
            Box(Modifier.heightIn(min = 48.dp).clip(CircleShape)
                .background(if (active) t.cardBackground else Color.Transparent)
                .nativePress(label = label, onClick = { change(tab) })
                .semantics { this.selected = active; role = Role.Tab }
                .testTag("panel11-tab-${tab.name}").padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                Text(label, color = if (active) Color(0xFF2563EB) else t.textSecondary,
                    fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Edits the exact preferences consumed by the original146 API client and history store. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanelApiSheet11(prefs: SharedPreferences, localPort: Int, onDismiss: () -> Unit,
    onApply: () -> Unit) {
    val t = LocalHetuTokens.current
    val state = rememberInteractiveSheetState()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(PanelApiDraft11.read(prefs)) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.cardBackground, contentColor = t.textPrimary,
        scrimColor = Color(0xFF0F172A).copy(alpha = .4f), tonalElevation = 0.dp,
        modifier = Modifier.testTag("panel11-api-sheet"),
        dragHandle = { Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(40.dp).height(5.dp).background(t.textMuted.copy(alpha = .4f), CircleShape))
        } }) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("API 与测速配置", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.textPrimary)
            PanelSettingToggle11("自定义测速 URL", draft.customDelay, "panel11-custom-delay") {
                draft = draft.copy(customDelay = it); error = null
            }
            if (draft.customDelay) PanelField11("测速 URL", draft.delayUrl, "panel11-delay-url", KeyboardType.Uri) {
                draft = draft.copy(delayUrl = it); error = null
            }
            Text("关闭时沿用配置/提供商的测速目标。修改后用于下次真实测速，不自动伪造测试结果。",
                color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
            PanelSettingToggle11("Clash API 历史采集", draft.history, "panel11-history") { draft = draft.copy(history = it) }
            Text("记录本地流量与连接历史；关闭后停止新增采集，不删除已有数据。",
                color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
            HorizontalDivider(color = t.textMuted.copy(alpha = .1f))
            PanelSettingToggle11("自定义后端接口", draft.customApi, "panel11-custom-api") {
                draft = draft.copy(customApi = it); error = null
            }
            if (draft.customApi) {
                PanelField11("后端主机（IPv4/域名）", draft.host, "panel11-api-host", KeyboardType.Uri) { draft = draft.copy(host = it); error = null }
                PanelField11("端口", draft.port, "panel11-api-port", KeyboardType.Number) { draft = draft.copy(port = it); error = null }
                PanelField11("API Secret", draft.secret, "panel11-api-secret", KeyboardType.Password, true) { draft = draft.copy(secret = it); error = null }
                Text("沿用当前核心客户端的 HTTP 接口，只连接你信任的主机。此处不开放本机监听端口，也不重启代理。",
                    color = t.textSecondary, fontSize = 11.sp, lineHeight = 17.sp)
            } else Text("使用本机核心：127.0.0.1:$localPort · 自动鉴权", color = t.textSecondary, fontSize = 12.sp)
            error?.let { Text(it, Modifier.testTag("panel11-api-error").semantics { liveRegion = LiveRegionMode.Polite },
                color = t.danger, fontSize = 12.sp, lineHeight = 18.sp) }
        }
        Button(onClick = {
            error = draft.validationError()
            if (error == null) {
                draft.save(prefs)
                onApply()
                scope.launch { state.hide(); onDismiss() }
            }
        }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("panel11-api-save")) {
            Text("保存并应用", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PanelSettingToggle11(title: String, value: Boolean, tag: String, change: (Boolean) -> Unit) {
    val t = LocalHetuTokens.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
        .background(t.controlBackground.copy(alpha = .45f)).padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = t.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Switch(checked = value, onCheckedChange = change, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun PanelField11(label: String, value: String, tag: String, keyboard: KeyboardType,
    password: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = change, label = { Text(label, fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(14.dp),
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
}
