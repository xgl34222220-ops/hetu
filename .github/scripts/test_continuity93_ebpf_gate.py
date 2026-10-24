#!/usr/bin/env python3
"""2026-10-13 eBPF authorization: the V20.93 gate admits exactly the three pinned eBPF payloads
built from native/ebpf, beside the unchanged original 23 payloads."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import continuity93_source_scope as scope  # noqa: E402
import verify_packaged_runtime as vpr  # noqa: E402

ROOT = scope.ROOT
MAIN = 'android-app/app/src/main/'
EBPF_NAMES = ('assets/hetu-bpf/hetu_match.o', 'assets/hetu-bpf/arm64-v8a/hetu-bpf', 'assets/hetu-bpf/x86_64/hetu-bpf')


def sha(data):
    return hashlib.sha256(data).hexdigest()


class OriginalPayloadsUntouched(unittest.TestCase):
    def test_original_23_and_flag_unchanged(self):
        original = json.loads((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload']
        self.assertEqual(len(original), 22)
        self.assertEqual((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_bytes(),
                         subprocess.check_output(['git', 'show', scope.BASE_COMMIT + ':UI92_RUNTIME146_INPUTS.json'], cwd=ROOT))
        self.assertFalse(set(vpr.EBPF_PAYLOADS) & (set(original) | {vpr.AUTOSTART}))
        self.assertIn('nativePayloadsUnchanged', scope.PROTECTED_FLAGS)
        self.assertIn('other21RuntimePayloadsPreserved', scope.PROTECTED_FLAGS)
        self.assertIn('ebpfPayloadsFromSource', scope.PROTECTED_FLAGS)
        for name in scope.EXTERNAL_PAYLOAD_FILES:
            self.assertFalse(scope.allowed_change(name))


class EbpfAllowList(unittest.TestCase):
    def test_exact_three_pinned_payloads(self):
        self.assertEqual(sorted(vpr.EBPF_PAYLOADS), sorted(EBPF_NAMES))
        self.assertTrue(all(len(v) == 64 and int(v, 16) >= 0 for v in vpr.EBPF_PAYLOADS.values()))
        self.assertEqual(scope.EBPF_FILES, {MAIN + name for name in EBPF_NAMES})
        for name in scope.EBPF_FILES:
            self.assertTrue(scope.allowed_change(name))
        for name in ('assets/hetu-bpf/armeabi-v7a/hetu-bpf', 'assets/hetu-bpf/extra.o', 'assets/bpf/hetu_match.o'):
            self.assertFalse(scope.allowed_change(MAIN + name))

    def pinned_changes(self):
        return {MAIN + name: digest for name, digest in vpr.EBPF_PAYLOADS.items()}

    def test_layer_check_accepts_only_full_pinned_set(self):
        self.assertEqual(scope.validate_ebpf_payloads({}, ROOT), {})
        self.assertEqual(scope.validate_ebpf_payloads(self.pinned_changes(), ROOT), self.pinned_changes())
        partial = self.pinned_changes()
        partial.pop(MAIN + EBPF_NAMES[0])
        with self.assertRaises(AssertionError):
            scope.validate_ebpf_payloads(partial, ROOT)
        for name in self.pinned_changes():
            tampered = self.pinned_changes()
            tampered[name] = '0' * 64
            with self.assertRaises(AssertionError):
                scope.validate_ebpf_payloads(tampered, ROOT)

    def test_sources_are_pinned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            shutil.copytree(ROOT / 'native/ebpf', root / 'native/ebpf')
            scope.validate_ebpf_payloads(self.pinned_changes(), root)
            (root / 'native/ebpf/extra.c').write_text('int x;\n')
            with self.assertRaises(AssertionError):
                scope.validate_ebpf_payloads(self.pinned_changes(), root)
            (root / 'native/ebpf/extra.c').unlink()
            with open(root / 'native/ebpf/hetu_match.c', 'a') as handle:
                handle.write('\n')
            with self.assertRaises(AssertionError):
                scope.validate_ebpf_payloads(self.pinned_changes(), root)

    @unittest.skipUnless(shutil.which(os.environ.get('CLANG', 'clang')) and shutil.which('go'), 'clang/go not available')
    def test_pinned_hashes_are_the_reproducible_source_build(self):
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(['sh', str(ROOT / 'native/ebpf/build.sh'), tmp], check=True, stdout=subprocess.DEVNULL)
            for name, digest in vpr.EBPF_PAYLOADS.items():
                self.assertEqual(sha((Path(tmp) / name.removeprefix('assets/hetu-bpf/')).read_bytes()), digest, name)

    def test_ci_runtime_snapshot_keeps_the_23_count(self):
        text = (ROOT / '.github/scripts/continuity93_ci_config.py').read_text()
        self.assertIn("snapshot['originalPayloadCount'] == 22 and len(snapshot['expected']) == 23 + len(ebpf)", text)
        self.assertIn("assert ebpf in ([], sorted(EBPF_FILES))", text)


class PackagedRuntime(unittest.TestCase):
    """Runs verify_packaged_runtime.main() on a synthetic tree with stand-in eBPF bytes."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.files = {'assets/hetu-root.sh': b'#!/bin/sh\necho root\n', 'lib/arm64-v8a/libx.so': b'\x7fELFx',
                      vpr.AUTOSTART: b'#!/bin/sh\necho boot\n'}
        self.ebpf = {name: ('fake ' + name).encode() for name in EBPF_NAMES}
        original = {name: sha(data) for name, data in self.files.items() if name != vpr.AUTOSTART}
        (self.root / 'UI92_RUNTIME146_INPUTS.json').write_text(json.dumps({'original146_payload': original}))
        for name, data in {**self.files, **self.ebpf}.items():
            path = vpr.source_path(name)
            (self.root / path).parent.mkdir(parents=True, exist_ok=True)
            (self.root / path).write_bytes(data)
        self.pins = mock.patch.dict(vpr.EBPF_PAYLOADS, {name: sha(data) for name, data in self.ebpf.items()})
        self.pins.start()
        self.cwd = os.getcwd()
        os.chdir(self.root)

    def tearDown(self):
        os.chdir(self.cwd)
        self.pins.stop()
        self.tmp.cleanup()

    def run_main(self, *argv):
        with mock.patch.object(sys, 'argv', ['verify_packaged_runtime.py', *argv]), \
                mock.patch('builtins.print'):
            vpr.main()
        return json.loads(Path('out/verification/packaged-runtime.json').read_text())

    def snapshot(self, ebpf=True):
        return self.run_main('snapshot', '--script-sha256', sha(self.files['assets/hetu-root.sh']),
                             '--autostart-sha256', sha(self.files[vpr.AUTOSTART]), *(['--ebpf'] if ebpf else []))

    def apk(self, overrides=None):
        with zipfile.ZipFile('app.apk', 'w') as archive:
            for name, data in {**self.files, **self.ebpf, **(overrides or {})}.items():
                archive.writestr(name, data)

    def verify(self):
        return self.run_main('verify', '--apk', 'app.apk')

    def rewrite(self, change):
        path = Path('out/verification/packaged-runtime.json')
        record = json.loads(path.read_text())
        change(record)
        path.write_text(json.dumps(record))

    def test_without_ebpf_is_unchanged(self):
        record = self.snapshot(ebpf=False)
        self.assertEqual(record['addedPayloads'], [vpr.AUTOSTART])
        self.assertFalse(record['ebpfPayloadsFromSource'])
        self.apk()
        self.assertTrue(self.verify()['apkVerified'])

    def test_with_ebpf_verifies(self):
        record = self.snapshot()
        self.assertEqual(record['addedPayloads'], [vpr.AUTOSTART, *sorted(EBPF_NAMES)])
        self.assertEqual(len(record['expected']), len(self.files) + 3)
        self.assertTrue(record['ebpfPayloadsFromSource'])
        self.apk()
        self.assertTrue(self.verify()['apkVerified'])

    def test_snapshot_rejects_source_not_matching_pin(self):
        vpr.source_path(EBPF_NAMES[1]).write_bytes(b'other')
        with self.assertRaises(AssertionError):
            self.snapshot()

    def test_apk_with_other_ebpf_bytes_rejected(self):
        self.snapshot()
        self.apk({EBPF_NAMES[2]: b'swapped'})
        with self.assertRaises(AssertionError):
            self.verify()

    def test_tampered_records_rejected(self):
        self.apk()
        for change in (lambda r: r['expected'].__setitem__(EBPF_NAMES[0], '0' * 64),
                       lambda r: r['addedPayloads'].remove(EBPF_NAMES[0]),
                       lambda r: r['addedPayloads'].append('assets/hetu-bpf/extra.o'),
                       lambda r: r.__setitem__('ebpfPayloadsFromSource', False),
                       lambda r: r['expected'].__setitem__('lib/arm64-v8a/libx.so', '0' * 64)):
            self.snapshot()
            self.rewrite(change)
            with self.assertRaises((AssertionError, KeyError)):
                self.verify()


if __name__ == '__main__':
    unittest.main()
