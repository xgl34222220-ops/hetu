#!/usr/bin/env python3
"""Derive the user-facing arm64-v8a-only release APK from the verified universal release APK.

2026-10-13 arm64 release authorization: the universal release (R8, baseline profile, both ABIs) stays
the CI smoke candidate on the x86_64 API 35/36 emulators. The shipped APK is the same file with every
non-arm64-v8a native library and the x86_64 Mihomo / eBPF loader assets removed, then zip-aligned and
re-signed with the unchanged CI identity. No entry is rewritten: every kept entry is byte-identical
(same name, CRC and bytes) to the tested universal release, and no payload bytes in the repository
change. Removal uses `zip -d` (raw copy of the kept entries) so nothing is recompressed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ABI = 'arm64-v8a'
SIGNING_SHA256 = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
ABI_ASSET_ROOTS = ('assets/mihomo-root/', 'assets/hetu-bpf/')
KNOWN_ABIS = ('arm64-v8a', 'armeabi-v7a', 'armeabi', 'x86', 'x86_64', 'riscv64', 'mips', 'mips64')
SIGNATURE_FILE = re.compile(r'^META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))$')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def tool(name):
    return str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0' / name)


def dropped(name):
    """True for an entry that belongs to another ABI (native library or per-ABI root asset)."""
    if name.startswith('lib/'):
        return name.split('/')[1] != ABI
    for root in ABI_ASSET_ROOTS:
        if name.startswith(root):
            parts = name[len(root):].split('/')
            return len(parts) > 1 and parts[0] in KNOWN_ABIS and parts[0] != ABI
    return False


def schemes(apk):
    text = subprocess.check_output([tool('apksigner'), 'verify', '-v', str(apk)], text=True)
    found = {}
    for version in ('1', '2', '3'):
        match = re.search(rf'Verified using v{version} scheme \(.*\): (true|false)', text)
        found[version] = bool(match and match.group(1) == 'true')
    return found


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('universal', type=Path, help='Verified universal release APK')
    parser.add_argument('output', type=Path, help='arm64-v8a-only release APK to write')
    parser.add_argument('--keystore', default=os.environ.get('HETU_TEST_KEYSTORE'))
    parser.add_argument('--runtime-manifest', default='out/verification/packaged-runtime.json')
    parser.add_argument('--version-code', default='2096')
    parser.add_argument('--report', default='out/verification/release-arm64-apk.json')
    parser.add_argument('--signing-sha256', default=SIGNING_SHA256, help='Expected signer (override only for local dry runs)')
    args = parser.parse_args()
    assert args.keystore and Path(args.keystore).is_file(), 'CI signing keystore missing'
    assert shutil.which('zip'), 'zip is required to drop entries without recompressing'

    with zipfile.ZipFile(args.universal) as archive:
        universal = {info.filename: info for info in archive.infolist()}
        universal_bytes = {name: archive.read(name) for name in universal}
    removed = sorted(name for name in universal if dropped(name))
    stale_signature = sorted(name for name in universal if SIGNATURE_FILE.match(name))
    assert f'lib/{ABI}/libhetu_core.so' in universal and f'assets/mihomo-root/{ABI}/mihomo' in universal
    assert removed, 'Universal APK has no other-ABI entries to drop'
    signing = schemes(args.universal)
    assert signing['2'], 'Universal release is not v2-signed'
    # apksigner verify skips v1 for minSdk >= 24, so mirror v1 from the JAR signature files themselves.
    v1 = any(name.endswith('.SF') for name in stale_signature)
    v1_name = next((Path(name).stem for name in stale_signature if name.endswith('.SF')), 'CERT')

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='hetu-arm64-') as directory:
        stripped = Path(directory) / 'stripped.apk'
        aligned = Path(directory) / 'aligned.apk'
        shutil.copyfile(args.universal, stripped)
        # zip -d copies every kept entry raw; it also drops the old v2/v3 signing block.
        subprocess.run(['zip', '-q', '-d', str(stripped), *removed, *stale_signature], check=True)
        subprocess.run([tool('zipalign'), '-f', '-p', '4', str(stripped), str(aligned)], check=True)
        subprocess.run([tool('apksigner'), 'sign', '--ks', args.keystore, '--ks-key-alias', 'androiddebugkey',
                        '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                        '--v1-signing-enabled', str(v1).lower(), '--v1-signer-name', v1_name,
                        '--v2-signing-enabled', 'true',
                        '--v3-signing-enabled', str(signing['3']).lower(),
                        '--v4-signing-enabled', 'false',
                        '--out', str(args.output), str(aligned)], check=True)

    # Verify: signature identity/schemes, alignment, entry set and byte identity, payload pins.
    certs = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(args.output)], text=True)
    assert args.signing_sha256 in certs, 'arm64 APK is not signed by the unchanged CI identity'
    assert schemes(args.output) == signing, 'arm64 APK signature schemes differ from the universal release'
    subprocess.run([tool('zipalign'), '-c', '-p', '4', str(args.output)], check=True)
    badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(args.output)], text=True)
    assert f"versionCode='{args.version_code}'" in badging, 'arm64 versionCode differs'
    assert 'application-debuggable' not in badging, 'arm64 APK must not be debuggable'
    native = [line for line in badging.splitlines() if line.startswith('native-code:')]
    assert native == [f"native-code: '{ABI}'"], f'Unexpected native-code: {native}'
    universal_badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(args.universal)], text=True)
    strip = lambda text: [line for line in text.splitlines() if not line.startswith(('native-code:', 'alt-native-code:'))]
    assert strip(badging) == strip(universal_badging), 'arm64 badging differs beyond native-code'

    with zipfile.ZipFile(args.output) as archive:
        names = {info.filename: info for info in archive.infolist()}
        kept = set(universal) - set(removed) - set(stale_signature)
        new_signature = {name for name in names if SIGNATURE_FILE.match(name)}
        assert any(name.endswith('.SF') for name in new_signature) is v1, 'v1 JAR signature presence differs from the universal release'
        assert set(names) - new_signature == kept, 'arm64 entry set differs from universal minus other ABIs'
        for name in sorted(kept):
            assert names[name].CRC == universal[name].CRC and archive.read(name) == universal_bytes[name], f'Entry changed: {name}'
            assert names[name].compress_type == universal[name].compress_type, f'Entry recompressed: {name}'
        for name in names:
            assert not dropped(name), f'Other-ABI entry left in arm64 APK: {name}'
        runtime = json.loads(Path(args.runtime_manifest).read_text())['expected']
        kept_payloads, dropped_payloads = {}, []
        for name, digest in sorted(runtime.items()):
            if dropped(name):
                assert name not in names
                dropped_payloads.append(name)
            else:
                assert sha(archive.read(name)) == digest, f'Packaged payload differs from tested source: {name}'
                kept_payloads[name] = digest
    expected_dropped = {'lib/x86_64/libhetu_core.so', 'assets/mihomo-root/x86_64/mihomo', 'assets/hetu-bpf/x86_64/hetu-bpf'}
    assert set(dropped_payloads) <= expected_dropped and 'lib/x86_64/libhetu_core.so' in dropped_payloads
    record = {'abi': ABI, 'universalApk': str(args.universal), 'universalBytes': args.universal.stat().st_size,
              'universalSha256': sha(args.universal.read_bytes()), 'arm64Apk': str(args.output),
              'arm64Bytes': args.output.stat().st_size, 'arm64Sha256': sha(args.output.read_bytes()),
              'removedEntries': removed, 'replacedSignatureEntries': stale_signature,
              'signatureSchemes': {**signing, 'v1JarSignature': v1}, 'signingSha256': args.signing_sha256,
              'keptRuntimePayloads': kept_payloads, 'droppedRuntimePayloads': dropped_payloads,
              'keptEntriesByteIdentical': len(kept)}
    Path(args.report).parent.mkdir(parents=True, exist_ok=True)
    Path(args.report).write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps({k: v for k, v in record.items() if k != 'keptRuntimePayloads'}, indent=2))


if __name__ == '__main__':
    main()
