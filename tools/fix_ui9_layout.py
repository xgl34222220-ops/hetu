#!/usr/bin/env python3
"""UI9 regression fixes: preserve all checks; update only superseded gap geometry."""
from pathlib import Path
import json
R='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
T='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed=[]
def edit(path,a,b):
    p=Path(path);s=p.read_text()
    if b in s: return
    assert s.count(a)==1,(path,a[:100],s.count(a))
    p.write_text(s.replace(a,b));changed.append(path)
# Reduce decoration spacing only, keeping 16sp metrics, 14dp card insets and 48dp tap targets.
edit(R+'CompactHomeDashboard.kt',
    'verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)',
    'verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)')
edit(R+'CompactHomeDashboard.kt',
    'HomeCard(modifier.heightIn(min = 58.dp), onClick = click, clickLabel = title)',
    'HomeCard(modifier.heightIn(min = 52.dp), onClick = click, clickLabel = title)')
edit(R+'CompactHomeDashboard.kt',
    'Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),',
    'Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),')
edit(R+'CompactHomeDashboard.kt',
    'bottom = if (LocalHomeCompactSpacing.current) 8.dp else 14.dp',
    'bottom = if (LocalHomeCompactSpacing.current) 4.dp else 14.dp')
# Separately rounded paragraphs can be taller than a single 39sp sum at 1.6x font scale.
# Measure resolved glyph heights in pixels, then convert the total once; do not shrink text.
edit(R+'CompactHomeDashboard.kt',
'''        val metricHeight = if (stacked) with(density) { 39.sp.toDp() } else line''',
'''        val metricHeight = if (stacked) {
            val labelStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 17.sp,
                fontWeight = FontWeight.Medium)
            val labelPixels = listOf("上行", "下行", "已用", "总量", "内存", "CPU").maxOf {
                kotlin.math.ceil(measurer.measure(androidx.compose.ui.text.AnnotatedString(it),
                    style = labelStyle, maxLines = 1).multiParagraph.height).toInt()
            }
            val numberPixels = kotlin.math.ceil(measurer.measure(
                androidx.compose.ui.text.AnnotatedString("180.0 MB/s"), style = textStyle,
                maxLines = 1, softWrap = false).multiParagraph.height).toInt()
            with(density) { (labelPixels + numberPixels).toDp() } + 2.dp
        } else line''')
# These old assertions expected two separate cards 12dp apart. The new file explicitly
# replaces that gap with a 1dp in-surface separator; keep all height/baseline/inset checks.
edit(T+'CompactHomeDashboardTest.kt',
    'assertEquals(12f, resource.left - sub.right, 1f)',
    'assertEquals(1f, resource.left - sub.right, 1f)')
edit(T+'HomeUi6RegressionTest.kt',
    'allFourCardsHaveIdenticalDimensionsAndTwelveDpGaps',
    'allFourHalvesKeepEqualGeometryWithOneDpDividerAndTwelveDpRows')
edit(T+'HomeUi6RegressionTest.kt',
    'assertEquals(12f,b[1].left-b[0].right,1f);assertEquals(12f,b[2].top-b[0].bottom,1f)',
    'assertEquals(1f,b[1].left-b[0].right,1f);assertEquals(12f,b[2].top-b[0].bottom,1f)')
edit(T+'HomeUi9BentoTest.kt',
    'assertTrue(bounds("home-health-group").bottom <= bounds("compact-home").bottom)',
    'assertTrue("Full health surface ${bounds(\"home-health-group\")} / viewport ${bounds(\"compact-home\")}", bounds("home-health-group").bottom <= bounds("compact-home").bottom)')
edit(T+'HomeUi9BentoTest.kt',
    'assertFalse(tag, text(tag).hasVisualOverflow)',
    '''val layout = text(tag)
            assertFalse("$tag size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} constraints=${layout.layoutInput.constraints}", layout.hasVisualOverflow)''')
p=Path('integration-evidence/ui9-changed-paths.json')
paths=json.loads(p.read_text()) if p.exists() else []
p.parent.mkdir(exist_ok=True)
p.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
Path('integration-evidence/UI9_first_run.md').write_text('''# UI9 first-run evidence
Run 36413015070, job 108897449440: APK compiled, 131 tests ran, 127 passed and 4 failed. No APK was published.
Two failures were explicit previous-design assertions of a 12dp horizontal gap; the new two-part surfaces intentionally use a 1dp divider. Equal widths/heights, 14dp inner margins, vertical 12dp pair spacing and 6dp tracks remain checked.
Two actual layout issues were a full first-screen health panel exceeding the viewport and stacked large-font metric clipping. Production fixes reduce decorative compact spacing without reducing tap targets or font sizes, and measure rounded label/number paragraph heights separately.
First-run evidence artifact 10966700309 retained. New tests keep the full-visibility and no-overflow assertions and add dimensions to failure messages.
''')
print('UI9 regression adjustments:',changed)
