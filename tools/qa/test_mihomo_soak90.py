#!/usr/bin/env python3
"""Host fixture/guard regressions only; these are not Android native soak evidence."""
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import tempfile
import time
import unittest
import warnings
from unittest.mock import Mock, patch
import zipfile

spec = importlib.util.spec_from_file_location('soak', Path(__file__).with_name('run_mihomo_soak90.py'))
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)
NONCE = 'a' * 32


class HostFixtureTests(unittest.TestCase):
    def test_real_loopback_connect_and_dns_fixture_payloads(self):
        with soak.controlled_fixtures(NONCE) as (state, proxy, dns):
            self.assertEqual('127.0.0.1', soak.dns_probe(dns, NONCE, 1)['address'])
            self.assertEqual(soak.sha256(soak.payload_bytes(NONCE, 1)), soak.proxy_probe(proxy, NONCE, 1)['payloadSha256'])
            self.assertEqual({'dns-upstream', 'connect', 'origin'}, {event['kind'] for event in state.events})
            self.assertTrue(all(event.get('accepted', True) for event in state.events))
        self.assertFalse(state.active)

    def test_original_request_timeout_and_late_response_do_not_pollute_next_payload(self):
        with soak.controlled_fixtures(NONCE) as (state, proxy, _):
            state.set_mode('hang')
            with self.assertRaises(TimeoutError):
                soak.proxy_probe(proxy, NONCE, 7, timeout=.15)
            state.set_mode('normal')
            self.assertEqual(soak.sha256(soak.payload_bytes(NONCE, 8)), soak.proxy_probe(proxy, NONCE, 8)['payloadSha256'])
            deadline = time.monotonic() + 5
            while not any(event['kind'] == 'late-connect-response' for event in state.events):
                self.assertLess(time.monotonic(), deadline)
                time.sleep(.05)
            self.assertTrue(any(event.get('sample') == 7 and event['kind'] == 'late-connect-response' for event in state.events))
            self.assertFalse(any(event.get('sample') == 7 and event['kind'] == 'origin' for event in state.events))

    def test_dropped_upstream_fails_then_recovers_without_changing_servers(self):
        with soak.controlled_fixtures(NONCE) as (state, proxy, _):
            state.set_mode('drop')
            with self.assertRaises(EOFError):
                soak.proxy_probe(proxy, NONCE, 9)
            state.set_mode('normal')
            self.assertEqual(soak.sha256(soak.payload_bytes(NONCE, 10)), soak.proxy_probe(proxy, NONCE, 10)['payloadSha256'])
            self.assertEqual(['drop', 'normal'], [event['mode'] for event in state.events if event['kind'] == 'connect'])

    def test_uncontrolled_connect_destination_is_refused(self):
        with soak.controlled_fixtures(NONCE) as (state, proxy, _):
            with socket.create_connection(('127.0.0.1', proxy), timeout=2) as stream:
                stream.sendall(b'CONNECT example.com:443 HTTP/1.1\r\n\r\n')
                self.assertEqual(b'', stream.recv(1))
            self.assertTrue(any(event['kind'] == 'rejected-connect-request' for event in state.events))
            self.assertFalse(any(event['kind'] == 'origin' for event in state.events))

    def test_uncontrolled_dns_name_is_refused(self):
        with soak.controlled_fixtures(NONCE) as (state, _, dns):
            packet = struct.pack('!6H', 123, 0x0100, 1, 0, 0, 0) + b'\x07example\x03com\0' + struct.pack('!HH', 1, 1)
            with socket.create_connection(('127.0.0.1', dns), timeout=2) as stream:
                stream.sendall(struct.pack('!H', len(packet)) + packet)
                size = struct.unpack('!H', soak.recv_exact(stream, 2))[0]
                response = soak.recv_exact(stream, size)
            self.assertEqual(5, struct.unpack('!6H', response[:12])[1] & 0xf)
            self.assertEqual(0, struct.unpack('!6H', response[:12])[3])
            self.assertEqual(False, state.events[0]['accepted'])


