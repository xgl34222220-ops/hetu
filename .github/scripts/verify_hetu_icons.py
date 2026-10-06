"""Fail closed if the existing, approved image assets change during transport/build."""
import argparse
import hashlib
from pathlib import Path
import struct
import zipfile

EXPECTED = {
    'ic_hetu_official.webp': 'ffd1dce1e68a23d47a9a87faf69d94cfbd9dcb428f7cb538fb8ab305199d4fcf',
    'ic_hetu_concept.png': '31573825cac7536b442c67e7a8110fd5584715a347d9cc8e9ef50cc66b29d04c',
}

def verify(name, data):
    digest = hashlib.sha256(data).hexdigest()
    assert digest == EXPECTED[name], f'{name}: unexpected image bytes {digest}'
    if name.endswith('.webp'):
        assert data[:4] == b'RIFF' and data[8:12] == b'WEBP', name
        assert struct.unpack('<I', data[4:8])[0] + 8 == len(data), f'{name}: truncated RIFF'
    else:
        assert data[:8] == b'\x89PNG\r\n\x1a\n', name
    print(f'{name}: {len(data)} bytes SHA256 {digest} verified')

def main():
    p = argparse.ArgumentParser()
    p.add_argument('mode', choices=['source', 'apk'])
    p.add_argument('path', type=Path)
    args = p.parse_args()
    if args.mode == 'source':
        for name in EXPECTED:
            verify(name, (args.path / 'app/src/main/res/drawable' / name).read_bytes())
    else:
        with zipfile.ZipFile(args.path) as apk:
            for name in EXPECTED:
                matches = [n for n in apk.namelist() if n.rsplit('/', 1)[-1] == name]
                assert len(matches) == 1, (name, matches)
                verify(name, apk.read(matches[0]))

if __name__ == '__main__':
    main()
