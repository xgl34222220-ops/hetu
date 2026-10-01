#!/usr/bin/env python3
"""Exercise permission decisions against a fake device; never touch real /dev/kvm."""
import importlib.util
import json
import os
from pathlib import Path
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('kvm', Path(__file__).resolve().parents[1] / 'tools/android_kvm_access.py')
kvm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(kvm)
BASE = '# file: /dev/kvm\n# owner: 0\n# group: 993\nuser::rw-\ngroup::rw-\nother::---\n\n'
GRANTED = BASE.replace('group::rw-', 'user:1001:rw-\ngroup::rw-\nmask::rw-')


class KvmTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name) / 'state'
        self.current = BASE
        self.device = dict(st_dev=1, st_ino=2, st_rdev=3, st_uid=0, st_gid=993, st_mode=stat.S_IFCHR | 0o660)
        self.calls = []
        self.fail_grant = False
        self.fail_restore = False
        self.verify_rw = True
        self.env = {'GITHUB_ACTIONS': 'true', 'HETU_KVM_ONCE': 'true', 'GITHUB_REPOSITORY': 'xgl34222220-ops/hetu',
                    'GITHUB_REF': 'refs/heads/test/dot-v2048-ui-safety', 'GITHUB_EVENT_NAME': 'push', 'GITHUB_RUN_ATTEMPT': '1'}
        self.patches = [patch.dict(os.environ, self.env, clear=True), patch.object(kvm, 'command', self.command),
                        patch.object(kvm, 'identity', lambda: dict(self.device)), patch.object(kvm.os, 'getuid', return_value=1001),
                        patch.object(kvm.pwd, 'getpwuid', return_value=SimpleNamespace(pw_name='runner')),
                        patch.object(kvm.grp, 'getgrgid', return_value=SimpleNamespace(gr_name='kvm')),
                        patch.object(kvm.shutil, 'which', side_effect=lambda name: '/usr/bin/' + name),
                        patch.object(kvm.os, 'access', side_effect=lambda *a: self.current == GRANTED and self.verify_rw)]
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def command(self, args):
        self.calls.append(args)
        if args == ['getfacl', '-p', '-n', '/dev/kvm']:
            return self.current
        if args == ['sudo', '-n', 'setfacl', '-m', 'u:1001:rw', '/dev/kvm']:
            self.current = GRANTED
            if self.fail_grant:
                raise RuntimeError('grant returned failure after applying')
            return ''
        if args == ['sudo', '-n', 'setfacl', '--restore=' + str((self.folder / 'original.acl').resolve())]:
            if self.fail_restore:
                raise RuntimeError('restore failed')
            self.current = (self.folder / 'original.acl').read_text()
            return ''
        raise AssertionError('Unexpected command: ' + repr(args))

    def mutations(self):
        return [x for x in self.calls if x[0] == 'sudo']

    def test_grant_only_uid_and_restore_exact_acl(self):
        kvm.grant(self.folder)
        self.assertEqual(self.current, GRANTED)
        self.assertEqual(json.loads((self.folder / 'original.json').read_text())['originalAcl'], BASE)
        kvm.restore(self.folder)
        self.assertEqual(self.current, BASE)
        self.assertTrue(json.loads((self.folder / 'restore-result.json').read_text())['restored'])
        self.assertEqual(len(self.mutations()), 2)

    def test_restore_is_idempotent_for_trap_and_always(self):
        kvm.grant(self.folder)
        kvm.restore(self.folder)
        kvm.restore(self.folder)
        self.assertEqual(len(self.mutations()), 2)

    def test_failed_grant_rolls_back_even_when_mutation_happened(self):
        self.fail_grant = True
        with self.assertRaisesRegex(RuntimeError, 'grant returned'):
            kvm.grant(self.folder)
        self.assertEqual(self.current, BASE)

    def test_failed_access_check_rolls_back(self):
        self.verify_rw = False
        with self.assertRaisesRegex(RuntimeError, 'verification'):
            kvm.grant(self.folder)
        self.assertEqual(self.current, BASE)

    def test_refuse_second_attempt_and_wrong_repo_or_branch(self):
        for key, value in [('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_EVENT_NAME', 'workflow_dispatch'),
                           ('GITHUB_REPOSITORY', 'other/repo'), ('GITHUB_REF', 'refs/heads/main'),
                           ('HETU_KVM_ONCE', 'false')]:
            with self.subTest(key=key), patch.dict(os.environ, {key: value}):
                with self.assertRaises(RuntimeError):
                    kvm.grant(self.folder)
        self.assertEqual(self.mutations(), [])

    def test_refuse_extended_or_world_writable_original_acl(self):
        for value in [GRANTED, BASE.replace('other::---', 'other::rw-')]:
            self.current = value
            with self.assertRaises(RuntimeError):
                kvm.grant(self.folder)
        self.assertEqual(self.mutations(), [])

    def test_refuse_changed_device_metadata_before_grant(self):
        for key, value in [('st_uid', 1001), ('st_mode', stat.S_IFCHR | 0o666)]:
            original = self.device[key]
            self.device[key] = value
            with self.assertRaises(RuntimeError):
                kvm.grant(self.folder)
            self.device[key] = original
        self.assertEqual(self.mutations(), [])

    def test_refuse_root_or_unexpected_user(self):
        with patch.object(kvm.os, 'getuid', return_value=0):
            with self.assertRaises(RuntimeError):
                kvm.grant(self.folder)
        with patch.object(kvm.pwd, 'getpwuid', return_value=SimpleNamespace(pw_name='other')):
            with self.assertRaises(RuntimeError):
                kvm.grant(self.folder)
        self.assertEqual(self.mutations(), [])

    def test_refuse_missing_tool(self):
        with patch.object(kvm.shutil, 'which', return_value=None):
            with self.assertRaises(RuntimeError):
                kvm.grant(self.folder)
        self.assertEqual(self.mutations(), [])

    def test_restore_refuses_replaced_device(self):
        kvm.grant(self.folder)
        self.device['st_ino'] += 1
        with self.assertRaisesRegex(RuntimeError, 'identity'):
            kvm.restore(self.folder)
        self.assertEqual(len(self.mutations()), 1)
        self.assertFalse(json.loads((self.folder / 'restore-result.json').read_text())['restored'])

    def test_restore_refuses_changed_backup(self):
        kvm.grant(self.folder)
        (self.folder / 'original.acl').write_text(GRANTED)
        with self.assertRaisesRegex(RuntimeError, 'Saved original'):
            kvm.restore(self.folder)
        self.assertEqual(len(self.mutations()), 1)

    def test_restore_failure_preserves_failure_report_and_backup(self):
        kvm.grant(self.folder)
        self.fail_restore = True
        with self.assertRaisesRegex(RuntimeError, 'restore failed'):
            kvm.restore(self.folder)
        self.assertFalse(json.loads((self.folder / 'restore-result.json').read_text())['restored'])
        self.assertEqual((self.folder / 'original.acl').read_text(), BASE)

    def test_restore_without_prepared_grant_is_noop(self):
        kvm.restore(self.folder)
        self.assertEqual(self.calls, [])

    def test_no_second_grant_over_original_backup(self):
        kvm.grant(self.folder)
        kvm.restore(self.folder)
        with self.assertRaisesRegex(RuntimeError, 'Existing KVM transaction'):
            kvm.grant(self.folder)
        self.assertEqual(len(self.mutations()), 2)

    def test_identity_rejects_symlink_and_regular_file(self):
        # Exercise actual identity implementation with only lstat isolated.
        self.patches[2].stop()
        for mode in [stat.S_IFLNK | 0o777, stat.S_IFREG | 0o660]:
            with patch.object(Path, 'lstat', return_value=SimpleNamespace(st_mode=mode)):
                with self.assertRaises(RuntimeError):
                    kvm.identity()


if __name__ == '__main__':
    unittest.main()
