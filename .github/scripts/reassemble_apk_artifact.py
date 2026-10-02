#!/usr/bin/env python3
"""Reassemble verified transport chunks; reject missing, extra, or altered bytes."""
import hashlib
import json
import os
from pathlib import Path
import sys

manifest = json.loads(Path(sys.argv[1]).read_text())
folder = Path(sys.argv[2])
output = Path(sys.argv[3])
parts = manifest['parts']
assert manifest['schema'] == 1
assert manifest['partCount'] == len(parts) and 1 <= len(parts) <= 6
assert manifest['chunkBytes'] == 24 * 1024 * 1024
assert sum(p['bytes'] for p in parts) == manifest['apkBytes'] > 0
assert {p.name for p in folder.glob('part*.bin')} == {p['name'] for p in parts}, 'Missing or extra part'
whole = hashlib.sha256()
output.parent.mkdir(parents=True, exist_ok=True)
temporary = output.with_name(output.name + '.unverified')
try:
    with temporary.open('wb') as dst:
        for index, part in enumerate(parts):
            assert part['index'] == index and part['name'] == f'part{index:02d}.bin', 'Invalid part order/name'
            assert 0 < part['bytes'] <= manifest['chunkBytes']
            if index + 1 < len(parts):
                assert part['bytes'] == manifest['chunkBytes']
            data = (folder / part['name']).read_bytes()
            assert len(data) == part['bytes'], f'Length mismatch: {part["name"]}'
            assert hashlib.sha256(data).hexdigest() == part['sha256'], f'Hash mismatch: {part["name"]}'
            dst.write(data)
            whole.update(data)
    assert temporary.stat().st_size == manifest['apkBytes']
    assert whole.hexdigest() == manifest['apkSha256'], 'Full APK hash mismatch'
    os.replace(temporary, output)
except BaseException:
    temporary.unlink(missing_ok=True)
    raise
print(f'Verified {len(parts)} parts and whole APK SHA256: {whole.hexdigest()}')
