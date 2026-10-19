#!/usr/bin/env python3
"""Verify the shipped V20.93 release APK: non-debuggable, R8-built, baseline profile packaged,
same CI signing identity as every previous Hetu APK (so it upgrades in place), same version and
the same tested runtime payloads as the debug test build."""
import argparse
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import zipfile
import zlib

SIGNING_SHA256 = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'


# Floors for the packaged ART profile compiled from the generated baseline-prof.txt (plus library
# profiles). The hand-written 15-line wildcard profile packaged 5020 bytes / 4196 classes / 15717 hot:
# its wildcards marked every method of six whole packages hot, used or not. The ART-recorded profile
# only carries what the profiled journeys executed (run 38213108145: 7813 bytes / 3441 classes /
# 9520 hot / 10550 startup), so it must package more bytes than the old file (proves it is not the
# old profile) while class/method counts are regression floors ~10% under the recorded profile.
MIN_PROFILE_BYTES = 5021
MIN_PROFILE_CLASSES = 3100
MIN_PROFILE_HOT_METHODS = 8500
MIN_PROFILE_STARTUP_METHODS = 9500


def decode_baseline_prof(data):
    """Decode the binary assets/dexopt/baseline.prof (ART profile version 010) header and bitmaps."""
    assert data[:4] == b'pro\x00', 'baseline.prof magic'
    assert data[4:8] == b'010\x00', 'Unexpected baseline.prof version ' + repr(data[4:8])
    count = data[8]
    raw_size, packed_size = struct.unpack_from('<II', data, 9)
    body = zlib.decompress(data[17:17 + packed_size])
    assert len(body) == raw_size, 'baseline.prof body size mismatch'
    offset, dexes = 0, []
    for _ in range(count):
        key_size, classes, hot_region, _checksum, method_ids = struct.unpack_from('<HHIII', body, offset)
        offset += 16
        dexes.append({'key': body[offset:offset + key_size].decode(), 'classes': classes,
                      'hotRegion': hot_region, 'methodIds': method_ids})
        offset += key_size
    for dex in dexes:
        region = body[offset:offset + dex['hotRegion']]
        offset += dex['hotRegion']
        cursor = hot = 0
        while cursor < len(region):
            _delta, caches = struct.unpack_from('<HH', region, cursor)
            cursor += 4
            hot += 1
            for _ in range(caches):
                cursor += 2
                types = region[cursor]
                cursor += 1 + (2 * types if types < 0xfe else 0)
        offset += 2 * dex['classes']
        ids = dex['methodIds']
        bitmap = body[offset:offset + (ids * 2 + 7) // 8]
        offset += (ids * 2 + 7) // 8
        bit = lambda index: bitmap[index // 8] >> (index % 8) & 1
        dex.update(hotMethods=hot, startupMethods=sum(bit(m) for m in range(ids)),
                   postStartupMethods=sum(bit(m + ids) for m in range(ids)))
        del dex['hotRegion']
    assert offset == len(body), 'baseline.prof has undecoded trailing bytes'
    return dexes


def tool(name):
    return str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0' / name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', default='android-app/app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--debug', default='android-app/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--version-code', default='2103')
    parser.add_argument('--output', default='out/verification/release-apk.json')
    args = parser.parse_args()
    release, debug = Path(args.release), Path(args.debug)
    assert release.is_file(), 'Release APK was not built'
    badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(release)], text=True)
    assert f"versionCode='{args.version_code}'" in badging, 'Release versionCode differs'
    assert 'application-debuggable' not in badging, 'Shipped APK must not be debuggable'
    debug_badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(debug)], text=True)
    assert 'application-debuggable' in debug_badging, 'Test APK identity changed'
    package = [line for line in badging.splitlines() if line.startswith('package:')][0]
    assert package.split(' versionName=')[0] == [line for line in debug_badging.splitlines() if line.startswith('package:')][0].split(' versionName=')[0], 'Package/version identity differs from the test APK'
    certs = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(release)], text=True)
    assert SIGNING_SHA256 in certs, 'Release is not signed by the unchanged CI identity'
    with zipfile.ZipFile(release) as archive:
        names = set(archive.namelist())
        assert 'assets/dexopt/baseline.prof' in names and 'assets/dexopt/baseline.profm' in names, 'Baseline profile not packaged'
        dex = sum(archive.getinfo(n).file_size for n in names if n.startswith('classes') and n.endswith('.dex'))
        profile_bytes = archive.read('assets/dexopt/baseline.prof')
    profile = decode_baseline_prof(profile_bytes)
    profile_summary = {'bytes': len(profile_bytes), 'dexFiles': profile,
                       'classes': sum(d['classes'] for d in profile), 'hotMethods': sum(d['hotMethods'] for d in profile),
                       'startupMethods': sum(d['startupMethods'] for d in profile)}
    assert profile_summary['bytes'] >= MIN_PROFILE_BYTES, 'Packaged baseline.prof is not the generated profile: ' + str(profile_summary)
    assert profile_summary['classes'] >= MIN_PROFILE_CLASSES, 'Packaged baseline.prof has too few classes: ' + str(profile_summary)
    assert profile_summary['hotMethods'] >= MIN_PROFILE_HOT_METHODS, 'Packaged baseline.prof has too few hot methods: ' + str(profile_summary)
    assert profile_summary['startupMethods'] >= MIN_PROFILE_STARTUP_METHODS, 'Packaged baseline.prof has too few startup methods: ' + str(profile_summary)
    with zipfile.ZipFile(debug) as archive:
        debug_dex = sum(archive.getinfo(n).file_size for n in archive.namelist() if n.startswith('classes') and n.endswith('.dex'))
    assert dex < debug_dex, 'R8 did not shrink the release dex'
    manifest = Path('out/verification/packaged-runtime-release.json')
    shutil.copyfile('out/verification/packaged-runtime.json', manifest)
    subprocess.run(['python3', '.github/scripts/verify_packaged_runtime.py', 'verify', '--apk', str(release), '--manifest', str(manifest)], check=True)
    record = {'releaseApk': str(release), 'debuggable': False, 'signingSha256': SIGNING_SHA256,
              'baselineProfile': True, 'baselineProfilePackaged': profile_summary, 'releaseDexBytes': dex, 'debugDexBytes': debug_dex,
              'packagedRuntimeVerified': True, 'versionCode': args.version_code}
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
