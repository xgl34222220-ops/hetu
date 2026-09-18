from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
text = path.read_text(encoding='utf-8')
needle = 'import androidx.compose.animation.core.animateFloatAsState\n'
addition = 'import androidx.compose.animation.core.animateFloat\n'
if addition not in text:
    if needle not in text:
        raise SystemExit('animateFloatAsState import not found')
    text = text.replace(needle, addition + needle, 1)
path.write_text(text, encoding='utf-8')
print('Ensured InfiniteTransition.animateFloat import')
