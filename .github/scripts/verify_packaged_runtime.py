#!/usr/bin/env python3
"""Verify that the tested shell and every original native payload enter the APK."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

# 2026-10-13 eBPF matcher authorization: three payloads added next to (never replacing) the original
# 23. Each is built reproducibly from in-repo source under native/ebpf (native/ebpf/build.sh) and is
# pinned here; any other added payload, or any other byte, is rejected.
EBPF_PAYLOADS = {
    'assets/hetu-bpf/hetu_match.o': '096e6be233a226866a1ead5de94f671fa77cee09ec4cf1e9cb01d9875c5920de',
    'assets/hetu-bpf/arm64-v8a/hetu-bpf': '72cd1b8e59d62d0d04bb09f674cbea415036d22f3f6ffec571048d6b49ad8046',
    'assets/hetu-bpf/x86_64/hetu-bpf': '3dd6a2246ee8b4f34d2ab4dcffef8c5d288a1432b7aa0d2b1cd9df111c5b7968',
}
AUTOSTART = 'assets/hetu-autostart.sh'
ALLOWED_ADDED = ([], [AUTOSTART], sorted(EBPF_PAYLOADS), [AUTOSTART, *sorted(EBPF_PAYLOADS)])


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
    parser.add_argument('--autostart-sha256')
    parser.add_argument('--ebpf', action='store_true', help='Include the three pinned eBPF payloads')
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
        added = []
        if args.autostart_sha256:
            assert len(args.autostart_sha256) == 64
            expected[AUTOSTART] = args.autostart_sha256
            added = [AUTOSTART]
        if args.ebpf:
            assert not set(EBPF_PAYLOADS) & set(expected), 'eBPF payload collides with an original payload'
            expected.update(EBPF_PAYLOADS)
            added += sorted(EBPF_PAYLOADS)
        for name, digest in expected.items():
            assert sha(source_path(name).read_bytes()) == digest, f'Source runtime hash mismatch: {name}'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps({'expected': expected, 'originalPayloadCount': len(original),
                                     'editedPayloads': [script], 'addedPayloads': added,
                                     'ebpfPayloadsFromSource': bool(args.ebpf), 'apkVerified': False}, indent=2) + '\n')
        print(f'Snapshot verified: {len(original)-1} unchanged runtime payloads + tested shell + {len(added)} added payloads')
    else:
        record = json.loads(output.read_text())
        added = record.get('addedPayloads', [])
        assert added in ALLOWED_ADDED, 'Unexpected additional payload'
        for name in set(added) & set(EBPF_PAYLOADS):
            assert record['expected'][name] == EBPF_PAYLOADS[name], f'eBPF payload differs from pinned source build: {name}'
        assert record.get('ebpfPayloadsFromSource', False) is bool(set(added) & set(EBPF_PAYLOADS)), 'eBPF flag mismatch'
        assert set(record['expected']) == set(original) | set(added), 'Runtime manifest is incomplete'
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
        print(f'APK runtime verified: all {len(record['expected'])} entries match tested source/pinned originals')


if __name__ == '__main__':
    main()
