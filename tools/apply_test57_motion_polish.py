#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")

def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing marker: {label}")
    return text.replace(old, new, 1)

def section(text, start_marker, end_marker):
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return start, end, text[start:end]

build_path = "android-app/app/build.gradle.kts"
build = read(build_path)
build = replace_once(build, 'versionCode = 456', 'versionCode = 457', 'versionCode')
build = replace_once(build, 'versionName = "0.4.0-test.56"', 'versionName = "0.4.0-test.57"', 'versionName')
write(build_path, build)

styles_path = "android-app/app/src/main/res/values/styles.xml"
styles = read(styles_path)
if "BichenSharedAxisWindowAnimation" not in styles:
    styles = replace_once(
        styles,
        '        <item name="android:windowBackground">#F1F5F9</item>',
        '        <item name="android:windowBackground">#F1F5F9</item>\n'
        '        <item name="android:windowAnimationStyle">@style/BichenSharedAxisWindowAnimation</item>',
        'light route animation style',
    )
    styles = replace_once(
        styles,
        '        <item name="android:windowBackground">#121714</item>',
        '        <item name="android:windowBackground">#121714</item>\n'
        '        <item name="android:windowAnimationStyle">@style/BichenSharedAxisWindowAnimation</item>',
        'dark route animation style',
    )
    styles = replace_once(
        styles,
        '</resources>',
        '''    <style name="BichenSharedAxisWindowAnimation">
        <item name="android:activityOpenEnterAnimation">@anim/bichen_route_open_enter</item>
        <item name="android:activityOpenExitAnimation">@anim/bichen_route_open_exit</item>
        <item name="android:activityCloseEnterAnimation">@anim/bichen_route_close_enter</item>
        <item name="android:activityCloseExitAnimation">@anim/bichen_route_close_exit</item>
    </style>
</resources>''',
        'route animation style block',
    )
write(styles_path, styles)

anim_dir = "android-app/app/src/main/res/anim"
write(f"{anim_dir}/bichen_route_open_enter.xml", '''<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="280"
    android:interpolator="@android:interpolator/fast_out_slow_in">
    <translate android:fromXDelta="16%p" android:toXDelta="0%p" />
    <scale android:fromXScale="0.985" android:toXScale="1.0"
        android:fromYScale="0.985" android:toYScale="1.0"
        android:pivotX="50%" android:pivotY="50%" />
    <alpha android:fromAlpha="0.88" android:toAlpha="1.0" />
</set>
''')
write(f"{anim_dir}/bichen_route_open_exit.xml", '''<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="280"
    android:interpolator="@android:interpolator/fast_out_slow_in">
    <translate android:fromXDelta="0%p" android:toXDelta="-3%p" />
    <scale android:fromXScale="1.0" android:toXScale="0.96"
        android:fromYScale="1.0" android:toYScale="0.96"
        android:pivotX="50%" android:pivotY="50%" />
    <alpha android:fromAlpha="1.0" android:toAlpha="0.85" />
</set>
''')
write(f"{anim_dir}/bichen_route_close_enter.xml", '''<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="270"
    android:interpolator="@android:interpolator/fast_out_slow_in">
    <translate android:fromXDelta="-3%p" android:toXDelta="0%p" />
    <scale android:fromXScale="0.96" android:toXScale="1.0"
        android:fromYScale="0.96" android:toYScale="1.0"
        android:pivotX="50%" android:pivotY="50%" />
    <alpha android:fromAlpha="0.85" android:toAlpha="1.0" />
</set>
''')
write(f"{anim_dir}/bichen_route_close_exit.xml", '''<?xml version="1.0" encoding="utf-8"?>
<set xmlns:android="http://schemas.android.com/apk/res/android"
    android:duration="250"
    android:interpolator="@android:interpolator/fast_out_slow_in">
    <translate android:fromXDelta="0%p" android:toXDelta="18%p" />
    <scale android:fromXScale="1.0" android:toXScale="0.995"
        android:fromYScale="1.0" android:toYScale="0.995"
        android:pivotX="50%" android:pivotY="50%" />
    <alpha android:fromAlpha="1.0" android:toAlpha="0.92" />
</set>
''')

