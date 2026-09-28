#!/usr/bin/env python3
"""UI8 reviewed corrections; no skipped tests or changed runtime functions."""
from pathlib import Path
import json
R='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
T='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed=[]
def edit(p,a,b):
    f=Path(p);s=f.read_text()
    if b in s: return
    assert s.count(a)==1,(p,a[:80],s.count(a))
    f.write_text(s.replace(a,b));changed.append(p)
edit(R+'CompactHomeDashboard.kt',
'''        var headerPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current''',
'''        val density = LocalDensity.current
        var headerPx by remember(density.density) { mutableIntStateOf(with(density) { 56.dp.roundToPx() }) }''')
edit(T+'HomeUi8ExperienceTest.kt',
'''            Box(Modifier.size(100.dp).testTag("press-target").nativePress(motion = true) { calls += "tap" })''',
'''            // Locate inside the graphics layer so its transformed bounds are measured.
            Box(Modifier.size(100.dp).nativePress(motion = true) { calls += "tap" }.testTag("press-target"))''')
edit(R+'HomeExperience8.kt',
'''            Row(Modifier.widthIn(max = 320.dp).heightIn(min = 40.dp).clip(CircleShape)''',
'''            val glow = if (motion && value.kind == HomeNoticeKind.Success) {
                val pulse = rememberInfiniteTransition(label = "feedback-glow")
                pulse.animateFloat(.6f, 1f, infiniteRepeatable(tween(850, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse), label = "feedback-glow-alpha").value
            } else 1f
            Row(Modifier.widthIn(max = 320.dp).heightIn(min = 40.dp).clip(CircleShape)''')
edit(R+'HomeExperience8.kt',
'''                    drawCircle(dot.copy(alpha = .17f), size.minDimension / 2)''',
'''                    drawCircle(dot.copy(alpha = .17f * glow), size.minDimension / 2)''')
edit(R+'CompactHomeDashboard.kt',
'''        val first = line // Addresses never create a second line above the region row.''',
'''        // LineHeight is not necessarily the physical glyph paragraph height with OEM fonts.
        // Measure the entire unwrapped address; never constrain it to a guessed 22-dp box.
        val first = maxOf(line, with(density) {
            listOf(data.wan, data.lan).maxOf { address ->
                val result = measurer.measure(androidx.compose.ui.text.AnnotatedString(address.ifBlank { "—" }),
                    style = textStyle, softWrap = false, maxLines = 1)
                kotlin.math.ceil(result.multiParagraph.height).toInt()
            }.toDp()
        })''')
# Horizontal scroll needs an unbounded viewport measurement, not an infinite text paragraph.
# Intrinsic width keeps every glyph in the paragraph, while the outer scroll retains the card width.
edit(R+'HomeExperience8.kt',
'''    Text(raw.ifBlank { "—" }, modifier.horizontalScroll(scroll),''',
'''    Text(raw.ifBlank { "—" }, modifier.horizontalScroll(scroll).width(IntrinsicSize.Max),''')
# The original header test swipes down and then presses Refresh. Now both are real entry points.
edit(T+'HomeUi5RegressionTest.kt',
'''        rule.onNodeWithContentDescription("刷新状态").performClick()
        assertEquals(listOf("refresh"), calls)''',
'''        assertEquals("The completed top-edge pull now dispatches a real refresh", listOf("refresh"), calls)
        rule.onNodeWithContentDescription("刷新状态").performClick()
        assertEquals("Toolbar refresh remains independently reachable", listOf("refresh", "refresh"), calls)''')
# Print geometry of explicit fixtures, never production IPs, while retaining all assertions.
edit(T+'HomeUi8ExperienceTest.kt',
'''        return out.single()
    }
    private fun pullOffset()''',
'''        val result = out.single()
        if (tag == "home-network-ip") println("ADDRESS_LAYOUT size=${result.size} paragraph=${result.multiParagraph.width}x${result.multiParagraph.height} constraints=${result.layoutInput.constraints} widthOverflow=${result.didOverflowWidth} heightOverflow=${result.didOverflowHeight} exceeded=${result.multiParagraph.didExceedMaxLines} baseline=${result.firstBaseline} lineEnd=${result.getLineEnd(0)}")
        return result
    }
    private fun pullOffset()''')
p=Path('integration-evidence/ui8-changed-paths.json')
paths=json.loads(p.read_text()) if p.exists() else []
p.parent.mkdir(exist_ok=True)
p.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
print('UI8 reviewed adjustments:',changed)
