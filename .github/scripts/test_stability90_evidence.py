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
from verify_stability90_delivery import (
    verify_glass_previews, REQUIRED_GLASS_PREVIEWS,
    verify_apk_signature_report, PINNED_CERTIFICATE_SHA256,
    verify_observed_navigation_frame,
)
from verify_stability90_supplemental import verify as verify_supplemental, expected_methods as supplemental_methods


class ApkSignatureReportTests(unittest.TestCase):
    # Exact 313-byte SDK37 record from the actual da586f candidate artifact.
    SDK37_REPORT = (
        'V2 Signer: certificate DN: CN=Hetu UI10 Debug, O=Android, C=US\n'
        'V2 Signer: certificate SHA-256 digest: 701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad\n'
        'V2 Signer: certificate SHA-1 digest: a91dd1063bee5a5ec2a57fa10350d92e5992ec78\n'
        'V2 Signer: certificate MD5 digest: 2145b4b9982d82183a16ada2d310e801\n'
    )

    def test_actual_sdk37_v2_record_is_accepted(self):
        self.assertEqual(len(self.SDK37_REPORT.encode()), 313)
        self.assertEqual(verify_apk_signature_report(self.SDK37_REPORT), PINNED_CERTIFICATE_SHA256)

    def test_original_numbered_signer_record_is_accepted(self):
        legacy = 'Verifies\nNumber of signers: 1\n' + self.SDK37_REPORT.replace('V2 Signer:', 'Signer #1')
        self.assertEqual(verify_apk_signature_report(legacy), PINNED_CERTIFICATE_SHA256)

    def test_uppercase_digest_preserves_exact_certificate_identity(self):
        report = self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256, PINNED_CERTIFICATE_SHA256.upper())
        self.assertEqual(verify_apk_signature_report(report), PINNED_CERTIFICATE_SHA256)

    def test_empty_or_pin_without_a_certificate_record_is_rejected(self):
        for report in ('', '\n', 'Verifies\n', PINNED_CERTIFICATE_SHA256, 'Expected pin: ' + PINNED_CERTIFICATE_SHA256):
            with self.subTest(report=report), self.assertRaises(AssertionError):
                verify_apk_signature_report(report)

    def test_malformed_certificate_or_unknown_signer_syntax_is_rejected(self):
        for report in (
            self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256, PINNED_CERTIFICATE_SHA256[:-1]),
            self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256, PINNED_CERTIFICATE_SHA256 + '0'),
            self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256, 'g' + PINNED_CERTIFICATE_SHA256[1:]),
            self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256 + '\n', PINNED_CERTIFICATE_SHA256 + ' extra\n'),
            self.SDK37_REPORT.replace('certificate SHA-256 digest:', 'certificate SHA-256:'),
            self.SDK37_REPORT.replace('V2 Signer:', 'V2 Signer'),
            self.SDK37_REPORT.replace('V2 Signer:', 'V3 Signer:'),
            self.SDK37_REPORT.replace('V2 Signer:', 'Signer #2'),
            self.SDK37_REPORT.replace('V2 Signer: certificate SHA-256', ' V2 Signer: certificate SHA-256'),
        ):
            with self.subTest(report=report), self.assertRaises(AssertionError):
                verify_apk_signature_report(report)

    def test_mismatched_digest_is_rejected_even_with_pin_in_another_line(self):
        wrong = self.SDK37_REPORT.replace(PINNED_CERTIFICATE_SHA256, '0' * 64)
        with self.assertRaises(AssertionError):
            verify_apk_signature_report(wrong + 'Expected pin: ' + PINNED_CERTIFICATE_SHA256 + '\n')

    def test_duplicate_and_multiple_signer_certificates_are_rejected(self):
        certificate = 'V2 Signer: certificate SHA-256 digest: ' + PINNED_CERTIFICATE_SHA256 + '\n'
        for extra in (certificate, certificate.replace('V2 Signer:', 'Signer #1'),
                      certificate.replace('V2 Signer:', 'Signer #2')):
            with self.subTest(extra=extra), self.assertRaises(AssertionError):
                verify_apk_signature_report(self.SDK37_REPORT + extra)

    def test_extra_malformed_certificate_or_second_signer_metadata_is_rejected(self):
        for extra in ('Signer #2 certificate SHA-256 digest: malformed\n',
                      'Signer #2 certificate DN: CN=Other\n',
                      'Signer #two certificate DN: CN=Other\n',
                      'Number of signers: 2\n'):
            with self.subTest(extra=extra), self.assertRaises(AssertionError):
                verify_apk_signature_report(self.SDK37_REPORT + extra)


