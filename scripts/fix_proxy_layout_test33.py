from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = path.read_text()


def one(old: str, new: str, label: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f'missing layout block: {label}')
    s = s.replace(old, new, 1)


# 1) Cold blue-gray canvas in light mode. Cards stay white so hierarchy comes from
# surface contrast instead of wireframe-style gray outlines.
one(
'''    val shellBackground = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        LocalBichenTokens.current.pageBackground
    } else {
        Color(0xFFF4F6F9)
    }''',
'''    val shellBackground = if (MaterialTheme.colorScheme.background.luminance() < .5f) {
        LocalBichenTokens.current.pageBackground
    } else {
        Color(0xFFF1F5F9)
    }''',
'light shell background',
)
old_page_bg = 'Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF4F6F9))'
if s.count(old_page_bg) != 2:
    raise SystemExit(f'expected 2 tools/settings light backgrounds, found {s.count(old_page_bg)}')
s = s.replace(old_page_bg, 'Modifier.fillMaxSize().background(if (dark) t.pageBackground else Color(0xFFF1F5F9))')

# 2) Hero status must never share a baseline with a long duration string. The duration
# gets its own physical line, eliminating overlap on narrow/high-density devices.
one(
'''                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Spacer(Modifier.width(9.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        if (state.running) "运行中" else "已停止",
                                        color = t.textPrimary,
                                        fontSize = 20.sp,
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                    Text(
                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",
                                        color = t.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.alignByBaseline(),
                                    )
                                }
                            }''',
'''                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(9.dp).background(if (state.running) t.success else t.danger, CircleShape))
                                Spacer(Modifier.width(9.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        if (state.running) "运行中" else "已停止",
                                        color = t.textPrimary,
                                        fontSize = 20.sp,
                                        lineHeight = 24.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        if (state.running) refDuration(runtime.elapsedSeconds) else "等待启动",
                                        color = t.textMuted,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                    )
                                }
                            }''',
'hero status duration layout',
)

# 3) The four home metric cards must be allowed to grow with text. 96dp fixed-height
# cards clipped provider totals and LAN metadata on some font scales/resolutions.
fixed = '.height(96.dp)'
if s.count(fixed) != 4:
    raise SystemExit(f'expected 4 fixed metric card heights, found {s.count(fixed)}')
s = s.replace(fixed, '.heightIn(min = 100.dp)')

# Give the floating white surfaces a very soft elevation instead of relying on borders.
one(
'''    Surface(onClick = onClick, enabled = !testing, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp) {''',
'''    Surface(onClick = onClick, enabled = !testing, shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp) {''',
'latency surface elevation',
)
one(
'''        color = t.cardBackground,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),''',
'''        color = t.cardBackground,
        shadowElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(13.dp),''',
'network surface elevation',
)
metric_surface = '''Surface(modifier = modifier.heightIn(min = 100.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 0.dp)'''
if s.count(metric_surface) != 3:
    raise SystemExit(f'expected 3 compact metric surfaces, found {s.count(metric_surface)}')
s = s.replace(metric_surface, '''Surface(modifier = modifier.heightIn(min = 100.dp), shape = RoundedCornerShape(18.dp), color = t.cardBackground, shadowElevation = 1.dp)''')

# The hero is a surface, not a wireframe card. Remove the hairline outline.
one(
'''                color = t.heroBackground,
                border = BorderStroke(.7.dp, scheme.primary.copy(alpha = .10f)),
                shadowElevation = 0.dp,''',
'''                color = t.heroBackground,
                shadowElevation = 1.dp,''',
'hero outline removal',
)

# 4) Strategy cards should read as compact 16:9-ish tiles, not tall near-squares.
one(
'''        modifier
            .height(92.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .shadow(if (expanded) 3.dp else 1.dp, shape, clip = false)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),''',
'''        modifier
            .height(86.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (pressed) .90f else 1f }
            .shadow(if (expanded) 3.dp else 1.dp, shape, clip = false)
            .background(if (expanded) t.selectionBackground else t.cardBackground, shape)
            .clip(shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),''',
'compact strategy group card',
)

# 5) Port details are already routed through RefInfoBottomSheet. Keep all informational
# sheets visually consistent and avoid the old heavy solid-blue close button look.
one(
'''            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }''',
'''            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = t.controlBackground,
                    contentColor = t.textPrimary,
                ),
            ) {
                Text(actionLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }''',
'info sheet tonal action',
)

# Guard the two design invariants that matter for this pass.
if 'AlertDialog(' in s:
    raise SystemExit('legacy centered AlertDialog remains in proxy activity')
if 'Color(0xFFF1F5F9)' not in s:
    raise SystemExit('light page background was not applied')
if '.height(96.dp)' in s:
    raise SystemExit('fixed 96dp home metric card remains')

path.write_text(s)
print('Applied test33 proxy layout polish without touching BichenGlassDock.kt')
