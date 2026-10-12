#!/usr/bin/env python3
"""Export only the validated current continuation's source and JUnit identities."""
import argparse
import json
import os
import subprocess

from continuity93_source_scope import ROOT, LAYER_FOLDER, validate_checkout, EBPF_FILES


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('export', 'junit-filters', 'runtime-snapshot', 'verify-source'))
    args = parser.parse_args()
    layer = json.loads((ROOT / LAYER_FOLDER / 'inputs.json').read_text())
    final = validate_checkout(ROOT, layer)
    if args.mode == 'export':
        values = {'HETU_EXPECTED_TESTS': layer['expectedUnitTests'], 'HETU_EXPECTED_TEST_XML_FILES': layer['expectedTestXmlFiles'],
                  'HETU_EXPECTED_VERSION': layer['versionCode'], 'HETU_EXPECTED_VERSION_NAME': layer['versionName']}
        with open(os.environ['GITHUB_ENV'], 'a') as output:
            for key, value in values.items():
                output.write(f'{key}={value}\n')
        print(json.dumps(values))
    elif args.mode == 'junit-filters':
        for cls in layer['finalUnitTestMethods']:
            print('--tests')
            print(cls)
    elif args.mode == 'runtime-snapshot':
        # 2026-10-13 eBPF authorization: the three pinned eBPF payloads ride along only when the
        # sealed layer records them; the original 23 entries are checked exactly as before.
        ebpf = sorted(EBPF_FILES & set(layer['changedOrAddedFiles']))
        assert ebpf in ([], sorted(EBPF_FILES)), 'eBPF payloads must be sealed all together'
        subprocess.run(['python3', str(ROOT / '.github/scripts/verify_packaged_runtime.py'), 'snapshot',
                        '--script-sha256', layer['rootScriptSha256'], '--autostart-sha256', layer['autostartScriptSha256'],
                        *(['--ebpf'] if ebpf else [])], cwd=ROOT, check=True)
        snapshot = json.loads((ROOT / 'out/verification/packaged-runtime.json').read_text())
        assert snapshot['originalPayloadCount'] == 22 and len(snapshot['expected']) == 23 + len(ebpf)
        assert snapshot['ebpfPayloadsFromSource'] is bool(ebpf)
    else:
        print(json.dumps({'requiredEffectiveInputs': len(final), 'allAvailableInputsMatch': True, 'completeSourceRequiredByBuild': True}))
