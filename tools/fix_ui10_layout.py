"""UI10 build corrections. Preserve the actual visibility, clipping and touch assertions."""
from pathlib import Path
import json
root=Path('android-app/app/src/test/java/io/github/xgl34222220/hetu')
changed=[]
def replace(file, old, new):
    p=root/file;s=p.read_text()
    if new in s and old not in s: return
    assert s.count(old)==1,(file,old,s.count(old))
    p.write_text(s.replace(old,new));changed.append(str(p))
replace('HomeUi10GridTest.kt',
    'assertEquals(h.center.x.value,brand.center.x.value,1f)',
    'assertEquals(((h.left + h.right) / 2).value, ((brand.left + brand.right) / 2).value, 1f)')
replace('HomeUi7InteractionTest.kt',
    'assertEquals(header.center.x,brand.center.x,1f)\n        assertEquals(28.sp,layout("home-brand").layoutInput.style.fontSize)',
    'assertEquals(header.center.x,brand.center.x,1f)\n        assertEquals(20.sp,layout("home-brand").layoutInput.style.fontSize)')
p=Path('integration-evidence/ui10-changed-paths.json')
paths=json.loads(p.read_text())
p.write_text(json.dumps(sorted(set(paths+changed)),indent=2))
Path('integration-evidence/UI10_attempts.md').write_text('''# UI10 verification history

- Run 36416713176: stopped before compilation because the UI9 signing cache was missing. The original UI9 run logs confirm its configured cache path did not exist and no key was saved. No same-signer claim is made.
- Run 36417117405: application Kotlin and APK assembly completed; test Kotlin compilation failed because the new test tried to access a nonexistent DpRect.center property. No test execution was reported as passing. The center check is now the equivalent (left+right)/2 formula.
- The standalone expanding-header test retains its original 28sp expanded / 20sp collapsed contract. Only the new dashboard opts into an always-centered 20sp header; an overbroad test-source replacement is corrected rather than altering that production component behavior.
- No tests are removed or disabled. Normal IPv4 visible-glyph width, scroll range zero, first-screen cards above the actual Dock, true ratios and real touch checks remain mandatory.
''')
print('UI10 test corrections:',changed)
