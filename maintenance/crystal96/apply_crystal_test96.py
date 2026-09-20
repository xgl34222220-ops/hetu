#!/usr/bin/env python3
"""One-shot presentation-only migration. Every expected integration point is checked."""
from pathlib import Path
import hashlib,json,re
root=Path(__file__).resolve().parents[1]
src=root/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
assert 'versionName = "0.4.0-test.95"' in (root/'android-app/app/build.gradle.kts').read_text()
protected=json.loads((root/'tests/ui-runtime-baseline.json').read_text())
for p,h in protected.items():assert hashlib.sha256((root/p).read_bytes()).hexdigest()==h,p

def once(s,a,b):
    assert s.count(a)==1,(a[:100],s.count(a));return s.replace(a,b)
def span(s,name):
    m=re.search(r'(?:private |internal )?fun '+name+r'\(',s);assert m,name
    start=m.start();i=s.index('{',m.end());n=0;quote=None;esc=False;j=i
    while j<len(s):
        c=s[j]
        if quote:
            if esc:esc=False
            elif c=='\\':esc=True
            elif c==quote:quote=None
        elif c in '\"\'':quote=c
        elif c=='{':n+=1
        elif c=='}':
            n-=1
            if not n:return start,j+1
        j+=1
    raise AssertionError(name)
def fun(s,name,text):
    a,b=span(s,name);return s[:a]+text+s[b:]

changed=[]
for path in src.rglob('*.kt'):
    if path.name in {'CrystalMaterial.kt','CrystalWorkspace.kt','LiquidWorkspace.kt','HetuGlassDock.kt','HetuLiquidGlassLens.kt','LiquidGlassLens.kt'}:continue
    text=path.read_text()
    if not re.search(r'(?<!\w)Surface\(',text):continue
    text=text.replace('import androidx.compose.material3.Surface\n','')
    text=text.replace('\n\nimport ', '\n\nimport io.github.xgl34222220.hetu.ui.CrystalSurface as Surface\nimport ',1)
    path.write_text(text);changed.append(path.name)

p=src/'ui/HetuTheme.kt';s=p.read_text()
s=once(s,'            content = inner,','            content = { CrystalEnvironment(content = inner) },')
p.write_text(s)

p=src/'ReferenceProxyActivity.kt';s=p.read_text()
s=s.replace('import io.github.xgl34222220.hetu.ui.HetuTheme\n', 'import io.github.xgl34222220.hetu.ui.HetuTheme\nimport io.github.xgl34222220.hetu.ui.crystalMaterial\nimport io.github.xgl34222220.hetu.ui.crystalPageBackground\n')
s=fun(s,'refHomeLiquidModifier','''private fun refHomeLiquidModifier(base: Modifier, hazeState: HazeState, glassEnabled: Boolean, shape: RoundedCornerShape): Modifier {
    return base.crystalMaterial(shape)
}''')
s=fun(s,'RefGroup','''private fun RefGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp)), content = content)
}''')
s=once(s,'Box(Modifier.matchParentSize().background(shellBackground))', 'Box(Modifier.matchParentSize().crystalPageBackground())')
s=once(s,'                }, diagnosticLoading)', '                }, diagnosticLoading, hazeState, glassEnabled)')
a,b=span(s,'RefTrafficOverview');piece=s[a:b].replace('FontFamily.Monospace','FontFamily.SansSerif');s=s[:a]+piece+s[b:]
p.write_text(s)

p=src/'LiquidWorkspace.kt';s=p.read_text()
s=once(s,'ConfiguredGroupIcon(group, Modifier.size(22.dp))','ConfiguredGroupIcon(group, Modifier.size(28.dp))')
a=s.index('        .shadow(3.dp, shape, clip = false,');b=s.index('        .testTag("strategy:',a)
s=s[:a]+'        .crystalMaterial(shape, selection = expanded)\n'+s[b:]
a=s.index('        .shadow(if (active) 3.dp else 1.dp,');b=s.index('        .clip(shape)) {',a)
s=s[:a]+'        .crystalMaterial(shape, depth = CrystalDepth.InsetItem, selection = active)\n'+s[b:]
a=s.index('    val frosted = if (glassEnabled && hazeState != null)');b=s.index('    Column(modifier.offset',a);s=s[:a]+s[b:]
s=fun(s,'LiquidHomeMenu','''internal fun LiquidHomeMenu(onLog: () -> Unit, onConnections: () -> Unit, onDiagnostics: () -> Unit,
    onAdblock: () -> Unit, diagnosticLoading: Boolean, hazeState: HazeState? = null, glassEnabled: Boolean = false) {
    CrystalHomeMenu(onLog, onConnections, onDiagnostics, onAdblock, diagnosticLoading, hazeState, glassEnabled)
}''')
p.write_text(s)

