#!/usr/bin/env python3
"""Expose only reviewed layer constants to CI; retain fixed runtime and test gates."""
import argparse
import json
import os
from pathlib import Path
import subprocess

from functionfix_source_scope import ROOT, previous_files, validate_layer, digest, ROOT_SCRIPT


def load():
    layer = json.loads((ROOT / 'updates/v2086-functionfix/inputs.json').read_text())
    validate_layer(layer, previous_files())
    return layer


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('export', 'junit-filters', 'runtime-snapshot'))
    args = parser.parse_args()
    layer = load()
    if args.mode == 'export':
        values = {'HETU_EXPECTED_TESTS': layer['expectedUnitTests'],
                  'HETU_EXPECTED_TEST_XML_FILES': layer['expectedTestXmlFiles'],
                  'HETU_EXPECTED_VERSION': layer['versionCode'],
                  'HETU_EXPECTED_VERSION_NAME': layer['versionName']}
        with open(os.environ['GITHUB_ENV'], 'a') as output:
            for name, value in values.items():
                output.write(f'{name}={value}\n')
        print(json.dumps(values))
    elif args.mode == 'junit-filters':
        for name in layer['newTestCounts']:
            print('--tests')
            print(name)
    else:
        assert digest(ROOT / ROOT_SCRIPT) == layer['rootScriptSha256']
        subprocess.run(['python3', str(ROOT / '.github/scripts/verify_packaged_runtime.py'), 'snapshot',
                        '--script-sha256', layer['rootScriptSha256'],
                        '--autostart-sha256', layer['autostartScriptSha256']], cwd=ROOT, check=True)
        snapshot = json.loads((ROOT / 'out/verification/packaged-runtime.json').read_text())
        assert snapshot['originalPayloadCount'] == 22 and len(snapshot['expected']) == layer['runtimePayloadCount'] == 23
        assert snapshot['expected']['assets/hetu-root.sh'] == layer['rootScriptSha256']
        assert snapshot['expected']['assets/hetu-autostart.sh'] == layer['autostartScriptSha256']
