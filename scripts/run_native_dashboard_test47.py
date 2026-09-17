from pathlib import Path

source = Path('scripts/polish_native_dashboard_test47.py')
code = source.read_text(encoding='utf-8')

start_marker = '# Dark Glass log sheet: make the whole terminal drawer obsidian, not a white drawer with a black rectangle.\n'
end_marker = '# Sanity checks for ReferenceProxyActivity.\n'
if start_marker not in code or end_marker not in code:
    raise SystemExit('test47 runner: terminal section markers missing')
start = code.index(start_marker)
end = code.index(end_marker, start)
code = code[:start] + '# Dark terminal shell is intentionally applied by fix_test47_scoped_terminal_and_imports.py\n\n' + code[end:]

required_terminal = "    'containerColor = if (terminal) Color(0xFF0B1220)',\n"
if required_terminal not in code:
    raise SystemExit('test47 runner: terminal invariant entry missing')
code = code.replace(required_terminal, '', 1)

exec(compile(code, str(source), 'exec'), {'__name__': '__main__'})
