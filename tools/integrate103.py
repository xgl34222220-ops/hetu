#!/usr/bin/env python3
"""Integrate a bounded test.103 presentation update; never change runtime/network baselines."""
from pathlib import Path
import re, hashlib, json
r=Path(__file__).resolve().parents[1]
s=r/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
assert 'versionName = "0.4.0-test.102"' in (r/'android-app/app/build.gradle.kts').read_text()
for name,digest in json.loads((r/'tests/ui-runtime-baseline.json').read_text()).items():
    assert hashlib.sha256((r/name).read_bytes()).hexdigest()==digest,name

def rep(t,a,b):
    assert t.count(a)==1,(a[:120],t.count(a))
    return t.replace(a,b)
def function(t,name,new):
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

f=s/'ReferenceProxyActivity.kt'; t=f.read_text()
t=rep(t,'                    EngineThroughput(upRate, downRate)\n','')
t=rep(t,'    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }',
    '    var rules by remember { mutableStateOf<List<ProxyRuleUi>>(emptyList()) }\n    var overviewRuleCount by remember { mutableStateOf<Int?>(null) }')
t=rep(t,'                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()\n                else -> Unit',
    '                RefPanelTab.RuleSets -> ruleSets = repo.ruleSets()\n                RefPanelTab.Overview -> { overviewRuleCount = null; rules = repo.rules(); overviewRuleCount = rules.size }\n                else -> Unit')
t=rep(t,'                    RefPanelTab.Overview -> {\n                        onRefreshState()',
    '                    RefPanelTab.Overview -> {\n                        onRefreshState()\n                        overviewRuleCount = null\n                        rules = repo.rules()\n                        overviewRuleCount = rules.size')
t=rep(t,'{ RefTrafficOverview(state) }','{ RefTrafficOverview(state, overviewRuleCount) }')
t=rep(t,'private fun RefTrafficOverview(state: ProxyComposeState) {','private fun RefTrafficOverview(state: ProxyComposeState, ruleCount: Int?) {')
a=t.index('private fun RefTrafficOverview('); b=t.index('@Composable\nprivate fun RefRateCard',a)
block=t[a:b]
block=rep(block,'    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {','    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {\n        OverviewInstruments(state, ruleCount)')
block=rep(block,'                Text("会话累计",', '                Text("会话累计",')
anchor='''        Surface(shape = RoundedCornerShape(18.dp), color = t.cardBackground) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {'''
block=rep(block,anchor,'        OverviewRouteRanking(state)\n'+anchor)
t=t[:a]+block+t[b:];f.write_text(t)

f=s/'InstrumentWorkspace.kt';t=f.read_text()
a=t.index('/** Current byte rates only.');b=t.index('internal fun ticketUpdatedAt',a);t=t[:a]+t[b:]
t=function(t,'InstrumentSubscriptionTicket','''internal fun InstrumentSubscriptionTicket(name: String, provider: DashboardProviderUi?, host: String = "",
    placeholder: Boolean = false, refreshing: Boolean = false, success: Boolean = false,
    onEdit: () -> Unit, onRefresh: (() -> Unit)? = null) {
    SubscriptionBoardingTicket(name, provider, host, placeholder, refreshing, success, onEdit, onRefresh)
}''');f.write_text(t)

f=s/'AlignedInstrumentPanel.kt';t=f.read_text()
t=t.replace('import androidx.compose.material3.*','import androidx.compose.material3.*\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.rounded.*')
needle='''                }
            }) { measurable, constraints ->
            val badge = measurable[1].measure'''
new='''                }
                Icon(when (reading.id) {
                    "network" -> Icons.Rounded.Public
                    "speed" -> Icons.Rounded.Speed
                    "usage" -> Icons.Rounded.DataUsage
                    else -> Icons.Rounded.Memory
                }, null, Modifier.size(14.dp).testTag("instrument-${reading.id}-icon"), tint = reading.accent.copy(alpha = .7f))
            }) { measurable, constraints ->
            val badge = measurable[1].measure'''
t=rep(t,needle,new)
t=rep(t,'            val title = measurable[0].measure','            val icon = measurable[2].measure(constraints.copy(minWidth = 0, minHeight = 0))\n            val titleStart = icon.width + 5.dp.roundToPx()\n            val title = measurable[0].measure')
t=rep(t,'constraints.maxWidth - badge.width - 4.dp.roundToPx()', 'constraints.maxWidth - titleStart - badge.width - 4.dp.roundToPx()')
t=rep(t,'                title.placeRelative(0, (baseline - title[FirstBaseline]).coerceAtLeast(0))',
    '                icon.placeRelative(0, (baseline - 11.sp.roundToPx()).coerceAtLeast(0))\n                title.placeRelative(titleStart, (baseline - title[FirstBaseline]).coerceAtLeast(0))')
t=rep(t,'"切换 ⇄", address,','"切换", address,')
t=rep(t,'"${((1f - it) * 100f).toInt()}% 剩余"','"${((1f - it) * 100f).toInt()}%"')
f.write_text(t)

f=s/'WorkspaceCards.kt';t=f.read_text()
t=rep(t,'modifier: Modifier = Modifier) {\n    val t = LocalHetuTokens.current\n    val band = delayBand(value)',
    'modifier: Modifier = Modifier, compact: Boolean = false) {\n    val t = LocalHetuTokens.current\n    val band = delayBand(value)')
