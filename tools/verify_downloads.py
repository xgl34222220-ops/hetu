#!/usr/bin/env python3
"""Verify the actual install packages committed to downloads/, including identity."""
import hashlib
import json
import re
import subprocess
import zipfile
from build_app import sdk_paths
from package import ROOT, VERSION, CODE, verify_module

# Public certificate fingerprint only; the private keystore never enters Git.
CERT_SHA256 = '35ce73c392ad7dd94a1e0c60dd4d89958d4e878cef7f865ceb2e3f91b26cab46'


def main():
    folder = ROOT / 'downloads'
    apk = folder / f'Hetu-{VERSION}.apk'
    module = folder / f'Hetu-{VERSION}-module.zip'
    expected = {apk.name, module.name}
    records = {}
    for line in (folder / 'SHA256SUMS.txt').read_text().splitlines():
        digest, name = line.split('  ', 1)
        if name in records or name not in expected or not re.fullmatch('[a-f0-9]{64}', digest):
            raise RuntimeError('Invalid download checksum manifest')
        records[name] = digest
    if set(records) != expected:
        raise RuntimeError('Incomplete download checksum manifest')
    for path in (apk, module):
        if hashlib.sha256(path.read_bytes()).hexdigest() != records[path.name]:
            raise RuntimeError(f'Checksum mismatch: {path.name}')
    verify_module(module)
    built = ROOT / 'out' / module.name
    if built.read_bytes() != module.read_bytes():
        raise RuntimeError('Download module differs from current source build')
    with zipfile.ZipFile(apk) as z:
        if z.testzip() is not None or z.read('assets/hetu-module.zip') != module.read_bytes():
            raise RuntimeError('APK corrupt or embedded module differs')
        info = json.loads(z.read('assets/module-info.json'))
        if info['sha256'] != records[module.name] or info['versionCode'] != CODE:
            raise RuntimeError('APK embedded manifest mismatch')
    tools, _ = sdk_paths()
    signature = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    if f'Signer #1 certificate SHA-256 digest: {CERT_SHA256}' not in signature:
        raise RuntimeError('Download APK does not retain the previous signing identity')
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True).splitlines()[0]
    for part in ("name='io.github.xgl34222220.hetu'", f"versionCode='{CODE}'", f"versionName='{VERSION}'"):
        if part not in badging:
            raise RuntimeError('Download APK identity/version mismatch')
    print('PASS: distributed APK signature, version, checksums, ZIP layout and embedded module/source equality')


if __name__ == '__main__':
    main()
