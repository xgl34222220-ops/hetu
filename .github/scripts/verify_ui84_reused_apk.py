"""Verify immutable V20.84 build evidence and the separate current installation run."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

APP_COMMIT = '121e0ab99904e7ea862b1dcb350a59169aeab0cd'
APP_TREE = '2954ad5c83131050acd3ffd52eb63d8689a8de33'
BUILD_RUN = 37270231154
BUILD_JOB = 111635473569
HISTORICAL_JOB = 111635473860
FAILED_INSTALL_JOBS = (111638870864, 111638870907)
REPOSITORY = 'xgl34222220-ops/hetu'
BRANCH = 'test/v20.76-new-ui'
APK_NAME = 'Hetu-0.12.14-v20-auth.apk'
APK_BYTES = 134727773
APK_SHA = '33d60ce01b63f40c6b6ccd8c63c5dd1ea38b85d4d25e37c32f48130c4207ff50'
CERT_SHA = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
AUTH_PATCH_SHA = '327983504205ff1ec26a3c6804a83ae08802a7d4f723de280a92af708e3ea4ee'
UI_PATCH_SHA = 'a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514'
ARTIFACTS = {
    'apk': (11328133959, 'Hetu-APK', 'afd9c02b6367b8ada228e40561acf11baecb24e5ceaf0c69a7736861769aa9ec'),
    'verification': (11328238664, 'Hetu-V20.84-UI-verification', 'cd6c3c8c27f431b940d2edd3b3350c1224faf4fec1f2544b303df7b1ad0f0b2c'),
    'manifest': (11328133960, 'Hetu-V20.84-APK-manifest', '2a572cf7361723c1c1817f0bf3cbf8e97ed78312829d2c7654a1bca2c97e99e8'),
}
# Every permitted path is test infrastructure or its explicit failure record.
# Changes to any application/test/build input or source layer require a full build.
ALLOWED = frozenset({
    '.github/scripts/run_hetu_emulator.py',
    '.github/scripts/smoke_hetu_apk.py',
    '.github/scripts/test_hetu_emulator_runner.py',
    '.github/scripts/test_panel_auth_smoke.py',
    '.github/scripts/verify_ui84_reused_apk.py',
    '.github/scripts/test_verify_ui84_reused_apk.py',
    '.github/workflows/ui-v84-install-validation.yml',
    'AGENTS.md',
    'docs/V20.84_CONTROLLER_AUTH.md',
    'docs/ci/V20.84_FOURTH_FAILURE.json',
    'docs/ci/V20.84_FIRST_INSTALLATION_FAILURE.json',
})
LAYERS = {
    'updates/v2082-new-ui/inputs.json': '2ed0a46bd92aff2567ce6d8a31a2460da918e12837ba82eb38abaa85f8c9511c',
    'updates/v2082-new-ui/integration.patch': '3f08f86d8413c9a905de1eae0c5347e8f7c5dd4032ee4a8e6181d439abda7002',
    'updates/v2083-ui-polish/inputs.json': 'd5e3c4811f3b3ff579a6b12abb6543c4b4a0c8631fa29f461c711ca198a4b081',
    'updates/v2083-ui-polish/ui.patch': UI_PATCH_SHA,
    'updates/v2084-controller-auth/inputs.json': '9ac175a21429d547c7f69e8765d719e4eb391cadd63278d5e58a6c2d6568c911',
    'updates/v2084-controller-auth/runtime.patch': AUTH_PATCH_SHA,
}
BASE_SUITES = {
    'AdblockSnifferTest': 4, 'AppCrashReportTest': 3,
    'AutomationConceptParity57Test': 4, 'AutomationSettingsConceptParityTest': 6,
    'AutomationToolsConceptParityTest': 6, 'BootStartup60Test': 6,
    'ConceptParity57Test': 6, 'ConceptRemainingCoverageTest': 26,
    'ConceptStateCoverageTest': 25, 'CoreManagerConcept57Test': 4,
    'CustomPaletteCompatibilityTest': 5, 'DocumentPickerSafetyTest': 4,
    'JournalReliabilityTest': 21, 'LargeTitleParity59Test': 1,
    'MainLanguageBindingTest': 4, 'NativeConceptScreenshotTest': 30,
    'NetworkDiagnosticsTest': 11, 'NetworkRecoveryStressTest': 13,
    'NewHomePanelInteractionTest': 9, 'NewUiIntegrationTest': 6,
    'NewUiShell83Test': 4, 'PanelActionSafetyTest': 30,
    'PanelConceptModelTest': 6, 'PanelLayoutDefaultsTest': 2,
    'PanelModel11Test': 12, 'ProxyAdblockExportTest': 8,
    'ProxyLatencyRegressionTest': 16, 'ProxyNetworkJournalTest': 11,
    'ProxyScriptSafetyTest': 11, 'RootAutostartTest': 5,
    'RootHealthUpgradeTest': 7, 'RootNetworkRecoveryTest': 13,
    'Runtime146ContractTest': 6, 'RuntimeStartup15Test': 13,
    'RuntimeYaml15Test': 14, 'SettingsBackupParity57Test': 3,
    'SettingsConceptParityTest': 8, 'SettingsPickerParity58Test': 2,
    'ToolsConceptParityTest': 3, 'UI83EditorHistoryTest': 12,
    'UI83ToolsTest': 8, 'WanDetails61Test': 4,
}
AUTH_SUITES = {
    'ToastFeedbackTest': 6, 'ControllerAuthenticationTest': 11,
    'LegacyControllerCredentialTest': 3, 'ControllerReadStateTest': 6,
    'PanelControllerErrorTest': 4,
}
AUTH_CHECKS = [
    'panel-controller-401-first-read', 'panel-controller-401-api-settings',
    'panel-controller-401-retry', 'panel-controller-200-recovered',
    'panel-controller-fixture-cleanup',
]


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_checkout(head, tree, changes, requested_sha):
    assert head == requested_sha, 'Installation checkout differs from the requested SHA'
    assert tree == APP_TREE, 'Android source, tests or compilation inputs changed'
    assert set(changes) <= ALLOWED, ('New full build required', sorted(set(changes) - ALLOWED))


def verify_source_run(run, jobs):
    assert run['id'] == BUILD_RUN and run['run_attempt'] == 1
    assert run['head_sha'] == APP_COMMIT and run['head_branch'] == BRANCH
    assert run['status'] == 'completed' and run['conclusion'] == 'failure', 'Preserve the original full-run failure'
    assert run['repository']['full_name'] == REPOSITORY
    by_id = {j['id']: j for j in jobs['jobs']}
    assert len(by_id) == len(jobs['jobs']), 'Duplicate source job identity'
    for job_id in (BUILD_JOB, HISTORICAL_JOB):
        assert by_id[job_id]['status'] == 'completed' and by_id[job_id]['conclusion'] == 'success'
    assert by_id[BUILD_JOB]['name'] == 'build'
    assert by_id[HISTORICAL_JOB]['name'] == 'Reproduce V20.74 custom palette crash on API 36 (36)'
    for job_id in FAILED_INSTALL_JOBS:
        assert by_id[job_id]['status'] == 'completed' and by_id[job_id]['conclusion'] == 'failure', 'Original installation failures must remain failures'


def verify_artifact_metadata(meta, kind):
    artifact_id, name, digest = ARTIFACTS[kind]
    assert meta['id'] == artifact_id and meta['name'] == name and not meta['expired']
    assert meta['workflow_run']['id'] == BUILD_RUN
    assert meta['workflow_run']['head_sha'] == APP_COMMIT
    assert meta['workflow_run']['head_branch'] == BRANCH
    assert meta['digest'] == 'sha256:' + digest, 'Immutable artifact digest changed'


def verify_archive(path, kind):
    assert sha(path) == ARTIFACTS[kind][2], kind + ' ZIP differs from the fixed artifact'


def verify_apk_identity(size, digest):
    assert size == APK_BYTES and digest == APK_SHA, 'Fixed APK bytes differ'


def verify_manifest(archive):
    assert unique_names(archive) == ['manifest.json', 'apk-metadata.txt', 'apk-signature.txt'], 'Unexpected APK manifest ZIP layout'
    manifest = read_json(archive, 'manifest.json')
    assert manifest['schema'] == 1 and manifest['apkName'] == APK_NAME
    verify_apk_identity(manifest['apkBytes'], manifest['apkSha256'])
    assert manifest['partCount'] == len(manifest['parts']) == 6
    assert sum(part['bytes'] for part in manifest['parts']) == APK_BYTES
    metadata = archive.read('apk-metadata.txt').decode()
    signature = archive.read('apk-signature.txt').decode()
    assert "package: name='io.github.xgl34222220.hetu' versionCode='2084' versionName='0.12.14-v20-auth'" in metadata
    assert 'certificate sha-256 digest: ' + CERT_SHA in signature.lower()
    # These immutable text records supplement the fresh SDK checks below.
    # They never replace actually running aapt and apksigner against the APK.
    return manifest


def unique_names(archive):
    names = archive.namelist()
    assert len(names) == len(set(names)), 'Duplicate ZIP member names'
    return names


def read_json(archive, name):
    return json.loads(archive.read(name))


def verify_xml(archive):
    files = [n for n in unique_names(archive)
             if '/test-results/testDebugUnitTest/TEST-' in n and n.endswith('.xml')]
    expected = {**BASE_SUITES, **AUTH_SUITES}
    seen = {}
    for name in files:
        suite = ET.fromstring(archive.read(name))
        cls = suite.get('name', '').removeprefix('io.github.xgl34222220.hetu.')
        assert cls in expected and cls not in seen, ('Unknown or duplicate XML suite', cls)
        counts = {k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts == {'tests': expected[cls], 'failures': 0, 'errors': 0, 'skipped': 0}, (cls, counts)
        cases = suite.findall('testcase')
        assert len(cases) == expected[cls], ('XML testcase count differs', cls)
        assert not [c for c in cases if c.find('failure') is not None or c.find('error') is not None or c.find('skipped') is not None], cls
        seen[cls] = counts
    assert set(seen) == set(expected) and len(files) == 47, 'Missing baseline or authentication XML suite'
    return {
        'baseline': {'tests': sum(BASE_SUITES.values()), 'failures': 0, 'errors': 0, 'skipped': 0},
        'baselineXmlFiles': len(BASE_SUITES),
        'authentication': {'tests': sum(AUTH_SUITES.values()), 'failures': 0, 'errors': 0, 'skipped': 0},
        'authenticationSuites': {k: seen[k] for k in AUTH_SUITES},
        'totalTests': 422, 'xmlFiles': 47,
    }


def verify_source_proof(proof):
    assert proof['checkoutMatchesGeneratedSource'] is True
    assert proof['historicPatchesAppliedToCheckout'] is False
    assert proof['baseCommit'] == '37e963d488a38900263e33cb8b2047f487bd6dcf'
    assert proof['baseRun'] == 37171744564
    assert proof['archiveSha256'] == '6190b4fd4fcd011cae77e81cdd33b5270e557194c56bf88d0a824d74d811b730'
    assert proof['integrationPatchSha256'] == LAYERS['updates/v2082-new-ui/integration.patch']
    ui = proof['presentationLayer']
    assert ui['baseCommit'] == 'aa599a53856fb26b4d256ba345a496b2a178b3b8' and ui['baseRun'] == 37211142058
    assert ui['patchSha256'] == UI_PATCH_SHA and ui['changedOrAddedFiles'] == 68
    assert ui['protectedRuntimeUnchanged'] is True
    auth = proof['controllerAuthenticationLayer']
    assert auth['baseCommit'] == 'a93a756d8578cf3afa60ee090405535ad097e49e' and auth['baseRun'] == 37240041766
    assert auth['baseAndroidAppTree'] == '2c698fb681f647e4c3a1d0050bf806a9f4cc5de2'
    assert auth['patchSha256'] == AUTH_PATCH_SHA and auth['changedOrAddedFiles'] == 17
    assert all(auth[k] is True for k in ('runtimePayloadsUnchanged', 'dependenciesUnchanged',
                                       'manifestAndPermissionsUnchanged', 'unchangedOutsideAuthScope'))
    assert auth['baselineUnitTests'] == 392 and auth['expectedUnitTests'] == 422
    assert auth['newTestCounts'] == AUTH_SUITES


def verify_verification(archive):
    counts = verify_xml(archive)
    assert read_json(archive, 'out/verification/controller-auth-tests.json') == counts
    lint = ET.fromstring(archive.read('android-app/app/build/reports/lint-results-debug.xml'))
    assert not [i for i in lint.findall('issue') if i.get('severity') in ('Error', 'Fatal')]
    verify_source_proof(read_json(archive, 'out/verification/effective-source-proof.json'))
    runtime = read_json(archive, 'out/verification/packaged-runtime.json')
    abi = read_json(archive, 'out/verification/materialkolor-abi.json')
    assert runtime['apkVerified'] is True and runtime['apkSha256'] == abi['apkSha256'] == APK_SHA
    assert len(runtime['expected']) == 23, 'Runtime payload count changed'
    assert abi['compatible'] is True and abi['constructorReferences'] == 687 and abi['missingConstructors'] == []
    return counts, runtime


def verify_installation_results(out, expected_api):
    results = json.loads((out / 'results.json').read_text())
    checks = results['checks']
    assert results['apiLevel'] == expected_api and expected_api in (35, 36)
    assert results['passed'] == len(checks) == 64 and results['legacyChecksPassed'] == 59
    assert results['paletteCasesPassed'] == 21
    assert all(c['result'] == 'passed' for c in checks)
    assert len({c['name'] for c in checks}) == 64, 'Duplicate installed check name'
    assert [c['name'] for c in checks[59:]] == AUTH_CHECKS
    assert results['newPanelAuthChecks'] == checks[59:]
    assert results['fixtureOnly'] is True and results['rootMutationActions'] == 0
    fixture = json.loads((out / 'panel-controller-auth-fixture.json').read_text())
    assert fixture['result'] == 'PASS' and fixture['newChecks'] == checks[59:]
    assert fixture['fixtureOnly'] is True and fixture['syntheticCachedRuntimeHint'] is True
    assert fixture['rootHealthEvidence'] is False and fixture['rootMutationActions'] == 0
    assert fixture['actualBearerAuthorizationVerified'] is True
    assert fixture['originalPreferencesRestored'] is True and fixture['adbReverseRestored'] is True
    assert fixture['target']['apiLevel'] == expected_api and fixture['target']['serial'] == 'emulator-5554'
    assert fixture['target']['avd'] == 'hetu-smoke' and fixture['target']['nonRootShell'] is True
    lifecycle = json.loads((out / 'runner-lifecycle.json').read_text())
    cleanup = json.loads((out / 'emulator-cleanup.json').read_text())
    assert lifecycle['result'] == 'PASS' and lifecycle['launcher_reaped'] is True
    assert lifecycle['kvm_before'] == lifecycle['kvm_after'] and cleanup['emulator_reaped'] is True
    report = {'installationTestCommit': os.environ['GITHUB_SHA'],
              'installationTestRun': int(os.environ['GITHUB_RUN_ID']),
              'applicationCommit': APP_COMMIT, 'buildRun': BUILD_RUN,
              'sourceRunConclusion': 'failure', 'apiLevel': expected_api,
              'checksPassed': 64, 'legacyChecksPassed': 59, 'newPanelAuthChecksPassed': 5,
              'paletteCasesPassed': 21, 'rootMutationActions': 0,
              'boundary': 'This separate run executes all current installed checks against the immutable compiled APK; the original full run remains failed.'}
    (out / 'installation-result-proof.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--installed-results', action='store_true')
    parser.add_argument('--api', type=int, required=True, choices=(35, 36))
    args = parser.parse_args()
    out = Path('out/android-smoke')
    out.mkdir(parents=True, exist_ok=True)
    if args.installed_results:
        verify_installation_results(out, args.api)
        return
    head = command('git', 'rev-parse', 'HEAD')
    tree = command('git', 'rev-parse', 'HEAD:android-app')
    changes = set(command('git', 'diff', '--name-only', APP_COMMIT, head).splitlines())
    verify_checkout(head, tree, changes, os.environ['GITHUB_SHA'])
    subprocess.run(['git', 'merge-base', '--is-ancestor', APP_COMMIT, head], check=True)
    assert not command('git', 'status', '--porcelain', '--untracked-files=no'), 'Tracked checkout was modified'
    assert os.environ['GITHUB_REPOSITORY'] == REPOSITORY
    for name, digest in LAYERS.items():
        assert sha(Path(name)) == digest, 'Source layer changed: ' + name
        original = subprocess.check_output(['git', 'show', APP_COMMIT + ':' + name])
        assert Path(name).read_bytes() == original, 'Source layer no longer matches the compiled commit: ' + name
    source_run = json.loads((out / 'source-run.json').read_text())
    jobs = json.loads((out / 'source-jobs.json').read_text())
    verify_source_run(source_run, jobs)
    for kind in ARTIFACTS:
        verify_artifact_metadata(json.loads((out / f'source-{kind}-artifact.json').read_text()), kind)
        verify_archive(Path(f'candidate-{kind}.zip'), kind)
    with zipfile.ZipFile('candidate-apk.zip') as archive:
        assert unique_names(archive) == [APK_NAME], 'Unexpected APK ZIP layout'
        apk = Path('candidate') / APK_NAME
        apk.parent.mkdir(exist_ok=True)
        apk.write_bytes(archive.read(APK_NAME))
    verify_apk_identity(apk.stat().st_size, sha(apk))
    with zipfile.ZipFile('candidate-manifest.zip') as archive:
        verify_manifest(archive)
    with zipfile.ZipFile('candidate-verification.zip') as archive:
        counts, runtime = verify_verification(archive)
    with zipfile.ZipFile(apk) as archive:
        unique_names(archive)
        for name, digest in runtime['expected'].items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == digest, 'Packaged runtime changed: ' + name
    signers = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))
    assert signers, 'Android APK signature verifier is missing'
    signer = signers[-1]
    signature = command(str(signer), 'verify', '--print-certs', str(apk))
    assert CERT_SHA in signature.lower(), 'Fixed certificate differs'
    metadata = command(str(signer.parent / 'aapt'), 'dump', 'badging', str(apk))
    assert "name='io.github.xgl34222220.hetu' versionCode='2084' versionName='0.12.14-v20-auth'" in metadata
    (out / 'reused-apk-signature.txt').write_text(signature + '\n')
    (out / 'reused-apk-metadata.txt').write_text(metadata + '\n')
    report = {'applicationCommit': APP_COMMIT, 'installationTestCommit': head,
              'installationTestRun': int(os.environ['GITHUB_RUN_ID']),
              'installationTestAttempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
              'apiLevel': args.api, 'androidAppTree': tree, 'buildRun': BUILD_RUN,
              'buildAttempt': 1, 'buildJob': BUILD_JOB, 'historicalCrashJob': HISTORICAL_JOB,
              'sourceRunConclusion': source_run['conclusion'],
              'originalFailedInstallationJobs': list(FAILED_INSTALL_JOBS),
              'apkArtifact': ARTIFACTS['apk'][0], 'verificationArtifact': ARTIFACTS['verification'][0],
              'manifestArtifact': ARTIFACTS['manifest'][0], 'apkSha256': APK_SHA,
              'apkBytes': APK_BYTES, 'certificateSha256': CERT_SHA,
              'sourceCompilationUnchanged': True, 'sourceLayerSha256': LAYERS,
              'reusedUnitTests': counts, 'runtimePayloadsVerified': 23,
              'abiConstructors': 687, 'changedTestInfrastructure': sorted(changes),
              'boundary': 'Build and 422-test evidence belong only to the fixed application commit. This continuation executes current installed tests without recompilation. The original full run remains failed.'}
    (out / 'reused-build-proof.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
