#!/usr/bin/env python3
"""One-shot, bounded UI-only integration over test.100. Removed after application."""
from pathlib import Path
import hashlib,json
r=Path(__file__).resolve().parents[1]
s=r/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
protected=json.loads((r/'tests/ui-runtime-baseline.json').read_text())
for p,digest in protected.items():
    assert hashlib.sha256((r/p).read_bytes()).hexdigest()==digest,p
assert 'versionName = "0.4.0-test.100"' in (r/'android-app/app/build.gradle.kts').read_text()
def rep(t,a,b):
    assert t.count(a)==1,(a[:90],t.count(a));return t.replace(a,b)
p=s/'CrystalWorkspace.kt';t=p.read_text();start=t.index('    var open by remember');end=t.index('\n@Composable\ninternal fun CrystalMenuContent',start)
t=t[:start]+'''    val host = LocalCrystalPopover.current
    val owner = remember { Any() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var anchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val logAction by rememberUpdatedState(onLog)
    val connectionsAction by rememberUpdatedState(onConnections)
    val diagnosticsAction by rememberUpdatedState(onDiagnostics)
    val adblockAction by rememberUpdatedState(onAdblock)
    val loading by rememberUpdatedState(diagnosticLoading)
    DisposableEffect(host, owner) { onDispose { host?.dismiss(owner, restoreFocus = false) } }
    Box {
        IconButton(onClick = {
            host?.show(owner, anchor, { runCatching { focus.requestFocus() } }) { dismiss ->
                var appeared by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { appeared = true }
                val progress by animateFloatAsState(if (appeared) 1f else 0f,
                    tween(if (LocalHetuMotionEnabled.current) 180 else 0), label = "popoverReveal")
                CrystalMenuContent(
                    onLog = { dismiss(); logAction() }, onConnections = { dismiss(); connectionsAction() },
                    onDiagnostics = { dismiss(); diagnosticsAction() }, onAdblock = { dismiss(); adblockAction() },
                    diagnosticLoading = loading, backdrop = hazeState, blurEnabled = glassEnabled,
                    modifier = Modifier.graphicsLayer {
                        alpha = progress; scaleX = .97f + .03f * progress; scaleY = scaleX
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                    })
            }
        }, modifier = Modifier.size(48.dp).focusRequester(focus).onGloballyPositioned {
            anchor = it.boundsInWindow(); host?.move(owner, anchor)
        }) {
            Icon(Icons.Rounded.MoreHoriz, "更多工具", tint = LocalHetuTokens.current.textSecondary)
        }
    }
}
''' + t[end:]
for name in ('Popup','PopupPositionProvider','PopupProperties'):t=t.replace('import androidx.compose.ui.window.'+name+'\n','')
t=rep(t,'import androidx.compose.ui.Modifier\n','import androidx.compose.ui.Modifier\nimport androidx.compose.ui.focus.focusRequester\nimport androidx.compose.ui.layout.onGloballyPositioned\nimport androidx.compose.ui.layout.boundsInWindow\n')
t=t.replace('/** Anchored popup semantics (outside/back dismissal, focus), not a white Material menu. */','/** Activity-hosted glass menu. Background capture and menu share the same window. */');p.write_text(t)
p=s/'ui/CrystalMaterial.kt';t=p.read_text()
t=rep(t,'enum class CrystalDepth { Card, InsetItem, Popover }','enum class CrystalDepth { Card, InsetItem, Popover, Sunken }')
t=rep(t,'            content()\n','            CrystalPopoverHost(content)\n')
t=rep(t,'val radius = when (depth) { CrystalDepth.Popover -> 24.dp; CrystalDepth.InsetItem -> 12.dp; else -> 24.dp }','val radius = when (depth) { CrystalDepth.Popover -> 24.dp; CrystalDepth.InsetItem, CrystalDepth.Sunken -> 12.dp; else -> 20.dp }')
t=rep(t,'CrystalDepth.Popover -> .78f; CrystalDepth.InsetItem -> .85f; else -> .96f','CrystalDepth.Popover -> .75f; CrystalDepth.InsetItem -> .85f; CrystalDepth.Sunken -> .78f; else -> .96f')
t=rep(t,'CrystalDepth.Popover -> .65f; CrystalDepth.InsetItem -> .70f; else -> .86f','CrystalDepth.Popover -> .65f; CrystalDepth.InsetItem -> .74f; CrystalDepth.Sunken -> .78f; else -> .85f')
t=rep(t,'else Color.White.copy(alpha = topAlpha)','else (if (depth == CrystalDepth.Sunken) Color(0xFFEEF2F6) else Color.White).copy(alpha = topAlpha)')
t=rep(t,'else Color(0xFFF8FAFE).copy(alpha = bottomAlpha)','else (if (depth == CrystalDepth.Sunken) Color(0xFFEEF2F6) else Color(0xFFF8FAFE)).copy(alpha = bottomAlpha)')
t=rep(t,'CrystalDepth.Popover -> 14.dp; CrystalDepth.InsetItem -> 2.dp; else -> 7.dp','CrystalDepth.Popover -> 14.dp; CrystalDepth.InsetItem -> 2.dp; CrystalDepth.Sunken -> 0.dp; else -> 7.dp')
t=rep(t,'val style = HazeStyle(backgroundColor = if (depth == CrystalDepth.Popover) Color.Transparent else t.pageBackground','val style = HazeStyle(backgroundColor = Color.Transparent')
t=rep(t,'drawRect(ambient); drawRect(mint)\n                drawContent()', '''drawRect(ambient); drawRect(mint)
                if (depth == CrystalDepth.Sunken) drawRect(Brush.verticalGradient(
                    listOf(Color(0xFF0F172A).copy(alpha = .04f), Color.Transparent), endY = 5.dp.toPx()))
                drawContent()''')
