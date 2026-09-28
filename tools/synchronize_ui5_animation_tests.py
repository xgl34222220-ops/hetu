#!/usr/bin/env python3
"""Synchronize the manually paused Compose clock; preserve every behavior assertion.

With autoAdvance=false, changing external state on the Android thread and then
advancing 32ms does not establish that snapshot notifications/recomposition have
been dispatched. Synchronize snapshot delivery and the start of the exit phase
before measuring animation time. This changes tests only, not product animation.
See https://developer.android.com/develop/ui/compose/testing/synchronization
"""
from pathlib import Path
import json

path = Path('android-app/app/src/test/java/io/github/xgl34222220/hetu/HomeUi5RegressionTest.kt')
s = path.read_text()
if '// ui5-explicit-snapshot-clock' in s:
    print('UI5 explicit snapshot synchronization is already present')
    raise SystemExit(0)
assert s.count('@Test fun ') == 10
s = s.replace('import androidx.compose.runtime.*\n',
    'import androidx.compose.runtime.*\nimport androidx.compose.runtime.snapshots.Snapshot\n', 1)
old = '''        rule.runOnUiThread { target.value = true }
        rule.mainClock.advanceTimeBy(48)
        rule.onNodeWithTag("single-body").assertTextEquals("WAN")
        assertTrue(transition.leaving)
        assertTrue(transition.progress.value in 0f..1f)
        assertTrue(transition.progress.value < 1f)
'''
new = '''        // ui5-explicit-snapshot-clock
        rule.runOnIdle {
            target.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(1_000) { transition.leaving }
        val exitStartedAt = rule.mainClock.currentTime
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("single-body").assertTextEquals("WAN")
        assertTrue(transition.leaving)
        assertTrue(transition.progress.value > 0f)
        assertTrue(transition.progress.value < 1f)
        println("Measured exit at ${rule.mainClock.currentTime - exitStartedAt}ms: ${transition.progress.value}")
'''
assert s.count(old) == 1
s = s.replace(old, new, 1)
old = '''            rule.runOnUiThread { target.value = !target.value }
            rule.mainClock.advanceTimeBy(32)'''
new = '''            rule.runOnIdle {
                target.value = !target.value
                Snapshot.sendApplyNotifications()
            }
            rule.mainClock.advanceTimeBy(32)'''
assert s.count(old) == 1
s = s.replace(old, new, 1)
old = '''        rule.runOnUiThread { target.value = true }
        rule.mainClock.advanceTimeBy(32)
        rule.runOnUiThread { motion.value = false }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")'''
new = '''        rule.runOnIdle {
            target.value = true
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeUntil(1_000) { transition.leaving }
        rule.mainClock.advanceTimeBy(32)
        assertTrue("This test must disable motion during a real exit", transition.leaving)
        rule.runOnIdle {
            motion.value = false
            Snapshot.sendApplyNotifications()
        }
        // Two composition frames plus the Android draw/layout pass, not an animation wait.
        rule.mainClock.advanceTimeBy(32)
        rule.waitForIdle()
        rule.onNodeWithTag("single-body").assertTextEquals("LAN")'''
assert s.count(old) == 1
s = s.replace(old, new, 1)
assert s.count('@Test fun ') == 10
path.write_text(s)
report = Path('integration-evidence/ui5-clock-synchronization.json')
report.parent.mkdir(exist_ok=True)
report.write_text(json.dumps({
    'first_run': 36396232478,
    'first_run_tests': 72,
    'first_run_failures': 2,
    'test_change': 'Explicit snapshot delivery and exit-phase synchronization before manual frame assertions',
    'production_animation_changed': False,
    'tests_removed_or_skipped': False,
}, indent=2))
print('Synchronized all three manual-clock tests; retained 10 tests and all behavior checks')