class GuardTests(unittest.TestCase):
    def test_short_duration_cannot_mutate_guest_or_claim_soak_pass(self):
        with patch.object(soak, 'Guest') as guest:
            with self.assertRaisesRegex(ValueError, '900'):
                soak.run_soak(output='ignored', seconds=30)
            guest.assert_not_called()

    def test_fixture_ports_and_configuration_cannot_select_public_upstream(self):
        config = soak.configuration(30100, 30101, 30102, 30103).decode()
        self.assertIn('server: 127.0.0.1', config)
        self.assertIn('tcp://127.0.0.1:30103', config)
        self.assertIn('enable: false', config)
        for ports in ((80, 30101, 30102, 30103), (30100, 30100, 30102, 30103)):
            with self.assertRaises(ValueError):
                soak.configuration(*ports)

    def test_payload_is_the_pinned_android_native_manifest_payload(self):
        # Build-job restoration makes the real original bytes available here.
        # Package those bytes in an actual ZIP; the Android API job instead
        # supplies its already-installed APK through the same read-only path.
        source = soak.ROOT / 'android-app/app/src/main' / soak.PAYLOAD
        original = source.read_bytes()
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'current.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr(soak.PAYLOAD, original)
            payload, digest, metadata = soak.native_payload(apk)
        manifest = json.loads((soak.ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())
        self.assertEqual(manifest['original146_payload'][soak.PAYLOAD], digest)
        self.assertEqual(original, payload)
        self.assertGreater(metadata['bytes'], 1000000)
        self.assertTrue(metadata['presentIgnoredSourceVerified'])

    def test_foreign_process_argv_is_refused_even_when_pid_exists(self):
        guest = Mock()
        directory = '/data/local/tmp/hetu-soak90-' + NONCE
        fields = ['S', '1'] + ['0'] * 17 + ['100']
        guest.shell.return_value = 'exists'
        guest.run.return_value = '42 (native) ' + ' '.join(fields)
        identity = {'pid': 42, 'startTicks': 100, 'executable': directory + '/mihomo', 'argv': []}
        with patch.object(soak, 'process_identity', side_effect=RuntimeError('Guest PID no longer belongs')):
            with self.assertRaisesRegex(RuntimeError, 'no longer belongs'):
                soak.live_identity_or_none(guest, identity, directory)
        self.assertFalse(any(call.args[:2] == ('shell', 'kill') for call in guest.run.call_args_list))

    def test_pid_start_ticks_reuse_is_not_absence(self):
        guest = Mock()
        directory = '/data/local/tmp/hetu-soak90-' + NONCE
        fields = ['S', '1'] + ['0'] * 17 + ['999']
        guest.shell.return_value = 'exists'
        guest.run.return_value = '42 (native) ' + ' '.join(fields)
        identity = {'pid': 42, 'startTicks': 100, 'executable': directory + '/mihomo', 'argv': []}
        with patch.object(soak, 'process_identity', return_value=dict(identity, startTicks=999)):
            with self.assertRaisesRegex(RuntimeError, 'reused PID'):
                soak.live_identity_or_none(guest, identity, directory)

    def test_missing_process_and_same_identity_zombie_are_not_signaled(self):
        guest = Mock()
        identity = {'pid': 42, 'startTicks': 100}
        guest.shell.return_value = ''
        self.assertIsNone(soak.live_identity_or_none(guest, identity, 'unused'))
        guest.shell.return_value = 'exists'
        guest.run.return_value = '42 (native) ' + ' '.join(['Z', '1'] + ['0'] * 17 + ['100'])
        self.assertIsNone(soak.live_identity_or_none(guest, identity, 'unused'))

    def test_missing_live_owned_supervisor_refused_before_adb_mutation(self):
        guest = Mock()
        with self.assertRaises((RuntimeError, KeyError)):
            soak.owned_target({}, guest)
        guest.run.assert_not_called()

    def test_resource_observation_refuses_root_uid_or_descriptor_growth(self):
        directory = '/data/local/tmp/hetu-soak90-' + NONCE
        identity = {'pid': 42, 'startTicks': 100}
        with patch.object(soak, 'process_identity', return_value=identity):
            for uid, fds in [('0', '1 2 3'), ('2000', ' '.join(str(value) for value in range(129)))]:
                guest = Mock()
                guest.run.side_effect = [f'VmRSS:\t100 kB\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n', fds]
                with self.assertRaises(RuntimeError):
                    soak.resources(guest, identity, directory)

    def test_request_and_dns_header_bounds_refuse_oversized_input(self):
        stream = Mock()
        stream.recv.return_value = b'x'
        with self.assertRaisesRegex(ValueError, 'exceeds bound'):
            soak.recv_header(stream)
        with self.assertRaises(ValueError):
            soak.dns_question(b'\0' * 16)


class CandidateApkPayloadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # A bounded Android ELF surrogate exercises genuine ZIP transport. The
        # suite uses its own trusted-manifest fixture, never changes production
        # manifest data, and does not claim to execute this synthetic binary.
        cls.original = bytearray(b'\0' * (1024 * 1024 + 1))
        cls.original[:6] = b'\x7fELF\x02\x01'
        cls.original[18:20] = struct.pack('<H', 62)
        cls.original[64:85] = b'/system/bin/linker64\0'
        cls.original = bytes(cls.original)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.apk = self.root / 'candidate/current.apk'
        self.apk.parent.mkdir()
        self.manifest = self.root / 'UI92_RUNTIME146_INPUTS.json'
        self.write_manifest(self.original)
        self.root_patch = patch.object(soak, 'ROOT', self.root)
        self.root_patch.start()

    def tearDown(self):
        self.root_patch.stop()
        self.temp.cleanup()

    def write_manifest(self, payload):
        self.manifest.write_text(json.dumps({'original146_payload': {soak.PAYLOAD: soak.sha256(payload)}}))

    def write_apk(self, *members):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.apk, 'w') as archive:
                for name, data in members:
                    archive.writestr(name, data)

    def test_unique_candidate_native_works_without_ignored_cross_job_source(self):
        self.write_apk((soak.PAYLOAD, self.original))
        payload, digest, metadata = soak.native_payload(self.apk)
        self.assertEqual(self.original, payload)
        self.assertEqual(soak.sha256(self.original), digest)
        self.assertEqual('downloaded candidate APK', metadata['source'])
        self.assertEqual(soak.sha256(self.apk.read_bytes()), metadata['candidateApkSha256'])
        self.assertFalse(metadata['presentIgnoredSourceVerified'])

    def test_exact_current_apk_argument_does_not_search_an_alternative(self):
        self.write_apk((soak.PAYLOAD, self.original))
        with self.assertRaisesRegex(RuntimeError, 'missing'):
            soak.native_payload(self.root / 'missing-installed.apk')
        self.assertEqual(self.original, soak.native_payload()[0])
        (self.apk.parent / 'second.apk').write_bytes(self.apk.read_bytes())
        with self.assertRaisesRegex(RuntimeError, 'unambiguous'):
            soak.native_payload()
        self.assertEqual(self.original, soak.native_payload(self.apk)[0])

    def test_missing_or_duplicate_native_zip_member_is_refused(self):
        for members in ([('assets/unrelated', b'other')],
                        [(soak.PAYLOAD, self.original), (soak.PAYLOAD, self.original)]):
            with self.subTest(members=len(members)):
                self.write_apk(*members)
                with self.assertRaisesRegex(RuntimeError, 'exactly one'):
                    soak.native_payload(self.apk)

    def test_candidate_native_sha_mismatch_is_refused(self):
        altered = self.original[:-1] + b'X'
        self.write_apk((soak.PAYLOAD, altered))
        with self.assertRaisesRegex(RuntimeError, 'pinned original146'):
            soak.native_payload(self.apk)

    def test_sha_matching_wrong_elf_architecture_or_host_linker_is_refused(self):
        for kind in ('magic', 'architecture', 'linker'):
            altered = bytearray(self.original)
            if kind == 'magic':
                altered[:4] = b'FAKE'
            elif kind == 'architecture':
                altered[18:20] = struct.pack('<H', 183)
            else:
                altered[64:85] = b'/lib64/ld-linux.so\0\0\0'
            altered = bytes(altered)
            self.write_manifest(altered)  # Test-only trusted hash; ELF still must fail.
            self.write_apk((soak.PAYLOAD, altered))
            with self.subTest(kind=kind), self.assertRaisesRegex(RuntimeError, 'Android x86_64'):
                soak.native_payload(self.apk)

    def test_present_ignored_source_must_match_actual_candidate_payload(self):
        self.write_apk((soak.PAYLOAD, self.original))
        source = self.root / 'android-app/app/src/main' / soak.PAYLOAD
        source.parent.mkdir(parents=True)
        source.write_bytes(self.original)
        self.assertTrue(soak.native_payload(self.apk)[2]['presentIgnoredSourceVerified'])
        source.write_bytes(self.original[:-1] + b'X')
        with self.assertRaisesRegex(RuntimeError, 'ignored native source differs'):
            soak.native_payload(self.apk)