t=rep(t,'Modifier.sizeIn(minWidth = 72.dp, minHeight = 48.dp)','Modifier.sizeIn(minWidth = if (compact) 56.dp else 72.dp, minHeight = 48.dp)')
t=rep(t,'Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)','Row(Modifier.padding(horizontal = if (compact) 6.dp else 9.dp, vertical = 5.dp)')
t=rep(t,'fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))','fontSize = if (compact) 10.5.sp else 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold))')
f.write_text(t)

f=s/'LiquidWorkspace.kt';t=f.read_text()
t=rep(t,'.testTag("strategy:${group.name}")) {','.testTag("strategy:${group.name}")\n        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onExpand)) {')
t=rep(t,'''            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.padding(start = 3.dp).size(14.dp)
                .graphicsLayer { rotationZ = angle }, tint = primary)
''','')
t=rep(t,'            LatencyChip(value, testing, onDelay, Modifier.testTag("strategy-delay:${group.name}"))',
'''            LatencyChip(value, testing, onDelay, Modifier.testTag("strategy-delay:${group.name}"), compact = true)
            Box(Modifier.padding(start = 3.dp).size(20.dp).background(primary.copy(alpha = .075f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(13.dp).graphicsLayer { rotationZ = angle }, tint = primary)
            }''')
f.write_text(t)

f=s/'ProxySubscriptionActivity.kt';t=f.read_text()
t=t.replace('import io.github.rosemoe.sora.event.ContentChangeEvent','import io.github.rosemoe.sora.event.SelectionChangeEvent\nimport io.github.rosemoe.sora.event.PublishSearchResultEvent\nimport io.github.rosemoe.sora.event.ContentChangeEvent')
t=rep(t,'            var outlineOpen by remember { mutableStateOf(false) }',
'''            var outlineOpen by remember { mutableStateOf(false) }
            var yamlSearchOpen by remember { mutableStateOf(false) }
            var yamlMatches by remember { mutableIntStateOf(0) }
            var yamlCursorLine by remember { mutableIntStateOf(1) }
            var yamlCursorColumn by remember { mutableIntStateOf(1) }''')
a=t.index('            fun insertYamlText(');b=t.index('            fun jumpToYamlLine(',a);t=t[:a]+t[b:]
a=t.index('                        IconButton(\n                            onClick = {\n                                outlineItems = yamlOutlineItems(currentYamlText())');b=t.index('                    }\n\n                    Surface(',a)
t=t[:a]+t[b:]
needle='''                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 7.dp),'''
new='''                    }
                    YamlWorkbenchActions(yamlCanUndo, yamlCanRedo, yamlSaving,
                        { yamlEditor?.undo() }, { yamlEditor?.redo() }, { yamlSearchOpen = !yamlSearchOpen },
                        { outlineItems = yamlOutlineItems(currentYamlText()); outlineOpen = true }, ::saveYaml)
                    if (yamlSearchOpen) YamlWorkbenchSearch(yamlEditor, yamlMatches) {
                        yamlSearchOpen = false; yamlEditor?.requestFocus()
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 7.dp),'''
t=rep(t,needle,new)
t=rep(t,'                                        subscribeAlways<ContentChangeEvent> {',
'''                                        subscribeAlways<SelectionChangeEvent> {
                                            yamlCursorLine = cursor.rightLine + 1
                                            yamlCursorColumn = cursor.rightColumn + 1
                                        }
                                        subscribeAlways<PublishSearchResultEvent> {
                                            yamlMatches = if (searcher.hasQuery()) searcher.matchedPositionCount else 0
                                        }
                                        subscribeAlways<ContentChangeEvent> {''')
a=t.index('                    androidx.compose.animation.AnimatedVisibility(\n                        visible = imeVisible,');b=t.index('\n                }\n            }\n\n            if (outlineOpen)',a)
t=t[:a]+'''                    if (!yamlSearchOpen) YamlWorkbenchAccessory(!yamlSaving) { symbol ->
                        yamlEditor?.let { applyYamlAccessory(it, symbol) }
                    }
                    YamlCursorStatus(yamlCursorLine, yamlCursorColumn, yamlLineCount, Modifier.navigationBarsPadding())
'''+t[b:]
a=t.index('@Composable\nprivate fun YamlAccessoryKey(');b=t.index('@Composable\nprivate fun SubscriptionMetric',a);t=t[:a]+t[b:]
f.write_text(t)
f=s/'HetuYamlLanguage.java';t=f.read_text();t=rep(t,'    private final Analyzer analyzer = new Analyzer();','    private final Analyzer analyzer = new Analyzer();\n    @Override public boolean useTab() { return false; }');f.write_text(t)

f=r/'android-app/app/src/test/java/io/github/xgl34222220/hetu/HomeScreenRenderTest.kt';t=f.read_text();t=rep(t,'            compose.onNodeWithContentDescription("更多工具").assertExists()',
'''            compose.onNodeWithContentDescription("更多工具").assertExists()
            compose.onNodeWithText("↑ 实时上行", true).assertDoesNotExist()
            compose.onNodeWithText("↓ 实时下行", true).assertDoesNotExist()''');f.write_text(t)
for path in ['android-app/app/build.gradle.kts','.github/workflows/build-hetu.yml']:
    f=r/path;t=f.read_text().replace('0.4.0-test.102','0.4.0-test.103').replace('versionCode = 502','versionCode = 503');f.write_text(t)
for name,digest in json.loads((r/'tests/ui-runtime-baseline.json').read_text()).items():
    assert hashlib.sha256((r/name).read_bytes()).hexdigest()==digest,name
print('test.103 presentation integrated; protected network baseline unchanged')
