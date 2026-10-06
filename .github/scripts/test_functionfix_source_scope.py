#!/usr/bin/env python3
"""Reject version, original-test, payload and unlisted-source boundary regressions."""
import copy
from pathlib import Path
import tempfile
import unittest

import functionfix_source_scope as scope


def fixture_layer(previous):
    plan = scope.test_plan()
    changes = scope.PRODUCTION_FILES | {scope.BUILD_FILE, scope.CONTRACT_FILE} | {item['source'] for item in plan.values()}
    if 'io.github.xgl34222220.hetu.PanelRequestOwnershipTest' in plan:
        changes = changes | {scope.NEW_TEST_HELPER}
    return {
        'schema': 1, 'baseCommit': scope.BASE_COMMIT, 'baseAndroidAppTree': scope.BASE_ANDROID_APP_TREE,
        'versionCode': scope.VERSION_CODE, 'versionName': scope.VERSION_NAME,
        'previousInputsSha256': scope.PREVIOUS_INPUTS_SHA256, 'previousPatchSha256': scope.PREVIOUS_PATCH_SHA256,
        'historicalFilesSha256': scope.HISTORICAL, 'testPlanSha256': scope.digest(scope.TEST_PLAN_PATH),
        'baselineUnitTests': 487, 'baselineTestXmlFiles': 51,
        'newTestCounts': {name: item['tests'] for name, item in plan.items()},
        'newTestFiles': {name: item['source'] for name, item in plan.items()},
        'expectedUnitTests': 487 + sum(item['tests'] for item in plan.values()), 'expectedTestXmlFiles': 51 + len(plan),
        'changedOrAddedFiles': {name: '0' * 64 for name in changes},
        'frozenFiles': {name: sha for name, sha in previous.items() if name not in changes},
        'patchSha256': 'a' * 64, 'runtimePayloadCount': 23, 'rootScriptSha256': '0' * 64,
        'originalRuntimeInputsSha256': scope.RUNTIME_INPUTS_SHA256,
        'autostartScriptSha256': scope.AUTOSTART_SHA256,
        'runtimePayloadChanges': {'assets/hetu-root.sh': {'before': previous[scope.ROOT_SCRIPT], 'after': '0' * 64}},
        'externalPayloadFiles': {name: previous[name] for name in scope.EXTERNAL_PAYLOAD_FILES},
        **{name: True for name in scope.PROTECTED_FLAGS},
    }