ref_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt"
ref = read(ref_path)
ref = ref.replace(
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 148.dp',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 164.dp'
)

start, end, _ = section(
    ref,
    '@Composable\nprivate fun RefNetworkIdentityCard',
    '\n@Composable\nprivate fun RefSpeedCard',
)
new_network = r'''@Composable
private fun RefNetworkIdentityCard(runtime: ProxyRuntimeSnapshot, connections: Int, modifier: Modifier) {
    val t = LocalBichenTokens.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var renderedLan by rememberSaveable { mutableStateOf(true) }
    var flipping by remember { mutableStateOf(false) }
    val flip = remember { Animatable(0f) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .97f else 1f, spring(dampingRatio = .74f, stiffness = 560f), label = "networkCardPress")
    val shape = RoundedCornerShape(20.dp)
    val valueColor = if (MaterialTheme.colorScheme.background.luminance() < .5f) t.textPrimary else Color(0xFF0F172A)

    Surface(
        modifier = modifier.height(112.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .94f else 1f }
            .clip(shape)
            .clickable(interactionSource = source, indication = null) {
                if (!flipping) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    scope.launch {
                        flipping = true
                        flip.animateTo(90f, androidx.compose.animation.core.tween(105, easing = androidx.compose.animation.core.FastOutSlowInEasing))
                        renderedLan = !renderedLan
                        flip.snapTo(-90f)
                        flip.animateTo(0f, spring(dampingRatio = .72f, stiffness = 520f))
                        flipping = false
                    }
                }
            },
        shape = shape,
        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (renderedLan) "LAN" else "WAN", color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp).background(Color(0xFFF1F5F9), CircleShape)
                        .border(.6.dp, Color.White.copy(alpha = .90f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.SwapHoriz, "切换 LAN/WAN", tint = Color(0xFF2563EB), modifier = Modifier.size(14.dp))
                }
            }
            Column(
                Modifier.fillMaxWidth().graphicsLayer {
                    rotationY = flip.value
                    cameraDistance = 18f * density
                    alpha = .94f + .06f * (1f - kotlin.math.abs(flip.value) / 90f)
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    if (renderedLan) runtime.lanAddress else runtime.wanAddress,
                    color = valueColor,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(20.dp),
                )
                Text(
                    if (renderedLan) "\${runtime.lanInterface} · $connections 连接"
                    else "\${countryEmoji(runtime.wanCountryCode)} \${runtime.wanRegion}",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(18.dp),
                )
            }
        }
    }
}
'''
ref = ref[:start] + new_network + ref[end:]

g_start, g_end, group = section(
    ref,
    '@Composable\nprivate fun RefGroupCard',
    '\n@Composable\nprivate fun RefInlineGroupExpansion',
)
group = group.replace('val shape = RoundedCornerShape(18.dp)', 'val shape = RoundedCornerShape(20.dp)', 1)
group = group.replace('.height(84.dp)', '.height(86.dp)', 1)
group = group.replace('.padding(horizontal = 11.dp, vertical = 9.dp)', '.padding(start = 14.dp, top = 11.dp, end = 13.dp, bottom = 10.dp)', 1)
group = group.replace('Modifier.fillMaxWidth().height(40.dp)', 'Modifier.fillMaxWidth().height(36.dp)', 1)
group = group.replace('Modifier.weight(1f).height(24.dp)', 'Modifier.weight(1f).height(22.dp)', 1)
ref = ref[:g_start] + group + ref[g_end:]

