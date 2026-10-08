#!/usr/bin/env python3
"""Host-only guards for the additive AOSP runner; synthetic files are not Android evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

import feedback_smoke93 as smoke
import verify_feedback_smoke93 as consumer
from run_hetu_feedback93_emulator import SmokeEntryAdapter

COMMIT = 'c' * 40
APK = 'a' * 64
PNG = b'\x89PNG\r\n\x1a\nHOST-SYNTHETIC-NOT-ANDROID'


def hierarchy(text, eof=False, dock=False):
    root = ET.Element('hierarchy')
    box = ET.SubElement(root, 'node', {'package': smoke.PKG, 'bounds': '[0,0][500,900]', 'scrollable': 'true'})
    ET.SubElement(box, 'node', {'package': smoke.PKG, 'text': text, 'bounds': '[10,30][480,90]'})
    if dock:
        ET.SubElement(box, 'node', {'package': smoke.PKG, 'content-desc': '首页', 'bounds': '[0,800][100,850]'})
    return ET.tostring(root, encoding='unicode')


def synthetic_proof(folder):
    """Build an explicitly synthetic API35 consumer input in an isolated temp directory."""
    root = Path(folder)
    output = root / 'feedback93'
    output.mkdir()
    catalog = json.loads(Path(smoke.__file__).with_name('feedback93_original65.json').read_text())
    original = {'apiLevel': 35, 'passed': 65, 'checks': [{'name': n, 'result': 'passed'} for n in catalog['checkNames']],
                'legacyChecksPassed': 59, 'paletteCasesPassed': 21, 'rootMutationActions': 0, 'rootSetupDenialAttempts': 1}
    (root / 'results.json').write_text(json.dumps(original))
    for name in ('launch.txt', 'first-frame-ready.json', 'logcat.txt'):
        (root / name).write_text('HOST SYNTHETIC NOT ANDROID\n')
    identity = {'pid': 31, 'startTicks': 310}
    launched = {'pid': 42, 'startTicks': 420}
    route = ['settings', 'basic-proxy', 'ProxyStartupConfigActivity', 'basic-proxy', 'settings', 'home']
    checks = []
    for kind, expected in (('startup-config-missing-file', '尚未生成启动配置'),
                           ('startup-config-short-file', 'HETU-FEEDBACK93-SHORT-hostnonce'),
                           ('startup-config-long-file', 'HETU-FEEDBACK93-LONG-BEGIN')):
        for stage, text in (('settings', '基础代理配置'), ('basic', '查看启动配置'),
                            ('viewer', '启动配置\n' + expected), ('back-to-basic', '查看启动配置'),
                            ('back-to-settings', '基础代理配置'), ('returned-home-ready', '河图')):
            (output / (kind + '-' + stage + '.xml')).write_text(hierarchy(text))
            (output / (kind + '-' + stage + '.png')).write_bytes(PNG)
        for stage, activity in (('before-tap', smoke.MAIN_ACTIVITY), ('viewer', smoke.STARTUP_ACTIVITY),
                                ('back-to-basic', smoke.MAIN_ACTIVITY)):
            (output / (kind + '-' + stage + '-activity-stack.txt')).write_text('mResumedActivity: ActivityRecord{ ' + activity + ' }\n')
        checks.append({'name': kind, 'result': 'passed', 'route': route, 'before': identity, 'after': identity,
                       'observedResumedActivities': {'beforeTap': smoke.MAIN_ACTIVITY, 'viewer': smoke.STARTUP_ACTIVITY, 'backToBasic': smoke.MAIN_ACTIVITY},
                       'regenerateOrServiceActions': 0})
    eof = hierarchy('HETU-FEEDBACK93-LONG-END')
    (output / 'startup-config-long-file-last-line.xml').write_text(eof)
    (output / 'startup-config-long-file-last-line.png').write_bytes(PNG)
    (output / 'startup-config-long-file-last-line-activity-stack.txt').write_text('mResumedActivity: ' + smoke.STARTUP_ACTIVITY + '\n')
    checks[-1]['eofGeometry'] = smoke.eof_geometry(ET.fromstring(eof))
    short = b'# HETU-FEEDBACK93-SHORT-hostnonce\nmode: rule\n'
    long = ('# HETU-FEEDBACK93-LONG-BEGIN\n' + '# controlled fixture line no service\n' * smoke.LONG_LINES + '# HETU-FEEDBACK93-LONG-END\n').encode()
    fixtures = []
    for kind, data in (('short', short), ('long', long)):
        (output / ('fixture-' + kind + '.yaml')).write_bytes(data)
        fixtures.append({'kind': kind, 'path': smoke.STARTUP, 'originalAbsent': True, 'writeAttempted': True,
                         'cleanedUp': True, 'bytes': len(data), 'sha256': smoke.sha(data),
                         'afterReadSha256': smoke.sha(data), 'cleanupObservedSha256': smoke.sha(data)})
    checks.append({'name': 'startup-config-fixture-cleanup', 'result': 'passed', 'originalAbsentAndAbsentAfter': True})
    zero = {'uiDumpCalls': 0, 'uiDumpWallSeconds': 0, 'fixedInputSettlingSeconds': 0}
    for name in ('cold', 'warm'):
        raw = 'Status: ok\nThisTime: 0\nTotalTime: 10\nWaitTime: 20\n'
        (output / (name + '-am-start.txt')).write_text(raw)
        (output / (name + '-home-ready-activity-stack.txt')).write_text('mResumedActivity: ' + smoke.MAIN_ACTIVITY + '\n')
        for stage, text in (('home-ready', '河图'), ('resource-detail', '资源占用\nCPU\n内存'),
                            ('interactive-returned-home-ready', '河图')):
            (output / (name + '-' + stage + '.xml')).write_text(hierarchy(text))
            (output / (name + '-' + stage + '.png')).write_bytes(PNG)
        checks.append({'name': name + '-home-interaction', 'result': 'passed',
                       'before': identity if name == 'cold' else launched, 'launched': launched, 'afterInteraction': launched,
                       'androidAmTiming': smoke.parse_am_start(raw), 'freshInstallOrPageCacheCold': False,
                       'explicitStartPage': 'home', 'hostSecondsUntilObservedHome': 2, 'hostSecondsThroughActualInteraction': 3,
                       'instrumentation': {k: zero.copy() for k in ('preparationBeforeAm', 'amThroughObservedHome', 'amThroughCompletedInteraction')},
                       'launchStartedUtc': '2026-10-08T00:00:02+00:00'})
        checks[-1]['observedHomeResumedComponent'] = smoke.MAIN_ACTIVITY
    (output / 'global-logcat-after-feedback.txt').write_text('HOST SYNTHETIC NO ANDROID MEASUREMENT\n')
    report = {'result': 'PASS', 'apiLevel': 35, 'observedRepositoryCommit': COMMIT, 'apkSha256': APK,
              'rootMutationActions': 0, 'rootSetupInputs': 0, 'rootReadAttemptsMeasured': False,
              'originalNavigationChecks': 65, 'originalResultsUnchanged': True,
              'originalResultsSha256': smoke.sha((root / 'results.json').read_bytes()), 'checks': checks,
              'originalPhase': {'nativePeriodDoesNotCoverSubsequentColdWarmLaunches': True,
                                'nativeApplicable': False, 'native': {'result': 'NOT_APPLICABLE'},
                                'originalAcceptanceReturnedUtc': '2026-10-08T00:00:00+00:00',
                                'originalEvidenceSha256': {name: smoke.sha((root / name).read_bytes()) for name in ('results.json', 'launch.txt', 'first-frame-ready.json', 'logcat.txt')}},
              'nativeSoakObservationWasCompletedBeforeAdditionalCases': False,
              'additionalPhaseInitialIdentity': identity, 'ownedTarget': {'apiLevel': 35, 'nonRootShell': True, 'serial': 'emulator-5554'},
              'additionalPhaseStartedUtc': '2026-10-08T00:00:01+00:00', 'additionalPhaseCompletedUtc': '2026-10-08T00:00:03+00:00',
              'startupFixtures': fixtures, 'launcherQuery': smoke.OFFICIAL_LAUNCHER + '\n', 'queriedLauncherComponent': smoke.OFFICIAL_LAUNCHER,
              'inputs': [{'kind': 'native adb tap', 'label': '查看启动配置'}] * 3 + [{'kind': 'native adb tap', 'label': '查看末尾'}],
              'globalLogcatSha256': smoke.sha((output / 'global-logcat-after-feedback.txt').read_bytes()),
              'globalLogcatCoversAdditionalRoutesAndBothLaunches': True, 'limitations': 'HOST SYNTHETIC FIXTURE ONLY'}
    refresh_hashes(output, report)
    return output, report


def refresh_hashes(output, report):
    report['filesSha256'] = {str(p.relative_to(output)): smoke.sha(p.read_bytes()) for p in output.rglob('*')
                            if p.is_file() and p.name != 'feedback93-proof.json'}
    (output / 'feedback93-proof.json').write_text(json.dumps(report))


class FeedbackSmoke93Tests(unittest.TestCase):
    def test_original65_order_and_source_are_pinned(self):
        catalog = json.loads(Path(smoke.__file__).with_name('feedback93_original65.json').read_text())
        root = Path(smoke.__file__).resolve().parents[2]
        for name, digest in catalog['sourceScriptsSha256'].items():
            self.assertEqual(digest, smoke.sha((root / name).read_bytes()))
            baseline = subprocess.check_output(['git', 'show', 'cffeff08ee38c7220922bdb36de81b621c935daf:' + name], cwd=root)
            self.assertEqual(baseline, (root / name).read_bytes())
        with tempfile.TemporaryDirectory() as folder:
            synthetic_proof(folder)
            report = json.loads((Path(folder) / 'results.json').read_text())
            smoke.validate_original_navigation(report)
            report['checks'][0], report['checks'][1] = report['checks'][1], report['checks'][0]
            with self.assertRaises(AssertionError):
                smoke.validate_original_navigation(report)

    def test_adapter_forwards_original_kwargs_and_only_one_exact_command(self):
        original = Mock()
        adapter = SmokeEntryAdapter(original, '/old.py', '/additional.py')
        adapter.run(['adb', 'shell'], timeout=3600, check=True)
        original.run.assert_called_with(['adb', 'shell'], timeout=3600, check=True)
        adapter.run([sys.executable, '/old.py'], timeout=3600, env={'x': 'literal'})
        original.run.assert_called_with([sys.executable, '/additional.py'], timeout=3600, env={'x': 'literal'})
        with self.assertRaises(AssertionError):
            adapter.run([sys.executable, '/old.py'])

    def test_am_timing_preserves_missing_zero_and_reuse(self):
        timing = smoke.parse_am_start('Status: ok\nThisTime: 0\nWarning: Activity not started\n')
        self.assertEqual(timing['ThisTimeMs'], 0)
        self.assertIsNone(timing['WaitTimeMs'])
        self.assertEqual(timing['missingFields'], ['TotalTime', 'WaitTime'])
        self.assertTrue(timing['activityNotStartedWarning'])
        for raw in ('Status: timeout\n', 'Status: ok\nThisTime: 0\nThisTime: 1\n'):
            with self.assertRaises(AssertionError):
                smoke.parse_am_start(raw)

    def test_activity_requires_actual_resumed_unique_component(self):
        self.assertEqual(smoke.resumed_component('mResumedActivity: ' + smoke.MAIN_ACTIVITY), smoke.MAIN_ACTIVITY)
        for raw in ('Historical ActivityRecord ' + smoke.MAIN_ACTIVITY,
                    'mResumedActivity: ' + smoke.MAIN_ACTIVITY + '\ntopResumedActivity: ' + smoke.STARTUP_ACTIVITY):
            with self.assertRaises(AssertionError):
                smoke.resumed_component(raw)
        self.assertTrue(smoke.activity_matches(smoke.OFFICIAL_LAUNCHER, smoke.MAIN_ACTIVITY))
        self.assertFalse(smoke.activity_matches(smoke.OFFICIAL_LAUNCHER, smoke.STARTUP_ACTIVITY))
        self.assertFalse(smoke.activity_matches(smoke.PKG + '/.LauncherOther', smoke.MAIN_ACTIVITY))

    def test_eof_geometry_rejects_clipped_duplicate_and_negative_nodes(self):
        root = ET.fromstring(hierarchy('HETU-FEEDBACK93-LONG-END'))
        smoke.eof_geometry(root)
        for bounds in ('[10,880][480,950]', '[-1,30][480,90]'):
            changed = copy.deepcopy(root)
            changed.find('.//node/node').set('bounds', bounds)
            with self.assertRaises(AssertionError):
                smoke.eof_geometry(changed)
        root.find('node').append(copy.deepcopy(root.find('.//node/node')))
        with self.assertRaises(AssertionError):
            smoke.eof_geometry(root)

    def test_api35_original_phase_cannot_claim_native900(self):
        with tempfile.TemporaryDirectory() as folder:
            synthetic_proof(folder)
            self.assertEqual(smoke.original_phase(folder, 35, APK)['native']['result'], 'NOT_APPLICABLE')
            (Path(folder) / 'post-native-application.json').write_text('{}')
            with self.assertRaises(AssertionError):
                smoke.original_phase(folder, 35, APK)

    def test_consumer_accepts_explicitly_synthetic_host_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            synthetic_proof(folder)
            self.assertEqual(consumer.verify(folder, COMMIT, APK, 35)['additionalCases'], 6)

    def test_rehashed_wrong_viewer_semantics_are_rejected(self):
        for kind, replacement in (('missing', 'Wrong page'), ('short', 'HETU-FEEDBACK93-SHORT-wrongnonce'),
                                  ('long', 'HETU-FEEDBACK93-LONG-END')):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as folder:
                output, report = synthetic_proof(folder)
                (output / ('startup-config-' + kind + '-file-viewer.xml')).write_text(hierarchy('启动配置\n' + replacement))
                refresh_hashes(output, report)
                with self.assertRaises(AssertionError):
                    consumer.verify(folder, COMMIT, APK, 35)

    def test_rehashed_home_instead_of_resource_detail_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            output, report = synthetic_proof(folder)
            (output / 'cold-resource-detail.xml').write_text(hierarchy('河图\n资源占用\nCPU\n内存', dock=True))
            refresh_hashes(output, report)
            with self.assertRaises(AssertionError):
                consumer.verify(folder, COMMIT, APK, 35)

    def test_rehashed_global_app_crash_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            output, report = synthetic_proof(folder)
            raw = 'FATAL EXCEPTION: main\nProcess: ' + smoke.PKG + '\n'
            (output / 'global-logcat-after-feedback.txt').write_text(raw)
            report['globalLogcatSha256'] = smoke.sha(raw.encode())
            refresh_hashes(output, report)
            with self.assertRaises(AssertionError):
                consumer.verify(folder, COMMIT, APK, 35)

    def test_rehashed_warm_pid_change_and_timing_mismatch_are_rejected(self):
        for mutation in ('pid', 'am'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as folder:
                output, report = synthetic_proof(folder)
                if mutation == 'pid':
                    report['checks'][-1]['launched'] = {'pid': 43, 'startTicks': 430}
                else:
                    (output / 'warm-am-start.txt').write_text('Status: ok\nWaitTime: 999\n')
                refresh_hashes(output, report)
                with self.assertRaises(AssertionError):
                    consumer.verify(folder, COMMIT, APK, 35)

    def test_main_alias_is_retained_as_observed_and_startup_alias_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            output, report = synthetic_proof(folder)
            (output / 'cold-home-ready-activity-stack.txt').write_text('mResumedActivity: ' + smoke.OFFICIAL_LAUNCHER + '\n')
            report['checks'][-2]['observedHomeResumedComponent'] = smoke.OFFICIAL_LAUNCHER
            refresh_hashes(output, report)
            self.assertEqual(consumer.verify(folder, COMMIT, APK, 35)['cold']['observedHomeResumedComponent'], smoke.OFFICIAL_LAUNCHER)
            report['checks'][0]['observedResumedActivities']['viewer'] = smoke.OFFICIAL_LAUNCHER
            (output / 'startup-config-missing-file-viewer-activity-stack.txt').write_text('mResumedActivity: ' + smoke.OFFICIAL_LAUNCHER + '\n')
            refresh_hashes(output, report)
            with self.assertRaises(AssertionError):
                consumer.verify(folder, COMMIT, APK, 35)

    def test_rejected_target_never_touches_any_device_for_work_or_diagnostics(self):
        with tempfile.TemporaryDirectory() as folder:
            synthetic_proof(folder)
            # run() creates its own new evidence directory, so remove this synthetic one.
            import shutil
            shutil.rmtree(Path(folder) / 'feedback93')
            adb = Mock(side_effect=AssertionError('Device must not be touched'))
            target = Mock(side_effect=AssertionError('Foreign target rejected'))
            with self.assertRaisesRegex(AssertionError, 'Foreign target'), patch.object(smoke.subprocess, 'run') as calls:
                smoke.run({'OUT': folder, 'ADB': 'MOCK-ADB', 'adb': adb, 'panel_fixture_target': target})
            adb.assert_not_called()
            calls.assert_not_called()
            report = json.loads((Path(folder) / 'feedback93/feedback93-proof.json').read_text())
            self.assertEqual(report['result'], 'FAIL')
            self.assertEqual(report['inputs'], [])
            self.assertEqual(report['startupFixtures'], [])

    def test_fixtures_require_owned_target_and_never_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            api = smoke.NativeFeedback({'adb': Mock(), 'ADB': 'MOCK-ADB'}, folder)
            with self.assertRaises(AssertionError), api.fixture('short', b'literal'):
                pass
            api.adb.assert_not_called()
            api.owned_target_verified = True
            with patch.object(api, 'exists', return_value=True), self.assertRaises(AssertionError), api.fixture('short', b'literal'):
                pass
            api.adb.assert_not_called()

    def test_fixture_stdin_preserves_literal_bytes_and_finally_cleans_only_own_hash(self):
        data = b'# literal `uname` $(id) \x00\n'
        with tempfile.TemporaryDirectory() as folder:
            adb = Mock(side_effect=lambda *args, **kw: '/sandbox/files/hetu/run/state\n' if 'readlink' in args else '/sandbox\n')
            api = smoke.NativeFeedback({'adb': adb, 'ADB': 'MOCK-ADB'}, folder)
            api.owned_target_verified = True
            with patch.object(api, 'exists', side_effect=[False, True, False]), patch.object(api, 'startup_sha', return_value=smoke.sha(data)), patch.object(smoke.subprocess, 'run') as run:
                with self.assertRaisesRegex(RuntimeError, 'viewer failed'), api.fixture('short', data):
                    raise RuntimeError('viewer failed')
                self.assertEqual(run.call_args.kwargs['input'], data)
                self.assertNotIn(data.decode(), ' '.join(run.call_args.args[0]))
                self.assertTrue(api.fixture_records[0]['cleanedUp'])
                self.assertIn(('shell', 'run-as', smoke.PKG, 'rm', '-f', smoke.STARTUP), [c.args for c in adb.call_args_list])

    def test_changed_or_partial_fixture_is_retained_and_cleanup_fails(self):
        for failure in ('changed', 'partial-write'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as folder:
                adb = Mock(side_effect=lambda *args, **kw: '/sandbox/files/hetu/run/state\n' if 'readlink' in args else '/sandbox\n')
                api = smoke.NativeFeedback({'adb': adb, 'ADB': 'MOCK-ADB'}, folder)
                api.owned_target_verified = True
                values = [smoke.sha(b'owned'), 'f' * 64] if failure == 'changed' else ['f' * 64]
                error = None if failure == 'changed' else subprocess.CalledProcessError(1, ['MOCK-ADB'])
                with patch.object(api, 'exists', side_effect=[False, True]), patch.object(api, 'startup_sha', side_effect=values), patch.object(smoke.subprocess, 'run', side_effect=error):
                    with self.assertRaisesRegex(AssertionError, 'Refuse to delete'), api.fixture('short', b'owned'):
                        raise RuntimeError('viewer failed')
                self.assertFalse(api.fixture_records[0]['cleanedUp'])
                self.assertFalse(any('rm' in c.args for c in adb.call_args_list))


if __name__ == '__main__':
    unittest.main()
