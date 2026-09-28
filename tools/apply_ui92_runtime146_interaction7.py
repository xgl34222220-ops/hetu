#!/usr/bin/env python3
"""One-shot, exact UI6 -> UI7 source migration. Never runs inside the application."""
from pathlib import Path
import subprocess,json
BASE='8d3d30842b81e72f769ba751190f95adf0ecc39e'
ROOT='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed=[]
def read(path):
    p=Path(path)
    expected=subprocess.check_output(['git','show',BASE+':'+path])
    assert p.read_bytes()==expected,('Not exact UI6 input',path)
    return p.read_text()
def write(path,s):
    Path(path).write_text(s);changed.append(path)
def once(s,old,new):
    assert s.count(old)==1,(old[:150],s.count(old))
    return s.replace(old,new)

p=ROOT+'CompactHomeDashboard.kt';s=read(p)
s=once(s,'import androidx.compose.foundation.lazy.LazyColumn','import androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.rememberLazyListState\nimport androidx.compose.ui.input.nestedscroll.nestedScroll\nimport dev.chrisbanes.haze.HazeState\nimport dev.chrisbanes.haze.hazeSource')
s=s.replace('Color(0xFFEFEBF8)','Color(0xFFF8FAFC)')
s=once(s,'    Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card),\n        propagateMinConstraints = true, content = content)', '''    if (LocalHomeGroupedSurface.current) {
        Box(interactive, propagateMinConstraints = true, content = content)
    } else Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card),
        propagateMinConstraints = true, content = content)''')
# Keep the existing Text itself as the measurable baseline/accessible value. Animate one layer.
a=s.index('    val split = value.lastIndexOf',s.index('private fun HomeNumber('));b=s.index('\n}\n',a)
old=s[a:b]
new=old.replace('    val split = value.lastIndexOf', '''    val motion = LocalHomeMotion.current
    val roll = rememberMetricRoll(value, motion)
    val shown = if (motion) roll.displayed else value
    val split = shown.lastIndexOf''').replace('value.substring','shown.substring').replace('else append(value)','else append(shown)')
new=once(new,'Text(annotated, modifier, color = color', '''Text(annotated, modifier.graphicsLayer {
        val progress = if (motion) roll.progress.value.coerceIn(0f, 1f) else 1f
        alpha = progress
        translationY = (1f - progress) * (if (roll.leaving) -10f else 10f) * density
        rotationX = (1f - progress) * (if (roll.leaving) 10f else -10f)
        cameraDistance = 800f * density
    }, color = color''')
s=s[:a]+new+s[b:]
s=once(s,'        var more by remember { mutableStateOf(false) }', '''        val listState = rememberLazyListState()
        val headerHaze = remember { HazeState() }
        val (elastic, elasticConnection) = rememberHomeElasticity(listState, motion)
        val collapseDistance = with(LocalDensity.current) { 40.dp.toPx() }
        val collapse by remember(listState, collapseDistance) { derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / collapseDistance).coerceIn(0f, 1f)
        } }
        var more by remember { mutableStateOf(false) }''')
a=s.index('            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp)',s.index('internal fun CompactHomeDashboard'))
b=s.index('        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f))',a)
header=s[a:b]
start=header.index('                Box {')
menu=header[start:header.rfind('            }')]
s=s[:a]+'''            HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, elastic.offset) {
                HomeIcon(Icons.Rounded.Refresh, "刷新状态", !data.refreshing && !data.busy, data.refreshing,
                    onClick = onRefresh)
'''+menu+'''            }
'''+s[b:]
s=once(s,'LazyColumn(Modifier.fillMaxSize().clipToBounds().testTag("compact-home"),','''LazyColumn(Modifier.fillMaxSize().clipToBounds().nestedScroll(elasticConnection)
            .hazeSource(headerHaze).testTag("compact-home"), state = listState,''')