t=rep(t,'    return shadow(shadowSize, shape, clip = false,', '''    val contact = if (depth == CrystalDepth.Sunken) Modifier else Modifier.shadow(
        1.dp, shape, clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .03f),
        spotColor = Color(0xFF0F172A).copy(alpha = .03f))
    return then(contact).shadow(shadowSize, shape, clip = false,''');p.write_text(t)
p=s/'ReferenceProxyActivity.kt';t=p.read_text();t=rep(t,'''Modifier.fillMaxSize().statusBarsPadding().background(
            t.pageBackground,
        )''','Modifier.fillMaxSize().statusBarsPadding()');t=t.replace('Modifier.fillMaxSize().statusBarsPadding().background(t.pageBackground)','Modifier.fillMaxSize().statusBarsPadding()');p.write_text(t)
p=s/'LiquidWorkspace.kt';t=p.read_text();a=t.index('        .clip(shape).background(Brush.verticalGradient',t.index('internal fun LiquidGroupWell'));b=t.index('        .padding(horizontal = 12.dp, vertical = 9.dp)',a);t=t[:a]+'        .crystalMaterial(shape, depth = CrystalDepth.Sunken)\n'+t[b:];a=t.index('internal fun LiquidGroupWell');part=t[a:];part=part.replace('val shape = RoundedCornerShape(22.dp)','val shape = RoundedCornerShape(20.dp)',1);t=t[:a]+part;p.write_text(t)
p=s/'ConfiguredGroupIcon.kt';t=p.read_text();t=rep(t,'''        if (!configured || !visible) return@produceState
        value = repository.peek(key)?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading''', '''        // produceState keeps its previous value when the URL key changes. Reset
        // before early returns so a removed/changed icon cannot reuse another image.
        value = repository.peek(key)?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading
        if (!configured || !visible) return@produceState''');p.write_text(t)
p=s/'ProxySubscriptionActivity.kt';t=p.read_text();a=t.index('                val selectedBrush = if (dark) {',t.index('items(configLibrary'));b=t.index('                Box(',a);t=t[:a]+t[b:];t=rep(t,'.crystalMaterial(cardShape, selection = config.selected)', '.crystalMaterial(cardShape, selection = config.selected,\n                            depth = if (config.selected) CrystalDepth.Card else CrystalDepth.Sunken)');p.write_text(t)
for name in ('android-app/app/build.gradle.kts','.github/workflows/build-hetu.yml'):
 p=r/name;t=p.read_text();t=rep(t,'0.4.0-test.100','0.4.0-test.101');t=rep(t,'versionCode = 500','versionCode = 501');p.write_text(t)
for p,digest in protected.items():
 assert hashlib.sha256((r/p).read_bytes()).hexdigest()==digest,p
print('UI-only integration completed; test.100 runtime files unchanged')