well_start, well_end, well = section(
    ref,
    '@Composable\nprivate fun RefInlineGroupExpansion',
    '\n@Composable\nprivate fun RefInlineNodeCard',
)
well = well.replace(
    'modifier = Modifier.fillMaxWidth(),',
    'modifier = Modifier.padding(top = 8.dp, bottom = 12.dp).fillMaxWidth(),',
    1,
)
well = well.replace(
    'Modifier.fillMaxWidth().background(wellBrush, shape).padding(12.dp),',
    'Modifier.fillMaxWidth().background(wellBrush, shape).padding(start = 14.dp, top = 16.dp, end = 14.dp, bottom = 14.dp),',
    1,
)
well = well.replace(
    'Text("切换落地节点", color = if (dark) t.textSecondary else Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Bold)',
    'Text("切换落地节点", color = if (dark) t.textSecondary else Color(0xFF64748B), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)',
    1,
)
well = well.replace(
    'Text("· 点击即生效", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Normal)',
    'Text("· 点击即生效", color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)',
    1,
)
ref = ref[:well_start] + well + ref[well_end:]

node_start, node_end, node = section(
    ref,
    '@Composable\nprivate fun RefInlineNodeCard',
    '\n@Composable\nprivate fun RefGroupCornerVisual',
)
node = node.replace('Box(modifier.height(62.dp))', 'Box(modifier.height(64.dp))', 1)
node = node.replace('.padding(horizontal = 10.dp, vertical = 8.dp)', '.padding(horizontal = 11.dp, vertical = 9.dp)', 1)
ref = ref[:node_start] + node + ref[node_end:]

panel_marker = '''androidx.compose.animation.AnimatedVisibility(
                                visible = expandedGroup != null,
                                enter = androidx.compose.animation.expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .78f, stiffness = 420f),
                                ) + androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .86f, stiffness = 520f),
                                ) + androidx.compose.animation.fadeOut(),'''
panel_replacement = '''androidx.compose.animation.AnimatedVisibility(
                                visible = expandedGroup != null,
                                enter = androidx.compose.animation.expandVertically(
                                    expandFrom = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .78f, stiffness = 420f),
                                    clip = false,
                                ) + androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.shrinkVertically(
                                    shrinkTowards = Alignment.Top,
                                    animationSpec = spring(dampingRatio = .86f, stiffness = 520f),
                                    clip = false,
                                ) + androidx.compose.animation.fadeOut(),'''
ref = replace_once(ref, panel_marker, panel_replacement, 'expanded group unclipped transition')

old_domino = '''onTestAll = {
                                            val pending = group.nodes.filter { testing[it.name] != true }
                                            if (pending.isNotEmpty()) scope.launch {
                                                pending.forEach { testing[it.name] = true }
                                                try {
                                                    pending.map { node ->
                                                        async {
                                                            try { delays[node.name] = repo.delay(node.name) }
                                                            catch (_: Exception) { delays[node.name] = -1L }
                                                        }
                                                    }.awaitAll()
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                } finally {
                                                    pending.forEach { testing.remove(it.name) }
                                                }
                                            }
                                        },'''
new_domino = '''onTestAll = {
                                            val pending = group.nodes.filter { testing[it.name] != true }
                                            if (pending.isNotEmpty()) scope.launch {
                                                try {
                                                    val wave = pending.mapIndexed { index, node ->
                                                        async {
                                                            delay(index * 30L)
                                                            testing[node.name] = true
                                                            try { repo.delay(node.name) }
                                                            catch (_: Exception) { -1L }
                                                        }
                                                    }
                                                    pending.forEachIndexed { index, node ->
                                                        delays[node.name] = wave[index].await()
                                                        delay(32L)
                                                        testing.remove(node.name)
                                                    }
                                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                                } finally {
                                                    pending.forEach { testing.remove(it.name) }
                                                }
                                            }
                                        },'''
ref = replace_once(ref, old_domino, new_domino, 'domino full latency wave')