a=s.index('            item("hero") {');b=s.index('            item("latency")',a)
s=s[:a]+'''            item("hero") {
                Box(Modifier.graphicsLayer {
                    translationY = elastic.offset * .15f
                    scaleY = 1f + elastic.offset / 2000f
                }) { HomeHero(data, onToggle, onReload, onRestart, onSettings, onWebUi, onLog) }
            }
'''+s[b:]
a=s.index('@Composable\nprivate fun HomeHero(');b=s.index('@Composable\nprivate fun HomeLatency(',a)
s=s[:a]+'''@Composable
private fun HomeHero(data: CompactHomeData, toggle: () -> Unit, reload: () -> Unit,
    restart: () -> Unit, settings: () -> Unit, webUi: () -> Unit, log: () -> Unit) {
    NativeStatusHero(data, toggle, reload, restart, settings, LocalHomeMotion.current, quickActions = {
        Row(Modifier.fillMaxWidth().testTag("home-shortcut-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeHeroShortcut("WebUI", Modifier.weight(1f).testTag("home-webui"), webUi)
            HomeHeroShortcut("日志", Modifier.weight(1f).testTag("home-log"), log)
        }
    })
}

@Composable
private fun HomeHeroShortcut(title: String, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    Row(modifier.heightIn(min = 48.dp).clip(CircleShape)
        .background(p.card.copy(alpha = if (p.page.luminance() < .5f) .25f else .42f))
        .homeClick(label = title, onClick = click).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = p.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = p.muted)
    }
}

'''+s[b:]
s=once(s,'}, label = "latency-result-$name") { shown ->','}, contentKey = { if (it.endsWith(" ms")) "number" else it }, label = "latency-result-$name") { shown ->')
# Group the two network cells on one shared surface, keeping existing geometry and touch ownership.
s=once(s,'''                    HomeNetwork(data,Modifier.fillMaxWidth().height(rows.height))
                    HomeSpeed(data,Modifier.fillMaxWidth().height(rows.height),connections)''','''                    HomeNetworkGroup(data, rows, true, connections)''')
s=once(s,'''                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeNetwork(data,Modifier.weight(1f).height(rows.height))
                        HomeSpeed(data,Modifier.weight(1f).height(rows.height),connections)
                    }''','''                    HomeNetworkGroup(data, rows, false, connections)''')
pos=s.index('@Composable\nprivate fun HomeDataHeading(')
s=s[:pos]+'''@Composable
private fun HomeNetworkGroup(data: CompactHomeData, rows: HomeGridRows, single: Boolean, connections: () -> Unit) {
    val p = LocalHomePalette.current
    HomeCard(Modifier.fillMaxWidth().testTag("home-network-group")) {
        if (!single) Box(Modifier.align(Alignment.Center).width(1.dp).height(rows.height * .6f).background(p.line))
        CompositionLocalProvider(LocalHomeGroupedSurface provides true) {
            if (single) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeNetwork(data, Modifier.fillMaxWidth().height(rows.height))
                HomeSpeed(data, Modifier.fillMaxWidth().height(rows.height), connections)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeNetwork(data, Modifier.weight(1f).height(rows.height))
                HomeSpeed(data, Modifier.weight(1f).height(rows.height), connections)
            }
        }
    }
}

'''+s[pos:]
s=once(s,'                    alpha = progress.coerceIn(0f, 1f)','''                    alpha = progress.coerceIn(0f, 1f)
                    rotationY = if (motion) networkPerspective(progress, transition.leaving) else 0f
                    cameraDistance = 800f * density''')
# Avoid driving a rolling text transition from a per-frame interpolated number.
a=s.index('@Composable\nprivate fun animatedMetric(');b=s.index('@Composable\nprivate fun MetricLine(',a)
s=s[:a]+s[b:]
s=s.replace('val up = animatedMetric(data.up)','val up = data.up').replace('val down = animatedMetric(data.down)','val down = data.down')
s=once(s,'''    val cpu by animateFloatAsState(if (known) data.cpu else 0f,
        if (motion) tween(400) else snap(), label = "cpu-number")''','''    val cpu = if (known) data.cpu else 0f''')
write(p,s)

p=ROOT+'NativeHomePolish.kt';s=read(p)
s=once(s,'import androidx.compose.ui.draw.clip','import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.drawBehind')
s=once(s,'if (enabled && pressed && motion) .975f else 1f','if (enabled && pressed && motion) .965f else 1f')
s=once(s,'spring(dampingRatio = .8f, stiffness = 550f), label = "native-press"','spring(dampingRatio = .78f, stiffness = 750f), label = "native-press"')
s=s.replace('Color(0xFF505AA0)','Color(0xFF0F172A)')
s=once(s,'    restart: () -> Unit, settings: () -> Unit, motion: Boolean) {','    restart: () -> Unit, settings: () -> Unit, motion: Boolean, quickActions: (@Composable () -> Unit)? = null) {')
s=once(s,'listOf(Color(0xFFE6E1FF), Color(0xFFECE7FE), Color(0xFF2563EB))','listOf(Color(0xFFEEF2FF), Color(0xFFDDE7FD), Color(0xFF2563EB))')
s=once(s,'.diffuseCardShadow(shape).clip(shape).background(Brush.linearGradient(listOf(top, bottom)))) {','''.diffuseCardShadow(shape).clip(shape).background(Brush.linearGradient(listOf(top, lerp(top, bottom, .45f), bottom)))
        .drawBehind {
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .10f), Color.Transparent),
                center = Offset(size.width * .9f, size.height * .12f), radius = size.width * .65f))
            drawRect(Brush.radialGradient(listOf(top.copy(alpha = .60f), Color.Transparent),
                center = Offset(size.width * .15f, size.height * .9f), radius = size.width * .8f))
        }) {''')
