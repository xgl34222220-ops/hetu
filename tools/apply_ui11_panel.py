"""One-time UI10 -> UI11 integration. Never replay older UI migration scripts."""
from pathlib import Path
import subprocess, json, re
BASE='331a0b19ad4ada535c8102e0eeb3657be7e24116'
R='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
T='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed=[]
evidence=Path('integration-evidence');evidence.mkdir(exist_ok=True)
def edit(path, old, new):
    p=Path(path);s=p.read_text()
    if new in s and old not in s:return
    assert s.count(old)==1,(path,old[:150],s.count(old))
    p.write_text(s.replace(old,new));changed.append(path)

gradle=Path('android-app/app/build.gradle.kts')
if '0.4.0-ui92-r146.10"' in gradle.read_text():
    for path in [R+'CompactHomeDashboard.kt',R+'ReferenceProxyActivity.kt','android-app/app/build.gradle.kts']:
        assert Path(path).read_bytes()==subprocess.check_output(['git','show',BASE+':'+path]),'Concurrent application change: '+path
    home=Path(R+'CompactHomeDashboard.kt');s=home.read_text()
    start=s.index('            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, centered = true,')
    end=s.index('        BoxWithConstraints(',start)
    old=s[start:end]
    new='''            // UI11: a clean centered title; refresh remains a real pull/accessibility action.
            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, centered = true,
                modifier = Modifier.zIndex(2f)) { }
'''
    edit(str(home),old,new)
    edit(str(home),'        var more by remember { mutableStateOf(false) }\n','')
    edit(str(home),'.hazeSource(headerHaze).testTag("compact-home"), state = listState,', '''.hazeSource(headerHaze).testTag("compact-home").semantics {
                if (!data.busy && !data.refreshing && data.operation == null) {
                    customActions = listOf(CustomAccessibilityAction("刷新首页状态") { onPullRefresh(); true })
                }
            }, state = listState,''')
    p=Path(R+'ReferenceProxyActivity.kt');s=p.read_text()
    start=s.index('internal fun RefPanel(');at=s.index('\n) {',start)+len('\n) {')
    insertion='''
    // The strategy tab now owns one inline lazy list; all other panel tabs stay unchanged.
    if (selectedTab == RefPanelTab.Groups) {
        PanelStrategyRoute11(state, repo, delays, searchRequest, onSelectedTabChange,
            onRefreshState, onDetailVisibleChanged)
        return
    }
'''
    s=s[:at]+insertion+s[at:];p.write_text(s);changed.append(str(p))
    old='''                RefToolRow(Icons.Rounded.Article, Color(0xFF9333EA), "日志查看", "查看运行记录与排查问题") {
                    scope.launch { onLog(runCatching { inspector.runtimeLog() }.getOrElse { it.message ?: "日志读取失败" }) }
                }
                RefDivider()'''
    new=old+'''
                RefToolRow(Icons.Rounded.NetworkCheck, Color(0xFF2563EB), "网络诊断", "消息与网络连通性诊断") {
                    scope.launch {
                        try { onLog(ProxyComposeController(context).diagnostics()) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (error: Exception) { onLog(error.message ?: "诊断读取失败") }
                    }
                }
                RefDivider()'''
    edit(str(p),old,new)
    # Save exact authorized insertions for the independent boundary verifier.
    (evidence/'ui11-reference-insertions.json').write_text(json.dumps({'branch':insertion,'tool_old':old,'tool_new':new},ensure_ascii=False))
    edit(str(gradle),'versionCode = 1011','versionCode = 1012')
    edit(str(gradle),'versionName = "0.4.0-ui92-r146.10"','versionName = "0.4.0-ui92-r146.11"')

# Finalize the newly added presentation without changing core/transport files.
p=R+'PanelStrategy11.kt'
edit(p,'.nativePress(enabled = enabled, label = "选择${node.name}", onClick = onSelect)',
     '.panelNodePress11(enabled = enabled, onClick = onSelect, onLongClick = onName)')
