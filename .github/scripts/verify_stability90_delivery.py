#!/usr/bin/env python3
"""Audit actual downloaded current artifacts; producer API metadata is a separate proof.

This verifier does not claim phone/Root/radio/Google validation or manufacture a
duration from a configured number. It checks individual samples and source bytes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile

from stability90_source_scope import ROOT, validate_checkout, EXTERNAL_PAYLOAD_FILES, compilation_inputs
from verify_stability90_test_results import verify as verify_tests
from verify_stability90_supplemental import verify as verify_supplemental, expected_methods as supplemental_methods

REQUIRED_GLASS_PREVIEWS = frozenset(
    [f'{mode}-{tab}.png' for mode in ('light', 'dark') for tab in ('home', 'panel', 'tools', 'settings')]
    + ['pure-black-motion-off.png', 'pure-black-motion-off-after-5s.png', 'dialog-ready.png',
       'dialog-busy.png', 'compact-large-text.png', 'home-controller-401.png',
       'home-takeover-unobserved.png', 'home-takeover-degraded.png', 'home-local-checks-ready.png'])
PINNED_CERTIFICATE_SHA256 = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'


def verify_apk_signature_report(signature):
    lines = signature.splitlines()
    certificates = [line for line in lines if re.search(r'certificate\s+SHA-256\b', line, re.IGNORECASE)]
    assert len(certificates) == 1, 'Missing or multiple APK signer certificates'
    certificate = re.fullmatch(r'(Signer #1|V2 Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})', certificates[0])
    assert certificate is not None, 'Malformed APK signer certificate report'
    assert certificate[2].lower() == PINNED_CERTIFICATE_SHA256, 'APK signing certificate differs'
    labels = set()
    for line in lines:
        if re.match(r'^(?:Signer\b|V\d+ Signer\b)', line):
            label = re.match(r'^(Signer #\d+|V\d+ Signer:)(?=\s)', line)
            assert label is not None, 'Malformed APK signer report label'
            labels.add(label[1])
        if line.startswith('Number of signers:'):
            assert line == 'Number of signers: 1', 'APK report does not identify one signer'
    assert labels == {certificate[1]}, 'Multiple or inconsistent APK signer identities'
    return certificate[2].lower()


def verify_glass_previews(images):
    images = list(images)
    assert len(images) == 17 and len({p.name for p in images}) == 17, 'Missing/duplicate current glass preview'
    assert {p.name for p in images} == REQUIRED_GLASS_PREVIEWS, ('Glass preview identities differ', sorted(p.name for p in images))
    assert all(p.read_bytes().startswith(b'\x89PNG\r\n\x1a\n') for p in images), 'Invalid glass preview PNG'


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        while part := f.read(1024 * 1024):
            h.update(part)
    return h.hexdigest()


def locate(folder, suffix):
    matches = [p for p in Path(folder).rglob(Path(suffix).name) if str(p).endswith(suffix)]
    assert len(matches) == 1, ('Missing/ambiguous artifact member', suffix, matches)
    return matches[0]


def audit(args):
    layer = json.loads((ROOT / 'updates/v2090-stability-glass/inputs.json').read_text())
    final = validate_checkout(ROOT, layer)
    assert sha(args.source_archive) == args.source_sha256
    # Every checked-in compilation input is bound to the caller's exact commit;
    # native inputs are separately checked in the source archive and APK.
    for n in final.keys() - EXTERNAL_PAYLOAD_FILES:
        data = subprocess.check_output(['git', 'show', args.commit + ':' + n], cwd=ROOT)
        assert hashlib.sha256(data).hexdigest() == final[n], 'Commit source mismatch: ' + n
    with tempfile.TemporaryDirectory(prefix='hetu-v2090-delivery-source-') as d:
        work = Path(d)
        with tarfile.open(args.source_archive) as z:
            z.extractall(work, filter='data')
        assert compilation_inputs(work) == set(final), 'Effective source archive lacks complete final inputs'
        assert all(sha(work / n) == digest for n, digest in final.items()), 'Effective source archive bytes differ'
    xml = locate(args.ui_dir, 'testDebugUnitTest/' + 'TEST-' + next(iter(layer['finalUnitTestMethods'])) + '.xml').parent
    junit = verify_tests(xml, layer)
    supplemental_expected = supplemental_methods()
    supplemental_xml = locate(args.supplemental_dir, 'test-results/TEST-' + next(iter(supplemental_expected)) + '.xml').parent
    supplemental = verify_supplemental(supplemental_xml, supplemental_expected)
    recorded_supplemental = json.loads(locate(args.supplemental_dir, '36-test-result.json').read_text())
    assert recorded_supplemental == supplemental, 'Supplemental aggregate differs from actual independent XML'
    proof = json.loads(locate(args.ui_dir, 'effective-source-proof.json').read_text())
    assert proof['checkoutMatchesGeneratedSource'] is True
    assert proof['historicPatchesAppliedToCheckout'] is False
    assert proof['stability90Layer']['patchSha256'] == layer['patchSha256']
    assert proof['stability90Layer']['expectedUnitTests'] == layer['expectedUnitTests']
    assert {'homePanelLayer', 'toolsSettingsUiLayer', 'runtime153Layer', 'stability90Layer'} <= proof.keys()
    manifest = json.loads(args.manifest.read_text())
    assert manifest['apkBytes'] == args.apk.stat().st_size and manifest['apkSha256'] == sha(args.apk)
    assert manifest['apkName'] == 'Hetu-' + layer['versionName'] + '.apk'
    metadata = args.manifest.with_name('apk-metadata.txt').read_text()
    signature = args.manifest.with_name('apk-signature.txt').read_text()
    assert "package: name='io.github.xgl34222220.hetu'" in metadata
    assert "versionCode='2090' versionName='0.12.17-v20-glass'" in metadata
    verify_apk_signature_report(signature)
    runtime = json.loads((ROOT / 'UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload']
    payloads = {**runtime, 'assets/hetu-root.sh': layer['rootScriptSha256'],
                'assets/hetu-autostart.sh': layer['autostartScriptSha256']}
    assert len(payloads) == 23
    with zipfile.ZipFile(args.apk) as z:
        assert len(z.namelist()) == len(set(z.namelist()))
        assert all(hashlib.sha256(z.read(n)).hexdigest() == digest for n, digest in payloads.items())
    packaged = json.loads(locate(args.ui_dir, 'packaged-runtime.json').read_text())
    assert packaged['expected'] == payloads and packaged['apkVerified'] is True
    assert packaged['apkSha256'] == sha(args.apk)
    abi = json.loads(locate(args.ui_dir, 'materialkolor-abi.json').read_text())
    assert abi['compatible'] is True and not abi['missingConstructors']
    assert abi['apkSha256'] == sha(args.apk)
    lint = ET.parse(locate(args.ui_dir, 'lint-results-debug.xml')).getroot()
    assert not [x for x in lint if x.get('severity') in ('Error', 'Fatal')]
    images = sorted(args.ui_dir.rglob('glass90/*.png'))
    verify_glass_previews(images)
    apis = {}
    for api, folder in ((35, args.api35_dir), (36, args.api36_dir)):
        navigation = [p for p in folder.rglob('results.json') if 'native-soak90' not in p.parts]
        assert len(navigation) == 1, 'Missing/ambiguous root navigation results'
        report = json.loads(navigation[0].read_text())
        # There are multiple results.json files on API36. Use the root smoke
        # member, never the nested native-soak result as navigation evidence.
        assert report['apiLevel'] == api and report['rootMutationActions'] == 0
        assert report['passed'] == len(report['checks']) >= 64
        assert report['legacyChecksPassed'] >= 59 and report['paletteCasesPassed'] == 21
        assert all(x['result'] == 'passed' for x in report['checks'])
        runner = json.loads(locate(folder, 'runner-lifecycle.json').read_text())
        assert runner['result'] == 'PASS' and runner['kvm_before'] == runner['kvm_after']
        assert runner['launcher_reaped'] is True
        cleanup = json.loads(locate(folder, 'emulator-cleanup.json').read_text())
        assert cleanup['emulator_reaped'] is True
        apis[api] = {'checks': report['passed'], 'paletteChecks': report['paletteCasesPassed'], 'rootMutationActions': 0}
    sys.path.insert(0, str(ROOT / 'tools/qa'))
    from run_mihomo_soak90 import evaluate
    native_path = locate(args.api36_dir, 'native-soak90/results.json')
    native = json.loads(native_path.read_text())
    samples = [json.loads(x) for x in native_path.with_name('samples.jsonl').read_text().splitlines()]
    requests = json.loads(native_path.with_name('fixture-requests.json').read_text())
    evaluated = evaluate(samples, native['requestedSeconds'], native['actualContinuousSeconds'], requests, native['resourceObservations'])
    assert native['result'] == 'PASS' and native['executedSamples'] == len(samples)
    assert native['observedRepositoryCommit'] == args.commit
    assert native['nativePayload']['candidateApkSha256'] == sha(args.apk), 'Native soak did not use this delivered candidate APK'
    assert native['soakScriptSha256'] == sha(ROOT / 'tools/qa/run_mihomo_soak90.py')
    assert native['nativeManifestSha256'] == sha(ROOT / 'UI92_RUNTIME146_INPUTS.json')
    assert all(native[k] == v for k, v in evaluated.items())
    assert not native['cleanup']['errors'] and all(native['cleanup'][k] for k in (
        'ownedGuestCoreStopped', 'adbChildReaped', 'ownedNonceDirectoryRemoved', 'adbForwardRestored', 'adbReverseRestored'))
    app = json.loads(locate(args.api36_dir, 'post-native-application.json').read_text())
    assert app['result'] == 'PASS' and app['before'] == app['after']
    assert app['before']['pid'] > 0 and app['before']['startTicks'] > 0
    assert app['observedRepositoryCommit'] == args.commit and app['apkSha256'] == sha(args.apk)
    assert app['nativeSoakActualSeconds'] == native['actualContinuousSeconds'] >= 900
    assert app['observedWallSeconds'] >= app['nativeSoakActualSeconds']
    assert app['noApplicationFatalOrAnrInRetainedLog'] is True
    assert app['launcherResumedForFinalCapture'] is True and app['applicationUiVisibleInFinalCapture'] is True
    assert locate(args.api36_dir, 'post-native-application.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    post_ui = ET.parse(locate(args.api36_dir, 'post-native-application.xml')).getroot()
    assert any(n.get('package') == 'io.github.xgl34222220.hetu' for n in post_ui.iter('node'))
    postlog = locate(args.api36_dir, 'post-native-application-logcat.txt').read_text()
    assert not re.search(r'FATAL EXCEPTION[\s\S]{0,1000}Process: io\.github\.xgl34222220\.hetu', postlog)
    assert not re.search(r'ANR in io\.github\.xgl34222220\.hetu(?:\s|\(|:)', postlog)
    model_path = locate(args.ui_dir, 'network-model-soak90/report.json')
    model = json.loads(model_path.read_text())
    assert model['javaExit'] == 0 and model['requestedDurationSeconds'] >= 900
    assert model['processWallTimeSeconds'] >= model['requestedDurationSeconds'] and model['result']['failures'] == 0
    for name, digest in model['sourcesSha256'].items():
        if name.startswith('android-app/'):
            assert final[name] == digest, 'Host model did not execute the current production source'
    assert sha(model_path.with_name('samples.jsonl')) == model['samplesSha256']
    assert sha(model_path.with_name('socket-events.jsonl')) == model['socketEventsSha256']
    return {'result': 'PASS', 'commit': args.commit, 'apkSha256': sha(args.apk), 'apkBytes': args.apk.stat().st_size,
            'completeSourceInputs': len(final), 'sourceArchiveSha256': sha(args.source_archive), 'unitTests': junit,
            'independentSupplementalTests': supplemental,
            'newGlassPreviewImages': len(images), 'apis': apis,
            'nativeCoreContinuousSeconds': native['actualContinuousSeconds'], 'nativeCoreSamples': len(samples),
            'applicationProcessDuringNativeSoak': app,
            'nativeCoreEvaluation': evaluated, 'hostModelContinuousSeconds': model['processWallTimeSeconds'],
            'hostModelResult': model['result'],
            'limitations': 'Actual GitHub run/head/job/artifact producer metadata must be bound independently. APK signing identity here is the downloaded build apksigner record, not a local cryptographic re-run. JVM, AOSP native-core loopback traffic and browser fixtures are separate from real phones, Root rules, Wi-Fi/mobile handover, public Google/GMS and long-term background battery policies.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('ui-dir', 'supplemental-dir', 'api35-dir', 'api36-dir', 'apk', 'manifest', 'source-archive', 'output'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--source-sha256', required=True)
    p.add_argument('--commit', required=True)
    args = p.parse_args()
    assert not args.output.exists(), 'Do not overwrite a prior pass/failure delivery record'
    try:
        report = audit(args)
    except Exception as error:
        report = {'result': 'FAIL', 'commit': args.commit, 'error': type(error).__name__ + ': ' + str(error)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        raise
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
