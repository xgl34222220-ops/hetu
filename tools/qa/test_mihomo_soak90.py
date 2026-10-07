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
from unittest.mock import Mock, patch

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
        source, digest, size = soak.native_payload()
        manifest = json.loads((soak.ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())
        self.assertEqual(manifest['original146_payload'][soak.PAYLOAD], digest)
        self.assertGreater(size, 1000000)
        self.assertEqual('mihomo', source.name)

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