class TransportGuestFixture:
    """Actual local files/cp shell, with only the guest UID boundary simulated.

    This does not execute any native payload or prove Android Toybox behavior.
    Push emulates the cited adbd user-bit expansion; the production helper runs
    its real shell guards and non-preserving cp against disposable host files.
    """
    def __init__(self, folder, stage_mode=0o777):
        self.directory = '/data/local/tmp/hetu-soak90-' + NONCE
        self.local = Path(folder) / 'owned-nonce'
        self.local.mkdir(mode=0o700)
        (self.local / 'owner').write_text(NONCE)
        (self.local / 'owner').chmod(0o600)
        self.stage_mode = stage_mode
        self.stage_bytes = None
        self.stage_symlink = False
        self.final_bytes = None
        self.final_mode = None
        self.stat_overrides = {}
        self.fail_copy = False
        self.commands = []

    def translate(self, path):
        if not path.startswith(self.directory):
            raise AssertionError('Fixture cannot access another guest path')
        return str(self.local) + path[len(self.directory):]

    def run(self, *args, **kwargs):
        self.commands.append(('run', args))
        if args in (('forward', '--list'), ('reverse', '--list')):
            return ''
        if args[0] == 'push':
            source, target = Path(args[1]), Path(self.translate(args[2]))
            if self.stage_symlink:
                target.symlink_to(source)
            else:
                target.write_bytes(source.read_bytes() if self.stage_bytes is None else self.stage_bytes)
                target.chmod(self.stage_mode)
            return 'fixture push'
        if args[:3] == ('shell', 'stat', '-c'):
            fmt, guest_path = args[3:]
            if guest_path in self.stat_overrides:
                return self.stat_overrides[guest_path]
            raw = subprocess.run(['stat', '-c', fmt, self.translate(guest_path)],
                                 capture_output=True, text=True, check=True).stdout.strip()
            if fmt == '%u:%g:%a:%f':
                return '2000:2000:' + ':'.join(raw.split(':')[2:])
            return raw
        if args[:2] == ('shell', 'sha256sum'):
            return soak.sha256(Path(self.translate(args[2])).read_bytes()) + '  ' + args[2]
        if args[:2] == ('shell', 'cat'):
            return Path(self.translate(args[2])).read_text().strip()
        if args[:3] == ('shell', 'rm', '-rf'):
            shutil.rmtree(self.translate(args[3]))
            return ''
        raise AssertionError('Unexpected guest command: ' + repr(args))

    def shell(self, command, **kwargs):
        self.commands.append(('shell', command))
        if ' && cp ' in command and self.fail_copy:
            raise RuntimeError('fixture cp failed')
        local_command = command.replace(self.directory, str(self.local))
        # Actual local UID is deliberately not represented as Android shell UID.
        # Shim only this identity output; type, permissions, bytes and all shell
        # guards/cp/umask are genuinely observed on the temporary local files.
        stat = shutil.which('stat')
        shim = ('stat() { if [ "$1" = -c ] && [ "$2" = %u:%g:%a ]; then '
                + stat + ' -c "2000:2000:%a" "$3"; else ' + stat + ' "$@"; fi; }; ')
        result = subprocess.run(['sh', '-c', shim + local_command], capture_output=True,
                                text=True, check=True, timeout=kwargs.get('timeout', 15)).stdout.strip()
        if ' && cp ' in command:
            target = self.local / 'mihomo'
            if self.final_bytes is not None:
                target.write_bytes(self.final_bytes)
            if self.final_mode is not None:
                target.chmod(self.final_mode)
        return result


class NativeTransportAndPartialCleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.guest = TransportGuestFixture(self.temp.name)
        self.payload = b'bounded transport fixture; never executed\n'
        self.digest = soak.sha256(self.payload)
        self.report = {}

    def tearDown(self):
        self.temp.cleanup()

    def transport(self):
        soak.transport_native_payload(self.guest, Path(self.temp.name), self.guest.directory,
                                      NONCE, self.payload, self.digest, self.report)

    def assert_no_copy_or_execute(self):
        self.assertFalse((self.guest.local / 'mihomo').exists())
        self.assertFalse(any(kind == 'shell' and ' && cp ' in value for kind, value in self.guest.commands))
        self.assertFalse(any(kind == 'run' and value[:2] == ('shell', self.guest.directory + '/mihomo')
                             for kind, value in self.guest.commands))

    def test_canonical_adb_777_stage_becomes_exact_700_using_actual_cp_umask(self):
        self.transport()
        stage, copied = self.guest.local / 'mihomo.transport', self.guest.local / 'mihomo'
        self.assertEqual(0o777, stage.stat().st_mode & 0o7777)
        self.assertEqual(0o700, copied.stat().st_mode & 0o7777)
        self.assertEqual(self.payload, stage.read_bytes())
        self.assertEqual(self.payload, copied.read_bytes())
        observations = self.report['guestFileObservations']
        self.assertEqual('777', observations['transportedStage']['mode'])
        self.assertEqual('700', observations['privateExecutable']['mode'])
        self.assertEqual(self.digest, observations['transportedStage']['sha256'])
        self.assertEqual(self.digest, self.report['guestNativePayloadSha256'])
        self.assertFalse(self.report['nativeTransportPolicy']['stageExecuted'])
        command = self.report['nativeTransportPolicy']['copyCommand']
        self.assertIn('umask 077 && cp ', command)
        self.assertNotIn('cp -p', command)
        self.assertNotIn('chmod', command)
        self.assertNotIn('chown', command)

    def test_already_private_700_stage_keeps_strict_final_700(self):
        self.guest.stage_mode = 0o700
        self.transport()
        self.assertEqual(0o700, (self.guest.local / 'mihomo').stat().st_mode & 0o7777)

    def test_bad_stage_hash_is_recorded_and_never_copied(self):
        self.guest.stage_bytes = b'wrong or truncated stage'
        with self.assertRaisesRegex(RuntimeError, 'stage hash'):
            self.transport()
        observed = self.report['guestFileObservations']['transportedStage']
        self.assertEqual(soak.sha256(self.guest.stage_bytes), observed['sha256'])
        self.assertIn('rawStat', observed)
        self.assertIn('rawSha256sum', observed)
        self.assert_no_copy_or_execute()

    def test_foreign_stage_uid_or_gid_is_recorded_and_never_copied(self):
        for identity in ('0:2000', '2000:0'):
            with self.subTest(identity=identity):
                self.guest.stat_overrides[self.guest.directory + '/mihomo.transport'] = identity + ':777:81ff'
                with self.assertRaisesRegex(RuntimeError, 'stage ownership'):
                    self.transport()
                self.assertEqual(identity + ':777:81ff', self.report['guestFileObservations']['transportedStage']['rawStat'])
                self.assert_no_copy_or_execute()
                (self.guest.local / 'mihomo.transport').unlink()

    def test_symlink_stage_is_not_hashed_copied_or_executed(self):
        self.guest.stage_symlink = True
        with self.assertRaisesRegex(RuntimeError, 'not a regular file'):
            self.transport()
        self.assertNotIn('rawSha256sum', self.report['guestFileObservations']['transportedStage'])
        self.assert_no_copy_or_execute()

    def test_setid_and_every_other_transport_mode_are_refused(self):
        for mode in (0o4777, 0o2777, 0o1777, 0o755, 0o775, 0o600, 0o666):
            with self.subTest(mode=oct(mode)):
                self.guest.stage_mode = mode
                with self.assertRaisesRegex(RuntimeError, 'stage ownership'):
                    self.transport()
                self.assert_no_copy_or_execute()
                (self.guest.local / 'mihomo.transport').unlink()

    def test_private_copy_failure_keeps_raw_stage_diagnostics_and_no_success_hash(self):
        self.guest.fail_copy = True
        with self.assertRaisesRegex(RuntimeError, 'cp failed'):
            self.transport()
        self.assertEqual(self.digest, self.report['guestFileObservations']['transportedStage']['sha256'])
        self.assertIn('cp failed', self.report['nativeTransportPolicy']['copyError'])
        self.assertNotIn('guestNativePayloadSha256', self.report)
        self.assertFalse((self.guest.local / 'mihomo').exists())

    def test_final_hash_mode_or_uid_mismatch_cannot_be_accepted(self):
        for flaw in ('hash', 'mode', 'uid'):
            with self.subTest(flaw=flaw):
                if flaw == 'hash':
                    self.guest.final_bytes = b'bad copied bytes'
                elif flaw == 'mode':
                    self.guest.final_mode = 0o777
                else:
                    self.guest.stat_overrides[self.guest.directory + '/mihomo'] = '0:2000:700:81c0'
                with self.assertRaisesRegex(RuntimeError, 'Private native executable'):
                    self.transport()
                self.assertIn('rawSha256sum', self.report['guestFileObservations']['privateExecutable'])
                self.assertNotIn('guestNativePayloadSha256', self.report)
                self.guest.final_bytes = self.guest.final_mode = None
                self.guest.stat_overrides.clear()
                (self.guest.local / 'mihomo.transport').unlink()
                (self.guest.local / 'mihomo').unlink()

    def test_failed_setup_without_config_still_removes_only_owned_nonce(self):
        self.guest.stage_bytes = b'bad stage'
        with self.assertRaises(RuntimeError):
            self.transport()
        cleanup = {'ownedNonceDirectoryRemoved': False}
        soak.remove_owned_nonce(self.guest, self.guest.directory, NONCE, self.report, cleanup)
        self.assertFalse(cleanup['configurationPresentBeforeRemoval'])
        self.assertEqual(0, cleanup['configurationBytesBeforeRemoval'])
        self.assertTrue(cleanup['ownedNonceDirectoryRemoved'])
        self.assertFalse(self.guest.local.exists())

    def test_production_run_soak_records_transport_failure_and_complete_partial_cleanup(self):
        self.guest.stage_bytes = b'bad actual transported fixture bytes'
        shutil.rmtree(self.guest.local)  # Production run_soak must create its own fresh nonce.
        output = Path(self.temp.name) / 'results'
        metadata = {'sha256': self.digest, 'bytes': len(self.payload), 'source': 'host test fixture only'}
        with patch.object(soak, 'Guest', return_value=self.guest), \
                patch.object(soak, 'owned_target', return_value={'proof': 'simulated host fixture, not AOSP'}), \
                patch.object(soak, 'native_payload', return_value=(self.payload, self.digest, metadata)), \
                patch.object(soak, 'guest_ports', return_value=(30100, 30101, 30102, 30103)), \
                patch.object(soak.uuid, 'uuid4', return_value=Mock(hex=NONCE)):
            with self.assertRaisesRegex(RuntimeError, 'stage hash'):
                soak.run_soak(output=output, seconds=900, environment={})
        report = json.loads((output / 'results.json').read_text())
        self.assertEqual('FAIL', report['result'])
        self.assertEqual(0, report['executedSamples'])
        self.assertNotIn('actualSeconds', report)
        self.assertIn('rawStat', report['guestFileObservations']['transportedStage'])
        self.assertEqual(soak.sha256(self.guest.stage_bytes), report['guestFileObservations']['transportedStage']['sha256'])
        self.assertEqual([], report['cleanup']['errors'])
        for key in ('ownedGuestCoreStopped', 'adbChildReaped', 'ownedNonceDirectoryRemoved',
                    'adbForwardRestored', 'adbReverseRestored'):
            self.assertTrue(report['cleanup'][key], key)
        self.assertFalse(report['cleanup']['configurationPresentBeforeRemoval'])
        self.assertEqual(0, report['cleanup']['configurationBytesBeforeRemoval'])

    def test_present_config_bytes_are_recorded_before_owned_removal(self):
        (self.guest.local / 'config.yaml').write_bytes(b'config fixture\n')
        cleanup = {}
        soak.remove_owned_nonce(self.guest, self.guest.directory, NONCE, self.report, cleanup)
        self.assertTrue(cleanup['configurationPresentBeforeRemoval'])
        self.assertEqual(len(b'config fixture\n'), cleanup['configurationBytesBeforeRemoval'])
        self.assertTrue(cleanup['ownedNonceDirectoryRemoved'])

    def test_foreign_marker_parent_mode_uid_or_symlink_directory_is_never_removed(self):
        for flaw in ('marker', 'mode', 'uid', 'symlink'):
            with self.subTest(flaw=flaw):
                if flaw == 'marker':
                    (self.guest.local / 'owner').write_text('b' * 32)
                elif flaw == 'mode':
                    self.guest.local.chmod(0o777)
                elif flaw == 'uid':
                    self.guest.stat_overrides[self.guest.directory] = '0:2000:700:41c0'
                else:
                    self.guest.stat_overrides[self.guest.directory] = '2000:2000:777:a1ff'
                cleanup = {'ownedNonceDirectoryRemoved': False}
                with self.assertRaisesRegex(RuntimeError, 'refusing mutation'):
                    soak.remove_owned_nonce(self.guest, self.guest.directory, NONCE, self.report, cleanup)
                self.assertTrue(self.guest.local.exists())
                self.assertFalse(cleanup['ownedNonceDirectoryRemoved'])
                self.assertFalse(any(kind == 'run' and value[:3] == ('shell', 'rm', '-rf')
                                     for kind, value in self.guest.commands))
                (self.guest.local / 'owner').write_text(NONCE)
                self.guest.local.chmod(0o700)
                self.guest.stat_overrides.clear()


