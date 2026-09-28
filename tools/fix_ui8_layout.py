#!/usr/bin/env python3
"""UI8 review adjustments; never disables regressions or changes runtime code."""
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
p=Path('integration-evidence/ui8-changed-paths.json')
paths=json.loads(p.read_text()) if p.exists() else []
p.parent.mkdir(exist_ok=True)
p.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
print('UI8 reviewed adjustments:',changed)
