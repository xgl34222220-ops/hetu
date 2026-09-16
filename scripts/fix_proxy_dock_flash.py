from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ui/BichenGlassDock.kt')
s = path.read_text()

old = '''            indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f),
            indicatorBorderColor = Color.White.copy(alpha = if (dark) .18f else .46f),
            indicatorShadow = 3.dp,
            selectedColor = scheme.primary,
            unselectedColor = scheme.onSurfaceVariant.copy(alpha = .90f),
            liquidGlass = activeGlass,
            indicatorBackdrop = dockSurfaceBackdrop.takeIf { runtimeLiquid },
            dark = dark,
'''
new = '''            indicatorColor = scheme.primary.copy(alpha = if (dark) .28f else .16f),
            indicatorBorderColor = Color.White.copy(alpha = if (dark) .18f else .46f),
            indicatorShadow = 3.dp,
            selectedColor = scheme.primary,
            unselectedColor = scheme.onSurfaceVariant.copy(alpha = .90f),
            liquidGlass = activeGlass,
            // Keep LuoShu's outer RuntimeShader glass, but force the moving selection lens
            // through LuoShu's own non-nested fallback path. On some HyperOS/GPU stacks,
            // nesting drawBackdrop inside a layerBackdrop shell flashes a wide white strip
            // exactly when the indicator travels back to Home.
            indicatorBackdrop = null,
            dark = dark,
'''

if old not in s:
    if 'indicatorBackdrop = null,' in s:
        print('dock flash fix already applied')
    else:
        raise SystemExit('expected dock indicator block not found')
else:
    s = s.replace(old, new, 1)
    path.write_text(s)
    print('disabled nested moving-lens backdrop; outer LuoShu glass preserved')
