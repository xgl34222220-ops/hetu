#!/usr/bin/env python3
"""Check packaged MaterialKolor constructor references against all APK DEX definitions.

Constructors cannot be inherited, so a referenced absent constructor is an ABI
error even when the responsible preference branch was not visited in smoke.
This bounded reader checks that specific namespace; it is not a general DEX verifier.
"""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def uleb(data, offset):
    value = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
        shift += 7
        assert shift <= 28, 'Invalid DEX ULEB'


def dex_methods(data):
    assert data[:4] == b'dex\n', 'Expected ordinary DEX'
    header = struct.unpack_from('<20I', data, 32)
    sn, so, tn, to, pn, po, _, _, mn, mo, cn, co = header[6:18]
    strings = []
    for offset in struct.unpack_from('<' + str(sn) + 'I', data, so):
        _, offset = uleb(data, offset)
        strings.append(data[offset:data.index(0, offset)].decode('utf-8', errors='replace'))
    types = [strings[i] for i in struct.unpack_from('<' + str(tn) + 'I', data, to)]
    protos = []
    for index in range(pn):
        _, result, offset = struct.unpack_from('<III', data, po + 12 * index)
        count = struct.unpack_from('<I', data, offset)[0] if offset else 0
        args = struct.unpack_from('<' + str(count) + 'H', data, offset + 4) if count else []
        protos.append('(' + ''.join(types[i] for i in args) + ')' + types[result])
    methods = [
        (types[cls], strings[name], protos[proto])
        for cls, proto, name in (struct.unpack_from('<HHI', data, mo + 8 * i) for i in range(mn))
    ]
    definitions = set()
    for index in range(cn):
        offset = struct.unpack_from('<I', data, co + 32 * index + 24)[0]
        if not offset:
            continue
        counts = []
        for _ in range(4):
            count, offset = uleb(data, offset)
            counts.append(count)
        for _ in range(counts[0] + counts[1]):
            _, offset = uleb(data, offset)
            _, offset = uleb(data, offset)
        for count in counts[2:]:
            method_index = 0
            for _ in range(count):
                diff, offset = uleb(data, offset)
                method_index += diff
                _, offset = uleb(data, offset)
                _, offset = uleb(data, offset)
                definitions.add(methods[method_index])
    return methods, definitions


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk')
    parser.add_argument('--output', required=True)
    parser.add_argument('--expect-incompatible', action='store_true')
    args = parser.parse_args()
    apk = Path(args.apk)
    references, definitions = set(), set()
    dex_count = 0
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith('.dex'):
                methods, defined = dex_methods(archive.read(name))
                references.update(methods)
                definitions.update(defined)
                dex_count += 1
    constructors = {method for method in references if method[0].startswith('Lcom/materialkolor/') and method[1] == '<init>'}
    assert constructors and dex_count, 'No MaterialKolor bytecode found'
    missing = sorted(constructors - definitions)
    record = {'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'dexCount': dex_count,
              'constructorReferences': len(constructors), 'missingConstructors': missing,
              'compatible': not missing, 'expectedIncompatibleBaseline': args.expect_incompatible}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(record, indent=2) + '\n')
    if args.expect_incompatible:
        assert any('SchemeTonalSpot;' in method[0] for method in missing), 'Historical crash ABI was not reproduced'
    else:
        assert not missing, f'MaterialKolor constructors absent from packaged runtime: {missing}'
    print(f'MaterialKolor ABI: {len(constructors)} constructors, {len(missing)} missing; expected-incompatible={args.expect_incompatible}')


if __name__ == '__main__':
    main()
