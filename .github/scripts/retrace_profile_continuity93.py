#!/usr/bin/env python3
"""Map an ART human-readable profile dumped from the R8 release APK back to source names.

Input: the `pm dump-profiles --dump-classes-and-methods` text (minified names) and the R8
mapping.txt of the very build that was profiled. Output: baseline-prof.txt rules in source names,
which AGP hands back to R8 so the next release build rewrites them to its own minified names and
packages them as assets/dexopt/baseline.prof(m).

Mapping rules (R8 map format): a class line `orig -> obf:`; member lines with a minified line range
form inline groups whose LAST line is the residual (real) method; a residual line may carry a
qualified original name when R8 moved the method from another class; a following
`com.android.tools.r8.residualsignature` comment gives the minified descriptor when R8 changed the
signature; `com.android.tools.r8.synthesized` classes/methods have no source counterpart and are
dropped (their code is reached through the source methods that are kept).
"""
import argparse
import json
from pathlib import Path
import re

PRIMITIVES = {'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S', 'int': 'I', 'long': 'J',
              'float': 'F', 'double': 'D'}
CLASS_LINE = re.compile(r'^(\S+) -> (\S+):$')
MEMBER_LINE = re.compile(r'^\s+(?:(\d+):(\d+):)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$')
RULE = re.compile(r'^([HSP]*)(L[^;]+;)(?:->(.+))?$')
DESC_TYPE = re.compile(r'\[*(?:L[^;]+;|[VZBCSIJFD])')


def java_to_desc(name):
    dims = 0
    while name.endswith('[]'):
        dims += 1
        name = name[:-2]
    base = PRIMITIVES.get(name) or 'L' + name.replace('.', '/') + ';'
    return '[' * dims + base


def map_desc(desc, classes):
    def repl(match):
        token = match.group(0)
        dims = len(token) - len(token.lstrip('['))
        core = token[dims:]
        if core.startswith('L'):
            core = 'L' + classes.get(core[1:-1], core[1:-1]) + ';'
        return '[' * dims + core
    return DESC_TYPE.sub(repl, desc)


def parse_mapping(path):
    """Return (obf->orig class map, orig->obf class map, residual methods, synthesized obf classes)."""
    obf_to_orig, orig_to_obf, synthesized = {}, {}, set()
    entries = []  # (obf_class, orig_class, orig_name, ret, args, obf_name, range, residual_signature, synthesized)
    current = None
    last_member = None
    for raw in Path(path).read_text(encoding='utf-8', errors='replace').splitlines():
        if not raw.strip():
            continue
        stripped = raw.strip()
        if stripped.startswith('#'):
            if not stripped.startswith('# {'):
                continue
            try:
                meta = json.loads(stripped[2:])
            except ValueError:
                continue
            kind = meta.get('id')
            if raw.startswith('#') and current is not None and last_member is None:
                if kind == 'com.android.tools.r8.synthesized':
                    synthesized.add(current[1])
            elif last_member is not None:
                if kind == 'com.android.tools.r8.residualsignature':
                    last_member['residual'] = meta.get('signature')
                elif kind == 'com.android.tools.r8.synthesized':
                    last_member['synthesized'] = True
            continue
        match = CLASS_LINE.match(raw)
        if match and not raw.startswith(' '):
            orig, obf = match.group(1).replace('.', '/'), match.group(2).replace('.', '/')
            obf_to_orig[obf] = orig
            orig_to_obf[orig] = obf
            current = (orig, obf)
            last_member = None
            continue
        member = MEMBER_LINE.match(raw)
        if member and current is not None:
            start, end, ret, name, args, obf_name = member.groups()
            last_member = {'obfClass': current[1], 'origClass': current[0], 'name': name, 'ret': ret,
                           'args': [a for a in args.split(',') if a], 'obfName': obf_name,
                           'range': (start, end) if start is not None else None, 'residual': None, 'synthesized': False}
            entries.append(last_member)
        else:
            last_member = None  # field line or unknown
    # Inline groups: consecutive entries of one class with the same minified range and obf name.
    residual = []
    for index, entry in enumerate(entries):
        following = entries[index + 1] if index + 1 < len(entries) else None
        if (entry['range'] is not None and following is not None and following['obfClass'] == entry['obfClass']
                and following['range'] == entry['range'] and following['obfName'] == entry['obfName']):
            continue  # inlined frame; the group's last line is the real method
        residual.append(entry)
    return obf_to_orig, orig_to_obf, residual, synthesized


