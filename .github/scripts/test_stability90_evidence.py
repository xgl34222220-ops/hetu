#!/usr/bin/env python3
"""Negative evidence guards; fixtures are not Android test or networking results."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

import stability90_source_scope as scope
from verify_stability90_test_results import verify
from verify_stability90_delivery import verify_glass_previews, REQUIRED_GLASS_PREVIEWS
from verify_stability90_supplemental import verify as verify_supplemental, expected_methods as supplemental_methods


class Stability90EvidenceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.layer = json.loads(scope.LAYER_FILE.read_text())
        cls.previous = scope.previous_files()

    def reject(self, mutator):
        wrong = copy.deepcopy(self.layer)
        mutator(wrong)
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)

    def fixture_results(self, folder):
        for cls, item in self.layer['finalUnitTestMethods'].items():
            suite = ET.Element('testsuite', name=cls, tests=str(len(item['methods'])), failures='0', errors='0', skipped='0')
            for method in item['methods']:
                ET.SubElement(suite, 'testcase', name=method, classname=cls, time='0')
            ET.ElementTree(suite).write(folder / ('TEST-' + cls + '.xml'))

    def test_original545_methods_cannot_be_omitted_despite_declared_count(self):
        cls = next(iter(self.layer['baselineUnitTestMethods']))
        self.reject(lambda x: x['finalUnitTestMethods'][cls]['methods'].pop())
        self.reject(lambda x: x.update(baselineUnitTests=544))
        self.reject(lambda x: x['baselineUnitTestMethods'][cls]['methods'].pop())

    def test_manifest_native_signing_and_history_remain_protected(self):
        for path in ('android-app/app/src/main/AndroidManifest.xml', 'android-app/settings.gradle.kts',
                     scope.AUTOSTART_SCRIPT, *scope.EXTERNAL_PAYLOAD_FILES):
            self.assertFalse(scope.allowed_change(path), path)
        for flag in scope.PROTECTED_FLAGS:
            self.reject(lambda x, flag=flag: x.update({flag: False}))
        self.reject(lambda x: x.update(previousPatchSha256='0' * 64))
        self.reject(lambda x: x['externalPayloadFiles'].update({next(iter(scope.EXTERNAL_PAYLOAD_FILES)): '0' * 64}))

    def test_missing_testcase_or_suite_cannot_be_hidden_by_xml_counters(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            self.fixture_results(out)
            path = next(out.glob('TEST-*.xml'))
            suite = ET.parse(path).getroot()
            suite.remove(suite.find('testcase'))
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError):
                verify(out, self.layer)
            path.unlink()
            with self.assertRaises(AssertionError):
                verify(out, self.layer)

    def test_duplicate_case_or_skipped_case_rejects_even_if_suite_count_is_zero(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            self.fixture_results(out)
            path = next(out.glob('TEST-*.xml'))
            suite = ET.parse(path).getroot()
            case = suite.find('testcase')
            ET.SubElement(case, 'skipped')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError):
                verify(out, self.layer)
            case.remove(case.find('skipped'))
            suite.append(copy.deepcopy(case))
            suite.set('tests', str(len(suite)))
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError):
                verify(out, self.layer)

    def test_historical_scope_and_wrapper_are_unmodified(self):
        for name in ('functionfix_source_scope.py', 'run_functionfix_baseline_hosts.py'):
            relative = '.github/scripts/' + name
            before = subprocess.check_output(['git', 'show', scope.ORIGINAL_TEST_HEAD + ':' + relative], cwd=scope.ROOT)
            self.assertEqual(before, (scope.ROOT / relative).read_bytes(), relative)

    def test_missing_glass_preview_or_duplicate_identity_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            for name in REQUIRED_GLASS_PREVIEWS:
                (out / name).write_bytes(b'\x89PNG\r\n\x1a\nfixture-only')
            images = sorted(out.glob('*.png'))
            verify_glass_previews(images)
            for missing in images:
                with self.assertRaises(AssertionError):
                    verify_glass_previews([p for p in images if p != missing])
            with self.assertRaises(AssertionError):
                verify_glass_previews(images[:-1] + [images[0]])

    def test_supplemental36_uses_unchanged_sources_and_rejects_missing_cases(self):
        expected = supplemental_methods()
        self.assertEqual(sum(map(len, expected.values())), 36)
        self.assertTrue(set(expected).isdisjoint(self.layer['finalUnitTestMethods']))
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            for cls, methods in expected.items():
                suite = ET.Element('testsuite', name=cls, tests=str(len(methods)), failures='0', errors='0', skipped='0')
                for method in methods:
                    ET.SubElement(suite, 'testcase', name=method, classname=cls)
                ET.ElementTree(suite).write(out / ('TEST-' + cls + '.xml'))
            self.assertEqual(verify_supplemental(out, expected)['totalTests'], 36)
            path = next(out.glob('TEST-*.xml'))
            suite = ET.parse(path).getroot()
            case = suite.find('testcase')
            ET.SubElement(case, 'skipped')
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError):
                verify_supplemental(out, expected)
            case.remove(case.find('skipped'))
            suite.remove(case)
            ET.ElementTree(suite).write(path)
            with self.assertRaises(AssertionError):
                verify_supplemental(out, expected)

    def test_new_workflow_retains_old_selectors_and_non_cancelled_fixed_identity(self):
        current = (scope.ROOT / '.github/workflows/v2090-build.yml').read_text()
        old = (scope.ROOT / '.github/workflows/ui-v76-build.yml').read_text()
        import re
        old_unit = old.split('    - name: Runtime unit tests\n', 1)[1].split('    - name: Android lint', 1)[0]
        for selector in re.findall(r"'(\*[A-Za-z][A-Za-z0-9_.]*)'", old_unit):
            self.assertIn(selector, current)
        for marker in ('contents: read', 'actions: read', 'cancel-in-progress: false',
                       'fail-on-cache-miss: true', 'key: hetu-ui10-debug-signing-v1',
                       '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad',
                       'verify_packaged_runtime.py verify', 'verify_materialkolor_abi.py',
                       'verify_stability90_test_results.py', 'test_core_identity_dns.py',
                       'verify_stability90_supplemental.py', 'supplemental36.init.gradle',
                       '*CompactHomeDashboardTest', '*Ui92IntegrationTest', '*NodeSelectionContinuityTest',
                       'test_dns_takeover_probe.py', 'HETU_NATIVE_SOAK90_SECONDS',
                       'run_network_soak90.py --duration 900', 'test/v20.76-new-ui'):
            self.assertIn(marker, current)
        self.assertNotIn('    - test/v20.76-new-ui\n', old)
        self.assertNotIn('contents: write', current)
        self.assertNotIn('actions: write', current)
        self.assertNotIn('gh release', current)


if __name__ == '__main__':
    unittest.main()
