#!/usr/bin/env python3
"""Verify that the tested shell and every original native payload enter the APK."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_path(name):
    if name.startswith('lib/'):
        return Path('android-app/app/src/main/jniLibs') / name.removeprefix('lib/')
    if name.startswith('assets/'):
        return Path('android-app/app/src/main') / name
    raise AssertionError(f'Unsupported runtime payload: {name}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['snapshot', 'verify'])
    parser.add_argument('--script-sha256')
    parser.add_argument('--apk', default='android-app/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--manifest', default='out/verification/packaged-runtime.json')
    args = parser.parse_args()
    output = Path(args.manifest)
    original = json.loads(Path('UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload']
    script = 'assets/hetu-root.sh'
    assert script in original
    if args.mode == 'snapshot':
        assert args.script_sha256 and len(args.script_sha256) == 64, 'Pinned tested script hash required'
        expected = dict(original)
        expected[script] = args.script_sha256
        for name, digest in expected.items():
            assert sha(source_path(name).read_bytes()) == digest, f'Source runtime hash mismatch: {name}'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({'expected': expected, 'originalPayloadCount': len(original),
                                     'editedPayloads': [script], 'apkVerified': False}, indent=2) + '\n')
        print(f'Snapshot verified: {len(expected)-1} unchanged runtime payloads + pinned tested shell')
    else:
        record = json.loads(output.read_text())
        assert set(record['expected']) == set(original), 'Runtime manifest is incomplete'
        assert record['editedPayloads'] == [script], 'Unexpected runtime payload change'
        for name, digest in original.items():
            if name != script:
                assert record['expected'][name] == digest, f'Original runtime hash changed: {name}'
        with zipfile.ZipFile(args.apk) as archive:
            for name, digest in record['expected'].items():
                assert sha(archive.read(name)) == digest, f'Packaged runtime differs from tested source: {name}'
        record['apkVerified'] = True
        record['apkSha256'] = sha(Path(args.apk).read_bytes())
        output.write_text(json.dumps(record, indent=2) + '\n')
        print(f'APK runtime verified: all {len(original)} entries match tested source/pinned originals')


if __name__ == '__main__':
    main()
