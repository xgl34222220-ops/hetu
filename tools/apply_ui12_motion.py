#!/usr/bin/env python3
"""One-time UI11 -> UI12 presentation migration. No runtime/network edits or old migrations."""
from pathlib import Path
import subprocess, json
BASE = '621e4f22fce4e9c5f5c717eab1147fad20dab950'
ROOT = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
CHANGED = ['NativeHomePolish.kt', 'HomeInteraction7.kt', 'HomeExperience8.kt',
           'CompactHomeDashboard.kt', 'PanelControls11.kt', 'PanelStrategy11.kt',
           'ReferenceProxyActivity.kt', 'ui/HetuGlassDock.kt']
OUT = Path('integration-evidence'); OUT.mkdir(exist_ok=True)
gradle = Path('android-app/app/build.gradle.kts')
if '0.4.0-ui92-r146.12"' in gradle.read_text():
    print('UI12 already integrated; compile current sources without replay.')
    raise SystemExit(0)
assert '0.4.0-ui92-r146.11"' in gradle.read_text(), 'UI12 requires verified UI11'
original = {}
for name in CHANGED:
    path = ROOT + name
    original[path] = subprocess.check_output(['git', 'show', BASE + ':' + path]).decode()
    assert Path(path).read_text() == original[path], 'Concurrent app edit: ' + path
assert gradle.read_bytes() == subprocess.check_output(['git','show',BASE+':'+str(gradle)])

def once(s, old, new):
    assert s.count(old) == 1, ('nonunique/missing source anchor', old[:180], s.count(old))
    return s.replace(old, new, 1)
def read(name): return Path(ROOT+name).read_text()
def write(name,s): Path(ROOT+name).write_text(s)
def section(s,start,end,fn):
    a=s.index(start); b=s.index(end,a)
    return s[:a]+fn(s[a:b])+s[b:]

s=read('NativeHomePolish.kt')
s=once(s,'private class SheetBackdrop { var count by mutableIntStateOf(0) }\nprivate val LocalSheetBackdrop = staticCompositionLocalOf<SheetBackdrop?> { null }\n','')
s=once(s,'    if (LocalSheetBackdrop.current != null) { content(); return }\n    val backdrop = remember { SheetBackdrop() }\n','')
s=once(s,'    val motion = LocalHetuMotionEnabled.current\n    val blur by animateDpAsState(if (backdrop.count > 0) 8.dp else 0.dp,\n        tween(if (motion) 250 else 0), label = "sheet-background-blur")\n','')
s=once(s,'''    CompositionLocalProvider(LocalSheetBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().background(background).testTag("immersive-window")) {
            // Compose blur is a no-op on API <31; the readable scrim is always present.
            Box(Modifier.fillMaxSize().blur(blur, BlurredEdgeTreatment.Unbounded)) { content() }
        }
    }''','''    Box(Modifier.fillMaxSize().background(background).testTag("immersive-window")) {
        ModalBackdropHost12(content)
    }''')
s=once(s,'else if (pressed) tween(150, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))\n        else spring(dampingRatio = .78f, stiffness = 750f)',
       'else if (pressed) tween(80, easing = CubicBezierEasing(.2f, 0f, 0f, 1f))\n        else spring(dampingRatio = .84f, stiffness = 650f)')
def details(part):
    part=once(part,'    val backdrop = LocalSheetBackdrop.current\n','')
    part=once(part,'''    DisposableEffect(backdrop) {
        backdrop?.let { it.count++ }
        onDispose { backdrop?.let { it.count = (it.count - 1).coerceAtLeast(0) } }
    }
''','')
    part=once(part,'    ModalBottomSheet(','    MotionModalSheet12(')
    part=once(part,'shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)','shape = SheetShape12(32.dp)')
    part=once(part,'Column(Modifier.fillMaxWidth().weight(1f, fill = false)','Column(Modifier.sheetReveal12().fillMaxWidth().weight(1f, fill = false)')
    part=once(part,'modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)','modifier = Modifier.sheetReveal12(1).padding(horizontal = 24.dp, vertical = 16.dp)')
    return part
s=section(s,'internal fun NativeDetailsSheet(', '\n@Composable\ninternal fun NativeNetworkDetails',details)
write('NativeHomePolish.kt',s)

