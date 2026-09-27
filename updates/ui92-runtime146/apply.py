#!/usr/bin/env python3
"""Apply the reviewed, locally compiled source delta without merging old UI branches.

Existing Git objects are compression dictionaries, not source-version selections.
Every output is checked against its final Git blob hash before any file is written.
No repository history, branches, tags, signing material, or user data are deleted.
"""
from pathlib import Path, PurePosixPath
import base64
import hashlib
import json
import lzma
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
EXPECTED = 'aac456149c763b27e64e4547edb15ce59c4c136b1be676b12a516a532151693e'


def blob(raw: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def git_blob(sha: str) -> bytes:
    if len(sha) != 40 or any(c not in '0123456789abcdef' for c in sha):
        raise ValueError('Invalid blob identifier')
    raw = subprocess.check_output(['git', 'cat-file', 'blob', sha], cwd=ROOT)
    if blob(raw) != sha:
        raise ValueError('Git object checksum mismatch')
    return raw


def main() -> None:
    encoded = ''.join((HERE / ('source-delta.%02d' % i)).read_text().strip() for i in range(4))
    raw = lzma.decompress(base64.b64decode(encoded, validate=True))
    if hashlib.sha256(raw).hexdigest() != EXPECTED:
        raise ValueError('Source delta checksum mismatch; nothing changed')
    manifest = json.loads(raw)
    pending = []
    for entry in manifest['files']:
        rel = PurePosixPath(entry['path'])
        if rel.is_absolute() or '..' in rel.parts or not (
            rel.parts[0] in ('android-app', 'native', 'tests') or str(rel) == 'UI92_RUNTIME146_INPUTS.json'
        ):
            raise ValueError('Path outside the reviewed source scope')
        target = ROOT / rel
        if target.is_symlink():
            raise ValueError('Refusing a symlink target')
        old = blob(target.read_bytes()) if target.is_file() else None
        if old == entry['sha']:
            continue
        if old != entry['old']:
            raise ValueError('Concurrent source change: ' + str(rel))
        if 'blob' in entry:
            content = git_blob(entry['blob'])
        elif 'parts' in entry:
            base = git_blob(entry['base'])
            pieces = []
            for part in entry['parts']:
                if isinstance(part, list):
                    offset, length = part
                    if offset < 0 or length < 0 or offset + length > len(base):
                        raise ValueError('Invalid source slice')
                    pieces.append(base[offset:offset + length])
                else:
                    pieces.append(base64.b64decode(part, validate=True))
            content = b''.join(pieces)
        elif 'edits' in entry:
            lines = git_blob(entry['base']).decode('utf-8').splitlines(keepends=True)
            for start, end, text in reversed(entry['edits']):
                lines[start:end] = [text]
            content = ''.join(lines).encode('utf-8')
        else:
            content = entry['text'].encode('utf-8')
        if blob(content) != entry['sha']:
            raise ValueError('Reconstructed source mismatch: ' + str(rel))
        pending.append((target, content, entry['mode']))
    # All preconditions and output hashes have passed before the first write.
    for target, content, mode in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        target.chmod(0o755 if mode == '100755' else 0o644)
    evidence = ROOT / 'integration-evidence'
    evidence.mkdir(exist_ok=True)
    (evidence / 'changed-paths.json').write_text(json.dumps([e['path'] for e in manifest['files']], indent=2))
    (evidence / 'source-delta.json').write_text(json.dumps({
        'delta_sha256': EXPECTED,
        'source_paths': len(manifest['files']),
        'applied_paths': len(pending),
        'ui_commit': '84d14dc1f673f557bffbb67d09a7c0ee69321e04',
        'runtime_apk_sha256': '9f276e8562d79011fffa4f57c1469063b3b4a2ff0208be249e0cfcb478ce508c',
        'outputs': {e['path']: e['sha'] for e in manifest['files']},
    }, indent=2))
    print('Verified source files:', len(manifest['files']), 'Applied:', len(pending))


if __name__ == '__main__':
    main()
