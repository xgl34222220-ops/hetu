#!/usr/bin/env python3
"""One-time, bounded presentation migration. Never restores runtime or replays the old merge."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
JAVA = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
EXPECTED = {
    JAVA+'CompactHomeDashboard.kt': 'b0b795c400c6a0b5795264e2a554e43d884de131',
    JAVA+'ReferenceProxyActivity.kt': '83bdfd94890e867207bdfacd1b96b02f37342128',
    JAVA+'ui/HetuTheme.kt': '365b58208ba7559da1c941492f75bea8e0d395c8',
    JAVA+'ui/HetuInteractions.kt': '260da78df3f8cb9916b1942fc1bf073dadb1db85',
    TEST+'CompactHomeDashboardTest.kt': '3c70a05cd8ed2e0fe137b342863e37c66650ba1b',
    'android-app/app/src/main/res/values/styles.xml': '5c774bb550bc47e7991d4ba36faaebdec33f5ebb',
    'android-app/app/build.gradle.kts': '01d0f7a7dbf4a72ba43af695c9dfa4e40746db8a',
}
if 'versionName = "0.4.0-ui92-r146.2"' in (ROOT/'android-app/app/build.gradle.kts').read_text():
    raise SystemExit('UI2 is already integrated; compile current source without applying this migration.')
sources = {}
for path, expected in EXPECTED.items():
    raw = (ROOT/path).read_bytes()
    blob = hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest()
    if blob != expected:
        raise SystemExit(f'Refusing concurrent/unreviewed edit: {path}: {blob} != {expected}')
    sources[path] = raw.decode()

def once(s, old, new):
    if s.count(old) != 1:
        raise RuntimeError(f'Expected one exact anchor, got {s.count(old)}: {old[:120]!r}')
    return s.replace(old, new, 1)

def section(s, start, end, new):
    if s.count(start) != 1 or s.count(end) != 1:
        raise RuntimeError('Ambiguous function boundaries: '+start)
    a, b = s.index(start), s.index(end)
    if b <= a: raise RuntimeError('Reversed function boundaries')
    return s[:a]+new+s[b:]

p = JAVA+'CompactHomeDashboard.kt'
s = sources[p]
s = once(s, '    val busy: Boolean = false,', '    val busy: Boolean = false,\n    val operation: HomeOperation? = null,')
s = once(s, '    val region: String = "",', '    val region: String = "",\n    val isp: String = "",\n    val asn: String = "",')
s = s.replace('0xFFF4F6F9', '0xFFF4F6FB')
s = section(s, '@Composable\nprivate fun Modifier.homeClick', '@Composable\nprivate fun HomeCard', '''@Composable
private fun Modifier.homeClick(enabled: Boolean = true, label: String? = null, onClick: () -> Unit): Modifier =
    nativePress(enabled, label, LocalHomeMotion.current, onClick)

''')
s = once(s, '''Box(interactive.shadow(3.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = .035f), spotColor = Color.Black.copy(alpha = .035f))
        .clip(shape).background(p.card), content = content)''', '''Box(interactive.diffuseCardShadow(shape).clip(shape).background(p.card), content = content)''')
s = once(s, 'SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)',
         'SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = color.copy(alpha = .66f))')
s = once(s, 'fontWeight = FontWeight.Bold, fontFamily = FontFamily.Default, maxLines = 2,',
         'fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 2,')
s = once(s, 'val motion = homeMotionAvailable(motionEnabled)',
         'val motion = homeMotionAvailable(motionEnabled && io.github.xgl34222220.hetu.ui.LocalHetuMotionEnabled.current)')
s = once(s, '''        var more by remember { mutableStateOf(false) }
        LazyColumn''', '''        var more by remember { mutableStateOf(false) }
        var feedbackDetails by remember { mutableStateOf<String?>(null) }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(data.message, data.busy, data.operation) {
            if (data.busy || data.operation != null) {
                snackbar.currentSnackbarData?.dismiss()
            } else {
                HomeLifecyclePresentation.feedback(data.message)?.let { summary ->
                    if (snackbar.showSnackbar(summary, actionLabel = "详情", withDismissAction = true,
                        duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) feedbackDetails = data.message
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
        LazyColumn''')
s = once(s, '.background(palette.page).statusBarsPadding()',
         '.background(palette.page).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))')
s = once(s, '''                right = { HomeResources(data, it) }) }
        }
    }
}''', '''                right = { HomeResources(data, it) }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp))
        }
        feedbackDetails?.let { detail -> NativeDetailsSheet("操作详情", { feedbackDetails = null }) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(detail, color = palette.text, fontSize = 12.sp, lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace)
            }
        } }
    }
}''')
s = section(s, '@Composable\nprivate fun HomeHero', '@Composable\nprivate fun HomeShortcut', '''@Composable
private fun HomeHero(data: CompactHomeData, toggle: () -> Unit, reload: () -> Unit,
    restart: () -> Unit, settings: () -> Unit) {
    NativeStatusHero(data, toggle, reload, restart, settings, LocalHomeMotion.current)
}

''')
s = once(s, '''                                HomeNumber(shown, Modifier.graphicsLayer { alpha = if (data.testing) pulse else 1f }
                                    .padding(top = 4.dp), tint, 21, TextAlign.Center)''', '''                                if (shown == "···") LoadingWaveDots(motion, p.blue, Modifier.padding(top = 4.dp))
                                else HomeNumber(shown, Modifier.padding(top = 4.dp), tint, 21, TextAlign.Center)''')
s = once(s, '''    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("网络详情") }, text = {
        Text("WAN\\n${data.wan}\\n${data.region}\\n\\nLAN\\n${data.lan}\\n${data.lanInterface}\\n\\n活动连接：${data.connections}")
    }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })''',
    '''    if (details) NativeNetworkDetails(data) { details = false }''')
s = once(s, '''style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1''', '''style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                        fontFamily = FontFamily.Monospace, maxLines = 1''')
sources[p] = s

# The operation token belongs to the shell, so page changes cannot lose the requested action.
p = JAVA+'ReferenceProxyActivity.kt'
s = sources[p]
anchor = re.search(r'    var operation by remember(?:Saveable)? \{ mutableStateOf\(""\) \}', s)
if not anchor: raise RuntimeError('Missing shell operation state')
s = once(s, anchor.group(), anchor.group()+'\n    var homeOperation by remember { mutableStateOf<HomeOperation?>(null) }')
s = once(s, '''    fun toggle() {
        if (operation.isNotBlank()) return
        scope.launch {''', '''    fun toggle() {
        if (operation.isNotBlank() || homeOperation != null) return
        homeOperation = if (state.running) HomeOperation.Stop else HomeOperation.Start
        scope.launch {''')
s = once(s, '''    fun reload() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {''', '''    fun reload() {
        if (!state.running || operation.isNotBlank() || homeOperation != null) return
        homeOperation = HomeOperation.Reload
        scope.launch {''')
s = once(s, '''    fun restart() {
        if (!state.running || operation.isNotBlank()) return
        scope.launch {''', '''    fun restart() {
        if (!state.running || operation.isNotBlank() || homeOperation != null) return
        homeOperation = HomeOperation.Restart
        scope.launch {''')
# Reconcile real core state before removing the busy presentation, rather than flashing a cached state.
s = once(s, '''if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                operation = ""''', '''if (state.running) controller.stop { operation = it } else controller.start { operation = it }
                state = controller.state()
                operation = ""''')
s = once(s, '''message = controller.reload()
                operation = ""''', '''message = controller.reload()
                state = controller.state()
                operation = ""''')
s = once(s, '''state = state.copy(runtimeSettingsPending = false, message = "")''',
         '''state = controller.state().copy(runtimeSettingsPending = false, message = "")''')
a = s.index('    fun toggle() {')
b = s.index('    suspend fun measureSitesInternal', a)
operations = s[a:b]
if operations.count('''            } finally {
                operation = ""
            }''') != 3: raise RuntimeError('Expected three operation finalizers')
operations = operations.replace('''            } finally {
                operation = ""
            }''', '''            } finally {
                operation = ""
                homeOperation = null
            }''')
s = s[:a]+operations+s[b:]
s = once(s, '''                    operation = operation,
                    message = message,''', '''                    operation = operation,
                    action = homeOperation,
                    message = message,''')
s = once(s, '''    operation: String,
    message: String,''', '''    operation: String,
    action: HomeOperation?,
    message: String,''')
s = once(s, '''running = state.running, busy = operation.isNotBlank(), refreshing = refreshing,''',
         '''running = state.running, busy = operation.isNotBlank() || action != null, operation = action, refreshing = refreshing,''')
s = s.replace('0xFFF4F6F9', '0xFFF4F6FB')
# Existing clickable node/group components keep their callbacks and cancel-on-scroll behavior.
s = s.replace('if (pressed) .96f else 1f', 'if (pressed) .975f else 1f')
s = s.replace('if (pressed) .95f else 1f', 'if (pressed) .975f else 1f')
s = s.replace('alpha = if (pressed) .92f else 1f', 'alpha = 1f')
s = s.replace('alpha = if (pressed) .86f else if (enabled) 1f else .50f', 'alpha = if (enabled) 1f else .50f')
sources[p] = s

p = JAVA+'ui/HetuTheme.kt'
s = sources[p].replace('0xFFF4F6F9', '0xFFF4F6FB')
s = once(s, '            content = inner,',
         '            content = { io.github.xgl34222220.hetu.ImmersiveUiHost { inner() } },')
sources[p] = s
p = JAVA+'ui/HetuInteractions.kt'
s = sources[p].replace('if (pressed && enabled) .975f else 1f', 'if (pressed && enabled && motion) .975f else 1f')
s = once(s, 'if (motion) spring(dampingRatio = .82f, stiffness = 650f) else snap(), label = "hetuPress"',
    'if (!motion) snap() else if (pressed) tween(150, easing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, 0f, 1f)) else spring(dampingRatio = .82f, stiffness = 650f), label = "hetuPress"')
sources[p] = s

p = 'android-app/app/src/main/res/values/styles.xml'
s = sources[p].replace('<item name="android:statusBarColor">#F1F5F9</item>', '<item name="android:statusBarColor">@android:color/transparent</item>')
s = s.replace('<item name="android:navigationBarColor">#F1F5F9</item>', '<item name="android:navigationBarColor">@android:color/transparent</item>')
s = s.replace('<item name="android:windowBackground">#F1F5F9</item>', '<item name="android:windowBackground">#F4F6FB</item>')
s = s.replace('<item name="android:statusBarColor">#121714</item>', '<item name="android:statusBarColor">@android:color/transparent</item>')
s = s.replace('<item name="android:navigationBarColor">#121714</item>', '<item name="android:navigationBarColor">@android:color/transparent</item>')
sources[p] = s
p = 'android-app/app/build.gradle.kts'
s = sources[p].replace('versionCode = 1002', 'versionCode = 1003').replace('0.4.0-ui92-r146.1', '0.4.0-ui92-r146.2')
sources[p] = s

p = TEST+'CompactHomeDashboardTest.kt'
s = sources[p]
s = once(s, '        render(fixture().copy(busy = true, message = "正在重载配置"))',
         '        render(fixture().copy(busy = true, operation = HomeOperation.Reload, message = "正在重载配置"))')
s = once(s, '''        listOf("home-reload", "home-toggle", "home-restart").forEach { rule.onNodeWithTag(it).assertIsNotEnabled() }
        rule.onNodeWithText("正在重载配置").assertIsDisplayed()''', '''        listOf("home-reload", "home-toggle", "home-restart").forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        rule.onNodeWithTag("home-processing-shimmer").assertExists()
        rule.onAllNodesWithText("正在重载").assertCountEquals(2)
        rule.onNodeWithText("正在重载配置").assertDoesNotExist()
        snapshot("processing-reload")''')
s = once(s, '        rule.onNodeWithTag("home-reload").assertIsNotEnabled()', '        rule.onNodeWithTag("home-reload").assertDoesNotExist()')
s = once(s, '        rule.onNodeWithTag("home-restart").assertIsNotEnabled()', '        rule.onNodeWithTag("home-restart").assertDoesNotExist()')
s = once(s, '        rule.onNodeWithText("网络详情").assertIsDisplayed()', '''        rule.onNodeWithText("网络详情").assertIsDisplayed()
        rule.onNodeWithTag("native-details-sheet").assertExists()
        rule.onNodeWithTag("sheet-confirm").assertIsDisplayed()
        rule.onNodeWithTag("sheet-confirm").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("native-details-sheet").assertDoesNotExist()''')
addition = '''
    @Test fun shellProgressDoesNotChangeOperationOrLeakIntoHero() {
        val raw = "停止守护\\nKill Switch: iptables -D OUTPUT\\nkill -9 1234"
        val data = fixture().copy(busy = true, operation = HomeOperation.Restart, message = raw)
        assertEquals(HomePhase.Processing, HomeLifecyclePresentation.phase(data))
        assertEquals("正在重启", HomeLifecyclePresentation.operationTitle(data))
        render(data)
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        rule.onNodeWithText(raw).assertDoesNotExist()
        rule.onNodeWithText("Kill Switch", substring = true).assertDoesNotExist()
        assertTrue("Busy hero must not expand for shell logs", rule.onNodeWithTag("home-hero").fetchSemanticsNode().boundsInRoot.height < 300f)
        snapshot("processing-restart")
    }

    @Test fun lifecycleAndFeedbackAreTruthfulWithoutInventingMetrics() {
        assertEquals(HomePhase.Stopped, HomeLifecyclePresentation.phase(CompactHomeData()))
        assertEquals(HomePhase.Running, HomeLifecyclePresentation.phase(fixture()))
        assertEquals(HomePhase.Processing, HomeLifecyclePresentation.phase(fixture().copy(operation = HomeOperation.Stop)))
        assertEquals("操作未完成，请查看详情", HomeLifecyclePresentation.feedback("Permission denied: /data/adb/hetu"))
        assertEquals("操作反馈已更新", HomeLifecyclePresentation.feedback("停止守护\\niptables -D OUTPUT"))
        assertNull(HomeLifecyclePresentation.feedback(" "))
        render(fixture().copy(busy = true, operation = HomeOperation.Stop))
        rule.onNodeWithTag("home-toggle").assertDoesNotExist()
        rule.onNodeWithTag("home-processing").assertIsDisplayed()
        snapshot("processing-stop")
    }

    @Test fun fullWidthStartReplacesStoppedSegments() {
        render(CompactHomeData())
        val start = rule.onNodeWithTag("home-toggle").fetchSemanticsNode().boundsInRoot.width
        val pill = rule.onNodeWithTag("home-control-pill").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Stopped start must fill its capsule", start >= pill - 9f)
        rule.onNodeWithTag("home-reload").assertDoesNotExist()
        rule.onNodeWithTag("home-restart").assertDoesNotExist()
    }
'''
s = s.rstrip()
if not s.endswith('}'): raise RuntimeError('Missing test class end')
sources[p] = s[:-1]+addition+'}\n'

# All transformations are prepared in memory before any source file is changed.
for path, text in sources.items(): (ROOT/path).write_text(text)
changed = list(sources)
(ROOT/'integration-evidence').mkdir(exist_ok=True)
(ROOT/'integration-evidence/ui2-migration.json').write_text(json.dumps({
    'changed_paths': changed, 'runtime_code_changed': False,
    'note': 'Presentation migration only. Compile and tests are independent gates.'
}, indent=2))
print('Applied reviewed UI2 migration:', len(changed), 'files')