class FunctionFixScopeTests(unittest.TestCase):
    def setUp(self):
        self.previous = scope.previous_files()
        self.layer = fixture_layer(self.previous)

    def test_exact_six_layer_baseline_and_additive_plan(self):
        self.assertEqual(379, len(self.previous))
        self.assertEqual(13, len(scope.HISTORICAL))
        scope.validate_layer(self.layer, self.previous)

    def test_existing487_and51_cannot_be_reduced(self):
        for key, value in (('baselineUnitTests', 486), ('baselineTestXmlFiles', 50),
                           ('expectedUnitTests', self.layer['expectedUnitTests'] - 1),
                           ('expectedTestXmlFiles', self.layer['expectedTestXmlFiles'] - 1)):
            wrong = copy.deepcopy(self.layer); wrong[key] = value
            with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)

    def test_manifest_permissions_signing_native_and_old_tests_cannot_enter_scope(self):
        for name in ('android-app/app/src/main/AndroidManifest.xml',
                     'android-app/settings.gradle.kts',
                     scope.TEST_PACKAGE + 'tools/ToolsDnsConsentIntakeTest.kt',
                     scope.PACKAGE + 'home/HomeScreen.kt', *scope.EXTERNAL_PAYLOAD_FILES):
            with self.assertRaises(AssertionError): scope.validate_scope(self.layer['changedOrAddedFiles'].keys() | {name})

    def test_frozen_input_removal_and_external_payload_change_fail(self):
        for name in (scope.AUTOSTART_SCRIPT, *scope.EXTERNAL_PAYLOAD_FILES):
            wrong = copy.deepcopy(self.layer); wrong['frozenFiles'].pop(name)
            with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)
        wrong = copy.deepcopy(self.layer)
        wrong['runtimePayloadChanges']['assets/hetu-route.sh'] = {'before': '0' * 64, 'after': 'f' * 64}
        with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)

    def test_runtime_revision_is_only_one_exact_change(self):
        before = 'prefix\nstatic final int RUNTIME_REVISION = 151;\nlistener.run();\nsuffix'
        after = before.replace('151', '152')
        scope.validate_revision_only(before, after)
        for wrong in (after.replace('listener.run()', 'listener.skip()'), after.replace('152', '153')):
            with self.assertRaises(AssertionError): scope.validate_revision_only(before, wrong)

    def test_original_runtime_contract_retains_every_case_and_assertion(self):
        before = '@Test fun contract() { assertEquals(151, ProxyRuntimeSettings.RUNTIME_REVISION)\nassertFalse(pending(true)) }\n@Test fun other() { assertTrue(healthy) }'
        after = before.replace('assertEquals(151,', 'assertEquals(152,')
        scope.validate_contract_only(before, after)
        for wrong in (after.replace('@Test fun other', 'fun other'),
                      after.replace('assertFalse(pending(true))', 'assertTrue(true)'),
                      after.replace('assertTrue(healthy)', 'assertTrue(true)')):
            with self.assertRaises(AssertionError): scope.validate_contract_only(before, wrong)
        with tempfile.TemporaryDirectory() as directory:
            before_root, after_root = Path(directory) / 'before', Path(directory) / 'after'
            pairs = {
                scope.BUILD_FILE: ('versionCode = 2085\nversionName = "0.12.15-v20-pdf"\n',
                                   'versionCode = 2086\nversionName = "0.12.16-v20-fix"\n'),
                scope.REVISION_FILE: ('static final int RUNTIME_REVISION = 151;\n',
                                      'static final int RUNTIME_REVISION = 152;\n'),
                scope.CONTRACT_FILE: (before, after),
                scope.ROOT_SCRIPT: ('functions\ncase "${1:-status}" in\n status) status;;\nesac',) * 2,
                scope.PACKAGE + 'RootProxyManager.java':
                    ('    private String[] autostartArgs(p) { return p.args; }\n    private void installAutostart(p) {}',) * 2,
            }
            for name, texts in pairs.items():
                for root, text in zip((before_root, after_root), texts):
                    path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(text.encode())
            scope.validate_source_transforms(before_root, after_root)
            (after_root / scope.CONTRACT_FILE).write_bytes(after.replace('\n', '\r\n').encode())
            with self.assertRaises(AssertionError): scope.validate_source_transforms(before_root, after_root)

    def test_gradle_only_two_version_values_change(self):
        before = 'versionCode = 2085\nversionName = "0.12.15-v20-pdf"\nimplementation("dependency:exact:1")\nsigningFixed()'
        after = before.replace('2085', '2086').replace('0.12.15-v20-pdf', '0.12.16-v20-fix')
        scope.validate_version_only(before, after)
        for wrong in (after.replace('exact:1', 'exact:2'), after.replace('signingFixed()', 'newSigning()')):
            with self.assertRaises(AssertionError): scope.validate_version_only(before, wrong)

    def test_root_dispatch_and_autostart_protocol_are_exact(self):
        script = 'old functions\ncase "${1:-status}" in\n start) [ "$#" = 16 ]; start "$2";;\n stop) stop;;\nesac'
        newer = script.replace('old functions', 'corrected functions')
        manager = 'prefix\n    private String[] autostartArgs(Prepared p){ return p.args; }\n\n    private void installAutostart(p) {}\nsuffix'
        scope.validate_root_protocols(script, newer, manager, manager)
        for wrong in (newer.replace('= 16', '= 17'), newer.replace(' stop) stop', ' stop) skip')):
            with self.assertRaises(AssertionError): scope.validate_root_protocols(script, wrong, manager, manager)
        with self.assertRaises(AssertionError):
            scope.validate_root_protocols(script, newer, manager, manager.replace('return p.args;', 'return newArgs;'))

    def test_new_test_count_is_pinned_to_actual_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            for cls, item in scope.test_plan().items():
                path = Path(directory) / item['source']; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('package io.github.xgl34222220.hetu\nclass ' + cls.rsplit('.', 1)[1] + ' {\n' + '@Test fun test() {}\n' * item['tests'] + '}')
            scope.validate_new_tests(directory)
            path = Path(directory) / next(iter(scope.test_plan().values()))['source']
            path.write_text(path.read_text().replace('@Test', '', 1))
            with self.assertRaises(AssertionError): scope.validate_new_tests(directory)

    def test_missing_new_suite_or_guarantee_cannot_lower_gate(self):
        wrong = copy.deepcopy(self.layer); wrong['newTestCounts'].pop(next(iter(wrong['newTestCounts'])))
        with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)
        for flag in scope.PROTECTED_FLAGS:
            wrong = copy.deepcopy(self.layer); wrong[flag] = False
            with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)

    def test_prior_layer_or_native_sha_cannot_drift(self):
        wrong = copy.deepcopy(self.layer); wrong['previousInputsSha256'] = '0' * 64
        with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)
        wrong = copy.deepcopy(self.layer); wrong['externalPayloadFiles'][next(iter(scope.EXTERNAL_PAYLOAD_FILES))] = '0' * 64
        with self.assertRaises(AssertionError): scope.validate_layer(wrong, self.previous)


if __name__ == '__main__': unittest.main()
