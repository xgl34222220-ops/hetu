"""UI10 build corrections; retain visible-glyph, full-screen and real-touch assertions."""
from pathlib import Path
import json
TEST=Path('android-app/app/src/test/java/io/github/xgl34222220/hetu')
MAIN=Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
changed=[]
def replace(p, old, new, count=1):
    p=Path(p);s=p.read_text()
    if new in s and old not in s: return
    assert s.count(old)==count,(str(p),old,s.count(old),count)
    p.write_text(s.replace(old,new));changed.append(str(p))
replace(TEST/'HomeUi10GridTest.kt',
    'assertEquals(h.center.x.value,brand.center.x.value,1f)',
    'assertEquals(((h.left + h.right) / 2).value, ((brand.left + brand.right) / 2).value, 1f)')
replace(TEST/'HomeUi7InteractionTest.kt',
    'assertEquals(header.center.x,brand.center.x,1f)\n        assertEquals(28.sp,layout("home-brand").layoutInput.style.fontSize)',
    'assertEquals(header.center.x,brand.center.x,1f)\n        assertEquals(20.sp,layout("home-brand").layoutInput.style.fontSize)')
replace(TEST/'HomeUi8ExperienceTest.kt',
    'assertTrue(ip.right <= card.right - 13.dp)',
    'assertTrue(ip.right <= card.right - 12.dp) // UI10 explicitly uses 12dp inner padding.')
# A genuinely short window has less room even after Dock occupancy is measured.
# Compress decoration only; retain 12dp card padding, font sizes and 48dp hit boxes.
replace(MAIN/'HomeProportions.kt',
    'internal val LocalHomeCompactSpacing = staticCompositionLocalOf { false }',
    'internal val LocalHomeCompactSpacing = staticCompositionLocalOf { false }\ninternal val LocalHomeShortSpacing = staticCompositionLocalOf { false }')
replace(MAIN/'HomeProportions.kt',
    'val baseline: Dp = 17.dp) {',
    'val baseline: Dp = 17.dp, val footer: Dp = 14.dp) {')
replace(MAIN/'HomeProportions.kt',
    '24.dp + heading + gap + first + 4.dp + second + 14.dp',
    '24.dp + heading + gap + first + 4.dp + second + footer')
replace(MAIN/'CompactHomeDashboard.kt',
    'CompositionLocalProvider(LocalHomeCompactSpacing provides compact) {',
    'CompositionLocalProvider(LocalHomeCompactSpacing provides compact,\n            LocalHomeShortSpacing provides (maxHeight < 650.dp)) {')
replace(MAIN/'CompactHomeDashboard.kt',
    'val heading = maxOf(32.dp, with(density) { 21.sp.toDp() })',
    'val heading = maxOf(if (LocalHomeShortSpacing.current) 24.dp else 32.dp, with(density) { 21.sp.toDp() })')
replace(MAIN/'CompactHomeDashboard.kt',
    'val rows = HomeGridRows(heading, maxOf(0.dp, (48.dp - heading) / 2), first, second, baseline)',
    'val rows = HomeGridRows(heading, maxOf(0.dp, (48.dp - heading) / 2), first, second, baseline,\n            footer = if (LocalHomeShortSpacing.current) 10.dp else 14.dp)')
replace(MAIN/'CompactHomeDashboard.kt',
    'Spacer(Modifier.height(14.dp))',
    'Spacer(Modifier.height(LocalHomeGridRows.current.footer))',2)
replace(MAIN/'CompactHomeDashboard.kt',
    'Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape)',
    'Box(Modifier.fillMaxWidth().padding(top = LocalHomeGridRows.current.footer - 6.dp).height(6.dp).clip(CircleShape)')
replace(MAIN/'NativeHomePolish.kt',
    'Column(Modifier.fillMaxWidth().padding(if (compact) 16.dp else 18.dp))',
    'Column(Modifier.fillMaxWidth().padding(if (LocalHomeShortSpacing.current) 14.dp else if (compact) 16.dp else 18.dp))')
replace(MAIN/'NativeHomePolish.kt',
    'Spacer(Modifier.height(if (compact) 10.dp else 16.dp))',
    'Spacer(Modifier.height(if (LocalHomeShortSpacing.current) 8.dp else if (compact) 10.dp else 16.dp))')
replace(TEST/'HomeUi10GridTest.kt',
    'private fun firstScreen() {\n        cards.forEach',
    'private fun firstScreen() {\n        snapshot("ui10-pre-assert-${bounds(\"home-viewport\").width.value.toInt()}")\n        cards.forEach')
p=Path('integration-evidence/ui10-changed-paths.json')
paths=json.loads(p.read_text());p.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
Path('integration-evidence/UI10_attempts.md').write_text('''# UI10 verification history

- Run 36416713176 stopped before compilation because the UI9 signing cache was missing. UI9 logs confirm its configured cache path did not exist and no key was saved. No same-signer claim is made.
- Run 36417117405 compiled the application and APK; test compilation failed on a nonexistent DpRect.center property. No passing test execution was claimed. Replaced that with the equivalent (left+right)/2 formula.
- Preserved the standalone expandable-header 28sp expanded / 20sp collapsed contract. The new dashboard alone opts into centered 20sp.
- Run 36417622005 executed all 145 tests: 143 passed, 2 failed. Normal IPv4 visible-glyph checks, notification isolation and all 393dp-screen checks passed. One old test still required the old 13dp inner clearance despite the explicitly specified new 12dp padding; its margin expectation is updated, with complete-address assertions retained.
- The other failure was a real 22dp short-window overflow at 360x800: the bottom card ended at740dp but the usable list ended at718dp. Production short-window layout now reduces heading decoration, unused bottom gaps and main-card padding by22dp in total. Font sizes, 12dp data-card padding, 10dp gutters, 48dp touch targets, full-card visibility and actual-Dock bounds remain mandatory.
- No tests are removed or disabled. Save a pre-assert screenshot for diagnosing any further failure rather than weakening the visibility checks.
''')
print('UI10 reviewed corrections:',changed)
