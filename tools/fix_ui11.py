"""Targeted UI11 corrections. No test is removed or skipped."""
from pathlib import Path
import json
R='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
E=Path('integration-evidence/ui11-changed-paths.json')
paths=json.loads(E.read_text())
def change(file,old,new):
    p=Path(R+file);s=p.read_text()
    if new in s and old not in s:return
    assert s.count(old)==1,(file,old[:120],s.count(old))
    p.write_text(s.replace(old,new));paths.append(str(p))
change('PanelControls11.kt','    RefPanelTab.entries.forEach { tab ->','''    listOf(RefPanelTab.Overview, RefPanelTab.Groups, RefPanelTab.Subscriptions,
            RefPanelTab.Connections, RefPanelTab.Rules, RefPanelTab.RuleSets).forEach { tab ->
            val label = if (tab == RefPanelTab.Groups) "策略" else tab.label''')
change('PanelControls11.kt','label = tab.title, onClick = { change(tab) }','label = label, onClick = { change(tab) }')
change('PanelControls11.kt','Text(tab.title, color =','Text(label, color =')
change('PanelControls11.kt','    val backdrop = LocalSheetBackdrop.current\n','')
change('PanelControls11.kt','''    DisposableEffect(backdrop) {
        backdrop?.let { it.count++ }
        onDispose { backdrop?.let { it.count = (it.count - 1).coerceAtLeast(0) } }
    }
''','')
change('CompactHomeDashboard.kt','private fun homeMotionAvailable(requested: Boolean): Boolean {','internal fun homeMotionAvailable(requested: Boolean): Boolean {')
change('PanelStrategy11.kt','''    val fontScale = LocalDensity.current.fontScale
''','''    val fontScale = LocalDensity.current.fontScale
    val hardware = androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    var blurEnabled by remember(prefs) { mutableStateOf(prefs.getBoolean("enableBlur", true)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == "enableBlur") blurEnabled = p.getBoolean(key, true)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
''')
change('PanelStrategy11.kt','''    Column(Modifier.fillMaxSize().background(t.pageBackground).padding(bottom = clearance)''','''    Column(Modifier.fillMaxSize().graphicsLayer {
        // Blur only this page behind its API sheet; existing detail sheets keep their own backdrop.
        renderEffect = if (settings && blurEnabled && hardware && android.os.Build.VERSION.SDK_INT >= 31)
            androidx.compose.ui.graphics.BlurEffect(8.dp.toPx(), 8.dp.toPx()) else null
    }.background(t.pageBackground).padding(bottom = clearance)''')
E.write_text(json.dumps(sorted(set(paths)),indent=2))