s=once(s,'''        }
        if (processing) ProcessingShimmer(accent, motion, Modifier.align(Alignment.BottomCenter))''','''            if (quickActions != null) {
                Spacer(Modifier.height(8.dp))
                quickActions()
            }
        }
        if (processing) ProcessingShimmer(accent, motion, Modifier.align(Alignment.BottomCenter))''')
s=once(s,'val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)','val sheet = rememberInteractiveSheetState()')
s=once(s,'ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet,','ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, sheetGesturesEnabled = true,')
s=once(s,'dragHandle = { Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {','''dragHandle = { Box(Modifier.fillMaxWidth().height(48.dp).testTag("sheet-drag-handle")
            .semantics { contentDescription = "下拉关闭" }, contentAlignment = Alignment.Center) {''')
write(p,s)

p=ROOT+'ReferenceProxyActivity.kt';s=read(p);s=s.replace('Color(0xFFEFEBF8)','Color(0xFFF8FAFC)')
a=s.index('private fun RefInfoBottomSheet(');b=s.index('@Composable\nprivate fun RefSheetDragHandle()',a)
log=s[a:b]
log=once(log,'private fun RefInfoBottomSheet(','internal fun RefInfoBottomSheet(')
log=once(log,'    val t = LocalHetuTokens.current','    val t = LocalHetuTokens.current\n    val sheet = rememberInteractiveSheetState()')
log=once(log,'        onDismissRequest = onDismiss,','''        onDismissRequest = onDismiss,
        sheetState = sheet,
        sheetGesturesEnabled = true,
        modifier = Modifier.then(Modifier.semantics { testTag = "native-log-sheet" }),''')
old='''            Box(
                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                    .background(if (terminal) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),
            )'''
new='''            Box(Modifier.fillMaxWidth().height(48.dp).semantics { testTag = "log-drag-handle"; contentDescription = "下拉关闭日志" },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 6.dp)
                    .background(if (terminal) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape))
            }'''
log=once(log,old,new)
s=s[:a]+log+s[b:]
# Fully qualified semantic test tags via the normal modifier extension, not property imports.
s=once(s,'import androidx.compose.ui.platform.LocalView','import androidx.compose.ui.platform.LocalView\nimport androidx.compose.ui.platform.testTag\nimport androidx.compose.ui.semantics.contentDescription')
s=s.replace('Modifier.then(Modifier.semantics { testTag = "native-log-sheet" })','Modifier.testTag("native-log-sheet")')
s=s.replace('.height(48.dp).semantics { testTag = "log-drag-handle"; contentDescription = "下拉关闭日志" }','.height(48.dp).testTag("log-drag-handle").semantics { contentDescription = "下拉关闭日志" }')
write(p,s)
for p in [ROOT+'ui/HetuTheme.kt','android-app/app/src/main/res/values/styles.xml']:
    s=read(p);assert 'EFEBF8' in s,p;write(p,s.replace('EFEBF8','F8FAFC'))
p='android-app/app/build.gradle.kts';s=read(p)
s=once(s,'versionCode = 1007','versionCode = 1008');s=once(s,'versionName = "0.4.0-ui92-r146.6"','versionName = "0.4.0-ui92-r146.7"');write(p,s)
# Only update expectations directly superseded by this user's new layout/color contract.
p=TEST+'CompactHomeDashboardTest.kt';s=read(p)
s=once(s,'assertEquals(header.center.x, brand.center.x, 1f)','assertEquals(header.left + 16f, brand.left, 1f)')
s=once(s,'assertEquals(22.sp, textLayout("home-brand").layoutInput.style.fontSize)','assertEquals(28.sp, textLayout("home-brand").layoutInput.style.fontSize)')
write(p,s)
p=TEST+'HomeUi6RegressionTest.kt';s=read(p)
s=s.replace('0xFFEFEBF8','0xFFF8FAFC');write(p,s)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui7-changed-paths.json').write_text(json.dumps(changed,indent=2))
print('UI7 production/test files changed:',changed)
