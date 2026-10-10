#!/usr/bin/env python3
"""Recovery evidence negative guards; synthetic fixtures are never Android execution."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

import continuity93_source_scope as scope
from verify_continuity93_test_results import verify


class Continuity93EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.layer = json.loads(scope.LAYER_FILE.read_text())
        cls.previous = scope.previous_files()

    def reject(self, change):
        invalid = copy.deepcopy(self.layer)
        change(invalid)
        with self.assertRaises(AssertionError):
            scope.validate_layer(invalid, self.previous)

    def test_all_current_source_inputs_and_original644_assertions_are_bound(self):
        final = scope.validate_checkout()
        self.assertGreater(len(final), len(self.previous))
        self.assertEqual(644, self.layer['baselineUnitTests'])
        self.assertEqual(67, self.layer['baselineTestXmlFiles'])
        self.assertGreaterEqual(self.layer['expectedUnitTests'], 644 + 23)
        scope.validate_baseline_test_sources()
        scope.validate_host_fixture()
        scope.validate_stress_fixture()

    def test_recovery_archive_bytes_and_partial_provenance_cannot_be_relabelled(self):
        self.reject(lambda layer: layer.update(recoveryPipelineProvenance='exact final pre-cleanup recovery'))
        self.reject(lambda layer: layer.update(recoveredArtifactHashes={}))

    def test_native_payloads_dependencies_permissions_and_signing_cannot_change(self):
        for name in ('android-app/app/src/main/AndroidManifest.xml', 'android-app/settings.gradle.kts', *scope.EXTERNAL_PAYLOAD_FILES):
            self.assertFalse(scope.allowed_change(name))
        for flag in scope.PROTECTED_FLAGS:
            self.reject(lambda layer, flag=flag: layer.update({flag: False}))
        self.reject(lambda layer: layer.update(previousInputsSha256='0' * 64))
        self.reject(lambda layer: layer['other21RuntimePayloadFiles'].update({next(iter(layer['other21RuntimePayloadFiles'])): '0' * 64}))

    def test_old_testcase_identity_cannot_be_replaced_with_matching_counts(self):
        name = next(iter(self.layer['baselineUnitTestMethods']))
        self.reject(lambda layer: layer['finalUnitTestMethods'][name]['methods'].__setitem__(0, 'inventedIdentity'))
        self.reject(lambda layer: layer.update(baselineUnitTests=643))

    def test_build_and_runtime_contract_changes_are_exact(self):
        before = scope.committed_bytes(scope.BUILD_FILE).decode()
        after = (scope.ROOT / scope.BUILD_FILE).read_text()
        scope.validate_version(before, after)
        with self.assertRaises(AssertionError):
            scope.validate_version(before, after + '\nimplementation("new:dependency:1")\n')
        # The release-build delta is pinned exactly: no other minify, signing or dependency change.
        for invalid in (after.replace('isMinifyEnabled = true', 'isMinifyEnabled = false'),
                        after.replace('isDebuggable = false', 'isDebuggable = true'),
                        after.replace('signingConfig = signingConfigs.getByName("debug")', 'signingConfig = signingConfigs.getByName("release")'),
                        after.replace('profileinstaller:1.4.1', 'profileinstaller:1.4.0')):
            self.assertNotEqual(invalid, after)
            with self.assertRaises(AssertionError):
                scope.validate_version(before, invalid)
        before = scope.committed_bytes(scope.REVISION_TEST).decode()
        after = (scope.ROOT / scope.REVISION_TEST).read_text()
        scope.validate_revision_test(before, after)
        with self.assertRaises(AssertionError):
            scope.validate_revision_test(before, after.replace('assertEquals', 'assertNotEquals'))

    def fixture_results(self, folder):
        for name, entry in self.layer['finalUnitTestMethods'].items():
            suite = ET.Element('testsuite', name=name, tests=str(len(entry['methods'])), failures='0', errors='0', skipped='0')
            for method in entry['methods']:
                ET.SubElement(suite, 'testcase', name=method, classname=name)
            ET.ElementTree(suite).write(folder / ('TEST-' + name + '.xml'))

    def test_missing_failed_skipped_or_relabelled_android_result_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            self.fixture_results(folder)
            name = next(iter(self.layer['finalUnitTestMethods']))
            path = folder / ('TEST-' + name + '.xml')
            suite = ET.parse(path).getroot()
            case = suite.find('testcase')
            original = case.get('name')
            case.set('name', 'inventedIdentity')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError): verify(folder, self.layer)
            case.set('name', original)
            for failure in ('failure', 'error', 'skipped'):
                element = ET.SubElement(case, failure)
                ET.ElementTree(suite).write(path)
                with self.assertRaises(AssertionError): verify(folder, self.layer)
                case.remove(element)
            path.unlink()
            with self.assertRaises(AssertionError): verify(folder, self.layer)

    def test_workflow_keeps_old_assertions_sustained_runs_and_no_cancel(self):
        text = (scope.ROOT / '.github/workflows/v2093-build.yml').read_text()
        for token in ('cancel-in-progress: false', 'gradle-version: 9.5.0', 'fail-on-cache-miss: true',
                      'test_root_health.py', 'test_google_firewall.py', 'test_root_autostart.py',
                      'test_root_safety93.py', 'test_app_recovery93.py', 'test_continuity92_evidence.py',
                      'test_mihomo_soak90.py', 'run_network_soak90.py --duration 900',
                      'HETU_NATIVE_SOAK90_SECONDS:', '900', 'verify_stability90_supplemental.py',
                      'verify_packaged_runtime.py', 'verify_materialkolor_abi.py', 'verify_hetu_icons.py',
                      ':app:assembleDebug', ':app:lintDebug', ':app:testDebugUnitTest',
                      'verify_continuity93_test_results.py', 'continuity93_ci_config.py junit-filters',
                      'transport_continuity93_failures.py',
                      'run_continuity93_baseline_hosts.py --historical-before-output',
                      '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad',
                      'Reproduce V20.74 custom palette crash on API 36',
                      ':app:assembleRelease', 'verify_release_continuity93.py', "HETU_RELEASE_SMOKE: '1'",
                      'test_core_support93.py', 'test_fast_start93.py', 'name: Hetu-V20.93-debug-test-APK'):
            self.assertIn(token, text)
        self.assertNotIn('continue-on-error:', text)
        old = (scope.ROOT / '.github/workflows/v2092-build.yml').read_text()
        self.assertNotIn('  push:', old, 'Only the current workflow should run on push')
        self.assertIn('workflow_dispatch:', old)

    def test_core_picker_sync_cannot_remove_assertions_or_extend_wait_budget(self):
        before = scope.committed_bytes(scope.CORE_READY_FIXTURE).decode()
        after = (scope.ROOT / scope.CORE_READY_FIXTURE).read_text()
        scope.validate_initial_core_ready_wait(before, after)
        for invalid in (before, after.replace('expect("Xray")', 'Unit'),
                        after.replace('5_000_000_000L', '50_000_000_000L'),
                        after.replace('assertEquals', 'assertNotEquals')):
            with self.assertRaises(AssertionError):
                scope.validate_initial_core_ready_wait(before, invalid)

    def test_failed_read_gate_cannot_remove_assertions_or_change_other_responses(self):
        before = scope.committed_bytes(scope.SELECTION_READ_FIXTURE).decode()
        after = (scope.ROOT / scope.SELECTION_READ_FIXTURE).read_text()
        scope.validate_failed_read_gate(before, after)
        for invalid in (before, after.replace('assertTrue', 'assertFalse'),
                        after.replace('if (connectionCode != 200) readGate?.hold()', 'readGate?.hold()'),
                        after.replace('TimeUnit.SECONDS.toNanos(6)', 'TimeUnit.SECONDS.toNanos(60)'),
                        after.replace('readCode = 500, connectionCode = 500', 'readCode = 500')):
            with self.assertRaises(AssertionError):
                scope.validate_failed_read_gate(before, invalid)

    def test_controller_read_gate_cannot_remove_assertions_or_change_other_responses(self):
        before = scope.committed_bytes(scope.CONTROLLER_READ_FIXTURE).decode()
        after = (scope.ROOT / scope.CONTROLLER_READ_FIXTURE).read_text()
        scope.validate_controller_read_gate(before, after)
        for invalid in (before, after.replace('assertTrue(server.requestCount >= 4)', 'assertTrue(server.requestCount >= 1)'),
                        after.replace('rejectedPath == "/connections" && request.path == "/connections"', 'request.path == "/connections"'),
                        after.replace('it.await(6, TimeUnit.SECONDS)', 'it.await(60, TimeUnit.SECONDS)')):
            with self.assertRaises(AssertionError):
                scope.validate_controller_read_gate(before, invalid)

    def test_same_source_api35_and_api36_are_preserved(self):
        text = (scope.ROOT / '.github/workflows/v2093-build.yml').read_text()
        self.assertIn("HETU_EXPECTED_VERSION: '2093'", text)
        self.assertIn('        - 35\n        - 36', text)
        self.assertIn('needs: build', text)
        self.assertIn('name: Hetu-APK', text)
        self.assertIn('python3 .github/scripts/run_hetu_emulator.py', text)


if __name__ == '__main__':
    unittest.main()
