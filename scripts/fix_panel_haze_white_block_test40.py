from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = path.read_text()

if 'import dev.chrisbanes.haze.HazeStyle\n' not in s:
    s = s.replace(
        'import dev.chrisbanes.haze.HazeState\n',
        'import dev.chrisbanes.haze.HazeState\nimport dev.chrisbanes.haze.HazeStyle\nimport dev.chrisbanes.haze.HazeTint\n',
    )

old = '''    val shape = RoundedCornerShape(28.dp)
    val shellTint = if (dark) scheme.surface.copy(alpha = .35f) else Color.White.copy(alpha = .36f)
    val fallbackBrush = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f), Color.White.copy(alpha = .035f)))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .23f), Color.White.copy(alpha = .085f)))
    }
    val hazeModifier = if (!runtimeLiquid) {
        Modifier.hazeEffect(state = hazeState, style = HazeMaterials.ultraThin()) {
            blurRadius = 28.dp
            noiseFactor = .016f
        }
    } else Modifier
    val liquidShellModifier = Modifier.then(hazeModifier).background(fallbackBrush)
'''
new = '''    val shape = RoundedCornerShape(28.dp)
    // Use a custom page-matched blur style here. The material preset adds too much white tint
    // on this very light page and turns the whole header into a large white slab.
    val panelBase = if (dark) scheme.background else Color(0xFFF1F5F9)
    val panelTint = if (dark) Color.White.copy(alpha = .025f) else Color.White.copy(alpha = .035f)
    val panelGlassStyle = HazeStyle(
        backgroundColor = panelBase,
        tint = HazeTint(panelTint),
        blurRadius = 22.dp,
        noiseFactor = .008f,
    )
    val glassSheen = if (dark) {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .055f), Color.Transparent))
    } else {
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .075f), Color.Transparent))
    }
    val hazeModifier = Modifier.hazeEffect(state = hazeState, style = panelGlassStyle)
    val liquidShellModifier = Modifier.then(hazeModifier).background(glassSheen)
'''
if old not in s:
    raise SystemExit('panel haze block not found')
s = s.replace(old, new, 1)

s = s.replace('.shadow(14.dp, shape, clip = false)', '.shadow(9.dp, shape, clip = false)', 1)
s = s.replace(
    'if (dark) Color.White.copy(alpha = .11f) else Color.White.copy(alpha = .30f),',
    'if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .20f),',
    1,
)
s = s.replace(
    "listOf(Color.White.copy(alpha = .34f), Color.White.copy(alpha = .14f))",
    "listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .055f))",
    1,
)
s = s.replace(
    '.border(.55.dp, Color.White.copy(alpha = if (dark) .12f else .34f), shape)',
    '.border(.55.dp, Color.White.copy(alpha = if (dark) .10f else .22f), shape)',
    1,
)
s = s.replace(
    "listOf(Color.White.copy(alpha = .18f), Color.White.copy(alpha = .065f))",
    "listOf(Color.White.copy(alpha = .085f), Color.White.copy(alpha = .028f))",
    1,
)
s = s.replace(
    'val trackBorder = if (dark) Color.White.copy(alpha = .085f) else Color.White.copy(alpha = .24f)',
    'val trackBorder = if (dark) Color.White.copy(alpha = .075f) else Color.White.copy(alpha = .15f)',
    1,
)
s = s.replace(
    'Color.White.copy(alpha = if (dark) .09f else .30f),',
    'Color.White.copy(alpha = if (dark) .07f else .16f),',
    1,
)
s = s.replace(
    '.border(.6.dp, Color.White.copy(alpha = if (dark) .14f else .38f), indicatorShape)',
    '.border(.6.dp, Color.White.copy(alpha = if (dark) .12f else .24f), indicatorShape)',
    1,
)

# Hard guards for this regression: only inspect the header itself. Other composables may
# legitimately still use the material Haze preset.
a = s.index('private fun RefPanelGlassHeader(')
b = s.index('@Composable\nprivate fun RefPanelHeaderAction', a)
block = s[a:b]
assert 'HazeMaterials.ultraThin()' not in block
assert 'HazeStyle(' in block and 'HazeTint(panelTint)' in block
assert 'Modifier.drawBackdrop(' not in block
assert 'liquidGlassLens(' not in block

path.write_text(s)
