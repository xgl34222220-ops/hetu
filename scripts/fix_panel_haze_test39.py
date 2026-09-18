from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
s = p.read_text(encoding='utf-8')

old_source = ".then(if (!liquid) Modifier.hazeSource(haze) else Modifier)\n                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier)"
new_source = ".then(if (!liquid || page == RefProxyPage.Panel) Modifier.hazeSource(haze) else Modifier)\n                .then(if (liquid) Modifier.layerBackdrop(liquidBackdrop) else Modifier)"
if old_source not in s and new_source not in s:
    raise SystemExit('shell backdrop source pattern not found')
s = s.replace(old_source, new_source, 1)

old_runtime = "    val runtimeLiquid = backdrop != null && isRuntimeShaderSupported()\n"
new_runtime = "    // Panel header deliberately uses Haze only. RuntimeShader here crashes on some OEM GPUs.\n    val runtimeLiquid = false\n"
if old_runtime in s:
    s = s.replace(old_runtime, new_runtime, 1)
elif new_runtime not in s:
    raise SystemExit('panel runtime flag pattern not found')

start_marker = "    val liquidShellModifier = if (runtimeLiquid) {\n"
end_marker = "\n\n    Box(\n"
start = s.find(start_marker, s.find('private fun RefPanelGlassHeader'))
if start == -1:
    safe_marker = "    val liquidShellModifier = Modifier.then(hazeModifier).background(fallbackBrush)\n"
    if safe_marker not in s:
        raise SystemExit('panel liquid modifier start not found')
else:
    end = s.find(end_marker, start)
    if end == -1:
        raise SystemExit('panel liquid modifier end not found')
    replacement = "    val liquidShellModifier = Modifier.then(hazeModifier).background(fallbackBrush)\n"
    s = s[:start] + replacement + s[end:]

# Always pass null for the panel-specific runtime backdrop. The dock still receives the real backdrop.
s = s.replace(
    "                    backdrop = liquidBackdrop.takeIf { liquid },\n                    onRefreshState = { scope.launch { refresh() } },",
    "                    backdrop = null,\n                    onRefreshState = { scope.launch { refresh() } },",
    1,
)

p.write_text(s, encoding='utf-8')

panel_start = s.index('private fun RefPanelGlassHeader')
panel_end = s.index('@Composable\nprivate fun RefPanelHeaderAction', panel_start)
panel = s[panel_start:panel_end]
if 'drawBackdrop(' in panel or 'liquidGlassLens(' in panel or 'layerBackdrop(' in panel:
    raise SystemExit('RuntimeShader/backdrop call still present in panel header')
if 'Modifier.hazeEffect' not in panel:
    raise SystemExit('Haze glass missing from panel header')
if 'val runtimeLiquid = false' not in panel:
    raise SystemExit('panel runtime shader is not hard-disabled')
print('test39: panel header now uses Haze-only stable glass')
