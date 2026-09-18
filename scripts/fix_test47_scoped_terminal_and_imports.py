from pathlib import Path
import re

UI = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ReferenceProxyActivity.kt')
SUB = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxySubscriptionActivity.kt')
text = UI.read_text(encoding='utf-8')
sub = SUB.read_text(encoding='utf-8')

# The first alpha=.35 ModalBottomSheet is RefChoiceBottomSheet. If the broad test47
# replacement touched it, restore it before compile, then style RefInfoBottomSheet
# in a function-scoped way.
choice_marker = 'private fun RefChoiceBottomSheet('
info_marker = 'private fun RefInfoBottomSheet('
handle_marker = 'private fun RefSheetDragHandle()'
if choice_marker not in text or info_marker not in text or handle_marker not in text:
    raise SystemExit('test47 followup: sheet function markers missing')

choice_start = text.index(choice_marker)
info_start = text.index(info_marker)
handle_start = text.index(handle_marker)
choice = text[choice_start:info_start]
info = text[info_start:handle_start]

# Restore Choice sheet if the broad replacement accidentally introduced `terminal`.
choice = re.sub(
    r'''containerColor = if \(terminal\) Color\(0xFF0B1220\) else t\.elevatedCardBackground,\n\s*contentColor = if \(terminal\) Color\(0xFFE2E8F0\) else t\.textPrimary,\n\s*tonalElevation = 0\.dp,\n\s*scrimColor = Color\.Black\.copy\(alpha = if \(terminal\) \.48f else \.35f\),\n\s*dragHandle = \{\n\s*Box\(\n\s*Modifier\.padding\(top = 10\.dp, bottom = 6\.dp\)\.size\(width = 36\.dp, height = 4\.dp\)\n\s*\.background\(if \(terminal\) Color\(0xFF334155\) else Color\(0xFFCBD5E1\), CircleShape\),\n\s*\)\n\s*\},''',
    '''containerColor = t.elevatedCardBackground,\n        contentColor = t.textPrimary,\n        tonalElevation = 0.dp,\n        scrimColor = Color.Black.copy(alpha = .35f),\n        dragHandle = { RefSheetDragHandle() },''',
    choice,
    count=1,
    flags=re.S,
)
choice = choice.replace(
    'Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
    'Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
)
if 'terminal' in choice:
    raise SystemExit('test47 followup: terminal leaked into RefChoiceBottomSheet')

# Darken the actual log/info sheet only.
old_info_header = '''containerColor = t.elevatedCardBackground,\n        contentColor = t.textPrimary,\n        tonalElevation = 0.dp,\n        scrimColor = Color.Black.copy(alpha = .35f),\n        dragHandle = { RefSheetDragHandle() },'''
new_info_header = '''containerColor = if (terminal) Color(0xFF0B1220) else t.elevatedCardBackground,\n        contentColor = if (terminal) Color(0xFFE2E8F0) else t.textPrimary,\n        tonalElevation = 0.dp,\n        scrimColor = Color.Black.copy(alpha = if (terminal) .48f else .35f),\n        dragHandle = {\n            Box(\n                Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)\n                    .background(if (terminal) Color(0xFF334155) else Color(0xFFCBD5E1), CircleShape),\n            )\n        },'''
if old_info_header in info:
    info = info.replace(old_info_header, new_info_header, 1)
elif 'containerColor = if (terminal) Color(0xFF0B1220)' not in info:
    raise SystemExit('test47 followup: RefInfoBottomSheet header shape unexpected')

info = info.replace(
    'Text(title, color = t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
    'Text(title, color = if (terminal) Color(0xFFF8FAFC) else t.textPrimary, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)',
    1,
)
if 'containerColor = if (terminal) Color(0xFF0B1220)' not in info:
    raise SystemExit('test47 followup: dark terminal shell not applied')

text = text[:choice_start] + choice + info + text[handle_start:]

# New full-screen YAML editor uses a floating Surface action dock.
if 'import androidx.compose.foundation.BorderStroke' not in sub:
    sub = sub.replace(
        'import androidx.compose.foundation.background\n',
        'import androidx.compose.foundation.background\nimport androidx.compose.foundation.BorderStroke\n',
        1,
    )
if 'import androidx.compose.ui.draw.shadow' not in sub:
    anchor = 'import androidx.compose.ui.graphics.Brush\n'
    if anchor not in sub:
        raise SystemExit('test47 followup: YAML shadow import anchor missing')
    sub = sub.replace(anchor, 'import androidx.compose.ui.draw.shadow\n' + anchor, 1)

required_sub = [
    'DialogProperties(usePlatformDefaultWidth = false)',
    'import androidx.compose.foundation.BorderStroke',
    'import androidx.compose.ui.draw.shadow',
    'lineHeight = 20.sp',
]
for token in required_sub:
    if token not in sub:
        raise SystemExit(f'test47 followup: missing subscription invariant {token}')

UI.write_text(text, encoding='utf-8')
SUB.write_text(sub, encoding='utf-8')
print('Applied test47 scoped terminal sheet and YAML editor compile guards')
