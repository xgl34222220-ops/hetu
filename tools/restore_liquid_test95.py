#!/usr/bin/env python3
"""One-time, fail-closed presentation migration from test.94 to test.95."""
from pathlib import Path
import re
r = Path(__file__).resolve().parents[1]
s = r/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
assert 'versionName = "0.4.0-test.94"' in (r/'android-app/app/build.gradle.kts').read_text(), 'Migration requires test.94'
def rep(t,a,b):
    assert t.count(a)==1,(a[:100],t.count(a)); return t.replace(a,b)
def fun(t,name,new):
    m=re.search(r'(?:private |internal )?fun '+name+r'\(',t); assert m,name
    start=m.start(); i=t.index('{',m.end()); depth=0; quote=None; esc=False; j=i
    while j<len(t):
        c=t[j]
        if quote:
            if esc: esc=False
            elif c=='\\': esc=True
            elif c==quote: quote=None
        else:
            if c in '\"\'': quote=c
            elif c=='{': depth+=1
            elif c=='}':
                depth-=1
                if depth==0: break
        j+=1
    assert depth==0,name
    return t[:start]+new+t[j+1:]
f=s/'WorkspaceCards.kt'; t=f.read_text()
t=fun(t,'StrategyGroupCard','''internal fun StrategyGroupCard(group: ProxyGroupUi, selected: String, expanded: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onExpand: () -> Unit, onDelay: () -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState? = null, glassEnabled: Boolean = false) {
    LiquidStrategyCard(group, selected, expanded, value, testing, modifier, onExpand, onDelay, hazeState, glassEnabled)
}''')
t=fun(t,'NodeChoiceCard','''internal fun NodeChoiceCard(node: ProxyNodeUi, active: Boolean, value: Long?, testing: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit, onDelay: () -> Unit) {
    LiquidNodeCard(node, active, value, testing, modifier, onSelect, onDelay)
}''')
t=t.replace('modifier.heightIn(min=132.dp)', 'modifier.heightIn(min=112.dp)').replace('modifier.heightIn(min = 132.dp)', 'modifier.heightIn(min = 112.dp)')
f.write_text(t)
f=s/'ReferenceProxyActivity.kt'; t=f.read_text()
t=rep(t,'                    Text("网络与广告过滤", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)\n','')
t=rep(t,'                RefPanelHeaderAction(Icons.Rounded.Article, "运行日志", onClick = onLog)', '''                LiquidHomeMenu(onLog, onConnections, onDiagnostics, {
                    context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java))
                }, diagnosticLoading)''')
a=t.index('        item(key = "home-shortcuts")'); b=t.index('        item(key = "home-latency")',a); t=t[:a]+t[b:]
a=t.index('                        Box(\n                            Modifier.size(52.dp)'); b=t.index('                        Column(Modifier.weight(1f)',a)
t=t[:a]+'                        LiquidStatusGlyph(state.running, busy)\n'+t[b:]
t=rep(t,'                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {','                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {')
t=fun(t,'RefHomeActions','''private fun RefHomeActions(running: Boolean, busy: Boolean, onToggle: () -> Unit, onReload: () -> Unit, onRestart: () -> Unit) {
    LiquidHomeActions(running, busy, onToggle, onReload, onRestart)
}''')
t=rep(t,'    StrategyGroupCard(group, selected, expanded, delay, testing, modifier, onClick, onDelay)', '    StrategyGroupCard(group, selected, expanded, delay, testing, modifier, onClick, onDelay, hazeState, glassEnabled)')
t=fun(t,'RefInlineGroupExpansion','''private fun RefInlineGroupExpansion(group: ProxyGroupUi, selected: String, delays: Map<String, Long>,
    testing: Map<String, Boolean>, onSelect: (String) -> Unit, onDelay: (String) -> Unit, onTestAll: () -> Unit) {
    LiquidGroupWell(group, selected, delays, testing, onSelect, onDelay, onTestAll)
}''')
t=rep(t,'val groupColumns = hetuCompactColumns(maxWidth - 32.dp, 152.dp)', 'val groupColumns = liquidColumns(maxWidth - 32.dp)')
t=rep(t,'                        val expandedGroup = pair.firstOrNull { it.name == selectedGroupName }', '''                        val expandedGroup = pair.firstOrNull { it.name == selectedGroupName }
                        // Keep the last content through the exit animation; a nullable let
                        // otherwise removes the entire well before shrinkVertically runs.
                        var closingGroup by remember(pair.map { it.name }) { mutableStateOf<ProxyGroupUi?>(null) }
                        SideEffect { if (expandedGroup != null) closingGroup = expandedGroup }''')
