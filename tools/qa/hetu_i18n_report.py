#!/usr/bin/env python3
"""List Chinese UI literals that the ht() word table cannot translate.

Usage: python3 tools/qa/hetu_i18n_report.py [--json] [--editable-only]
Reports, per file and line:
  * Text("中文…") literals that are not wrapped in ht() or are missing from the word table;
  * ht("中文…") calls whose key is missing from the word table;
  * string templates inside ht("…$x…") that can never match a table key (use ht("… %s").fill(x)).
"Editable" = inside a folder or file the V20.93 source gate lets change (app/, home/, panel/, tools/, ui/
or AUTHORIZED_FILES); other files are frozen by the gate and are listed for information only.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PKG = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
WORDS = ROOT / PKG / 'ui/HetuLanguage.kt'
CJK = re.compile(r'[\u4e00-\u9fff]')
STR = r'"((?:[^"\\$]|\\.|\$(?!\{|[A-Za-z_]))*)"'
TEXT_RE = re.compile(r'\bText\(\s*' + STR)
HT_RE = re.compile(r'\bht\(\s*' + STR)
HT_TEMPLATE_RE = re.compile(r'\bht\(\s*"([^"\n]*\$[{A-Za-z_][^"\n]*)"')


def vocabulary(text=None):
    text = WORDS.read_text(encoding='utf-8') if text is None else text
    keys = set()
    for block in re.findall(r'"""\n(.*?)"""', text, re.S):
        for line in block.splitlines():
            parts = line.replace('\\n', '\n').split('|')
            if len(parts) >= 4 and parts[0]:
                keys.add(parts[0])
    return keys


def editable(rel):
    try:
        sys.path.insert(0, str(ROOT / '.github/scripts'))
        import continuity93_source_scope as scope  # noqa: E402
        return scope.allowed_change(rel)
    except Exception:  # gate scripts unavailable (e.g. partial checkout): fall back to folder rule
        return any(rel.startswith(PKG + f) for f in ('app/', 'home/', 'panel/', 'tools/', 'ui/'))


def unescape(s):
    return s.replace('\\n', '\n').replace('\\"', '"').replace('\\$', '$')


def scan(root=ROOT):
    keys = vocabulary()
    rows = []
    for path in sorted((root / PKG).rglob('*.kt')):
        rel = str(path.relative_to(root))
        if rel.endswith('ui/HetuLanguage.kt'):
            continue
        for no, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            for m in TEXT_RE.finditer(line):
                value = unescape(m.group(1))
                if CJK.search(value):
                    rows.append({'file': rel, 'line': no, 'kind': 'text-literal', 'text': value,
                                 'inTable': value in keys, 'editable': editable(rel)})
            for m in HT_RE.finditer(line):
                value = unescape(m.group(1))
                if CJK.search(value) and value not in keys:
                    rows.append({'file': rel, 'line': no, 'kind': 'ht-missing', 'text': value,
                                 'inTable': False, 'editable': editable(rel)})
            for m in HT_TEMPLATE_RE.finditer(line):
                rows.append({'file': rel, 'line': no, 'kind': 'ht-template', 'text': m.group(1),
                             'inTable': False, 'editable': editable(rel)})
    return rows


def main(argv):
    rows = scan()
    if '--editable-only' in argv:
        rows = [r for r in rows if r['editable']]
    if '--json' in argv:
        print(json.dumps(rows, ensure_ascii=False, indent=1))
        return 0
    for r in rows:
        flag = 'EDIT' if r['editable'] else 'gate'
        print(f"{flag} {r['kind']:<12} {r['file'][len(PKG):]}:{r['line']}  {r['text']!r}")
    by_kind = {}
    for r in rows:
        by_kind[r['kind']] = by_kind.get(r['kind'], 0) + 1
    print('summary:', json.dumps(by_kind, ensure_ascii=False), 'editable:', sum(r['editable'] for r in rows))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
