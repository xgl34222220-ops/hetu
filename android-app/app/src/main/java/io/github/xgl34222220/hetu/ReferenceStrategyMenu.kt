package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.xgl34222220.hetu.ui.LocalHetuTokens
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.menu.WindowIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu

/** Native cascading menus stay attached to the tapped toolbar button. */
@Composable
internal fun ReferenceStrategyMenu() {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("proxySelector") == true) revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val entries = remember(revision) {
        fun choices(key: String, fallback: String, values: List<Pair<String,String>>) = values.map { (value,label) ->
            DropdownItem(label, selected = prefs.getString(key, fallback) == value,
                onClick = { prefs.edit().putString(key, value).apply() })
        }
        fun columns(key: String) = (0..3).map { n ->
            DropdownItem(if (n == 0) "自动" else "$n 列", selected = prefs.getInt(key, 0) == n,
                onClick = { prefs.edit().putInt(key, n).apply() })
        }
        val sorts = listOf("config" to "配置顺序", "name" to "名称", "delay" to "延迟")
        listOf(DropdownEntry(listOf(
            DropdownItem("节点排序", children = choices("proxySelectorNodeSort", "config", listOf("config" to "配置顺序", "name" to "名称", "latency" to "延迟"))),
            DropdownItem("倒序", selected = prefs.getBoolean("proxySelectorSortDescending", false),
                onClick = { prefs.edit().putBoolean("proxySelectorSortDescending", !prefs.getBoolean("proxySelectorSortDescending", false)).apply() }),
            DropdownItem("策略组排序", children = choices("proxySelectorGroupSort", "config", sorts)),
            DropdownItem("节点列数", children = columns("proxySelectorNodeColumns")),
            DropdownItem("策略组列数", children = columns("proxySelectorGroupColumns")),
            DropdownItem("卡片密度", children = choices("proxySelectorDensity", "standard", listOf("standard" to "标准", "compact" to "紧凑"))),
            DropdownItem("名称显示", children = choices("proxySelectorNameOverflow", "ellipsis", listOf("ellipsis" to "省略", "scroll" to "滚动", "wrap" to "换行"))),
        )))
    }
    WindowIconCascadingDropdownMenu(entries, modifier = Modifier.size(44.dp).testTag("strategy-layout-menu"),
        minWidth = 44.dp, minHeight = 44.dp) {
        Icon(Icons.Rounded.Sort, "排序与布局", Modifier.size(21.dp), tint = LocalHetuTokens.current.textPrimary)
    }
}

@Composable
internal fun ReferenceStrategyFilterMenu() {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var revision by remember { mutableIntStateOf(0) }
    val options = remember(revision) {
        listOf(
            Triple("proxySelectorShowHidden", "显示隐藏策略组", false),
            Triple("proxySelectorShowGlobalByMode", "按当前模式显示全局策略组", true),
            Triple("proxySelectorGroupByProvider", "按订阅分组", false),
            Triple("proxySelectorExpandSelectedInSheet", "底部弹窗展开", false),
            Triple("proxySelectorCollapsePrevious", "折叠先前展开的策略组", true),
            Triple("proxySelectorDetectIpv6", "检测 IPv6", true),
        ).map { (key,label,fallback) ->
            DropdownItem(label, selected = prefs.getBoolean(key, fallback), onClick = {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, fallback)).apply(); revision++
            })
        }
    }
    WindowIconDropdownMenu(DropdownEntry(options), modifier = Modifier.size(44.dp).testTag("strategy-filter-menu"),
        collapseOnSelection = false, minWidth = 44.dp, minHeight = 44.dp) {
        Icon(Icons.Rounded.FilterList, "策略显示选项", Modifier.size(21.dp), tint = LocalHetuTokens.current.textPrimary)
    }
}