class EvaluationContractTests(unittest.TestCase):
    def successful_contract(self):
        samples, events = [], []
        for number in range(300):
            elapsed = number * 3
            phase = soak.phase_for(elapsed, 900)
            record = {'sample': number + 1, 'phase': phase, 'elapsedStartSeconds': elapsed,
                      'elapsedEndSeconds': elapsed + .1, 'dnsPassed': True, 'proxyPassed': phase == 'normal'}
            if phase == 'normal':
                record['payloadSha256'] = 'sample-' + str(number)
                events.append({'kind': 'origin', 'sample': number + 1, 'payloadSha256': record['payloadSha256']})
            else:
                record['errorType'] = 'TimeoutError' if phase == 'hang' else 'EOFError'
            events.append({'kind': 'connect', 'sample': number + 1})
            samples.append(record)
        events.append({'kind': 'late-connect-response', 'sample': 197})
        resources = [{'rssKiB': 100, 'fileDescriptors': 8}]
        return samples, events, resources

    def test_complete_contract_requires_both_actual_faults_and_recoveries(self):
        samples, events, resources = self.successful_contract()
        result = soak.evaluate(samples, 900, 900.1, events, resources)
        self.assertEqual({'drop', 'hang'}, {event['fault'] for event in result['recoveries']})
        self.assertGreaterEqual(result['actualClientTimeouts'], 2)

    def test_short_wall_time_normal_failure_and_missing_origin_are_refused(self):
        samples, events, resources = self.successful_contract()
        with self.assertRaisesRegex(RuntimeError, 'wall time'):
            soak.evaluate(samples, 900, 899.99, events, resources)
        samples[0]['proxyPassed'] = False
        with self.assertRaisesRegex(RuntimeError, 'Normal native'):
            soak.evaluate(samples, 900, 900.1, events, resources)
        samples, events, resources = self.successful_contract()
        events = [event for event in events if not (event['kind'] == 'origin' and event['sample'] == 1)]
        with self.assertRaisesRegex(RuntimeError, 'origin payload hash'):
            soak.evaluate(samples, 900, 900.1, events, resources)

    def test_fault_that_succeeded_or_never_timed_out_cannot_pass(self):
        for flaw in ('succeeded', 'no-timeout'):
            samples, events, resources = self.successful_contract()
            target = next(sample for sample in samples if sample['phase'] == 'hang')
            if flaw == 'succeeded':
                target['proxyPassed'] = True
            else:
                target['errorType'] = 'EOFError'
            with self.assertRaises(RuntimeError):
                soak.evaluate(samples, 900, 900.1, events, resources)


if __name__ == '__main__':
    unittest.main()
