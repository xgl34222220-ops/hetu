#!/usr/bin/env python3
"""Host regression for full-source freezing and immutable additive intake."""
import copy
import json
import shutil
import subprocess
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import tools_intake_source_scope as scope
from generate_tools_intake_source_layer import generate

ROOT = Path(__file__).resolve().parents[2]


def fixture_layer(previous, helper=False):
    changes = scope.REQUIRED_FILES | ({scope.OPTIONAL_HELPER} if helper else set())
    return {
        'schema': 1, 'baseCommit': scope.BASE_COMMIT,
        'baseAndroidAppTree': scope.BASE_ANDROID_APP_TREE,
        'previousInputsSha256': scope.PREVIOUS_INPUTS_SHA256,
        'previousPatchSha256': scope.PREVIOUS_PATCH_SHA256,
        'historicalFilesSha256': dict(scope.HISTORICAL_FILES_SHA256),
        'patchSha256': 'a' * 64,
        'versionCode': scope.VERSION_CODE, 'versionName': scope.VERSION_NAME,
        'baselineUnitTests': scope.BASE_TESTS, 'expectedUnitTests': scope.EXPECTED_TESTS,
        'baselineTestXmlFiles': scope.BASE_TEST_XML_FILES,
        'expectedTestXmlFiles': scope.EXPECTED_TEST_XML_FILES,
        'newTestClasses': list(scope.NEW_TEST_COUNTS), 'newTestCounts': dict(scope.NEW_TEST_COUNTS),
        'externalPayloadFiles': {name: previous[name] for name in scope.EXTERNAL_PAYLOAD_FILES},
        'changedOrAddedFiles': {name: '0' * 64 for name in changes},
        'frozenFiles': {name: sha for name, sha in previous.items() if name not in changes},
        **{name: True for name in scope.PROTECTED_FLAGS},
    }


def write(root, name, data):
    path = Path(root) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data)
    return path