s=read('HomeInteraction7.kt')
s=once(s,'120.dp.toPx()', 'HetuMotion12.DismissDistanceDp.dp.toPx()')
s=once(s,'1800.dp.toPx()', 'HetuMotion12.DismissVelocityDp.dp.toPx()')
write('HomeInteraction7.kt',s)

s=read('HomeExperience8.kt')
s=once(s,'import kotlin.math.exp','import kotlin.math.exp\nimport kotlin.math.ln')
s=once(s,'''        animation?.cancel()
        val before = rawPx''','''        animation?.cancel()
        // A new drag catches the current spring position instead of snapping to zero.
        if (rawPx == 0f && offsetPx > 0f) {
            rawPx = (-190f * ln((1f - offsetDp / 110f).coerceIn(.0025f, 1f))) * density
        }
        val before = rawPx''')
s=once(s,'fun release(enabled: Boolean, onRefresh: () -> Unit): Boolean {','fun release(enabled: Boolean, onRefresh: () -> Unit, velocityPx: Float = 0f): Boolean {')
s=once(s,'''        val trigger = enabled && armed
        rawPx = 0f''','''        val trigger = enabled && armed
        val derivative = (110f / 190f) * (1f - offsetDp / 110f).coerceIn(0f, 1f)
        val releaseVelocity = if (velocityPx.isFinite()) (velocityPx * derivative).coerceIn(-600f * density, 600f * density) else 0f
        rawPx = 0f''')
# Scope only release, not synchronization from authoritative refreshing flags.
s=section(s,'    fun release(', '    private fun settle(',lambda p: p.replace('settle(46f * density)','settle(46f * density, releaseVelocity)').replace('} else settle(0f)','} else settle(0f, releaseVelocity)'))
s=once(s,'private fun settle(target: Float) {','private fun settle(target: Float, velocity: Float = 0f) {')
s=once(s,'Animatable(offsetPx).animateTo(target, spring(dampingRatio = .82f, stiffness = 380f)) {',
       'Animatable(offsetPx).animateTo(target, spring(dampingRatio = .84f, stiffness = 280f), initialVelocity = velocity) {')
s=once(s,'state.release(currentEnabled, currentRefresh)','state.release(currentEnabled, currentRefresh, available.y)')
write('HomeExperience8.kt',s)

s=read('CompactHomeDashboard.kt')
s=once(s,'        val collapseDistance = with(LocalDensity.current)',
       '        val (bottomRebound, bottomConnection) = rememberBottomRebound12(listState, motion)\n        val collapseDistance = with(LocalDensity.current)')
s=once(s,'graphicsLayer { translationY = pull.offsetPx }','graphicsLayer { translationY = pull.offsetPx + bottomRebound.offsetPx }')
s=once(s,'.nestedScroll(pullConnection)','.nestedScroll(pullConnection).nestedScroll(bottomConnection)')
write('CompactHomeDashboard.kt',s)

s=read('PanelControls11.kt')
assert s.count('            DropdownMenu(expanded =')==2
s=s.replace('            DropdownMenu(expanded =','            MotionPopover12(expanded =')
s=s.replace('shape = RoundedCornerShape(20.dp), containerColor = t.cardBackground,','shape = HomeContinuousShape(20.dp), containerColor = t.cardBackground,')
s=once(s,'    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state,','    MotionModalSheet12(onDismissRequest = onDismiss, sheetState = state,')
s=once(s,'shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)','shape = SheetShape12(30.dp)')
s=once(s,'Column(Modifier.fillMaxWidth().weight(1f, fill = false)','Column(Modifier.sheetReveal12().fillMaxWidth().weight(1f, fill = false)')
s=once(s,'modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)','modifier = Modifier.sheetReveal12(1).padding(horizontal = 20.dp, vertical = 14.dp)')
write('PanelControls11.kt',s)

s=read('PanelStrategy11.kt')
a=s.index('    val hardware = androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated')
b=s.index('    val columns =',a)
s=s[:a]+s[b:]
s=once(s,'    val (pull, connection) = rememberHomePull(list, refreshing, true, motion, ::requestRefresh)',
       '    val (pull, connection) = rememberHomePull(list, refreshing, true, motion, ::requestRefresh)\n    val (bottomRebound, bottomConnection) = rememberBottomRebound12(list, motion)\n    val pixelDensity = LocalDensity.current.density')
