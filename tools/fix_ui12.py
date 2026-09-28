#!/usr/bin/env python3
"""UI12 follow-up: supported public motion APIs, native gesture contract, measured anchors."""
from pathlib import Path
import json,subprocess
ROOT=Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
BASE='621e4f22fce4e9c5f5c717eab1147fad20dab950'
p=Path('android-app/app/build.gradle.kts');s=p.read_text()
new='implementation("androidx.compose.material3:material3") { version { strictly("1.5.0-alpha22") } }'
for old in ['implementation("androidx.compose.material3:material3:1.5.0-alpha16")','implementation("androidx.compose.material3:material3")']:
    if new not in s and old in s:
        assert s.count(old)==1;s=s.replace(old,new,1)
assert new in s;p.write_text(s)
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
                    // The native anchors use these same constraints, including IME resizing.
                    val placeable = measurable.measure(constraints)
                    layer.anchorViewport = constraints.maxHeight.toFloat()
                    layer.height = placeable.height.toFloat()
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }.drawWithContent'''
    assert old in s;s=s.replace(old,new,1)
s=s.replace('    const val DismissVelocityDp = 1100f\n','').replace('    const val DismissDistanceDp = 120f\n','')
p.write_text(s)
p=ROOT/'HomeInteraction7.kt';s=p.read_text()
s=s.replace('HetuMotion12.DismissDistanceDp.dp.toPx()','120.dp.toPx()').replace('HetuMotion12.DismissVelocityDp.dp.toPx()','1800.dp.toPx()')
assert s==subprocess.check_output(['git','show',BASE+':'+str(p)]).decode();p.write_text(s)
p=Path('android-app/app/src/test/java/io/github/xgl34222220/hetu/MotionUi12Test.kt');s=p.read_text()
if 'unpaused150DpPerSecondGestureFollowsTheNativeFlingContract' not in s:
    old='''node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),600) }'''
    assert s.count(old)==1
    s=s.replace(old,'''// 90px / 1.2s = 75dp/s on this mdpi fixture: below native 125dp/s.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),1200) }''',1)
    anchor='    @Test fun shortFastFlingClosesWithoutRequiring120dpTravel() {'
    new='''    @Test fun unpaused150DpPerSecondGestureFollowsTheNativeFlingContract() {
        renderSheet()
        // Preserve the exact failed-attempt gesture, now correctly classified.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),600) }
        node("native-details-sheet").assertDoesNotExist()
        assertEquals(1,closes); assertEquals(0f,progress(),0f)
    }
'''+anchor
    assert anchor in s;s=s.replace(anchor,new,1);p.write_text(s)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui12-dependency-decision.json').write_text(json.dumps({
    'material3_requested_and_strictly_locked':'1.5.0-alpha22',
    'kind':'explicit UI alpha override, not a core upgrade',
    'reason':'Public MotionScheme. The previous alpha16 request actually resolved alpha22 through JetBrains Compose dependencies; now explicitly lock the tested effective version instead of reporting the requested minimum.',
    'source':'https://developer.android.com/jetpack/androidx/releases/compose-material3',
    'velocity_policy':'native AnchoredDraggableDefaults; no claimed custom 1100dp/s override',
    'attempts':[
        {'run':36429557553,'result':'Production compile failed on internal API; zero tests, no release'},
        {'run':36430846940,'result':'202 tests, 201 passed; 90px/600ms misclassified as below the native velocity boundary. Original gesture retained as fling test; added 90px/1200ms slow case.'},
        {'run':36432162660,'result':'All 203 tests passed, source/runtime/package checks passed; signing-parser format error after successful apksigner verification prevented release. Evidence shows both actual V2 certificate digests equal the fixed UI10/UI11 fingerprint.'}
    ]
},ensure_ascii=False,indent=2))
print('UI12 explicitly locks the tested resolved Material3 alpha22; native velocity policy retained.')