log_start, log_end, log_sheet = section(
    ref,
    '@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun RefInfoBottomSheet',
    '\nprivate fun refFormatElapsed',
)
log_sheet = log_sheet.replace('.fillMaxHeight(.78f)', '.fillMaxHeight(.80f)', 1)
log_sheet = log_sheet.replace(
    '.background(if (terminal) Color(0xFF0F172A) else t.controlBackground.copy(alpha = .54f), RoundedCornerShape(16.dp))\n'
    '                    .border(if (terminal) .8.dp else 0.dp, if (terminal) Color(0xFF334155) else Color.Transparent, RoundedCornerShape(16.dp))\n'
    '                    .padding(14.dp)',
    '.background(if (terminal) Color.Transparent else t.controlBackground.copy(alpha = .54f), if (terminal) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp))\n'
    '                    .padding(if (terminal) 4.dp else 14.dp)',
    1,
)
log_sheet = log_sheet.replace(
    'modifier = Modifier.size(34.dp).background(if (terminal) Color.White.copy(alpha = .07f) else t.controlBackground.copy(alpha = .72f), CircleShape),',
    'modifier = Modifier.size(width = 38.dp, height = 30.dp).background(if (terminal) Color.White.copy(alpha = .07f) else t.controlBackground.copy(alpha = .72f), RoundedCornerShape(12.dp)),',
    1,
)
ref = ref[:log_start] + log_sheet + ref[log_end:]
write(ref_path, ref)

apps_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAppSelectionActivity.kt"
apps = read(apps_path)
if 'import androidx.compose.foundation.lazy.itemsIndexed' not in apps:
    apps = apps.replace('import androidx.compose.foundation.lazy.items\n', 'import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.itemsIndexed\n')
if 'import androidx.compose.ui.geometry.Offset' not in apps:
    apps = apps.replace('import androidx.compose.ui.graphics.Color\n', 'import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.geometry.Offset\n')
if 'import androidx.compose.ui.platform.LocalDensity' not in apps:
    apps = apps.replace('import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity\n')
apps = apps.replace(
    'androidx.compose.animation.core.tween(1500, easing = androidx.compose.animation.core.LinearEasing)',
    'androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)',
    1,
)
old_brush = 'val shimmerBrush = Brush.horizontalGradient(listOf(t.controlBackground.copy(alpha = .46f), if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .92f), t.controlBackground.copy(alpha = .46f)), startX = shimmerX * 360f, endX = (shimmerX + 1f) * 360f)'
new_brush = '''val shimmerBrush = Brush.linearGradient(
        listOf(t.controlBackground.copy(alpha = .46f), if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .92f), t.controlBackground.copy(alpha = .46f)),
        start = Offset(shimmerX * 420f, -120f),
        end = Offset((shimmerX + 1f) * 420f, 260f),
    )
    val listReveal by animateFloatAsState(
        targetValue = if (apps.isEmpty()) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "appListReveal",
    )
    val revealOffsetPx = with(LocalDensity.current) { 8.dp.toPx() }'''
apps = replace_once(apps, old_brush, new_brush, 'app shimmer brush')
apps = replace_once(
    apps,
    '''        if (loadingApps && apps.isEmpty()) {
            item("loading-skeleton") {
                Column''',
    '''        item("loading-skeleton") {
            androidx.compose.animation.AnimatedVisibility(
                visible = loadingApps && apps.isEmpty(),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200)),
            ) {
                Column''',
    'app skeleton visibility',
)
apps = replace_once(
    apps,
    '        items(visible, key = { it.packageName }) { app ->',
    '        itemsIndexed(visible, key = { _, app -> app.packageName }) { index, app ->',
    'app indexed list',
)
apps = replace_once(
    apps,
    'modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable {',
    '''modifier = Modifier.fillMaxWidth()
                    .graphicsLayer {
                        alpha = listReveal
                        translationY = (1f - listReveal) * revealOffsetPx * (1f + (index.coerceAtMost(6) * .03f))
                    }
                    .clip(RoundedCornerShape(18.dp)).clickable {''',
    'app list reveal modifier',
)
apps = apps.replace(
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 40.dp',
    1,
)
write(apps_path, apps)

