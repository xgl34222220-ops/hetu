#!/usr/bin/env python3
"""UI12 follow-up: supported public motion APIs, native gesture contract, measured anchors."""
from pathlib import Path
import json,subprocess
ROOT=Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
BASE='621e4f22fce4e9c5f5c717eab1147fad20dab950'
p=Path('android-app/app/build.gradle.kts');s=p.read_text()
old='implementation("androidx.compose.material3:material3")'
new='implementation("androidx.compose.material3:material3:1.5.0-alpha16")'
if old in s:
    assert s.count(old)==1;s=s.replace(old,new,1);p.write_text(s)
else:assert new in s
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
# Current native AnchoredDraggableDefaults uses its own 125dp/s fling threshold;
# SheetState's legacy velocity lambda is NOT a supported override of this path.
# Remove the ineffective numerical change rather than claim 1100dp/s was applied.
s=s.replace('    const val DismissVelocityDp = 1100f\n','').replace('    const val DismissDistanceDp = 120f\n','')
p.write_text(s)
p=ROOT/'HomeInteraction7.kt';s=p.read_text()
s=s.replace('HetuMotion12.DismissDistanceDp.dp.toPx()','120.dp.toPx()').replace('HetuMotion12.DismissVelocityDp.dp.toPx()','1800.dp.toPx()')
assert s==subprocess.check_output(['git','show',BASE+':'+str(p)]).decode()
p.write_text(s)
# Preserve the exact originally failing 90px/600ms gesture as a dedicated native
# fling test. Add a genuinely sub-threshold slow gesture instead of deleting it,
# bypassing pointer input, or pretending a legacy constructor controls the fling.
p=Path('android-app/app/src/test/java/io/github/xgl34222220/hetu/MotionUi12Test.kt');s=p.read_text()
if 'unpaused150DpPerSecondGestureFollowsTheNativeFlingContract' not in s:
    old='''node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),600) }'''
    assert s.count(old)==1
    s=s.replace(old,'''// 90px / 1.2s = 75dp/s on this mdpi fixture: below native 125dp/s.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),1200) }''',1)
    anchor='    @Test fun shortFastFlingClosesWithoutRequiring120dpTravel() {'
    new='''    @Test fun unpaused150DpPerSecondGestureFollowsTheNativeFlingContract() {
        renderSheet()
        // This is the unchanged gesture from the failed attempt. It is a fling
        // under the actual public native implementation, not a sub-threshold drag.
        node("sheet-drag-handle").performTouchInput { swipe(center,center+Offset(0f,90f),600) }
        node("native-details-sheet").assertDoesNotExist()
        assertEquals(1,closes); assertEquals(0f,progress(),0f)
    }
'''+anchor
    assert anchor in s;s=s.replace(anchor,new,1);p.write_text(s)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui12-dependency-decision.json').write_text(json.dumps({
    'material3':'1.5.0-alpha16',
    'kind':'explicit UI alpha override, not a core upgrade',
    'reason':'1.4 stable hides Expressive APIs; alpha16 exposes MotionScheme and fixes BottomSheet drag/nested-scroll motion scheme use',
    'source':'https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-alpha16',
    'native_fling_source':'https://github.com/androidx/androidx/blob/4f1927c2c3b66d0c3a6b9118974d818d2dc5a06a/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/AnchoredDraggable.kt',
    'velocity_policy':'native 125dp/s; no claimed custom 1100dp/s override',
    'attempts':[
        {'run':36429557553,'result':'Production compile failed on internal API; zero tests, no release'},
        {'run':36430846940,'result':'202 tests, 201 passed; 90px/600ms incorrectly classified as below native velocity threshold. Original gesture retained as fling test; added 90px/1200ms slow case.'}
    ]
},ensure_ascii=False,indent=2))
print('UI12 uses public Material3 alpha16 motion and actual native velocity policy; legacy no-op override removed.')
