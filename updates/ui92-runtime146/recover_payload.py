#!/usr/bin/env python3
"""Reproduce the selected user's exact 146 native bytes, without recompiling a core.
The reference artifact provides identical JNI libraries. Its two Root binaries have
only 64/65 differing bytes (build records); these explicit offsets reproduce the
selected APK's complete SHA-256 hashes. A differing input fails before writing.
"""
from pathlib import Path
import hashlib
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RECOVERY = {
 'assets/mihomo-root/arm64-v8a/mihomo': {
  'base_sha256': '332588e5cfc0ea18fe1cc89a2a5fa93504982e7c9461863f301000e4b950ac80',
  'result_sha256': '2eb2ffd88f8822dd7d9a3e68f37661660f46073ded2d1faf93698f47084e68e1',
  'size': 62796768,
  'changes': [[872,90],[873,120],[874,106],[875,113],[876,45],[877,99],[878,119],[879,66],[880,105],[881,116],[882,70],[883,71],[884,98],[885,106],[886,118],[887,71],[888,103],[889,76],[890,52],[891,83],[935,54],[936,69],[937,102],[938,81],[939,56],[940,84],[941,52],[942,66],[943,66],[944,79],[945,87],[946,45],[947,89],[948,90],[949,51],[950,116],[951,74],[952,66],[953,118],[954,55],[972,68],[973,153],[974,91],[975,190],[976,17],[977,174],[978,100],[980,61],[981,138],[982,107],[983,213],[984,189],[985,23],[986,185],[987,157],[988,9],[989,141],[990,71],[991,95],[7822617,54],[7822619,50],[7822620,48],[7822623,49],[7822626,49]]
 },
 'assets/mihomo-root/x86_64/mihomo': {
  'base_sha256': 'b7b6cb22008415db8182a8d5f470f643ac0de1cd6f10d662c0bdaca53420606f',
  'result_sha256': 'e96fb9a38ebd38207c99b01f019e1fd0b2a81b308f8f47280fbc9c42f2911b61',
  'size': 67652160,
  'changes': [[872,119],[873,51],[874,49],[875,75],[876,112],[877,57],[878,101],[879,83],[880,98],[881,86],[882,77],[883,57],[884,113],[885,111],[886,85],[887,80],[888,100],[889,67],[890,80],[891,95],[935,114],[936,119],[937,75],[938,90],[939,81],[940,117],[941,108],[942,80],[943,82],[944,79],[945,89],[946,120],[947,82],[948,87],[949,90],[950,98],[951,121],[952,111],[953,66],[954,55],[972,19],[973,23],[974,24],[975,157],[976,212],[977,154],[978,225],[979,207],[980,94],[981,82],[982,108],[983,192],[984,139],[985,130],[986,192],[987,121],[988,24],[989,15],[990,143],[991,225],[7814345,54],[7814347,50],[7814348,48],[7814351,49],[7814354,49]]
 }
}


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit('Usage: recover_payload.py reference.apk')
    reference = Path(sys.argv[1])
    if sha(reference.read_bytes()) != '580fbe67c95642153dd2987c80b3a8995cab0e8a7aff71f1a2a6ef89d8258779':
        raise ValueError('Wrong reference artifact')
    expected = json.loads((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload']
    pending = []
    with zipfile.ZipFile(reference) as archive:
        if archive.testzip() is not None:
            raise ValueError('Invalid artifact ZIP')
        for name in expected:
            if not (name.startswith('lib/') or name in RECOVERY or name in ('assets/MIHOMO-LICENSE', 'assets/mihomo-revision.txt')):
                continue
            raw = archive.read(name)
            if name in RECOVERY:
                item = RECOVERY[name]
                if len(raw) != item['size'] or sha(raw) != item['base_sha256']:
                    raise ValueError('Core input mismatch: ' + name)
                buf = bytearray(raw)
                for offset, value in item['changes']:
                    buf[offset] = value
                raw = bytes(buf)
                if sha(raw) != item['result_sha256']:
                    raise ValueError('Core recovery mismatch: ' + name)
            if sha(raw) != expected[name]:
                raise ValueError('Selected user146 checksum mismatch: ' + name)
            destination = ROOT / 'android-app/app/src/main' / (name.replace('lib/', 'jniLibs/', 1) if name.startswith('lib/') else name)
            pending.append((destination, raw))
    for destination, raw in pending:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
    for name, digest in expected.items():
        destination = ROOT / 'android-app/app/src/main' / (name.replace('lib/', 'jniLibs/', 1) if name.startswith('lib/') else name)
        if sha(destination.read_bytes()) != digest:
            raise ValueError('Runtime asset mismatch: ' + name)
    print('Exact original146 payload entries verified:', len(expected))


if __name__ == '__main__':
    main()
