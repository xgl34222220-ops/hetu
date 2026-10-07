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


def load_runtime_layer():
    """The effective runtime layer: v2089 r153 if present, else v2086."""
    r153_inputs = ROOT / 'updates/v2089-runtime153/inputs.json'
    if r153_inputs.exists():
        from runtime153_source_scope import validate_layer as validate_r153
        r153 = json.loads(r153_inputs.read_text())
        # previous is the v2086-final file map; validate against it.
        base = load()
        # Build the file map after v2086 + presentation layers from the checkout.
        import subprocess, hashlib
        out = subprocess.run(['git', 'ls-files', 'android-app'], cwd=str(ROOT),
                             capture_output=True, text=True).stdout.split()
        prev = {}
        for f in out:
            blob = subprocess.run(['git', 'show', f'HEAD:{f}'], cwd=str(ROOT),
                                  capture_output=True).stdout
            prev[f] = hashlib.sha256(blob).hexdigest()
        validate_r153(r153, prev)
        # Return a merged view: v2086 constants + r153 runtime SHAs.
        merged = dict(base)
        merged['rootScriptSha256'] = r153['rootScriptSha256']
        return merged
    return load()


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
        layer = load_runtime_layer()
        assert digest(ROOT / ROOT_SCRIPT) == layer['rootScriptSha256']
        subprocess.run(['python3', str(ROOT / '.github/scripts/verify_packaged_runtime.py'), 'snapshot',
                        '--script-sha256', layer['rootScriptSha256'],
                        '--autostart-sha256', layer['autostartScriptSha256']], cwd=ROOT, check=True)
        snapshot = json.loads((ROOT / 'out/verification/packaged-runtime.json').read_text())
        assert snapshot['originalPayloadCount'] == 22 and len(snapshot['expected']) == layer['runtimePayloadCount'] == 23
        assert snapshot['expected']['assets/hetu-root.sh'] == layer['rootScriptSha256']
        assert snapshot['expected']['assets/hetu-autostart.sh'] == layer['autostartScriptSha256']
