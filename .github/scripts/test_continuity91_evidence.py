#!/usr/bin/env python3
"""Negative guards for the new layer; synthetic XML/PNG are never execution evidence."""
import copy
import json
from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

import continuity91_source_scope as scope
from verify_continuity91_test_results import verify
from verify_continuity91_delivery import verify_continuation_previews, REQUIRED_CONTINUATION_PREVIEWS
from run_continuity91_before_comparison import (verify_regression_results, require_original_instance_generation,
    EXPECTED_FAILURES, CLASS, PRODUCTION)


class Continuity91EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.layer = json.loads(scope.LAYER_FILE.read_text())
        cls.previous = scope.previous_files()

    def reject_layer(self, mutate):
        wrong = copy.deepcopy(self.layer)
        mutate(wrong)
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)

    def fixture_results(self, folder):
        for cls, entry in self.layer['finalUnitTestMethods'].items():
            suite = ET.Element('testsuite', name=cls, tests=str(len(entry['methods'])), failures='0', errors='0', skipped='0')
            for method in entry['methods']:
                ET.SubElement(suite, 'testcase', name=method, classname=cls)
            ET.ElementTree(suite).write(folder / ('TEST-' + cls + '.xml'))

    def test_full_current_source_and_exact631_predecessor_are_validated(self):
        final = scope.validate_checkout()
        self.assertEqual(len(final), len(self.previous) + 2)
        self.assertEqual(self.layer['expectedUnitTests'], 639)
        self.assertEqual(self.layer['expectedTestXmlFiles'], 66)
        self.assertEqual(5, len(self.layer['changedOrAddedFiles']))

    def test_old_layers_failures_native_signing_permissions_and_existing_tests_are_frozen(self):
        for path in (scope.ROOT_SCRIPT, scope.AUTOSTART_SCRIPT, 'android-app/app/src/main/AndroidManifest.xml',
                     'android-app/settings.gradle.kts', *scope.EXTERNAL_PAYLOAD_FILES,
                     scope.TEST_ROOT + 'java/io/github/xgl34222220/hetu/GlassMaterial90Test.kt'):
            self.assertFalse(scope.allowed_change(path), path)
        for flag in scope.PROTECTED_FLAGS:
            self.reject_layer(lambda layer, flag=flag: layer.update({flag: False}))
        self.reject_layer(lambda layer: layer.update(previousInputsSha256='0' * 64))
        self.reject_layer(lambda layer: layer['historicalFilesSha256'].update({next(iter(layer['historicalFilesSha256'])): '0' * 64}))

    def test_preserved631_identity_and_source_cannot_be_replaced_with_same_counts(self):
        cls = next(iter(self.layer['baselineUnitTestMethods']))
        self.reject_layer(lambda layer: layer['finalUnitTestMethods'][cls]['methods'].__setitem__(0, 'inventedIdentity'))
        self.reject_layer(lambda layer: layer['finalUnitTestMethods'][cls].update(source=scope.TEST_ROOT + 'Other.kt'))
        self.reject_layer(lambda layer: layer.update(baselineUnitTests=630))

    def test_gradle_inputs_are_version_only_and_dependency_or_signing_changes_fail(self):
        before = scope.committed_bytes(scope.BUILD_FILE).decode()
        current = (scope.ROOT / scope.BUILD_FILE).read_text()
        scope.validate_version(before, current)
        for suffix in ('\nimplementation("new:dependency:1")\n', '\nsigningChanged()\n'):
            with self.assertRaises(AssertionError):
                scope.validate_version(before, current + suffix)

    def test_current_xml_missing_replaced_or_failed_cases_cannot_hide_behind_counts(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            self.fixture_results(folder)
            path = folder / ('TEST-' + CLASS + '.xml')
            suite = ET.parse(path).getroot()
            case = suite.find('testcase')
            original = case.get('name')
            case.set('name', 'inventedIdentity')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError): verify(folder, self.layer)
            case.set('name', original)
            ET.SubElement(case, 'failure', message='real failure must reject current pass')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError): verify(folder, self.layer)
            path.unlink()
            with self.assertRaises(AssertionError): verify(folder, self.layer)

    def test_missing_or_duplicate_new_preview_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            for name in REQUIRED_CONTINUATION_PREVIEWS:
                (folder / name).write_bytes(b'\x89PNG\r\n\x1a\nnegative-fixture-only')
            images = list(folder.glob('*.png'))
            verify_continuation_previews(images)
            with self.assertRaises(AssertionError): verify_continuation_previews(images[:-1])
            with self.assertRaises(AssertionError): verify_continuation_previews(images[:-1] + [images[0]])

    def test_before_comparison_requires_all_four_exact_failure_identities_and_reasons(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            suite = ET.Element('testsuite', name=CLASS, tests='4', failures='4', errors='0', skipped='0')
            for method, (kind, message) in EXPECTED_FAILURES.items():
                case = ET.SubElement(suite, 'testcase', name=method, classname=CLASS)
                ET.SubElement(case, 'failure', type=kind, message=message)
            path = folder / ('TEST-' + CLASS + '.xml')
            ET.ElementTree(suite).write(path)
            self.assertEqual(verify_regression_results(folder)['tests'], 4)
            failure = suite.find('testcase/failure')
            failure.set('message', 'Connection refused')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError): verify_regression_results(folder)
            failure.set('message', next(iter(EXPECTED_FAILURES.values()))[1])
            failure.set('type', 'java.net.ConnectException')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError): verify_regression_results(folder)

    def test_exact_original_instance_source_is_accepted_and_shared_current_source_is_rejected(self):
        before = scope.committed_bytes(PRODUCTION).decode()
        require_original_instance_generation(before)
        with self.assertRaises(AssertionError):
            require_original_instance_generation((scope.ROOT / PRODUCTION).read_text())

    def test_workflow_retains_original_selectors_and_non_cancelled_read_only_signing(self):
        current = (scope.ROOT / '.github/workflows/v2091-build.yml').read_text()
        before = scope.committed_bytes('.github/workflows/v2090-build.yml').decode()
        old_unit = before.split('    - name: Runtime unit tests\n', 1)[1].split('    - name: Android lint', 1)[0]
        for selector in re.findall(r"'(\*[A-Za-z][A-Za-z0-9_.]*)'", old_unit):
            self.assertIn(selector, current)
        for marker in ('contents: read', 'actions: read', 'cancel-in-progress: false', 'fail-on-cache-miss: true',
                       'key: hetu-ui10-debug-signing-v1', '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad',
                       'verify_packaged_runtime.py verify', 'verify_materialkolor_abi.py', 'verify_stability90_supplemental.py',
                       'supplemental36.init.gradle', '*CompactHomeDashboardTest', '*Ui92IntegrationTest', '*NodeSelectionContinuityTest',
                       'HETU_NATIVE_SOAK90_SECONDS', 'run_network_soak90.py --duration 900',
                       'run_continuity91_before_comparison.py', 'verify_continuity91_test_results.py', 'outputs/glass90/', 'outputs/glass91/'):
            self.assertIn(marker, current)
        for marker in ('contents: write', 'actions: write', 'gh release', 'cancel-in-progress: true'):
            self.assertNotIn(marker, current)

    def test_installed65_dual_api_and_historical_jobs_remain_byte_exact_except_candidate_identity(self):
        before = scope.committed_bytes('.github/workflows/v2090-build.yml').decode()
        current = (scope.ROOT / '.github/workflows/v2091-build.yml').read_text()
        normalized = current.replace('Hetu-V20.91-', 'Hetu-V20.90-').replace("HETU_EXPECTED_VERSION: '2091'", "HETU_EXPECTED_VERSION: '2090'")
        def installed_jobs(source):
            return source.split('  android-ui-smoke:\n', 1)[1].split("'on':\n", 1)[0]
        self.assertEqual(installed_jobs(before), installed_jobs(normalized))

    def test_only_new_workflow_owns_this_branch_push_and_predecessor_body_is_preserved(self):
        old = (scope.ROOT / '.github/workflows/v2090-build.yml').read_text()
        before = scope.committed_bytes('.github/workflows/v2090-build.yml').decode()
        self.assertEqual(old.split("'on':\n", 1)[0], before.split("'on':\n", 1)[0])
        self.assertEqual(old.split("'on':\n", 1)[1], '  workflow_dispatch: null\n')
        self.assertNotIn('    - test/v20.76-new-ui\n', old)
        current = (scope.ROOT / '.github/workflows/v2091-build.yml').read_text()
        self.assertEqual(1, current.count('    - test/v20.76-new-ui\n'))
        self.assertIn('workflow_dispatch: null', old)
        positions = [current.index(marker) for marker in (
            '    - name: Build APK\n',
            '    - name: Reproduce shared-cache and late-result defects on the exact original instance generation\n',
            '    - name: Runtime unit tests\n', '    - name: Android lint compatibility gate\n',
            '    - name: Execute all 36 unchanged and applicable supplemental regressions\n',
            '    - name: Run sustained synthetic network recovery state-machine regression\n',
            '      name: Upload concept UI verification evidence\n', '    - name: Verify APK\n')]
        self.assertEqual(positions, sorted(positions))
        self.assertIn('    - if: always()\n      name: Upload concept UI verification evidence', current)


if __name__ == '__main__':
    unittest.main()