t=rep(t,'                                expandedGroup?.let { group ->', '                                (expandedGroup ?: closingGroup)?.let { group ->')
t=t.replace('animationSpec = spring(dampingRatio = .9f, stiffness = 420f)', 'animationSpec = spring(dampingRatio = .78f, stiffness = 380f)')
f.write_text(t)
f=s/'ProxySubscriptionActivity.kt'; t=f.read_text()
a=t.index('                        Box(\n                            Modifier.size(24.dp).background(Color.Transparent)'); b=t.index('                        Spacer(Modifier.width(11.dp))',a)
t=t[:a]+'                        LiquidConfigIndicator(config.selected)\n'+t[b:]
a=t.index('                            FilledTonalButton(\n                                onClick = { importLauncher.launch'); b=t.index('                            Button(',a)
t=t[:a]+'''                            LiquidPill("导入配置", Icons.Rounded.FileOpen,
                                { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f))
'''+t[b:]
f.write_text(t)
f=s/'ProxyGroupIcons.kt'; t=f.read_text(); t=t.replace('import org.yaml.snakeyaml.constructor.SafeConstructor','import org.yaml.snakeyaml.constructor.SafeConstructor\nimport org.yaml.snakeyaml.nodes.*\nimport java.io.StringReader\nimport java.util.Collections\nimport java.util.IdentityHashMap')
a=t.index('internal object ProxyGroupIcons {'); b=t.index('\ninternal sealed interface GroupIconLoad',a)
t=t[:a]+'''internal object ProxyGroupIcons {
    fun parse(source: String): Map<String, String> {
        val options = LoaderOptions().apply {
            codePointLimit = 4_194_304
            maxAliasesForCollections = 4096
            nestingDepthLimit = 64
            isAllowDuplicateKeys = false
        }
        // Compose the bounded YAML graph, not the entire configuration's objects.
        // Only scalar name/icon fields and bounded merge chains are projected.
        // Unrelated provider templates must not erase all strategy icons after 64 aliases.
        val root = Yaml(SafeConstructor(options)).compose(StringReader(source)) as? MappingNode ?: return emptyMap()
        val groups = root.value.lastOrNull { (it.keyNode as? ScalarNode)?.value == "proxy-groups" }
            ?.valueNode as? SequenceNode ?: return emptyMap()
        fun field(node: Node, key: String, seen: MutableSet<Node>, depth: Int = 0): String? {
            if (depth > 12 || !seen.add(node)) return null
            val row = node as? MappingNode ?: return null
            row.value.lastOrNull { (it.keyNode as? ScalarNode)?.value == key }?.let {
                return (it.valueNode as? ScalarNode)?.value
            }
            for (entry in row.value) {
                if ((entry.keyNode as? ScalarNode)?.value != "<<") continue
                val merged = entry.valueNode
                val candidates = if (merged is SequenceNode) merged.value else listOf(merged)
                for (candidate in candidates) field(candidate, key, seen, depth + 1)?.let { return it }
            }
            return null
        }
        fun read(node: Node, key: String): String? = field(node, key,
            Collections.newSetFromMap(IdentityHashMap<Node, Boolean>()))
        val result = linkedMapOf<String, String>()
        for (entry in groups.value) {
            val name = read(entry, "name") ?: continue
            val icon = read(entry, "icon") ?: continue
            if (name.isNotBlank() && icon.isNotBlank()) result[name] = icon
        }
        return result
    }
}
''' + t[b:]; f.write_text(t)
f=s/'ConfiguredGroupIcon.kt'; t=f.read_text(); t=rep(t,'        if (configured) value = repository.load(key, group.iconPath)', '''        value = cached?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading
        if (configured) value = repository.load(key, group.iconPath)'''); f.write_text(t)
f=r/'tools/test_ui_audit_invariants.py'; t=f.read_text(); t=rep(t,'assert "ConfiguredGroupIcon(group" in cards', 'liquid = (src / "LiquidWorkspace.kt").read_text()\nassert "LiquidStrategyCard(group" in cards and "ConfiguredGroupIcon(group" in liquid'); t+='''\nassert 'item(key = "home-shortcuts")' not in main
assert 'Text("网络与广告过滤"' not in main
assert "expandedGroup ?: closingGroup" in main
assert "LiquidConfigIndicator(config.selected)" in (src / "ProxySubscriptionActivity.kt").read_text()
'''; f.write_text(t)
f=r/'android-app/app/src/test/java/io/github/xgl34222220/hetu/HomeScreenRenderTest.kt'; t=f.read_text().replace('performScrollToIndex(4)', 'performScrollToIndex(3)'); t=rep(t,'            compose.onNodeWithText("停止",true).assertExists()', '''            compose.onNodeWithText("停止",true).assertExists()
            compose.onNodeWithText("网络与广告过滤",true).assertDoesNotExist()
            compose.onNodeWithText("应用连接",true).assertDoesNotExist()
            compose.onNodeWithContentDescription("更多工具").assertExists()'''); f.write_text(t)
f=r/'android-app/app/src/test/java/io/github/xgl34222220/hetu/PresentationTest.kt'; t=f.read_text(); pos=t.index('    @Test fun rasterAndSvg'); t=t[:pos]+'''    @Test fun manyUnrelatedAliasesDoNotDropConfiguredBrandIcons() {
        val source = buildString {
            append("template: &node {type: http, interval: 3600}\\nproxy-providers:\\n")
            repeat(120) { append("  provider-$it: {<<: *node}\\n") }
            append("proxy-groups:\\n  - {name: Google, icon: 'https://icons.example/google.png'}\\n")
            append("  - {name: Microsoft, icon: 'https://icons.example/microsoft.svg'}\\n")
        }
        assertEquals(listOf("Google", "Microsoft"), ProxyGroupIcons.parse(source).keys.toList())
        val bundled = BundledProxyConfig.open().bufferedReader().use { it.readText() }
        val icons = ProxyGroupIcons.parse(bundled)
        assertTrue("Bundled alias-rich config lost its icons", icons.size >= 10)
    }
    @Test fun recursiveTemplateProjectionTerminatesAndExplicitIconWins() {
        val source = "template: &self {<<: *self, icon: 'https://icons.example/template.svg'}\\n" +
            "proxy-groups: [{<<: *self, name: Google, icon: 'https://icons.example/explicit.svg'}]"
        assertEquals("https://icons.example/explicit.svg", ProxyGroupIcons.parse(source)["Google"])
    }
''' + t[pos:]; f.write_text(t)
f=r/'android-app/app/build.gradle.kts'; t=f.read_text().replace('0.4.0-test.94','0.4.0-test.95').replace('versionCode = 494','versionCode = 495'); f.write_text(t)
print('Presentation migration applied; runtime baseline remains unchanged')
