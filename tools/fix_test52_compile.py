from pathlib import Path

path = Path('android-app/app/src/main/java/io/github/xgl34222220/bichen/ProxyAdvancedSettingsActivity.kt')
text = path.read_text()
needle = 'import androidx.compose.material3.*\n'
addition = 'import androidx.compose.material3.*\nimport androidx.compose.animation.core.animateFloat\n'
if 'import androidx.compose.animation.core.animateFloat\n' not in text:
    if needle not in text:
        raise RuntimeError('Material3 import anchor missing')
    text = text.replace(needle, addition, 1)
path.write_text(text)
print('test52 compile import fixed')