web_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyWebUiActivity.kt"
web = read(web_path)
if 'import androidx.compose.ui.geometry.Offset' not in web:
    web = web.replace('import androidx.compose.ui.Alignment\n', 'import androidx.compose.ui.Alignment\nimport androidx.compose.ui.geometry.Offset\n')
if 'import androidx.compose.ui.graphics.Brush' not in web:
    web = web.replace('import androidx.compose.ui.Modifier\n', 'import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.graphicsLayer\n')
web = replace_once(
    web,
    '    var forceRepair by remember { mutableStateOf(false) }\n',
    '''    var forceRepair by remember { mutableStateOf(false) }
    val skeletonTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "webSkeletonShimmer")
    val skeletonX by skeletonTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "webSkeletonShimmerX",
    )
    val skeletonBrush = Brush.linearGradient(
        listOf(tokens.controlBackground.copy(alpha = .52f), Color.White.copy(alpha = .78f), tokens.controlBackground.copy(alpha = .52f)),
        start = Offset(skeletonX * 460f, -140f),
        end = Offset((skeletonX + 1f) * 460f, 280f),
    )
    val webReady = !preparing && progress >= 100 && pageError.isBlank()
    val webReveal by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (webReady) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(200),
        label = "webContentReveal",
    )
''',
    'web shimmer state',
)
web = replace_once(
    web,
    'modifier = Modifier.fillMaxSize(),\n            )',
    '''modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = webReveal
                    translationY = (1f - webReveal) * 8f
                },
            )''',
    'webview reveal modifier',
)
web = replace_once(
    web,
    '''            if ((preparing || progress < 100) && pageError.isBlank()) {
                Column(''',
    '''            androidx.compose.animation.AnimatedVisibility(
                visible = (preparing || progress < 100) && pageError.isBlank(),
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(120)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
            ) {
                Column(''',
    'web skeleton animated visibility',
)
skeleton_start = web.index('androidx.compose.animation.AnimatedVisibility(\n                visible = (preparing || progress < 100)')
skeleton_end = web.index('\n            if (pageError.isNotBlank())', skeleton_start)
sk = web[skeleton_start:skeleton_end]
sk = sk.replace('background(tokens.controlBackground, RoundedCornerShape(7.dp))', 'background(skeletonBrush, RoundedCornerShape(7.dp))')
sk = sk.replace('background(tokens.controlBackground.copy(alpha = .72f), RoundedCornerShape(5.dp))', 'background(skeletonBrush, RoundedCornerShape(5.dp))')
sk = sk.replace('background(tokens.controlBackground, RoundedCornerShape(11.dp))', 'background(skeletonBrush, RoundedCornerShape(11.dp))')
sk = sk.replace('background(tokens.controlBackground, RoundedCornerShape(6.dp))', 'background(skeletonBrush, RoundedCornerShape(6.dp))')
sk = sk.replace('background(tokens.controlBackground.copy(alpha = .64f), RoundedCornerShape(4.dp))', 'background(skeletonBrush, RoundedCornerShape(4.dp))')
web = web[:skeleton_start] + sk + web[skeleton_end:]
write(web_path, web)

yaml_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxySubscriptionActivity.kt"
yaml = read(yaml_path)
if 'import androidx.compose.ui.platform.LocalDensity' not in yaml:
    yaml = yaml.replace('import androidx.compose.ui.platform.LocalContext\n', 'import androidx.compose.ui.platform.LocalContext\nimport androidx.compose.ui.platform.LocalDensity\n')
