from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ReferenceProxyActivity.kt')
s = p.read_text(encoding='utf-8')
old_in = 'androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { 4.dp.roundToPx() }'
new_in = 'androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { fullHeight -> (fullHeight * .08f).toInt().coerceAtLeast(4) }'
old_out = 'androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { -4.dp.roundToPx() }'
new_out = 'androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(160)) { fullHeight -> -(fullHeight * .08f).toInt().coerceAtLeast(4) }'
if old_in not in s or old_out not in s:
    raise SystemExit('slide offset anchors missing')
s = s.replace(old_in, new_in, 1).replace(old_out, new_out, 1)
p.write_text(s, encoding='utf-8')
print('test56 slide offsets fixed')
