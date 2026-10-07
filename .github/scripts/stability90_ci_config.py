#!/usr/bin/env python3
"""Export only the validated current layer values and complete JUnit identities."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from stability90_source_scope import ROOT, validate_checkout


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=('export', 'junit-filters', 'runtime-snapshot', 'verify-source'))
    args = p.parse_args()
    layer = json.loads((ROOT / 'updates/v2090-stability-glass/inputs.json').read_text())
    final = validate_checkout(ROOT, layer)
    if args.mode == 'export':
        values = {'HETU_EXPECTED_TESTS': layer['expectedUnitTests'],
                  'HETU_EXPECTED_TEST_XML_FILES': layer['expectedTestXmlFiles'],
                  'HETU_EXPECTED_VERSION': layer['versionCode'], 'HETU_EXPECTED_VERSION_NAME': layer['versionName']}
        with open(os.environ['GITHUB_ENV'], 'a') as f:
            for k, v in values.items():
                f.write(f'{k}={v}\n')
        print(json.dumps(values))
    elif args.mode == 'junit-filters':
        for cls in layer['finalUnitTestMethods']:
            print('--tests')
            print(cls)
    elif args.mode == 'runtime-snapshot':
        subprocess.run(['python3', str(ROOT / '.github/scripts/verify_packaged_runtime.py'), 'snapshot',
                        '--script-sha256', layer['rootScriptSha256'],
                        '--autostart-sha256', layer['autostartScriptSha256']], cwd=ROOT, check=True)
        snapshot = json.loads((ROOT / 'out/verification/packaged-runtime.json').read_text())
        assert snapshot['originalPayloadCount'] == 22 and len(snapshot['expected']) == 23
    else:
        print(json.dumps({'requiredEffectiveInputs': len(final), 'allAvailableInputsMatch': True,
                          'completeSourceRequiredByBuild': True}))
