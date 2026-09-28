#!/usr/bin/env python3
"""UI7 follow-up: target actual unmerged handle nodes; retain every drag assertion."""
from pathlib import Path
import json
root='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
test='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changed=[]
def edit(path,old,new):
    p=Path(path);s=p.read_text()
    if new in s: return
    assert s.count(old)==1,(path,old[:100],s.count(old))
    p.write_text(s.replace(old,new));changed.append(path)
p=test+'HomeUi7InteractionTest.kt'
s=Path(p).read_text()
# Material wraps the handle in a merged accessibility node. Hit its real child coordinates.
for tag,count in [('sheet-drag-handle',2),('log-drag-handle',1)]:
    old=f'onNodeWithTag("{tag}")';new=f'onNodeWithTag("{tag}",useUnmergedTree=true)'
    if old in s:
        assert s.count(old)==count,(tag,s.count(old))
        s=s.replace(old,new)
    else: assert s.count(new)==count
Path(p).write_text(s);changed.append(p)
edit(p,'''        rule.runOnIdle{target.value="80 ms";Snapshot.sendApplyNotifications()}
        rule.runOnIdle{target.value="114 ms";Snapshot.sendApplyNotifications()}
        rule.waitForIdle()
        rule.onNodeWithTag("rolled-value").assertTextEquals("114 ms")
        rule.onAllNodesWithTag("rolled-value").assertCountEquals(1)
        assertEquals(1f,state.progress.value,0f)''','''        rule.mainClock.autoAdvance=false
        rule.runOnIdle{target.value="80 ms";Snapshot.sendApplyNotifications()}
        repeat(4){rule.mainClock.advanceTimeByFrame();rule.waitForIdle()}
        assertTrue("Interrupt the outgoing phase, not an already finished value",state.leaving && state.progress.value<1f)
        rule.runOnIdle{target.value="114 ms";Snapshot.sendApplyNotifications()}
        repeat(32){rule.mainClock.advanceTimeByFrame();rule.waitForIdle()}
        rule.onNodeWithTag("rolled-value").assertTextEquals("114 ms")
        rule.onAllNodesWithTag("rolled-value").assertCountEquals(1)
        assertEquals(1f,state.progress.value,0f)
        rule.mainClock.autoAdvance=true''')
# The wide expanded title does not need the compact title's symmetric action reserve.
edit(root+'HomeInteraction7.kt',
    'maxWidth = (width - 2 * side - 2 * buttons.width).coerceAtLeast(1)))',
    'maxWidth = (width - 2 * side - buttons.width * (1f + fraction)).toInt().coerceAtLeast(1)))')
edit(test+'CompactHomeDashboardTest.kt','fun ui4BrandIsCenteredAndRefreshStillWorks()',
     'fun ui7BrandStartsLeadingAndRefreshStillWorks()')
edit(test+'HomeUi6RegressionTest.kt','fun lavenderAndCrispForegroundPillAreActuallyPainted()',
     'fun clearCanvasAndCrispForegroundPillAreActuallyPainted()')
manifest=Path('integration-evidence/ui7-changed-paths.json')
paths=json.loads(manifest.read_text()) if manifest.exists() else []
manifest.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
Path('integration-evidence/UI7_first_run.md').write_text('''# UI7 first-run evidence

Run 36404150042 compiled and packaged successfully. 92/95 checks passed.
The three failed checks could not locate the drag-handle child in the merged semantics tree, before any drag was injected.
The rerun targets the actual unmerged handle node; long/short drag distances, durations, dismiss expectations and return-position assertions are unchanged.
The number-roll regression is additionally strengthened to interrupt its verified outgoing phase, rather than waiting until one value has already settled.
Expanded-header width allocation is adjusted for large text without changing compact-center alignment or action targets.
The first run and its evidence artifact 10961756066 are retained. No checks were deleted, skipped or relaxed.
''')
print('UI7 exact handle targeting and active-phase interruption checks applied')
