from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = p.read_text()

old_root = '''                .then(if (!liquid || page == RefProxyPage.Panel) Modifier.hazeSource(haze) else Modifier)\n                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),'''
new_root = '''                .then(if (!liquid) Modifier.hazeSource(haze) else Modifier)\n                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier),'''
if old_root in s:
    s = s.replace(old_root, new_root, 1)

header_start = s.index('private fun RefPanelGlassHeader(')
header_end = s.index('private fun RefPanelHeaderAction(', header_start)
header = s[header_start:header_end]

if 'Modifier.background(shellBrush)' not in header:
    start_marker = '''    val t = LocalHetuTokens.current\n    val scheme = MaterialTheme.colorScheme\n    val dark = scheme.background.luminance() < .5f\n    // Panel header deliberately uses Haze only. RuntimeShader here crashes on some OEM GPUs.\n'''
    start = s.index(start_marker, header_start)
    box_marker = '''\n\n    Box(\n        Modifier\n            .fillMaxWidth()'''
    end = s.index(box_marker, start)
    replacement = '''    val t = LocalHetuTokens.current\n    val scheme = MaterialTheme.colorScheme\n    val dark = scheme.background.luminance() < .5f\n    // Panel header intentionally avoids both RuntimeShader and Haze on affected OEM GPUs.\n    // The glass look is drawn from transparent layered highlights only, so no rectangular\n    // backdrop sampling surface can leak through as a giant white block.\n    val runtimeLiquid = false\n    val shape = RoundedCornerShape(28.dp)\n    val shellBrush = if (dark) {\n        Brush.verticalGradient(\n            listOf(\n                scheme.surface.copy(alpha = .34f),\n                Color.White.copy(alpha = .035f),\n                scheme.surface.copy(alpha = .18f),\n            ),\n        )\n    } else {\n        Brush.verticalGradient(\n            listOf(\n                Color.White.copy(alpha = .20f),\n                Color(0xFFDCE8F5).copy(alpha = .085f),\n                Color.White.copy(alpha = .035f),\n            ),\n        )\n    }\n    val edgeColor = if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .24f)\n    val liquidShellModifier = Modifier.background(shellBrush)\n'''
    s = s[:start] + replacement + s[end:]

s = s.replace('.shadow(9.dp, shape, clip = false)', '.shadow(7.dp, shape, clip = false)', 1)
s = s.replace(
    '''            .border(\n                if (runtimeLiquid) .45.dp else .7.dp,\n                if (dark) Color.White.copy(alpha = .09f) else Color.White.copy(alpha = .20f),\n                shape,\n            ),''',
    '''            .border(.7.dp, edgeColor, shape),''',
    1,
)

p.write_text(s)

# Hard assertions: only the panel header is required to contain no backdrop/Haze sampling.
s = p.read_text()
a = s.index('private fun RefPanelGlassHeader(')
b = s.index('private fun RefPanelHeaderAction(', a)
panel = s[a:b]
assert 'hazeEffect(' not in panel
assert 'HazeStyle(' not in panel
assert 'HazeTint(' not in panel
assert 'drawBackdrop(' not in panel
assert 'liquidGlassLens(' not in panel
assert 'Modifier.background(shellBrush)' in panel
assert old_root not in s
assert new_root in s
print('test41 panel header: no Haze/RuntimeShader backdrop sampling')