p=src/'ProxySubscriptionActivity.kt';s=p.read_text()
a=s.index('                        .shadow(',s.index('items(configLibrary,'));b=s.index('                        .clickable(enabled = !loading)',a)
s=s[:a]+'                        .crystalMaterial(cardShape, selection = config.selected)\n'+s[b:]
assert 'LiquidConfigIndicator(config.selected)' in s
assert 'LiquidPill("导入配置"' in s
p.write_text(s)

p=src/'ui/HetuUiKit.kt';s=p.read_text()
s=once(s,'    style: TextStyle = MaterialTheme.typography.titleMedium,\n)', '    style: TextStyle = MaterialTheme.typography.titleMedium,\n    monospaced: Boolean = false,\n)')
s=once(s,'fontFamily = FontFamily.Monospace','fontFamily = if (monospaced) FontFamily.Monospace else FontFamily.SansSerif')
s=once(s,'return if (dock > 0.dp) dock + 16.dp else system + 16.dp','return if (dock > 0.dp) dock + 30.dp else system + 16.dp')
p.write_text(s)
p=src/'WorkspaceCards.kt';s=p.read_text()
s=once(s,'value == null -> "未测速"','value == null -> "— ms"')
s=once(s,'color = if (showBusy) t.controlBackground else background','color = if (showBusy || value == null) Color.Transparent else background')
s=once(s,'HetuNumber(it, color = if (showBusy) t.textSecondary else foreground,','HetuNumber(it, monospaced = true, color = if (showBusy || value == null) t.textSecondary.copy(alpha = .65f) else foreground,')
s=once(s,'HetuNumber(display, style =', 'HetuNumber(display, monospaced = lan || runtime.wanState in listOf("success", "stale"), style =')
p.write_text(s)

p=src/'ConfiguredGroupIcon.kt';s=p.read_text()
s=s.replace('import androidx.compose.ui.graphics.asImageBitmap','import androidx.compose.ui.graphics.asImageBitmap\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.unit.sp\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.semantics.contentDescription')
s=once(s,'Box(Modifier.fillMaxSize().background(LocalHetuTokens.current.controlBackground, RoundedCornerShape(8.dp)),','Box(Modifier.fillMaxSize(),')
s=once(s,'else -> Icon(refScenarioIcon(group.name, group.type), "${group.name} 未配置图标", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)', '''else -> Text(group.name.filter { it.isLetterOrDigit() }.take(2).ifBlank { "?" },
                Modifier.semantics { contentDescription = "${group.name} 未配置图标" },
                color = LocalHetuTokens.current.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)''')
p.write_text(s)

p=src/'ProxyGroupIcons.kt';s=p.read_text()
s=s.replace('import java.net.URL\n','import java.net.URL\nimport java.net.Proxy\nimport java.net.InetSocketAddress\n')
s=once(s,'    private val directory = File(context.cacheDir,','    private val app = context.applicationContext\n    private val directory = File(context.cacheDir,')
s=once(s,'            val connection = url.openConnection() as HttpURLConnection','            val connection = url.openConnection(imageProxy()) as HttpURLConnection')
anchor='    private fun fetch(address: String): ByteArray {'
s=once(s,anchor,'''    internal fun imageProxy(): Proxy {
        val prefs = app.getSharedPreferences("hetu", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("proxyRootRuntimeRunning", false)) return Proxy.NO_PROXY
        val controller = prefs.getInt("proxyControllerPort", MihomoStartupConfig.CONTROLLER_PORT)
        val port = MihomoStartupConfig.egressProbePort(controller)
        if (port !in 1024..65535) throw IOException("图标代理端口未就绪")
        // No direct fallback when an active proxy is requested. Cached icons remain usable.
        return Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
    }

'''+anchor)
p.write_text(s)

p=root/'android-app/app/src/test/java/io/github/xgl34222220/hetu/WorkspaceRenderTest.kt';s=p.read_text()
s=once(s,'assertEquals(124f,padding,.01f)','assertEquals(138f,padding,.01f)');p.write_text(s)
p=root/'android-app/app/build.gradle.kts';s=p.read_text().replace('versionCode = 495','versionCode = 496').replace('0.4.0-test.95','0.4.0-test.96');p.write_text(s)
p=root/'.github/workflows/build-hetu.yml';s=p.read_text().replace('versionCode = 495','versionCode = 496').replace('0.4.0-test.95','0.4.0-test.96');p.write_text(s)
for p,h in protected.items():assert hashlib.sha256((root/p).read_bytes()).hexdigest()==h,p
print('Crystal material installed on',len(changed),'presentation files:',', '.join(changed))
