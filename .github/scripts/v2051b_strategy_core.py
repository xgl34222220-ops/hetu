from pathlib import Path

root = Path('.')

# Proxies / strategy page.
proxy = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ProxiesScreen.kt'
s = proxy.read_text()
# icon imports for exact sheets
if 'import androidx.compose.material.icons.rounded.Close\n' not in s:
    s = s.replace('import androidx.compose.material.icons.rounded.Check\n', 'import androidx.compose.material.icons.rounded.Check\nimport androidx.compose.material.icons.rounded.Close\nimport androidx.compose.material.icons.rounded.ChevronRight\n', 1)
# state
old = '    var showFilter by remember { mutableStateOf(false) }\n    var showApi by remember { mutableStateOf(false) }\n'
assert old in s
s = s.replace(old, '    var showFilter by remember { mutableStateOf(false) }\n    var showLayout by remember { mutableStateOf(false) }\n    var showApi by remember { mutableStateOf(false) }\n    var nodeInfo by remember { mutableStateOf<Pair<String, String>?>(null) }\n', 1)
# only one top-right control on Strategy: sort/layout. 测速与 API moves into layout sheet like PDF page 11.
old = '''        actions = {
            if (state.running) BoxProxyMiuixTheme17 { ReferenceStrategyMenu(vm.prefs) }
            HxBarAction(Icons.Rounded.Settings, "测速与 API", onClick = { showApi = true })
        },
'''
assert old in s
s = s.replace(old, '''        actions = {
            if (state.running) HxBarAction(Icons.Rounded.Sort, "排序与布局", onClick = { showLayout = true })
        },
''', 1)
# wire info callback to every node tile
old = '''                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f),
                            )
'''
assert old in s
s = s.replace(old, '''                                nameOverflow = options.nameOverflow,
                                modifier = Modifier.weight(1f),
                                onInfo = { nodeInfo = entry.group.name to node.name },
                            )
''', 1)
# replace filter action menu with checkbox popup and add layout/node info sheets.
old = '''    if (showFilter) {
        HxActionMenu(
            title = "策略筛选",
            actions = listOf(
                HxMenuAction((if (options.showHidden) "✓ " else "") + "显示隐藏策略") { options.copy(showHidden = !options.showHidden).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.globalByMode) "✓ " else "") + "根据模式显示 GLOBAL") { options.copy(globalByMode = !options.globalByMode).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.providers) "✓ " else "") + "按订阅分组节点") { options.copy(providers = !options.providers).save(vm.prefs); showFilter = false },
                HxMenuAction((if (options.collapsePrevious) "✓ " else "") + "展开新策略时折叠上一个") { options.copy(collapsePrevious = !options.collapsePrevious).save(vm.prefs); showFilter = false },
            ),
            onDismiss = { showFilter = false },
        )
    }


    if (showApi) ApiSettingsSheet(vm, onDismiss = { showApi = false })
}
'''
assert old in s
s = s.replace(old, '''    if (showFilter) {
        StrategyFilterMenu(
            options = options,
            onChange = { it.save(vm.prefs) },
            onDismiss = { showFilter = false },
        )
    }

    if (showLayout) {
        StrategyLayoutSheet(
            vm = vm,
            options = options,
            onDismiss = { showLayout = false },
            onOpenApi = {
                showLayout = false
                showApi = true
            },
        )
    }

    val infoPair = nodeInfo
    if (infoPair != null) {
        val infoGroup = state.groups.firstOrNull { it.name == infoPair.first }
        val infoNode = infoGroup?.nodes?.firstOrNull { it.name == infoPair.second }
        if (infoGroup == null || infoNode == null) {
            LaunchedEffect(infoPair) { nodeInfo = null }
        } else {
            StrategyNodeInfoSheet(infoGroup, infoNode, onDismiss = { nodeInfo = null })
        }
    }

    if (showApi) ApiSettingsSheet(vm, onDismiss = { showApi = false })
}
''', 1)
# node card signature + long press behavior
old = '''private fun StrategyNodeCard(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    node: ProxyNodeUi,
    compact: Boolean,
    nameOverflow: String,
    modifier: Modifier,
) {
'''
assert old in s
s = s.replace(old, '''private fun StrategyNodeCard(
    vm: HetuViewModel,
    group: ProxyGroupUi,
    node: ProxyNodeUi,
    compact: Boolean,
    nameOverflow: String,
    modifier: Modifier,
    onInfo: () -> Unit,
) {
''', 1)
old = '''                onLongClick = {
                    haptics.perform(HetuHaptic.LongPress)
                    vm.testNode(node.name)
                },
'''
assert old in s
s = s.replace(old, '''                onLongClick = {
                    haptics.perform(HetuHaptic.LongPress)
                    onInfo()
                },
''', 1)
# latency status text exactly follows concept (测速中 / 超时 / 未知)
start = s.index('@Composable\nprivate fun StrategyCompactDelayPill')
end = s.index('\n/** Floating controls', start)
new_delay = r'''@Composable
private fun StrategyCompactDelayPill(delay: Long?, testing: Boolean, onClick: () -> Unit) {
    val c = Hx.colors
    val timeout = !testing && delay != null && delay < 0L
    val unknown = !testing && (delay == null || delay == 0L)
    val bg = when {
        testing -> c.accentSoft
        timeout -> c.badSoft
        unknown -> c.surface
        delay != null && delay < 800L -> c.accentSoft
        else -> c.warnSoft
    }
    val fg = when {
        testing -> c.accent
        timeout -> c.bad
        unknown -> c.textFaint
        delay != null && delay < 800L -> c.accent
        else -> c.warn
    }
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .hxPressScale(source, .92f)
            .clip(Hx.pillShape)
            .background(bg)
            .clickable(interactionSource = source, indication = null, enabled = !testing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (testing) HxSpinner(9.dp)
        Text(
            when {
                testing -> "测速中"
                timeout -> "超时"
                unknown -> "未知"
                else -> "$delay ms"
            },
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
        )
    }
}
'''
s = s[:start] + new_delay + s[end:]
proxy.write_text(s)
print('V20.51 strategy core parity applied')

# Chain panel state parity.
import runpy
runpy.run_path('.github/scripts/v2052a_tabbed.py', run_name='__main__')
runpy.run_path('.github/scripts/v2052b_connections.py', run_name='__main__')
runpy.run_path('.github/scripts/v2052c_rules.py', run_name='__main__')