def build_index(path):
    obf_to_orig, orig_to_obf, residual, synthesized = parse_mapping(path)
    index = {}
    for entry in residual:
        if entry['synthesized']:
            continue
        name, orig_class = entry['name'], entry['origClass']
        if '.' in name:  # moved from another class by R8
            owner, name = name.rsplit('.', 1)
            orig_class = owner.replace('.', '/')
        orig_desc = '(' + ''.join(java_to_desc(a) for a in entry['args']) + ')' + java_to_desc(entry['ret'])
        obf_desc = entry['residual'] or map_desc(orig_desc, orig_to_obf)
        index.setdefault((entry['obfClass'], entry['obfName'], obf_desc), set()).add((orig_class, name, orig_desc))
    return obf_to_orig, index, synthesized


def retrace(lines, mapping):
    obf_to_orig, index, synthesized = build_index(mapping)
    rules, stats = {}, {'input': 0, 'methods': 0, 'classes': 0, 'droppedSynthesized': 0, 'droppedUnmapped': 0,
                        'identityMethods': 0, 'ambiguous': 0}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        match = RULE.match(line)
        if not match:
            continue
        stats['input'] += 1
        flags, cls, method = match.group(1), match.group(2)[1:-1], match.group(3)
        if cls in synthesized:
            stats['droppedSynthesized'] += 1
            continue
        if method is None:
            orig = obf_to_orig.get(cls, cls)
            rules.setdefault('L' + orig + ';', set())
            continue
        name, desc = method.split('(', 1)
        desc = '(' + desc
        targets = index.get((cls, name, desc))
        if targets is None:
            if cls in obf_to_orig:
                stats['droppedUnmapped'] += 1  # e.g. R8-synthesized member without a source method
                continue
            targets = {(cls, name, map_desc(desc, obf_to_orig))}  # class not processed by R8: identity
            stats['identityMethods'] += 1
        if len(targets) > 1:
            stats['ambiguous'] += 1
        for orig_class, orig_name, orig_desc in targets:
            key = 'L' + orig_class + ';->' + orig_name + orig_desc
            rules.setdefault(key, set()).update(flags)
    out = []
    for key in sorted(rules):
        if '->' in key:
            flags = ''.join(f for f in 'HSP' if f in rules[key]) or 'P'
            out.append(flags + key)
            stats['methods'] += 1
        else:
            out.append(key)
            stats['classes'] += 1
    return out, stats


HEADER = '''# Hetu baseline profile — GENERATED, do not edit by hand.
# Recorded by ART from the R8 release APK on the CI AOSP emulator (v2093-build.yml, API 35 job,
# HETU_BASELINE_PROFILE=1) over cold start, 首页, 面板, 工具 › 网络测试, 工具 › 日志文件 and
# 设置 › 基础代理配置 › 配置选择, then mapped back to source names with that build's R8 mapping.
# Regenerate: .github/scripts/profile_release_continuity93.py + retrace_profile_continuity93.py
# (artifact Hetu-V20.93-Android-35-smoke › baseline-profile/baseline-prof.generated.txt).
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile', required=True)
    parser.add_argument('--mapping', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--report')
    args = parser.parse_args()
    rules, stats = retrace(Path(args.profile).read_text(errors='replace').splitlines(), args.mapping)
    Path(args.output).write_text(HEADER + '\n'.join(rules) + '\n')
    if args.report:
        Path(args.report).write_text(json.dumps(stats, indent=2) + '\n')
    print(json.dumps(stats, indent=2))
    assert stats['methods'] > 0, 'No method rules survived retrace'


if __name__ == '__main__':
    main()
