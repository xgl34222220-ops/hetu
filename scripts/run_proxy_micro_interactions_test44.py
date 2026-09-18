from pathlib import Path

source = Path('scripts/patch_proxy_micro_interactions_test44.py')
code = source.read_text(encoding='utf-8')
old = """replace_once(\n    '    val context = LocalContext.current\\n    val t = LocalHetuTokens.current\\n    var refreshing by remember { mutableStateOf(false) }',\n    '    val context = LocalContext.current\\n    val t = LocalHetuTokens.current\\n    val haptic = LocalHapticFeedback.current\\n    var refreshing by remember { mutableStateOf(false) }',\n    'add panel haptics',\n)"""
new = """replace_once(\n    '    val context = LocalContext.current\\n    val t = LocalHetuTokens.current\\n    val tab = selectedTab\\n    var refreshing by remember { mutableStateOf(false) }',\n    '    val context = LocalContext.current\\n    val t = LocalHetuTokens.current\\n    val haptic = LocalHapticFeedback.current\\n    val tab = selectedTab\\n    var refreshing by remember { mutableStateOf(false) }',\n    'add panel haptics',\n)"""
if old not in code:
    raise SystemExit('test44 runner: could not find haptic replacement block')
code = code.replace(old, new, 1)
exec(compile(code, str(source), 'exec'), {'__name__': '__main__'})