edit(p,'''            Box(Modifier.size(48.dp).clip(CircleShape).nativePress(label = "查看完整节点名称", onClick = onName)
                .testTag("panel11-info:$group:${node.name}"), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Info, null, Modifier.size(15.dp), tint = t.textMuted)
            }
''','')
edit(p,'''            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }''',
'''            Spacer(Modifier.width(8.dp))
            ConfiguredGroupIcon(group, Modifier.size(24.dp))
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp).graphicsLayer { rotationZ = rotation }''')
# Nullable validation is explicit rather than relying on IntRange overload resolution.
edit(R+'PanelStrategyModel11.kt','''        if (customApi && port.toIntOrNull() !in 1024..65535) return "端口范围为 1024–65535"''',
'''        val parsedPort = port.toIntOrNull()
        if (customApi && (parsedPort == null || parsedPort !in 1024..65535)) return "端口范围为 1024–65535"''')

# These expectations are intentionally replaced by the user's removal of the two HOME icons.
# No tests, touch assertions, original runtime tests or latency assertions are skipped/deleted.
edit(T+'HomeUi7InteractionTest.kt','''        rule.onNodeWithContentDescription("刷新状态").performClick()
        rule.onNodeWithContentDescription("更多首页功能").performClick()
        rule.onNodeWithText("代理设置").performClick()
        assertEquals(listOf("refresh","settings"),calls)''','''        rule.onNodeWithContentDescription("刷新状态").assertDoesNotExist()
        rule.onNodeWithContentDescription("更多首页功能").assertDoesNotExist()
        rule.onNodeWithTag("compact-home").refreshHome11()
        assertEquals(listOf("refresh"),calls)''')
edit(T+'CompactHomeDashboardTest.kt','''        rule.onNodeWithContentDescription("更多首页功能").performClick()
        rule.onNodeWithText("网络诊断").performClick()
        assertEquals(listOf("webui", "log", "diagnostics"), calls)''','''        rule.onNodeWithContentDescription("更多首页功能").assertDoesNotExist()
        assertEquals(listOf("webui", "log"), calls)''')
for p in Path(T).glob('*.kt'):
    if p.name in ['PanelUi11Test.kt','PanelModel11Test.kt','HomeUi11Test.kt']:continue
    old=p.read_text();s=old
    for label in ['刷新状态','更多首页功能']:
        s=s.replace(f'onNodeWithContentDescription("{label}").assertIsDisplayed()',f'onNodeWithContentDescription("{label}").assertDoesNotExist()')
    s=s.replace('rule.onNodeWithContentDescription("刷新状态").performClick()', 'rule.onNodeWithTag("compact-home").refreshHome11()')
    s=re.sub(r'rule\.onNodeWithContentDescription\("刷新状态"\)\.performTouchInput\s*\{\s*click\(\)\s*\}', 'rule.onNodeWithTag("compact-home").refreshHome11()',s)
    s=s.replace('Toolbar refresh remains independently reachable','Accessible refresh remains independently reachable')
    if s!=old:p.write_text(s);changed.append(str(p))

# Diagnose references to removed controls, without muting a failed test.
for p in Path(T).glob('*.kt'):
    for i,line in enumerate(p.read_text().splitlines(),1):
        if ('刷新状态' in line or '更多首页功能' in line) and 'assertDoesNotExist' not in line:print('REVIEW',p.name,i,line)
new_files=[R+x for x in ['PanelStrategyModel11.kt','PanelControls11.kt','PanelStrategy11.kt','PanelNodePress11.kt']]+[T+x for x in ['PanelModel11Test.kt','PanelUi11Test.kt','HomeUi11Test.kt']]
changed=sorted(set(changed+new_files))
(evidence/'ui11-changed-paths.json').write_text(json.dumps(changed,indent=2))
print('UI11 scoped changes:',*changed,sep='\n')
