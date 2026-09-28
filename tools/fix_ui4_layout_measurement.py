#!/usr/bin/env python3
"""Correct two regressions exposed by UI4 run 36392630398. Tests are not relaxed."""
from pathlib import Path
import hashlib
import json

root = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
p = root / 'CompactHomeDashboard.kt'
s = p.read_text()
start = s.index('                    // A FlowRow keeps short addresses inline,')
end = s.index('                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {', start)
s = s[:start] + '''                    // Put the address on its own full-width line. Unlike FlowRow's
                    // remaining-width measurement this has the same intrinsic and actual
                    // height in the equal-height pair; IPv4/IPv6 can never lose a line.
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        HomeLabel("IP")
                        Text((if (isLan) data.lan else data.wan).ifBlank { "—" },
                            Modifier.fillMaxWidth().testTag("home-network-ip"), color = p.text,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = (-.5).sp,
                            softWrap = true, overflow = TextOverflow.Clip,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))
                    }
''' + s[end:]
p.write_text(s)

p = root / 'ui/HetuGlassDock.kt'
s = p.read_text()
old = '.then(if (floating) Modifier.squircleClip(31.dp) else Modifier.clip(shape))'
assert s.count(old) == 1, 'Expected the reviewed dock shell mask'
s = s.replace(old, '.clip(shape)')
# Real Haze and refraction rendering remain intact; only the outer rounded mask changes.
s = s.replace('if (floating) RoundedCornerShape(31.dp) else RoundedCornerShape(topStart = 31.dp, topEnd = 31.dp)',
              'if (floating) RoundedCornerShape(32.dp) else RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)')
p.write_text(s)

paths = json.loads(Path('integration-evidence/ui4-changed-paths.json').read_text())
Path('integration-evidence/ui4-source-hashes.json').write_text(json.dumps({p: hashlib.sha256(Path(p).read_bytes()).hexdigest() for p in paths}, indent=2))
Path('integration-evidence/first-run-regressions.json').write_text(json.dumps({
    'run': 36392630398,
    'tests': 62, 'passed': 60,
    'failures': ['longest IPv4 visual overflow', 'software Canvas cannot draw squircle RuntimeShader'],
    'production_fixes': ['full-width address column with consistent intrinsic measurement', 'standard native rounded clip; real glass effects retained'],
    'removed_tests': 0, 'relaxed_assertions': 0
}, indent=2))
print('Fixed production measurement and round clipping; kept all 62 test assertions.')