s=once(s,'''    Column(Modifier.fillMaxSize().graphicsLayer {
        // Blur only this page behind its API sheet; existing detail sheets keep their own backdrop.
        renderEffect = if (settings && blurEnabled && hardware && android.os.Build.VERSION.SDK_INT >= 31)
            androidx.compose.ui.graphics.BlurEffect(8.dp.toPx(), 8.dp.toPx()) else null
    }.background(t.pageBackground)''','''    Column(Modifier.fillMaxSize().background(t.pageBackground)''')
s=once(s,'expandVertically(tween(if (motion) 220 else 0))','expandVertically(HetuMotion12.spatial(motion))')
s=once(s,'shrinkVertically(tween(if (motion) 180 else 0))','shrinkVertically(HetuMotion12.spatial(motion))')
s=once(s,'graphicsLayer { translationY = pull.offsetPx }','graphicsLayer { translationY = pull.offsetPx + bottomRebound.offsetPx }')
s=once(s,'.nestedScroll(connection).testTag("panel11-list")',
       '.nestedScroll(connection).nestedScroll(bottomConnection).semantics { this[EdgeOffset12] = bottomRebound.offsetPx / pixelDensity }.testTag("panel11-list")')
s=once(s,'placementSpec = spring(dampingRatio = .86f, stiffness = 500f)','placementSpec = spring(dampingRatio = .84f, stiffness = 280f)')
s=once(s,'if (open) 180f else 0f, tween(if (motion) 220 else 0)','if (open) 180f else 0f, HetuMotion12.spatial(motion)')
write('PanelStrategy11.kt',s)

s=read('ReferenceProxyActivity.kt')
def logs(part):
    part=once(part,'    val sheet = rememberInteractiveSheetState()',
              '    val sheet = rememberInteractiveSheetState()\n    val closeScope = rememberCoroutineScope()')
    part=once(part,'    ModalBottomSheet(','    MotionModalSheet12(')
    part=once(part,'shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)','shape = SheetShape12(30.dp)')
    part=once(part,'Modifier.fillMaxWidth().fillMaxHeight(.80f)','Modifier.sheetReveal12().fillMaxWidth().fillMaxHeight(.80f)')
    part=once(part,'                    onClick = onDismiss,','                    onClick = { closeScope.launch { sheet.hide(); onDismiss() } },')
    return part
s=section(s,'internal fun RefInfoBottomSheet(', '@Composable\nprivate fun RefSheetDragHandle()',logs)
write('ReferenceProxyActivity.kt',s)

s=read('ui/HetuGlassDock.kt')
for old,new in [('blurRadius = 30.dp','blurRadius = 20.dp'),('noiseFactor = .018f','noiseFactor = .006f'),
                ('saturation = 1.80f','saturation = 1.15f'),('refractionHeight = 17.dp.toPx()','refractionHeight = 12.dp.toPx()'),
                ('refractionAmount = 13.dp.toPx()','refractionAmount = 8.dp.toPx()'),
                ('.copy(alpha = if (dark) .72f else .86f)','.copy(alpha = if (dark) .16f else .24f)'),
                ('spring(dampingRatio = .88f, stiffness = 420f)','spring(dampingRatio = .84f, stiffness = 300f)')]:
    s=once(s,old,new)
write('ui/HetuGlassDock.kt',s)

gradle.write_text(once(once(gradle.read_text(),'versionCode = 1012','versionCode = 1013'),
                       'versionName = "0.4.0-ui92-r146.11"','versionName = "0.4.0-ui92-r146.12"'))
# Record exact pre/post application bytes. Runtime functions outside the log sheet
# in ReferenceProxyActivity must match byte-for-byte independently of file-level allowlists.
report={path:{'before':before,'after':Path(path).read_text()} for path,before in original.items()}
(OUT/'ui12-source-changes.json').write_text(json.dumps(report,ensure_ascii=False))
print('UI12 integrated:',len(CHANGED),'existing presentation files; no dependency or runtime upgrade')
