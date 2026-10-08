#!/usr/bin/env python3
"""Read-only, exact-byte transport for the fixed synthetic Hetu UI evidence list.

This does not generate screenshots or turn missing evidence into a test pass.
It supplements the unchanged artifact upload when that download is unavailable.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import struct

PREFIX = 'HETU_UI93_PNG '
CHUNK_BYTES = 3072  # 4096 base64 characters; each log line stays small.
MAX_FILE_BYTES = 512 * 1024
MAX_GROUP_BYTES = 4 * 1024 * 1024
OUT = 'android-app/app/build/outputs/'
HOST = tuple(OUT + 'glass90/' + name + '.png' for name in (
    'light-home', 'light-panel', 'light-tools', 'light-settings',
    'dark-home', 'dark-panel', 'dark-tools', 'dark-settings')) + tuple(
    OUT + 'ui93/' + name + '.png' for name in (
    'group-collapsed-measuring', 'node-measurement-failed', 'compact-large-font-node',
    'delay-result-settled', 'dock-large-font-2x', 'dock-attached',
    'dock-repeated-taps-settled', 'dock-motion-off-after-2s')) + tuple(
    OUT + 'startup-config-feedback/' + name + '.png' for name in (
    'settings-actual-large-entry', 'settings-visible-eof', 'settings-actual-read-error',
    'tools-actual-large-entry')) + (
    OUT + 'glass91/dark-settings-group.png',
    OUT + 'concept-state-coverage/04-009-theme-motion.png',
    OUT + 'concept-state-coverage/04-029-mirror-dialog.png')
API36 = tuple('out/android-smoke/' + name + '.png' for name in (
    '01-home-new-ui', '02-panel-stopped', '03-tools', '04-settings',
    'panel-controller-200-footer-before', 'panel-controller-200-footer-after-1',
    'panel-controller-200-footer-after-2'))
OPTIONAL = {'out/android-smoke/panel-controller-200-footer-after-2.png'}
GROUPS = {'host': HOST, 'api36': API36}
ALLOWED = frozenset(HOST + API36)
assert len(HOST) == 23 and len(API36) == 7 and len(ALLOWED) == 30


def png_size(data):
    if len(data) < 33 or data[:8] != b'\x89PNG\r\n\x1a\n' or data[12:16] != b'IHDR':
        raise ValueError('Invalid PNG signature or IHDR')
    width, height = struct.unpack('>II', data[16:24])
    if not (0 < width <= 10000 and 0 < height <= 10000):
        raise ValueError('Invalid evidence dimensions')
    return width, height


def transport(root, group, commit, emit=print):
    root = Path(root).resolve()
    used = 0
    records = []
    def write(record):
        emit(PREFIX + json.dumps({'version': 1, 'commit': commit, **record}, separators=(',', ':')))
    write({'kind': 'limits', 'group': group, 'allowedFiles': len(GROUPS[group]),
           'maxFileBytes': MAX_FILE_BYTES, 'maxGroupBytes': MAX_GROUP_BYTES, 'chunkBytes': CHUNK_BYTES})
    for name in GROUPS[group]:
        path = root / name
        record = {'kind': 'file', 'path': name, 'optional': name in OPTIONAL}
        if not path.exists():
            record['status'] = 'missing'
        elif path.resolve() != path or not path.is_file():
            record['status'] = 'unsafe-path'
        else:
            size = path.stat().st_size
            record['bytes'] = size
            if size > MAX_FILE_BYTES or used + size > MAX_GROUP_BYTES:
                record['status'] = 'oversize'
            else:
                data = path.read_bytes()
                if len(data) != size:
                    record['status'] = 'changed-during-read'
                else:
                    try:
                        width, height = png_size(data)
                    except ValueError:
                        record['status'] = 'invalid-png'
                    else:
                        count = (size + CHUNK_BYTES - 1) // CHUNK_BYTES
                        record.update(status='ready', sha256=hashlib.sha256(data).hexdigest(),
                                      width=width, height=height, chunks=count)
                        write(record)
                        for index in range(count):
                            write({'kind': 'chunk', 'path': name, 'index': index, 'chunks': count,
                                   'data': base64.b64encode(data[index*CHUNK_BYTES:(index+1)*CHUNK_BYTES]).decode('ascii')})
                        write({'kind': 'end', 'path': name, 'chunks': count, 'bytes': size,
                               'sha256': record['sha256']})
                        used += size
                        records.append(record)
                        continue
        write(record)
        records.append(record)
    write({'kind': 'summary', 'group': group, 'bytes': used,
           'transported': sum(record['status'] == 'ready' for record in records),
           'states': {record['path']: record['status'] for record in records},
           'isScreenshotOrTestAcceptance': False})
    return records


def decode(log, destination, expected_commit):
    destination = Path(destination).resolve()
    files, chunks, complete = {}, {}, set()
    for raw in log.splitlines():
        if PREFIX not in raw:
            continue
        record = json.loads(raw.split(PREFIX, 1)[1])
        assert record['version'] == 1 and record['commit'] == expected_commit, 'Wrong producer commit'
        kind = record['kind']
        if kind in ('limits', 'summary'):
            continue
        name = record['path']
        assert name in ALLOWED, 'Not in the fixed fixture allowlist'
        if kind == 'file':
            assert name not in files, 'Duplicate transport header'
            files[name] = record
            chunks[name] = []
        elif kind == 'chunk':
            header = files[name]
            assert header['status'] == 'ready' and name not in complete
            assert record['index'] == len(chunks[name]) and record['chunks'] == header['chunks'], 'Missing/reordered chunks'
            chunks[name].append(base64.b64decode(record['data'], validate=True))
        elif kind == 'end':
            header = files[name]
            assert name not in complete and header['status'] == 'ready'
            assert len(chunks[name]) == record['chunks'] == header['chunks']
            data = b''.join(chunks[name])
            assert len(data) == record['bytes'] == header['bytes'] <= MAX_FILE_BYTES
            assert hashlib.sha256(data).hexdigest() == record['sha256'] == header['sha256'], 'PNG hash mismatch'
            assert png_size(data) == (header['width'], header['height'])
            path = destination / name
            assert path.resolve().is_relative_to(destination), 'Output path escapes destination'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            complete.add(name)
        else:
            raise AssertionError('Unknown transport record')
    assert complete == {name for name, record in files.items() if record['status'] == 'ready'}, 'Truncated screenshot transport'
    assert files, 'No PNG transport records found'
    return {'commit': expected_commit, 'decoded': sorted(complete),
            'unavailable': {name: record['status'] for name, record in files.items() if name not in complete}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--group', choices=GROUPS)
    parser.add_argument('--decode-log', type=Path)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--expected-commit')
    args = parser.parse_args()
    if args.decode_log:
        assert args.destination and args.expected_commit
        print(json.dumps(decode(args.decode_log.read_text(), args.destination, args.expected_commit), indent=2))
    else:
        assert args.group and os.environ.get('GITHUB_SHA'), 'CI producer commit required'
        transport(Path(__file__).resolve().parents[2], args.group, os.environ['GITHUB_SHA'])
