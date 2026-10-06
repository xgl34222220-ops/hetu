#!/usr/bin/env python3
"""Regress the independent authentication boundary without Android tooling."""
import unittest

from auth_source_scope import (AUTH_FILES, BASE_ANDROID_APP_TREE, BASE_COMMIT, BASE_RUN,
                               BUILD_FILE, NEW_TEST_CLASSES, NEW_TEST_COUNTS, PACKAGE,
                               PREVIOUS_INPUTS_SHA256, PREVIOUS_PATCH_SHA256,
                               TEST_PACKAGE, validate_layer, validate_scope,
                               validate_version_only, HISTORY_FIXTURE, validate_history_fixture_only,
                               SAFETY_FIXTURE, validate_safety_diagnostics_only, HISTORY_REPLACEMENTS)
from ui_source_scope import validate_scope as validate_ui_scope


class AuthenticationScope(unittest.TestCase):
    def test_old_action_safety_assertion_can_only_gain_failure_details(self):
        before = 'assertTrue(reason, predicate())'
        after = 'assertTrue("$reason [panelReady=${vm.state.panelReady}, readFailed=${vm.state.controllerReadFailed}, pendingSelection=${vm.pendingSelection.keys}, historyPaused=${PanelActionRuntimeShadows.HistoryRecord.pause}, requestPaths=${requestSnapshot().map { it.path }}]", predicate())'
        validate_scope([SAFETY_FIXTURE])
        validate_safety_diagnostics_only(before, after)
        for modified in (after.replace('predicate()', 'true'), after + '\n// unrelated change'):
            with self.assertRaises(AssertionError):
                validate_safety_diagnostics_only(before, modified)

    def test_history_fixture_can_only_add_cross_thread_visibility_and_audit(self):
        before = '\n'.join(original for original, _ in HISTORY_REPLACEMENTS) + '\n// Root boundary unchanged'
        after = before
        for original, replacement in HISTORY_REPLACEMENTS:
            after = after.replace(original, replacement)
        validate_scope([HISTORY_FIXTURE])
        validate_history_fixture_only(before, after)
        for modified in (after.replace('Root boundary unchanged', 'Root boundary changed'),
                         after.replace('volatile boolean pause', 'volatile boolean skip'),
                         after.replace('samples.add(new kotlin.Pair<>(upload, download))', 'samples.clear()')):
            with self.assertRaises(AssertionError):
                validate_history_fixture_only(before, modified)

    def test_exact_authentication_production_files_are_allowed(self):
        validate_scope(AUTH_FILES | {BUILD_FILE})

    def test_runtime_assets_manager_manifest_and_dependencies_stay_protected(self):
        for name in ['android-app/app/src/main/AndroidManifest.xml',
                     'android-app/app/src/main/assets/hetu-root.sh',
                     'android-app/app/src/main/assets/hetu-autostart.sh',
                     'android-app/app/src/main/jniLibs/x86_64/libhetu_core.so',
                     'android-app/build.gradle.kts', PACKAGE + 'RootProxyManager.java',
                     PACKAGE + 'MihomoStartupConfig.java', PACKAGE + 'ProxyNetworkMatchService.java']:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                validate_scope([name])

    def test_unrelated_presentation_sources_are_not_authorized(self):
        for name in ['app/NewUiIntegration.kt', 'panel/PanelOverviewTab.kt',
                     'app/ConfigScreens.kt', 'tools/ToolsScreen.kt']:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                validate_scope([PACKAGE + name])

    def test_only_the_named_authentication_tests_are_allowed(self):
        validate_scope([TEST_PACKAGE + name + '.kt' for name in NEW_TEST_CLASSES])
        with self.assertRaises(AssertionError):
            validate_scope([TEST_PACKAGE + 'RootHealthUpgradeTest.java'])

    def test_path_traversal_is_rejected(self):
        with self.assertRaises(AssertionError):
            validate_scope([PACKAGE + 'panel/../RootProxyManager.java'])

    def test_only_the_new_version_can_change_in_build_inputs(self):
        before = 'versionCode = 2083\nversionName = "0.12.13-v20-ui"\nstrictly("5.0.1")\n'
        after = 'versionCode = 2084\nversionName = "0.12.14-v20-auth"\nstrictly("5.0.1")\n'
        validate_version_only(before, after)
        for changed in [after.replace('5.0.1', '2.0.0'), after.replace('2084', '2085')]:
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                validate_version_only(before, changed)

    def test_existing_ui_boundary_still_rejects_authentication_runtime(self):
        for name in ['MihomoControllerClient.java', 'LegacyAppMigrator.java', 'ProxyComposeController.kt']:
            with self.subTest(name=name), self.assertRaises(AssertionError):
                validate_ui_scope([PACKAGE + name])

    def test_manifest_requires_fixed_provenance_and_preserves_the_baseline(self):
        layer = {'schema': 1, 'baseCommit': BASE_COMMIT, 'baseRun': BASE_RUN,
                 'baseAndroidAppTree': BASE_ANDROID_APP_TREE,
                 'previousInputsSha256': PREVIOUS_INPUTS_SHA256,
                 'previousPatchSha256': PREVIOUS_PATCH_SHA256,
                 'versionCode': 2084, 'versionName': '0.12.14-v20-auth',
                 'baselineUnitTests': 392, 'expectedUnitTests': 422,
                 'newTestClasses': list(NEW_TEST_CLASSES),
                 'newTestCounts': NEW_TEST_COUNTS,
                 'changedOrAddedFiles': {BUILD_FILE: 'x', PACKAGE + 'MihomoControllerClient.java': 'x',
                                        **{TEST_PACKAGE + name + '.kt': 'x' for name in NEW_TEST_CLASSES}}}
        validate_layer(layer)
        for key, value in [('baseRun', 37238394831), ('previousInputsSha256', 'wrong'),
                           ('expectedUnitTests', 392), ('baselineUnitTests', 348),
                           ('newTestClasses', list(NEW_TEST_CLASSES[:-1]))]:
            with self.subTest(key=key), self.assertRaises(AssertionError):
                validate_layer({**layer, key: value})


if __name__ == '__main__':
    unittest.main(verbosity=2)
