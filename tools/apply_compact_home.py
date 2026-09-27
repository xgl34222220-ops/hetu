#!/usr/bin/env python3
"""One-time, hash-checked test.92 UI integration. No branch resets or core edits.

CI commits the resulting Kotlin/Gradle source only after tests and APK validation.
Re-running on the integrated source validates the marker without replaying changes.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = '92707c5850e0b3eb3cea05f868a4cfa6694ad48e'
APP = ROOT / 'android-app/app'
SOURCE = APP / 'src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt'
GRADLE = APP / 'build.gradle.kts'
MARKER = '// compact-home-test92-ui1: presentation-only adapter'
EXPECTED_BLOB = 'f606d2e73dee8ceda4b49c84bfc1b1688e8dd380'

def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)

def source_hashes():
    tracked = git('ls-files').decode().splitlines()
    protected = [p for p in tracked if p.startswith(('native/', 'android-app/app/src/main/assets/'))
                 or (p.startswith('android-app/app/src/main/java/') and p.endswith('.java'))
                 or p.endswith(('ProxyComposeController.kt', 'ProxyRuntimeInspector.kt', 'ProxyDashboardRepository.kt'))]
    return {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in protected if (ROOT / p).is_file()}

before = source_hashes()
raw = SOURCE.read_bytes()
s = raw.decode()
if MARKER not in s:
    blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
    if blob != EXPECTED_BLOB:
        raise SystemExit(f'Wrong ReferenceProxyActivity baseline: {blob}; refusing to mix branches')
    start = s.index('@OptIn(ExperimentalHazeMaterialsApi::class)\n@Composable\nprivate fun refHomeLiquidModifier(')
    finish = s.index('@Composable\nprivate fun RefActionText(', start)
    old = s[start:finish]
    declaration = '@Composable\nprivate fun RefHome('
    home_start = old.index(declaration)
    header_end = old.index('\n) {', home_start) + len('\n) {')
    header = old[home_start:header_end].replace('    testing: Boolean,', '    testing: Boolean,\n    refreshing: Boolean,')
    body = r'''
    // compact-home-test92-ui1: presentation-only adapter
    val context = LocalContext.current
    val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val total = tracked.sumOf { it.total }.takeIf { it > 0L } ?: cachedSubscription.total
    val used = if (tracked.isNotEmpty()) tracked.sumOf { it.used } else cachedSubscription.used
    var webUiOpen by remember { mutableStateOf(false) }
    CompactHomeDashboard(
        data = CompactHomeData(
            running = state.running, busy = operation.isNotBlank(), refreshing = refreshing,
            testing = testing, uptimeSeconds = runtime.elapsedSeconds,
            core = state.core, mode = state.mode, config = state.config,
            message = operation.ifBlank { message }, pendingSettings = state.runtimeSettingsPending,
            delays = siteDelays, wan = runtime.wanAddress, lan = runtime.lanAddress,
            countryCode = runtime.wanCountryCode, region = runtime.wanRegion, lanInterface = runtime.lanInterface,
            up = upRate, down = downRate, used = used, total = total,
            memory = runtime.rssBytes.takeIf { it > 0L } ?: state.memoryBytes,
            cpu = cpuPercent, connections = if (state.panelReady) state.connections.size else cachedConnections,
            diagnosticLoading = diagnosticLoading,
        ),
        onRefresh = onRefresh, onToggle = onToggle, onReload = onReload, onRestart = onRestart,
        onDelay = onDelay, onWebUi = { webUiOpen = true }, onLog = onLog,
        onSubscription = onSubscription, onConnections = onConnections, onSettings = onSettings,
        onDiagnostics = onDiagnostics,
        onAdblock = { context.startActivity(Intent(context, ProxyAdblockChainActivity::class.java)) },
    )
    if (webUiOpen) CompactHomeWebUiDialog { webUiOpen = false }
}

'''
    wrapper = header + body
    # Keep any old helpers genuinely referenced by another page; remove unreachable home-only code.
    declarations = list(re.finditer(r'(?m)^(?:(?:@[^\n]+\n)+)?private fun (\w+)\(', old))
    blocks = {m.group(1): old[m.start():declarations[i + 1].start() if i + 1 < len(declarations) else len(old)]
              for i, m in enumerate(declarations) if m.group(1) != 'RefHome'}
    outside = s[:start] + wrapper + s[finish:]
    kept = set()
    while True:
        needed = {name for name in blocks if re.search(r'\b' + re.escape(name) + r'\s*\(', outside)} - kept
        if not needed:
            break
        for name in needed:
            outside += blocks[name]
        kept |= needed
    preserved = ''.join(blocks[name] for name in blocks if name in kept)
    s = s[:start] + wrapper + preserved + s[finish:]
    anchor = '                    testing = testing,\n                    hazeState = haze,'
    if s.count(anchor) != 1:
        raise SystemExit('Expected one live home call; refusing ambiguous integration')
    s = s.replace(anchor, '                    testing = testing,\n                    refreshing = homeRefreshing,\n                    hazeState = haze,', 1)
    # Same backdrop for the home and its glass dock; no flash to the old grey.
    s = s.replace('Color(0xFFF1F5F9)\n    }\n    Box(Modifier.fillMaxSize().background(shellBackground))',
                  'Color(0xFFF4F6F9)\n    }\n    Box(Modifier.fillMaxSize().background(shellBackground))', 1)
    SOURCE.write_text(s)
    print('Removed unreachable legacy home helpers:', ', '.join(sorted(set(blocks) - kept)))
else:
    print('Compact home adapter already integrated; not replaying baseline edits')

s = GRADLE.read_text()
if 'versionName = "0.4.0-test.92"' in s:
    s = s.replace('versionName = "0.4.0-test.92"', 'versionName = "0.4.0-test.92-ui.1"', 1)
    s = s.replace('versionCode = 492', 'versionCode = 1001', 1)
if 'versionName = "0.4.0-test.92-ui.1"' not in s:
    raise SystemExit('Unexpected version baseline; not overwriting it')
if '// compact-home-render-tests' not in s:
    s = s.replace('    buildFeatures {', '''    // compact-home-render-tests
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
        }
    }

    buildFeatures {''', 1)
    s += '''
// UI-only validation; no runtime/network dependency changes.
dependencies {
    testImplementation(platform("androidx.compose:compose-bom:2026.03.00"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
'''
GRADLE.write_text(s)
after = source_hashes()
assert before == after, 'UI integration changed protected core files'
for path, digest in before.items():
    original = git('show', BASE + ':' + path)
    assert hashlib.sha256(original).hexdigest() == digest, f'Protected file differs from approved test.92: {path}'
report = ROOT / 'compact-home-validation.json'
report.write_text(json.dumps({'baseline': BASE, 'protected_files_unchanged': len(before),
    'source': str(SOURCE.relative_to(ROOT)), 'version': '0.4.0-test.92-ui.1', 'versionCode': 1001,
    'truth': 'Source invariants only. Compile, rendered tests and device results are separate.'}, indent=2))
print(report.read_text())