class ToolsIntakeScopeTest(unittest.TestCase):
    def setUp(self):
        self.previous = scope.verified_previous_files(ROOT)
        self.layer = fixture_layer(self.previous)

    def test_all_prior_layers_are_pinned_and_full_map_is_retained(self):
        self.assertEqual(len(self.previous), 376)
        self.assertEqual(len(scope.HISTORICAL_FILES_SHA256), 8)
        self.assertEqual(len(self.layer['frozenFiles']), 374)
        scope.validate_layer(self.layer, self.previous)

    def test_exact_required_sources_and_optional_consent_helper(self):
        scope.validate_layer(self.layer, self.previous)
        scope.validate_layer(fixture_layer(self.previous, helper=True), self.previous)
        for name in scope.REQUIRED_FILES:
            wrong = copy.deepcopy(self.layer)
            del wrong['changedOrAddedFiles'][name]
            with self.assertRaises(AssertionError):
                scope.validate_layer(wrong, self.previous)

    def test_home_panel_runtime_manifest_gradle_and_old_tests_are_frozen(self):
        for suffix in ('home/HomeScreen.kt', 'panel/PanelRoute.kt',
                       'RootProxyManager.java', 'MihomoControllerClient.java'):
            name = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/' + suffix
            with self.assertRaises(AssertionError):
                scope.validate_scope(scope.REQUIRED_FILES | {name})
        for name in ('android-app/app/src/main/assets/hetu-root.sh',
                     'android-app/app/src/main/AndroidManifest.xml',
                     'android-app/app/build.gradle.kts',
                     'android-app/app/src/main/res/xml/network_security_config.xml',
                     'android-app/app/src/test/java/io/github/xgl34222220/hetu/SettingsPickerParity58Test.kt'):
            with self.assertRaises(AssertionError):
                scope.validate_scope(scope.REQUIRED_FILES | {name})

    def test_escape_and_unlisted_helper_are_rejected(self):
        for name in ('/android-app/other.kt', '../android-app/other.kt',
                     scope.PACKAGE + '../ToolsFeatureAdapter.kt',
                     'android-app\\app\\other.kt', scope.PACKAGE + 'ToolsExtra.kt'):
            with self.assertRaises(AssertionError):
                scope.validate_scope(scope.REQUIRED_FILES | {name})

    def test_historic_inputs_patch_commit_and_tree_cannot_change(self):
        for key in ('baseCommit', 'baseAndroidAppTree', 'previousInputsSha256', 'previousPatchSha256'):
            wrong = copy.deepcopy(self.layer)
            wrong[key] = 'f' * 64
            with self.assertRaises(AssertionError):
                scope.validate_layer(wrong, self.previous)
        wrong = copy.deepcopy(self.layer)
        wrong['historicalFilesSha256']['updates/v2085-pdf-tools-settings/runtime.patch'] = 'f' * 64
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)

    def test_no_frozen_input_may_be_omitted_or_modified(self):
        for key in ('android-app/app/build.gradle.kts',
                    'android-app/app/src/main/assets/mihomo-root/arm64-v8a/mihomo',
                    'android-app/app/src/main/java/io/github/xgl34222220/hetu/home/HomeScreen.kt'):
            for remove in (True, False):
                wrong = copy.deepcopy(self.layer)
                if remove:
                    del wrong['frozenFiles'][key]
                else:
                    wrong['frozenFiles'][key] = 'f' * 64
                with self.assertRaises(AssertionError):
                    scope.validate_layer(wrong, self.previous)

    def test_version_and_all_baseline_tests_must_stay_exact(self):
        for key, value in (('versionCode', 2086), ('versionName', '0.12.16'),
                           ('baselineUnitTests', 478), ('expectedUnitTests', 486),
                           ('baselineTestXmlFiles', 48), ('expectedTestXmlFiles', 50)):
            wrong = copy.deepcopy(self.layer)
            wrong[key] = value
            with self.assertRaises(AssertionError):
                scope.validate_layer(wrong, self.previous)

    def test_new_test_counts_and_scope_guarantees_are_required(self):
        wrong = copy.deepcopy(self.layer)
        wrong['newTestCounts']['tools.ToolsDnsConsentIntakeTest'] = 4
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)
        for flag in scope.PROTECTED_FLAGS:
            wrong = copy.deepcopy(self.layer)
            wrong[flag] = False
            with self.assertRaises(AssertionError):
                scope.validate_layer(wrong, self.previous)

    def test_unchanged_adapter_is_not_a_recorded_delta(self):
        wrong = copy.deepcopy(self.layer)
        name = sorted(scope.EXISTING_PRODUCTION_FILES)[0]
        wrong['changedOrAddedFiles'][name] = self.previous[name]
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)

    def test_invalid_hash_or_already_existing_new_helper_is_rejected(self):
        wrong = copy.deepcopy(self.layer)
        wrong['patchSha256'] = 'not-a-hash'
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)
        previous = {**self.previous, scope.OPTIONAL_HELPER: 'f' * 64}
        with self.assertRaises(AssertionError):
            scope.validate_layer(fixture_layer(previous), previous)
        wrong = copy.deepcopy(self.layer)
        payload = sorted(scope.EXTERNAL_PAYLOAD_FILES)[0]
        wrong['externalPayloadFiles'][payload] = 'f' * 64
        with self.assertRaises(AssertionError):
            scope.validate_layer(wrong, self.previous)

    def test_new_source_annotations_are_exactly_five_plus_three(self):
        with tempfile.TemporaryDirectory() as directory:
            for cls, count in scope.NEW_TEST_COUNTS.items():
                short = cls.rsplit('.', 1)[1]
                source = 'package io.github.xgl34222220.hetu.tools\nclass ' + short + ' {\n'
                source += ''.join('@Test fun case%d() {}\n' % index for index in range(count)) + '}\n'
                write(directory, scope.TEST_PACKAGE + short + '.kt', source)
            scope.validate_new_source_files(directory, self.layer)
            path = Path(directory) / (scope.TEST_PACKAGE + 'ToolsDnsConsentIntakeTest.kt')
            path.write_text(path.read_text().replace('@Test fun case0', 'fun case0'))
            with self.assertRaises(AssertionError):
                scope.validate_new_source_files(directory, self.layer)

    def test_optional_helper_only_allows_activity_result_platform_apis(self):
        layer = fixture_layer(self.previous, helper=True)
        with tempfile.TemporaryDirectory() as directory:
            for cls, count in scope.NEW_TEST_COUNTS.items():
                short = cls.rsplit('.', 1)[1]
                write(directory, scope.TEST_PACKAGE + short + '.kt',
                      'package io.github.xgl34222220.hetu.tools\nclass ' + short + ' {\n' + '@Test fun sample() {}\n' * count + '}')
            source = 'package io.github.xgl34222220.hetu.tools\ninternal enum class Consent { DNS, AUTOSTART }\n'
            write(directory, scope.OPTIONAL_HELPER, source)
            scope.validate_new_source_files(directory, layer)
            write(directory, scope.OPTIONAL_HELPER, source + '\n'.join('import ' + name for name in scope.CONSENT_PLATFORM_IMPORTS) + '\n')
            scope.validate_new_source_files(directory, layer)
            for extra in ('import android.net.VpnService\n', 'import androidx.compose.runtime.State\n',
                          'import java.net.Socket\n', 'import java.io.File\n',
                          'val process = Runtime.getRuntime()\n', 'val shell = RootBridge\n'):
                write(directory, scope.OPTIONAL_HELPER, source + extra)
                with self.assertRaises(AssertionError):
                    scope.validate_new_source_files(directory, layer)

    def test_pdf_frozen_checkout_is_checked_against_explicit_final_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work, checkout = root / 'work', root / 'checkout'
            name = scope.PACKAGE + 'ToolsFeatureAdapter.kt'
            protected = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/home/HomeScreen.kt'
            write(work, name, 'explicit intake delta')
            write(checkout, name, 'explicit intake delta')
            write(work, protected, 'frozen home')
            write(checkout, protected, 'frozen home')
            final = {name: scope.digest(work / name), protected: scope.digest(work / protected)}
            scope.validate_checkout_matches_generated(work, checkout, final)
            write(checkout, protected, 'unrecorded home change')
            with self.assertRaises(AssertionError):
                scope.validate_checkout_matches_generated(work, checkout, final)
            write(checkout, protected, 'frozen home')
            write(checkout, name, 'stale pre-intake adapter')
            with self.assertRaises(AssertionError):
                scope.validate_checkout_matches_generated(work, checkout, final)

    def test_unrecorded_or_missing_final_compilation_inputs_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            work, checkout = root / 'work', root / 'checkout'
            name = scope.PACKAGE + 'ToolsFeatureAdapter.kt'
            write(work, name, 'recorded')
            write(checkout, name, 'recorded')
            final = {name: scope.digest(work / name)}
            extra = write(checkout, scope.PACKAGE + 'Extra.kt', 'unrecorded')
            with self.assertRaises(AssertionError):
                scope.validate_checkout_matches_generated(work, checkout, final)
            extra.unlink()
            (checkout / name).unlink()
            with self.assertRaises(FileNotFoundError):
                scope.validate_checkout_matches_generated(work, checkout, final)

    def test_generator_cannot_overwrite_any_historic_layer(self):
        for name in scope.HISTORICAL_FILES_SHA256:
            for directory in ((ROOT / name).parent, (ROOT / name).parent / 'nested'):
                with self.assertRaises(AssertionError):
                    generate(ROOT, directory)

    def test_current_generator_reproduces_available_inputs_and_reports_missing_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            intake_view = ROOT
            if (ROOT / 'updates/v2085-pdf-final-geometry/inputs.json').exists():
                # The intake generator still rejects every non-intake delta.
                # Verify and remove only the exact later presentation layer
                # in a disposable predecessor view, never in the checkout.
                from pdf85_final_source_scope import ALLOWED, BASE_COMMIT, validate_presentation
                intake_view = Path(directory) / 'intake-view'
                intake_view.mkdir()
                subprocess.run(['git', 'init', '-q', str(intake_view)], check=True)
                subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1', str(ROOT), scope.BASE_COMMIT], cwd=intake_view, check=True)
                for name in scope.compilation_inputs(ROOT) | scope.HISTORICAL_FILES_SHA256.keys():
                    target = intake_view / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / name, target)
                for name in ALLOWED:
                    before = subprocess.check_output(['git', 'show', BASE_COMMIT + ':' + name], cwd=ROOT).decode()
                    validate_presentation(before, (ROOT / name).read_text(), name)
                    (intake_view / name).write_text(before)
            report = generate(intake_view, Path(directory) / 'generated')
            missing = set(self.previous) - scope.compilation_inputs(ROOT)
            self.assertEqual(report['locallyMissingUnchangedPayloadFiles'], sorted(missing))
            self.assertEqual(report['completeEffectiveSourceVerified'], not missing)
            self.assertEqual(report['requiredEffectiveInputs'], 379)
            self.assertEqual(report['locallyVerifiedInputs'], 379 - len(missing))
            self.assertEqual(report['changedOrAddedFiles'], 5)
            layer = json.loads((Path(directory) / 'generated/inputs.json').read_text())
            scope.validate_layer(layer, self.previous)
            scope.validate_new_source_files(ROOT, layer)
            self.assertEqual(scope.digest(Path(directory) / 'generated/runtime.patch'), layer['patchSha256'])

    def test_generator_cannot_treat_missing_source_as_external_payload(self):
        missing = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/home/HomeScreen.kt'
        names = scope.compilation_inputs(ROOT) - {missing}
        with tempfile.TemporaryDirectory() as directory:
            with patch('generate_tools_intake_source_layer.compilation_inputs', return_value=names):
                with self.assertRaises(AssertionError):
                    generate(ROOT, directory)

    def test_final_preparation_has_no_early_root_pdf_frozen_check(self):
        source = (ROOT / '.github/scripts/prepare_new_ui_source.py').read_text()
        self.assertIn("assert digest(work/name)==sha, 'Frozen PDF baseline changed: '", source)
        self.assertNotIn('digest(work/name)==sha and digest(ROOT/name)==sha', source)
        self.assertIn('validate_checkout_matches_generated(work, ROOT, final_files)', source)
        self.assertLess(source.index('final_files.update(intake[\'changedOrAddedFiles\'])'),
                        source.index('validate_checkout_matches_generated(work, ROOT, final_files)'))


if __name__ == '__main__':
    unittest.main()
