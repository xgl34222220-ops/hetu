#!/usr/bin/env python3
"""Consolidate the 173 reference-state inventory; never equate capture with parity."""
import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[2])
parser.add_argument('--expected-tests', type=int, default=160)
a = parser.parse_args()
repo = a.repo.resolve()
qa = repo / 'docs/qa'
base = json.loads((qa / 'concept-state-coverage.json').read_text())
supplement = {x['reference']: x for x in json.loads((qa / 'concept-remaining-coverage.json').read_text())['pages']}
counts = Counter()
for p in (repo / 'android-app/app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    result = ET.parse(p).getroot()
    for k in ['tests', 'failures', 'errors', 'skipped']:
        counts[k] += int(result.get(k, 0))
assert counts['tests'] == a.expected_tests and counts['failures'] == counts['errors'] == counts['skipped'] == 0, counts
captures = {}
for folder in ['concept-state-coverage', 'concept-remaining-coverage']:
    for p in (repo / 'android-app/app/build/outputs' / folder).glob('*.json'):
        m = re.match(r'(01|02|03A|03B|04)-(\d{3})-', p.stem)
        if not m:
            continue
        image = p.with_suffix('.png')
        assert image.exists()
        data = json.loads(p.read_text())
        captures.setdefault(f'{m[1]}/{m[2]}', []).append({
            'image': str(image.relative_to(repo)), 'metadata': str(p.relative_to(repo)),
            'image_sha256': hashlib.sha256(image.read_bytes()).hexdigest(),
            'captured_at_utc': data.get('capturedAtUtc'), 'api_level': data.get('apiLevel', 35),
            'fixture_only': True,
        })
offline = {'02/029': 'panel-stopped', '03A/001': 'tools-offline', '03A/004': 'configs',
           '03A/038': 'shared-network', '03A/039': 'cnip', '03A/040': 'diagnostics',
           '03B/045': 'sub-store', '04/001': 'settings-offline'}
corrected_notes = {
    '01/009': 'Production IP details with synthetic response values; existing upstream optional fields are displayed only when available. ISP and ASN copy actions verified.',
    '01/010': 'Production LAN tab with isolated runtime fixture and real tab interaction.',
    '01/013': 'Deterministic test-only telemetry; missing CPU samples are gaps, memory remains independently present. Not a live device trace.',
    '01/014': 'Empty production telemetry history; absence is not represented as zero.',
    '03B/031': 'Reference radio/title/scrim menu; automation remains OFF and no service/network mutation is performed.',
    '03B/032': 'Reference radio/title/scrim menu; automation remains OFF and no service/network mutation is performed.',
    '04/031': 'Actual full bundled GPL text, wrapped and scrollable; not replaced by the short reference excerpt.',
    '04/032': 'Actual full bundled GPL text, wrapped and scrollable; not replaced by the short reference excerpt.',
    '04/033': 'Actual bundled ISC attribution, wrapped and scrollable; full text retained.',
}
rows = []
for old in base['pages']:
    ref = old['reference']
    row = {k: old[k] for k in ['reference', 'title', 'reference_pdf', 'reference_image']}
    row['pixel_exact_pass'] = False
    row['visual_acceptance'] = 'review_in_progress'
    row['limitations'] = corrected_notes.get(ref, supplement.get(ref, {}).get('limitations', old.get('caveat', '')))
    if ref in captures:
        row['status'] = 'controlled_native_capture'
        row['evidence'] = sorted(captures[ref], key=lambda x: x.get('captured_at_utc') or '')
    elif ref in offline:
        p = repo / 'android-app/app/build/outputs/concept59' / (offline[ref] + '.png')
        assert p.exists(), p
        row['status'] = 'current_native_offline_fixture'
        row['evidence'] = [{'image': str(p.relative_to(repo)), 'image_sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'fixture_only': True}]
        row['limitations'] = 'Current production renderer and passed native test; offline/empty data differs from the populated reference. This is not pixel acceptance.'
    else:
        assert ref in {f'03B/{i:03d}' for i in range(40, 45)}, ref
        row['status'] = 'native_webview_content_pending'
        row['limitations'] = 'Robolectric cannot render Chromium. CI browser fixtures are supplementary evidence, not Android WebView/device acceptance.'
    rows.append(row)
assert len(rows) == len({x['reference'] for x in rows}) == 173
sources = {}
for p in (repo / 'android-app/app/src').rglob('*'):
    if p.is_file() and p.suffix in {'.java', '.kt', '.xml', '.sh', '.png', '.webp'}:
        sources[str(p.relative_to(repo))] = hashlib.sha256(p.read_bytes()).hexdigest()
result = {'schema': 2, 'reference_pages': 173, 'test_counts': dict(counts),
          'status_counts': dict(Counter(x['status'] for x in rows)),
          'boundaries': ['System fonts retained; editor uses the system monospace face.',
                         'Synthetic data exists only in test sources. No real Root mutations, device restarts or live backend changes.',
                         'Scripts and runtime editor use an explicitly labeled API-28 raster fallback; hardware effects are not verified.',
                         'OS bars, keyboard, real WebView and GPU blur require separate device verification.',
                         'Capture inventory and passing tests do not imply 173-state pixel parity.'],
          'source_sha256': sources, 'pages': rows}
(qa / 'concept-173-current.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
lines = ['# 173状态当前验收记录', '', f"Android测试：{counts['tests']}项通过，失败/错误/跳过均为0。", '',
         '截图覆盖不等于视觉通过。真实设备、键盘、GPU材质和WebView加载仍需单独验证。', '',
         '| 参考页 | 状态 | 当前证据 |', '|---|---|---|']
for x in rows:
    lines.append(f"| {x['reference']} | {x['title']} | {x['status']} |")
(qa / 'concept-173-current.md').write_text('\n'.join(lines) + '\n')
print(json.dumps({'tests': dict(counts), 'states': result['status_counts']}, ensure_ascii=False))
