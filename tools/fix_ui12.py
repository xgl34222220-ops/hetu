#!/usr/bin/env python3
"""UI12 follow-up: public Material3 motion API, no reflection/internal-access suppression."""
from pathlib import Path
import json
ROOT=Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
p=Path('android-app/app/build.gradle.kts');s=p.read_text()
old='implementation("androidx.compose.material3:material3")'
new='implementation("androidx.compose.material3:material3:1.5.0-alpha16")'
if old in s:
    assert s.count(old)==1
    s=s.replace(old,new,1);p.write_text(s)
else:assert new in s
# 1.4 stable makes Expressive motion customization internal. alpha16 exposes
# supported MotionScheme and also fixes sheets ignoring it during drag/nested scroll.
# Only this UI dependency is explicitly overridden; resolved transitive versions
# are captured in CI. No core, service, DNS or routing binaries are updated.
p=ROOT/'MotionMaterials12.kt';s=p.read_text()
if 'var anchorViewport by mutableFloatStateOf(0f)' not in s:
    s=s.replace('import androidx.compose.ui.layout.onSizeChanged','import androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.layout.layout',1)
    old='''    var height by mutableFloatStateOf(0f)
    fun coverage(viewport: Float): Float = sheetCoverage12(viewport, height,'''
    new='''    var height by mutableFloatStateOf(0f)
    var anchorViewport by mutableFloatStateOf(0f)
    fun coverage(viewport: Float): Float = sheetCoverage12(anchorViewport.takeIf { it > 0f } ?: viewport, height,'''
    assert old in s;s=s.replace(old,new,1)
    old='''modifier = modifier.onSizeChanged { layer.height = it.height.toFloat() }
                    .drawWithContent'''
    new='''modifier = modifier.layout { measurable, constraints ->
                    // Native draggableAnchors uses these same constraints. This
                    // tracks keyboard/window resizing, not an assumed screen height.
                    val placeable = measurable.measure(constraints)
                    layer.anchorViewport = constraints.maxHeight.toFloat()
                    layer.height = placeable.height.toFloat()
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }.drawWithContent'''
    assert old in s;s=s.replace(old,new,1)
    p.write_text(s)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui12-dependency-decision.json').write_text(json.dumps({
    'material3':'1.5.0-alpha16',
    'kind':'explicit UI alpha dependency override, not a runtime-core upgrade',
    'reason':'1.4 stable hides Expressive APIs; alpha16 exposes MotionScheme and fixes BottomSheet drag/nested-scroll motion scheme use',
    'source':'https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-alpha16',
    'previous_attempt':36429557553,
    'previous_failure':'Production did not compile: Expressive API inaccessible in Material3 1.4.0; no tests or release from that attempt'
},ensure_ascii=False,indent=2))
print('Explicit UI dependency: Material3 1.5.0-alpha16. Runtime resources remain unchanged.')