yaml = replace_once(
    yaml,
    '                    val lineCount = remember(editorText) { maxOf(1, editorText.count { it == \'\\n\' } + 1) }\n',
    '''                    val lineCount = remember(editorText) { maxOf(1, editorText.count { it == '\\n' } + 1) }
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
''',
    'yaml ime visibility',
)
bottom_marker = '''                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                            .shadow(18.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .10f), spotColor = Color(0xFF0F172A).copy(alpha = .14f)),'''
yaml = replace_once(
    yaml,
    bottom_marker,
    '''                    androidx.compose.animation.AnimatedVisibility(
                        visible = !imeVisible,
                        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 2 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(140)) { it / 2 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                    ) {
                        Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)
                            .shadow(18.dp, RoundedCornerShape(28.dp), clip = false, ambientColor = Color(0xFF0F172A).copy(alpha = .10f), spotColor = Color(0xFF0F172A).copy(alpha = .14f)),''',
    'yaml dock animated visibility open',
)
needle = '''                            }
                        }
                    }
                }
            }
        }
    }
}

private fun subscriptionSummary'''
replacement = '''                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

private fun subscriptionSummary'''
yaml = replace_once(yaml, needle, replacement, 'yaml dock animated visibility close')
write(yaml_path, yaml)

adb_path = "android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdblockChainActivity.kt"
adb = read(adb_path)
adb = replace_once(
    adb,
    '    var busy by remember { mutableStateOf(false) }\n    var notice by remember { mutableStateOf("") }\n',
    '''    var busy by remember { mutableStateOf(false) }
    var updatingRules by remember { mutableStateOf(false) }
    var updateSuccess by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
''',
    'adblock morph state',
)
adb = adb.replace(
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp',
    'bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 80.dp',
    1,
)
update_start = adb.index('        item("update") {')
update_end = adb.index('\n        if (notice.isNotBlank()', update_start)
new_update = r'''        item("update") {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                contentAlignment = Alignment.Center,
            ) {
                val targetWidth = if (updatingRules) 44.dp else maxWidth
                val buttonWidth by androidx.compose.animation.core.animateDpAsState(
                    targetValue = targetWidth,
                    animationSpec = spring(dampingRatio = .72f, stiffness = 430f),
                    label = "adblockUpdateMorphWidth",
                )
                val container = if (updateSuccess) Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                Button(
                    onClick = {
                        if (busy) return@Button
                        scope.launch {
                            busy = true
                            updatingRules = true
                            updateSuccess = false
                            val result = runCatching { adController.updateRules() }
                            updatingRules = false
                            result
                                .onSuccess {
                                    notice = "$it；代理运行中时请重启代理应用新快照"
                                    revision++
                                    updateSuccess = true
                                    delay(900)
                                    updateSuccess = false
                                }
                                .onFailure { notice = it.message ?: "规则更新失败" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.width(buttonWidth).height(44.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(horizontal = if (updatingRules) 0.dp else 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = container,
                        contentColor = Color.White,
                        disabledContainerColor = container,
                        disabledContentColor = Color.White,
                    ),
                ) {
                    when {
                        updatingRules -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        updateSuccess -> {
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("更新完成", fontWeight = FontWeight.Bold)
                        }
                        else -> {
                            Icon(Icons.Rounded.Sync, null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("更新广告规则", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
'''
adb = adb[:update_start] + new_update + adb[update_end:]
metric_start, metric_end, metric = section(
    adb,
    '@Composable\nprivate fun ChainMetric',
    '\n@Composable\nprivate fun ChainSectionLabel',
)
metric = metric.replace(
    '''    val accent = when (label) {
        "规则源" -> Color(0xFF059669)
        "本次命中" -> Color(0xFF2563EB)
        else -> Color(0xFF002FA7)
    }''',
    '    val accent = if (dark) Color(0xFF60A5FA) else Color(0xFF2563EB)',
    1,
)
metric = metric.replace(
    'color = if (dark) t.controlBackground.copy(alpha = .72f) else Color(0xFFF8FAFC),',
    'color = if (dark) t.controlBackground.copy(alpha = .72f) else Color.White,',
    1,
)
metric = metric.replace(
    'shadowElevation = if (dark) 0.dp else 1.dp,',
    'shadowElevation = if (dark) 0.dp else 3.dp,',
    1,
)
adb = adb[:metric_start] + metric + adb[metric_end:]
write(adb_path, adb)

print("test57 motion polish applied")
