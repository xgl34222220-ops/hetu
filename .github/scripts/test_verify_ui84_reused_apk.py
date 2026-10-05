"""Host regressions for fixed APK evidence; no SDK, emulator or GitHub mutation."""
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location('reused84', Path(__file__).with_name('verify_ui84_reused_apk.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def source_evidence():
    run = {'id': guard.BUILD_RUN, 'run_attempt': 1, 'head_sha': guard.APP_COMMIT,
           'head_branch': guard.BRANCH, 'status': 'completed', 'conclusion': 'failure',
           'repository': {'full_name': guard.REPOSITORY}}
    jobs = {'jobs': [
        {'id': guard.BUILD_JOB, 'name': 'build', 'status': 'completed', 'conclusion': 'success'},
        {'id': guard.HISTORICAL_JOB, 'name': 'Reproduce V20.74 custom palette crash on API 36 (36)', 'status': 'completed', 'conclusion': 'success'},
        *[{'id': job, 'status': 'completed', 'conclusion': 'failure'} for job in guard.FAILED_INSTALL_JOBS],
    ]}
    return run, jobs


def artifact(kind='apk'):
    artifact_id, name, digest = guard.ARTIFACTS[kind]
    return {'id': artifact_id, 'name': name, 'digest': 'sha256:' + digest, 'expired': False,
            'workflow_run': {'id': guard.BUILD_RUN, 'head_sha': guard.APP_COMMIT, 'head_branch': guard.BRANCH}}


def xml_archive(counts=None, skipped_case=None, duplicate_suite=None):
    counts = counts or {**guard.BASE_SUITES, **guard.AUTH_SUITES}
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as z:
        for name, total in counts.items():
            actual_name = duplicate_suite[1] if duplicate_suite and name == duplicate_suite[0] else name
            suite = ET.Element('testsuite', name='io.github.xgl34222220.hetu.' + actual_name,
                               tests=str(total), failures='0', errors='0', skipped='0')
            for i in range(total):
                case = ET.SubElement(suite, 'testcase', name=f'case{i}')
                if name == skipped_case and i == 0:
                    ET.SubElement(case, 'skipped')
            z.writestr(f'android-app/app/build/test-results/testDebugUnitTest/TEST-{name}.xml', ET.tostring(suite))
    data.seek(0)
    return zipfile.ZipFile(data)


def source_proof():
    return {
        'checkoutMatchesGeneratedSource': True, 'historicPatchesAppliedToCheckout': False,
        'baseCommit': '37e963d488a38900263e33cb8b2047f487bd6dcf', 'baseRun': 37171744564,
        'archiveSha256': '6190b4fd4fcd011cae77e81cdd33b5270e557194c56bf88d0a824d74d811b730',
        'integrationPatchSha256': guard.LAYERS['updates/v2082-new-ui/integration.patch'],
        'presentationLayer': {
            'baseCommit': 'aa599a53856fb26b4d256ba345a496b2a178b3b8', 'baseRun': 37211142058,
            'patchSha256': guard.UI_PATCH_SHA, 'changedOrAddedFiles': 68, 'protectedRuntimeUnchanged': True,
        },
        'controllerAuthenticationLayer': {
            'baseCommit': 'a93a756d8578cf3afa60ee090405535ad097e49e', 'baseRun': 37240041766,
            'baseAndroidAppTree': '2c698fb681f647e4c3a1d0050bf806a9f4cc5de2',
            'patchSha256': guard.AUTH_PATCH_SHA, 'changedOrAddedFiles': 17,
            'runtimePayloadsUnchanged': True, 'dependenciesUnchanged': True,
            'manifestAndPermissionsUnchanged': True, 'unchangedOutsideAuthScope': True,
            'baselineUnitTests': 392, 'expectedUnitTests': 422, 'newTestCounts': guard.AUTH_SUITES,
        },
    }


def installed_evidence():
    checks = [{'name': f'legacy-{i}', 'result': 'passed'} for i in range(59)]
    checks += [{'name': name, 'result': 'passed'} for name in guard.AUTH_CHECKS]
    result = {'apiLevel': 35, 'checks': checks, 'passed': 64, 'legacyChecksPassed': 59,
              'paletteCasesPassed': 21, 'newPanelAuthChecks': checks[59:],
              'fixtureOnly': True, 'rootMutationActions': 0}
    fixture = {'result': 'PASS', 'newChecks': checks[59:], 'fixtureOnly': True,
               'syntheticCachedRuntimeHint': True, 'rootHealthEvidence': False,
               'rootMutationActions': 0, 'actualBearerAuthorizationVerified': True,
               'originalPreferencesRestored': True, 'adbReverseRestored': True,
               'target': {'apiLevel': 35, 'serial': 'emulator-5554', 'avd': 'hetu-smoke', 'nonRootShell': True}}
    return {'results.json': result, 'panel-controller-auth-fixture.json': fixture,
            'runner-lifecycle.json': {'result': 'PASS', 'launcher_reaped': True, 'kvm_before': {'mode': 432}, 'kvm_after': {'mode': 432}},
            'emulator-cleanup.json': {'emulator_reaped': True}}


class FixedApkEvidence(unittest.TestCase):
    def test_current_test_head_is_distinct_from_compiled_head(self):
        head = 'a' * 40
        guard.verify_checkout(head, guard.APP_TREE, {'.github/scripts/smoke_hetu_apk.py'}, head)
        with self.assertRaisesRegex(AssertionError, 'requested SHA'):
            guard.verify_checkout(head, guard.APP_TREE, set(), guard.APP_COMMIT)

    def test_android_tree_change_requires_new_build_even_when_paths_are_empty(self):
        with self.assertRaisesRegex(AssertionError, 'compilation inputs'):
            guard.verify_checkout('new', 'different-tree', set(), 'new')

    def test_whole_repo_scope_refuses_root_dependencies_and_source_layers(self):
        for path in ('android-app/build.gradle.kts', 'android-app/app/src/main/assets/hetu-root.sh',
                     'updates/v2084-controller-auth/runtime.patch', '.github/scripts/auth_source_scope.py'):
            with self.subTest(path=path), self.assertRaisesRegex(AssertionError, 'New full build'):
                guard.verify_checkout('new', guard.APP_TREE, {path}, 'new')

    def test_original_failed_run_and_installation_jobs_are_preserved(self):
        run, jobs = source_evidence()
        guard.verify_source_run(run, jobs)
        for changed in ('run', 'job'):
            r, j = copy.deepcopy((run, jobs))
            if changed == 'run': r['conclusion'] = 'success'
            else: j['jobs'][2]['conclusion'] = 'success'
            with self.subTest(changed=changed), self.assertRaises(AssertionError):
                guard.verify_source_run(r, j)

    def test_failed_build_and_missing_historical_regression_cannot_be_reused(self):
        for index in (0, 1):
            run, jobs = source_evidence(); jobs['jobs'][index]['conclusion'] = 'failure'
            with self.subTest(index=index), self.assertRaises(AssertionError):
                guard.verify_source_run(run, jobs)

    def test_wrong_run_attempt_or_commit_is_rejected(self):
        for field, value in (('run_attempt', 2), ('head_sha', 'uncompiled'), ('head_branch', 'main'), ('id', 37240041766)):
            run, jobs = source_evidence(); run[field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                guard.verify_source_run(run, jobs)

    def test_artifact_id_digest_name_expiry_and_source_sha_are_fixed(self):
        for kind in guard.ARTIFACTS:
            good = artifact(kind); guard.verify_artifact_metadata(good, kind)
            for field, value in (('id', 1), ('digest', 'sha256:wrong'), ('name', 'other'), ('expired', True)):
                bad = copy.deepcopy(good); bad[field] = value
                with self.subTest(kind=kind, field=field), self.assertRaises(AssertionError):
                    guard.verify_artifact_metadata(bad, kind)
            bad = copy.deepcopy(good); bad['workflow_run']['head_sha'] = 'other'
            with self.subTest(kind=kind, field='head_sha'), self.assertRaises(AssertionError):
                guard.verify_artifact_metadata(bad, kind)

    def test_actual_zip_bytes_must_match_the_immutable_archive(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'changed.zip'; path.write_bytes(b'Changed artifact with authentic-looking metadata')
            with self.assertRaisesRegex(AssertionError, 'ZIP differs'):
                guard.verify_archive(path, 'apk')

    def test_apk_identity_requires_both_exact_size_and_digest(self):
        guard.verify_apk_identity(134727773, guard.APK_SHA)
        for size, digest in ((134727772, guard.APK_SHA), (134727773, 'wrong')):
            with self.subTest(size=size, digest=digest), self.assertRaisesRegex(AssertionError, 'APK bytes'):
                guard.verify_apk_identity(size, digest)

    def test_exact_baseline_and_new_suites_are_separate(self):
        with xml_archive() as z:
            report = guard.verify_xml(z)
        self.assertEqual((42, 392, 5, 30, 47, 422),
                         (report['baselineXmlFiles'], report['baseline']['tests'], len(report['authenticationSuites']),
                          report['authentication']['tests'], report['xmlFiles'], report['totalTests']))

    def test_same_422_total_cannot_mask_missing_new_authentication_assertions(self):
        counts = {**guard.BASE_SUITES, **guard.AUTH_SUITES}
        counts['ControllerAuthenticationTest'] -= 1; counts['LegacyControllerCredentialTest'] += 1
        self.assertEqual(422, sum(counts.values()))
        with xml_archive(counts) as z, self.assertRaises(AssertionError):
            guard.verify_xml(z)

    def test_same_baseline_total_cannot_mask_removing_original_safety_tests(self):
        counts = {**guard.BASE_SUITES, **guard.AUTH_SUITES}
        counts['PanelActionSafetyTest'] -= 1; counts['NewUiIntegrationTest'] += 1
        self.assertEqual(422, sum(counts.values()))
        with xml_archive(counts) as z, self.assertRaises(AssertionError):
            guard.verify_xml(z)

    def test_hidden_skipped_testcase_and_duplicate_suite_are_rejected(self):
        with xml_archive(skipped_case='ToastFeedbackTest') as z, self.assertRaises(AssertionError):
            guard.verify_xml(z)
        with xml_archive(duplicate_suite=('LegacyControllerCredentialTest', 'ControllerAuthenticationTest')) as z, self.assertRaises(AssertionError):
            guard.verify_xml(z)

    def test_auth_layer_and_protected_boundaries_cannot_change(self):
        good = source_proof(); guard.verify_source_proof(good)
        for field, value in (('patchSha256', 'other'), ('changedOrAddedFiles', 18),
                             ('runtimePayloadsUnchanged', False), ('dependenciesUnchanged', False),
                             ('manifestAndPermissionsUnchanged', False), ('unchangedOutsideAuthScope', False)):
            bad = copy.deepcopy(good); bad['controllerAuthenticationLayer'][field] = value
            with self.subTest(field=field), self.assertRaises(AssertionError):
                guard.verify_source_proof(bad)

    def test_complete_current_installation_evidence_is_required(self):
        good = installed_evidence()
        bad_cases = [
            ('results.json', 'legacyChecksPassed', 58), ('results.json', 'paletteCasesPassed', 20),
            ('results.json', 'rootMutationActions', 1), ('results.json', 'apiLevel', 36),
            ('panel-controller-auth-fixture.json', 'actualBearerAuthorizationVerified', False),
            ('panel-controller-auth-fixture.json', 'rootHealthEvidence', True),
            ('panel-controller-auth-fixture.json', 'originalPreferencesRestored', False),
            ('panel-controller-auth-fixture.json', 'adbReverseRestored', False),
            ('runner-lifecycle.json', 'result', 'FAIL'), ('emulator-cleanup.json', 'emulator_reaped', False),
        ]
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'GITHUB_SHA': 'testhead', 'GITHUB_RUN_ID': '1'}), patch('builtins.print'):
            out = Path(temp)
            def write(evidence):
                for name, data in evidence.items(): (out / name).write_text(json.dumps(data))
            write(good); guard.verify_installation_results(out, 35)
            for name, key, value in bad_cases:
                bad = copy.deepcopy(good); bad[name][key] = value; write(bad)
                with self.subTest(name=name, key=key), self.assertRaises(AssertionError):
                    guard.verify_installation_results(out, 35)

    def test_missing_current_results_cannot_reuse_failed_run_screenshots(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp); (out / 'panel-controller-401-first-read.png').write_bytes(b'old image')
            with self.assertRaises(FileNotFoundError):
                guard.verify_installation_results(out, 35)


if __name__ == '__main__':
    unittest.main()
