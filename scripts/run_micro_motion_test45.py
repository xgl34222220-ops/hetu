from pathlib import Path

source = Path('scripts/polish_micro_motion_test45.py')
code = source.read_text(encoding='utf-8')
old = """once(\n    '            .clickable(interactionSource = source, indication = null, onClick = onClick),',\n    '            .clickable(interactionSource = source, indication = null) {\\n                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)\\n                onClick()\\n            },',\n    'header haptic click',\n)"""
new = """header_start = text.index('private fun RefPanelHeaderAction')\nheader_end = text.index('@Composable\\nprivate fun RefPanelTabs', header_start)\nheader_body = text[header_start:header_end]\nold_header_click = '            .clickable(interactionSource = source, indication = null, onClick = onClick),'\nnew_header_click = '            .clickable(interactionSource = source, indication = null) {\\n                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)\\n                onClick()\\n            },'\nif header_body.count(old_header_click) != 1:\n    raise SystemExit(f'header haptic click scoped: expected 1 match, got {header_body.count(old_header_click)}')\nheader_body = header_body.replace(old_header_click, new_header_click, 1)\ntext = text[:header_start] + header_body + text[header_end:]"""
if old not in code:
    raise SystemExit('corrected test45 runner could not find generic header click block')
code = code.replace(old, new, 1)
exec(compile(code, str(source), 'exec'), {'__name__': '__main__'})