class NavigationWindowEvidenceTests(unittest.TestCase):
    VIEWPORT = (0, 0, 394, 852)
    FRAME = (0, 804, 394, 852)
    INSET = 'InsetsSource: {2 mType=navigationBars mFrame=[0,804][394,852] mVisible=true mFlags=[]}'

    def fixtures(self):
        return [Path(__file__).with_name('fixtures').joinpath(f'panel-footer-window-api{api}.txt').read_text()
                for api in (35, 36)]

    def taskbar(self, raw):
        import re
        blocks = re.split(r'(?=^  Window #\d+ Window\{)', raw, flags=re.MULTILINE)
        matches = [block for block in blocks if re.search(r'\bty=NAVIGATION_BAR\b', block)]
        self.assertEqual(len(matches), 1)
        return matches[0]

    def test_actual_api35_and_api36_current_window_frames_are_bound(self):
        for raw in self.fixtures():
            with self.subTest(api_dump_sha256=__import__('hashlib').sha256(raw.encode()).hexdigest()):
                verify_observed_navigation_frame(raw, self.VIEWPORT, self.FRAME)

    def test_missing_hidden_other_display_or_unready_windows_never_substitute(self):
        for raw in self.fixtures():
            nav = self.taskbar(raw)
            for old, new in (('isVisible=true', 'isVisible=false'), ('mDisplayId=0', 'mDisplayId=1'),
                             ('mViewVisibility=0x0', 'mViewVisibility=0x4'), ('mHaveFrame=true', 'mHaveFrame=false'),
                             ('mHasSurface=true', 'mHasSurface=false'), ('isReadyForDisplay()=true', 'isReadyForDisplay()=false'),
                             ('isOnScreen=true', 'isOnScreen=false'), ('ty=NAVIGATION_BAR ', 'ty=NAVIGATION_BAR_PANEL ')):
                with self.subTest(old=old), self.assertRaises(AssertionError):
                    verify_observed_navigation_frame(raw.replace(nav, nav.replace(old, new)), self.VIEWPORT, self.FRAME)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw.replace(nav, ''), self.VIEWPORT, self.FRAME)

    def test_parent_display_last_or_configured_insets_cannot_supply_current_frame(self):
        for raw in self.fixtures():
            nav = self.taskbar(raw)
            for changed in (nav.replace(' frame=[0,804][394,852]', ''),
                            nav.replace(' frame=[0,804][394,852]', ' frame=[0,790][394,852]'),
                            nav.replace(' frame=[0,804][394,852]', ' frame=[0,804][200,852]'),
                            nav.replace(' frame=[0,804][394,852]', ' frame=[0,804][394,850]')):
                with self.assertRaises(AssertionError):
                    verify_observed_navigation_frame(raw.replace(nav, changed), self.VIEWPORT, self.FRAME)
            providers = '\n'.join(line for line in raw.splitlines() if 'InsetsFrameProvider:' in line)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(providers, self.VIEWPORT, self.FRAME)

    def test_insets_and_window_observations_must_agree(self):
        for raw in self.fixtures():
            verify_observed_navigation_frame(raw + '\n' + self.INSET, self.VIEWPORT, self.FRAME)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw + '\n' + self.INSET.replace('804', '790'), self.VIEWPORT, self.FRAME)

    def test_same_line_insets_and_rect_formats_preserve_visible_type_scope(self):
        status = 'InsetsSource: {1 mType=statusBars mFrame=[0,0][394,24] mVisible=true}, '
        verify_observed_navigation_frame(status + self.INSET, self.VIEWPORT, self.FRAME)
        verify_observed_navigation_frame(self.INSET.replace('[0,804][394,852]', 'Rect(0, 804 - 394, 852)'), self.VIEWPORT, self.FRAME)
        for raw in ('', self.INSET.replace('mVisible=true', 'mVisible=false'),
                    status + self.INSET.replace('mVisible=true', 'mVisible=false')):
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw, self.VIEWPORT, self.FRAME)

    def test_other_window_frame_cannot_leak_into_navigation_scope(self):
        for raw in self.fixtures():
            nav = self.taskbar(raw)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw.replace(nav, nav.replace(' frame=[0,804][394,852]', '')),
                                                 self.VIEWPORT, self.FRAME)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw, self.VIEWPORT, (0, 790, 394, 852))

    def test_current_attrs_type_and_unique_window_are_required(self):
        import re
        for raw in self.fixtures():
            nav = self.taskbar(raw)
            # Rotation configurations still mention NAVIGATION_BAR. They do
            # not make an active NAVIGATION_BAR_PANEL window a navigation bar.
            changed = re.sub(r'(^\s+mAttrs=\{[^\n]*\bty=)NAVIGATION_BAR\b',
                             r'\1NAVIGATION_BAR_PANEL', nav, count=1, flags=re.MULTILINE)
            self.assertNotEqual(nav, changed)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw.replace(nav, changed), self.VIEWPORT, self.FRAME)
            with self.assertRaises(AssertionError):
                verify_observed_navigation_frame(raw + '\n' + nav, self.VIEWPORT, self.FRAME)

    def test_hidden_navigation_observations_cannot_be_masked_by_other_sources(self):
        for raw in self.fixtures():
            nav = self.taskbar(raw)
            for changed in (raw.replace(nav, nav.replace('isVisible=true', 'isVisible=false')) + '\n' + self.INSET,
                            raw + '\n' + self.INSET.replace('mVisible=true', 'mVisible=false')):
                with self.assertRaises(AssertionError):
                    verify_observed_navigation_frame(changed, self.VIEWPORT, self.FRAME)


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
        ordered = ('    - name: Build APK\n', '    - name: Runtime unit tests\n',
                   '    - name: Android lint compatibility gate\n',
                   '    - name: Execute all 36 unchanged and applicable supplemental regressions\n',
                   '    - name: Verify independent supplemental XML and all 36 testcase identities\n',
                   '    - name: Run sustained synthetic network recovery state-machine regression\n',
                   '      name: Upload concept UI verification evidence\n', '    - name: Verify APK\n')
        positions = [current.index(marker) for marker in ordered]
        self.assertEqual(positions, sorted(positions), 'Android failures must precede the mandatory soak; evidence and APK transport follow it')
        self.assertIn('    - if: always()\n      name: Upload concept UI verification evidence', current)


if __name__ == '__main__':
    unittest.main()
