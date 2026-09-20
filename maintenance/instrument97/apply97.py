#!/usr/bin/env python3
"""One-time test.96 -> test.97 presentation-only migration. Refuse unexpected sources."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
s=r/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
changes={}
def edit(path): return path.read_text()
def once(t,a,b):
    assert t.count(a)==1,(a[:90],t.count(a)); return t.replace(a,b)
def section(t,a,b,new):
    assert t.count(a)==1,a
    i=t.index(a);j=t.index(b,i);return t[:i]+new+t[j:]
f=s/'WorkspaceCards.kt';t=edit(f)
t=section(t,'@Composable\ninternal fun WorkspaceBento','@Composable\ninternal fun RuleMetricSummary', '''@Composable
internal fun WorkspaceBento(runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    used: Long, total: Long, count: Int, memory: Long, cpu: Float, onSubscription: () -> Unit) {
    InstrumentBento(runtime, connections, up, down, used, total, count, memory, cpu, onSubscription)
}

''');changes[f]=t
f=s/'ReferenceProxyActivity.kt';t=edit(f)
t=section(t,'@Composable\ninternal fun RefProviderRow','private fun refExpireDays', '''@Composable
internal fun RefProviderRow(item: DashboardProviderUi, refreshing: Boolean, success: Boolean, onRefresh: () -> Unit, onClick: () -> Unit) {
    InstrumentSubscriptionTicket(item.name, item, refreshing = refreshing, success = success, onEdit = onClick, onRefresh = onRefresh)
}

''')
t=once(t,'                    RefHomeActions(state.running, busy, onToggle, onReload, onRestart)','                    EngineThroughput(upRate, downRate)\n                    RefHomeActions(state.running, busy, onToggle, onReload, onRestart)')
a=t.index('private fun RefRuleGroupCard(');b=t.index('        Column(Modifier.fillMaxWidth()) {',a)
t=t[:a]+'''internal fun RefRuleGroupCard(items: List<ProxyRuleUi>) {
    val t = LocalHetuTokens.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val shape = RoundedCornerShape(22.dp)
    Surface(
        modifier = Modifier.fillMaxWidth().crystalMaterial(shape).testTag("rules-inset-group"),
        shape = shape, color = Color.Transparent, shadowElevation = 0.dp,
    ) {
'''+t[b:]
if 'import androidx.compose.ui.platform.testTag' not in t:t=t.replace('import androidx.compose.ui.Modifier','import androidx.compose.ui.platform.testTag\nimport androidx.compose.ui.Modifier')
t=once(t,'filteredRules.chunked(15)', 'instrumentRuleBatches(filteredRules)')
# The expanded expression must remain complete; no changes to rules, matching or order.
t=once(t,'maxLines = if (open) 5 else 1,','maxLines = if (open) Int.MAX_VALUE else 1,')
t=once(t,'modifier.background(t.cardBackground, shape).border(.7.dp, t.outline.copy(alpha = .45f), shape).clickable(onClick = onClick).padding(11.dp)', 'modifier.crystalMaterial(shape, depth = CrystalDepth.InsetItem).clickable(onClick = onClick).padding(11.dp)')
# Give actual rate cards color-specific light and allow long real numbers to wrap.
t=once(t,'Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp)', 'Surface(modifier = modifier.crystalMaterial(RoundedCornerShape(22.dp), tint = color), shape = RoundedCornerShape(22.dp), color = Color.Transparent, shadowElevation = 0.dp)')
t=once(t,'Text(refSpeed(value), color = t.textPrimary, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)', 'HetuNumber(refSpeed(value), color = t.textPrimary, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold))')
changes[f]=t
f=s/'ProxySubscriptionActivity.kt';t=edit(f)
a=t.index('            items(\n                subscriptions,');b=t.index('            item("yaml-heading")',a)
t=t[:a]+'''            items(subscriptions, key = { "sub-${it.name}" }) { item ->
                val provider = liveProviders[item.name] ?: liveProviders.entries.firstOrNull { it.key.equals(item.name, true) }?.value
                InstrumentSubscriptionTicket(item.name, provider, subscriptionHost(item), item.placeholder, onEdit = {
                    addingSubscription = false
                    editSubscription = item
                    editorName = item.name
                    editorUrl = if (item.placeholder) "" else item.url
                    editorError = ""
                })
            }

'''+t[b:]
t=once(t,'color = tokens.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace','color = tokens.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.SansSerif')
changes[f]=t
f=s/'ui/CrystalMaterial.kt';t=edit(f)
t=t.replace('import androidx.compose.ui.graphics.drawscope.Stroke','import androidx.compose.ui.graphics.drawscope.Stroke\nimport androidx.compose.ui.graphics.drawscope.inset')
t=once(t,'CrystalDepth.InsetItem -> 8.dp; else -> 20.dp','CrystalDepth.InsetItem -> 12.dp; else -> 24.dp')
t=once(t,'CrystalDepth.Popover -> .76f; CrystalDepth.InsetItem -> .87f; else -> .94f','CrystalDepth.Popover -> .78f; CrystalDepth.InsetItem -> .85f; else -> .96f')
t=once(t,'CrystalDepth.Popover -> .67f; CrystalDepth.InsetItem -> .74f; else -> .85f','CrystalDepth.Popover -> .65f; CrystalDepth.InsetItem -> .70f; else -> .86f')
t=once(t,'HazeStyle(backgroundColor = t.pageBackground, tints = emptyList()', 'HazeStyle(backgroundColor = if (depth == CrystalDepth.Popover) Color.Transparent else t.pageBackground, tints = emptyList()')
t=once(t,'if (selection) .12f else .065f','if (selection) .15f else .105f')
t=once(t,'if (dark) .025f else .038f','if (dark) .04f else .065f')
t=once(t,'                drawOutline(outline, rim, style = Stroke(width = 1f))', '''                drawOutline(outline, rim, style = Stroke(width = 1f))
                if (size.minDimension > 8.dp.toPx()) inset(1.5.dp.toPx()) {
                    val inner = shape.createOutline(size, layoutDirection, this)
                    drawOutline(inner, Brush.verticalGradient(listOf(
                        Color.White.copy(alpha = if (dark) .08f else .52f), Color.Transparent)), style = Stroke(1f))
                }''')
changes[f]=t
f=s/'LiquidWorkspace.kt';t=edit(f)
t=once(t,'padding(top = 2.dp, bottom = 10.dp)', 'padding(top = 2.dp, bottom = 16.dp)')
# No extra opaque background inside the already-translucent material.
assert '.crystalMaterial(shape, depth = CrystalDepth.InsetItem, selection = active)' in t
assert 'ConfiguredGroupIcon(group, Modifier.size(28.dp))' in t
changes[f]=t
f=s/'ConfiguredGroupIcon.kt';t=edit(f)
t=t.replace('import io.github.xgl34222220.hetu.ui.*','''import io.github.xgl34222220.hetu.ui.*
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay''')
a=t.index('    val loaded by produceState<GroupIconLoad>');b=t.index('    val ready = loaded as?',a)
t=t[:a]+'''    val prefs = remember(context) { context.getSharedPreferences("hetu", 0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var epoch by remember { mutableIntStateOf(0) }
    var visible by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(prefs, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) epoch++
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == "proxyRootRuntimeRunning" || changed == "proxyControllerPort") epoch++
        }
        lifecycle.addObserver(observer)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { lifecycle.removeObserver(observer); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val loaded by produceState<GroupIconLoad>(cached?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading,
        key, group.iconPath, epoch, visible) {
        if (!configured || !visible) return@produceState
        value = repository.peek(key)?.let { GroupIconLoad.Ready(it, true) } ?: GroupIconLoad.Loading
        // A failed first attempt must not remain stuck until the entire process dies.
        // Only visible composed cards retry; cache, per-URL single flight and bounds remain.
        repeat(3) { attempt ->
            value = repository.load(key, group.iconPath, retry = epoch > 0 || attempt > 0)
            if (value is GroupIconLoad.Ready) return@produceState
            if (attempt < 2) delay(60_000L)
        }
    }
'''+t[b:]
changes[f]=t
f=s/'ProxyGroupIcons.kt';t=edit(f)
t=once(t,'suspend fun load(url: String, legacyPath: String = ""): GroupIconLoad','suspend fun load(url: String, legacyPath: String = "", retry: Boolean = false): GroupIconLoad')
t=once(t,'if (SystemClock.elapsedRealtime() < (retries[url] ?: 0L))', 'if (!retry && SystemClock.elapsedRealtime() < (retries[url] ?: 0L))')
changes[f]=t
f=r/'android-app/app/build.gradle.kts';t=edit(f)
t=once(t,'versionName = "0.4.0-test.96"','versionName = "0.4.0-test.97"');t=once(t,'versionCode = 496','versionCode = 497');changes[f]=t
for path, content in changes.items():path.write_text(content)
print('Applied test.97 to',len(changes),'presentation files; no network-control changes')
