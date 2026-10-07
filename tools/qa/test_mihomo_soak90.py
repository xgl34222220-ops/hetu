#!/usr/bin/env python3
"""Host fixture/guard regressions only; these are not Android native soak evidence."""
import importlib.util
import json
from pathlib import Path
import socket
import struct
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
