#!/usr/bin/env python3
"""Verify the shipped V20.93 release APK: non-debuggable, R8-built, baseline profile packaged,
same CI signing identity as every previous Hetu APK (so it upgrades in place), same version and
the same tested runtime payloads as the debug test build."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

SIGNING_SHA256 = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'


def tool(name):
    return str(Path(os.environ['ANDROID_HOME']) / 'build-tools/37.0.0' / name)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--release', default='android-app/app/build/outputs/apk/release/app-release.apk')
    parser.add_argument('--debug', default='android-app/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--version-code', default='2093')
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
    with zipfile.ZipFile(debug) as archive:
        debug_dex = sum(archive.getinfo(n).file_size for n in archive.namelist() if n.startswith('classes') and n.endswith('.dex'))
    assert dex < debug_dex, 'R8 did not shrink the release dex'
    manifest = Path('out/verification/packaged-runtime-release.json')
    shutil.copyfile('out/verification/packaged-runtime.json', manifest)
    subprocess.run(['python3', '.github/scripts/verify_packaged_runtime.py', 'verify', '--apk', str(release), '--manifest', str(manifest)], check=True)
    record = {'releaseApk': str(release), 'debuggable': False, 'signingSha256': SIGNING_SHA256,
              'baselineProfile': True, 'releaseDexBytes': dex, 'debugDexBytes': debug_dex,
              'packagedRuntimeVerified': True, 'versionCode': args.version_code}
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    Path(args.output).write_text(json.dumps(record, indent=2) + '\n')
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
