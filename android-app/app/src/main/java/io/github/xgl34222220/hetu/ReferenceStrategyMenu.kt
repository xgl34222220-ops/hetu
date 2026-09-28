package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.Icon\nimport top.yukonga.miuix.kmp.icon.MiuixIcons\nimport top.yukonga.miuix.kmp.icon.extended.Filter\nimport top.yukonga.miuix.kmp.icon.extended.Sort
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
    val prefs = LocalContext.current.getSharedPreferences("proxy_selector_preferences", 0)
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            revision++
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val entries = remember(revision) {
        fun choice(key: String, fallback: String, values: List<Pair<String,String>>) =
            values.map { (value,label) ->
                DropdownItem(label, selected = prefs.getString(key, fallback) == value,
                    onClick = { prefs.edit().putString(key, value).apply() })
            }
        fun columns(modeKey: String, countKey: String) = buildList {
            add(DropdownItem("自动", selected = prefs.getString(modeKey, "auto") == "auto",
                onClick = { prefs.edit().putString(modeKey, "auto").apply() }))
            (1..4).forEach { n ->
                add(DropdownItem("$n 列",
                    selected = prefs.getString(modeKey, "auto") != "auto" && prefs.getInt(countKey, 1) == n,
                    onClick = { prefs.edit().putString(modeKey, "fixed").putInt(countKey, n).apply() }))
            }
        }
        listOf(DropdownEntry(listOf(
            DropdownItem("节点排序", children = choice("node_sort_mode", "defaultsort",
                listOf("defaultsort" to "按配置", "name" to "名称", "latency" to "延迟"))),
            DropdownItem("倒序", selected = prefs.getBoolean("node_sort_descending", false),
                onClick = { prefs.edit().putBoolean("node_sort_descending",
                    !prefs.getBoolean("node_sort_descending", false)).apply() }),
            DropdownItem("策略栏数", children = columns("group_column_mode", "group_column_count")),
            DropdownItem("节点栏数", children = columns("node_column_mode", "node_column_count")),
            DropdownItem("策略密度", children = choice("group_density", "standard",
                listOf("standard" to "标准", "compact" to "紧凑"))),
            DropdownItem("节点密度", children = choice("node_density", "standard",
                listOf("standard" to "标准", "compact" to "紧凑"))),
            DropdownItem("名称显示", children = choice("name_overflow_mode", "clip",
                listOf("clip" to "截断隐藏", "scroll" to "滚动显示", "wrap" to "自动换行"))),
        )))
    }
    WindowIconCascadingDropdownMenu(entries, modifier = Modifier.size(44.dp).testTag("strategy-layout-menu"),
        minWidth = 44.dp, minHeight = 44.dp) {
        Icon(MiuixIcons.Sort, "排序与布局", Modifier.size(21.dp), tint = LocalHetuTokens.current.textPrimary)
    }
}

@Composable
internal fun ReferenceStrategyFilterMenu() {
    val prefs = LocalContext.current.getSharedPreferences("hetu", 0)
    var revision by remember { mutableIntStateOf(0) }
    val options = remember(revision) {
        listOf(
            Triple("show_hidden_groups", "显示隐藏策略", true),
            Triple("display_global_by_mode", "根据模式显示 GLOBAL", false),
            Triple("group_by_provider", "节点根据提供商分组", false),
            Triple("expand_selected_policy_in_bottom_sheet", "底部弹窗展开策略", false),
            Triple("collapse_previous_group_on_expand", "展开新策略时折叠上一个", false),
            Triple("detect_ipv6_on_latency_test", "检测 IPv6", false),
            Triple("disconnect_on_select", "切换节点时自动断开连接", false),
        ).map { (key,label,fallback) ->
            DropdownItem(label, selected = prefs.getBoolean(key, fallback), onClick = {
                prefs.edit().putBoolean(key, !prefs.getBoolean(key, fallback)).apply()
                revision++
            })
        }
    }
    WindowIconDropdownMenu(DropdownEntry(options), modifier = Modifier.size(44.dp).testTag("strategy-filter-menu"),
        collapseOnSelection = false, minWidth = 44.dp, minHeight = 44.dp) {
        Icon(MiuixIcons.Filter, "策略显示选项", Modifier.size(21.dp), tint = LocalHetuTokens.current.textPrimary)
    }
}
