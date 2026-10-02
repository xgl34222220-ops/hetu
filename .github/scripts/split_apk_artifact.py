#!/usr/bin/env python3
"""Transport-only chunks; installable APK remains the primary artifact."""
import hashlib
import json
from pathlib import Path
import sys

apk = Path(sys.argv[1])
out = Path('out/apk-transfer')
out.mkdir(parents=True, exist_ok=True)
size = 24 * 1024 * 1024
parts = []
whole = hashlib.sha256()
with apk.open('rb') as src:
    while data := src.read(size):
        index = len(parts)
        assert index < 6, 'APK exceeds the six transport-artifact slots; do not truncate'
        name = f'part{index:02d}.bin'
        (out / name).write_bytes(data)
        whole.update(data)
        parts.append({'index': index, 'name': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
assert parts, 'Empty APK'
manifest = {'schema': 1, 'apkName': apk.name, 'apkBytes': apk.stat().st_size,
            'apkSha256': whole.hexdigest(), 'chunkBytes': size, 'partCount': len(parts), 'parts': parts}
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
