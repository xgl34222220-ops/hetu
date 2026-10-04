"""Bind a test-only installation continuation to the exact validated V20.83 APK."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import xml.etree.ElementTree as ET
import zipfile

APP_COMMIT = '7d082aca02345efb75a5d1e2b83fc6529232d455'
APP_TREE = '2c698fb681f647e4c3a1d0050bf806a9f4cc5de2'
BUILD_RUN = 37238394831
APK_SHA = '24f96438c1ebe1e648e23352e1191aa91ab472ee938cc62e1eba39dd2abe7c57'
CERT_SHA = '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'
ALLOWED = {
    '.github/scripts/native_scroll_bounds.py',
    '.github/scripts/test_native_scroll_bounds.py',
    '.github/scripts/fixtures/scroll-settings-dock-clearance.xml',
    '.github/scripts/verify_ui83_reused_apk.py',
    '.github/workflows/ui-v83-install-validation.yml',
    'docs/ci/V20.83_SIXTH_FAILURE.json',
}


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    out = Path('out/android-smoke')
    out.mkdir(parents=True, exist_ok=True)
    head = command('git', 'rev-parse', 'HEAD')
    assert head == os.environ['GITHUB_SHA'], 'Test checkout does not match requested SHA'
    tree = command('git', 'rev-parse', 'HEAD:android-app')
    assert tree == APP_TREE, 'Android application, tests or build inputs changed'
    changes = set(command('git', 'diff', '--name-only', APP_COMMIT, head).splitlines())
    assert changes <= ALLOWED, ('Changes require a new complete build', sorted(changes - ALLOWED))
    source_run = json.loads((out / 'source-run.json').read_text())
    assert source_run['id'] == BUILD_RUN and source_run['run_attempt'] == 1
    assert source_run['head_sha'] == APP_COMMIT and source_run['status'] == 'completed'
    jobs = {j['id']: j for j in json.loads((out / 'source-jobs.json').read_text())['jobs']}
    assert jobs[111542001292]['name'] == 'build' and jobs[111542001292]['conclusion'] == 'success'
    assert jobs[111542001166]['conclusion'] == 'success', 'Historical crash regression was not completed'
    artifacts = [
        (11315943930, 'Hetu-APK', '920cbded6db5216f65ecefd8836336575e12eb4ae8b93fc29e930d94d202f741', 'apk'),
        (11316830187, 'Hetu-V20.83-UI-verification', 'ad919a6a91a4354b902f6fbd236468259d0c3d5576fea8f51edd0e0a11286004', 'verification'),
    ]
    for artifact_id, name, digest, kind in artifacts:
        meta = json.loads((out / f'source-{kind}-artifact.json').read_text())
        assert meta['id'] == artifact_id and meta['name'] == name and not meta['expired']
        assert meta['workflow_run']['id'] == BUILD_RUN and meta['workflow_run']['head_sha'] == APP_COMMIT
        assert meta['digest'] == 'sha256:' + digest
        archive = Path(f'candidate-{kind}.zip')
        assert sha(archive) == digest, f'{kind} archive differs from immutable artifact'
    with zipfile.ZipFile('candidate-apk.zip') as z:
        names = [n for n in z.namelist() if not n.endswith('/')]
        assert names == ['Hetu-0.12.13-v20-ui.apk'], names
        apk = Path('candidate') / names[0]
        apk.parent.mkdir(exist_ok=True)
        apk.write_bytes(z.read(names[0]))
    assert apk.stat().st_size == 134721428 and sha(apk) == APK_SHA
    with zipfile.ZipFile('candidate-verification.zip') as z:
        names = z.namelist()
        xml = [n for n in names if '/test-results/testDebugUnitTest/TEST-' in n and n.endswith('.xml')]
        counts = {key: sum(int(ET.fromstring(z.read(n)).get(key, 0)) for n in xml)
                  for key in ('tests', 'failures', 'errors', 'skipped')}
        assert len(xml) == 42 and counts == {'tests': 392, 'failures': 0, 'errors': 0, 'skipped': 0}, counts
        lint = ET.fromstring(z.read('android-app/app/build/reports/lint-results-debug.xml'))
        assert not [i for i in lint.findall('issue') if i.get('severity') in ('Error', 'Fatal')]
        proof = json.loads(z.read('out/verification/effective-source-proof.json'))
        assert proof['checkoutMatchesGeneratedSource']
        layer = proof['presentationLayer']
        assert layer['protectedRuntimeUnchanged'] and layer['changedOrAddedFiles'] == 68
        assert layer['patchSha256'] == 'a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514'
        runtime = json.loads(z.read('out/verification/packaged-runtime.json'))
        abi = json.loads(z.read('out/verification/materialkolor-abi.json'))
    assert runtime['apkVerified'] and runtime['apkSha256'] == abi['apkSha256'] == APK_SHA
    assert abi['compatible'] and abi['constructorReferences'] == 687 and abi['missingConstructors'] == []
    assert len(runtime['expected']) == 23
    with zipfile.ZipFile(apk) as z:
        for name, expected in runtime['expected'].items():
            assert hashlib.sha256(z.read(name)).hexdigest() == expected, name
    tools = sorted(Path(os.environ['ANDROID_HOME']).glob('build-tools/*/apksigner'))
    assert tools, 'No Android signature verifier available'
    signer = tools[-1]
    signature = command(str(signer), 'verify', '--print-certs', str(apk))
    assert CERT_SHA in signature.lower(), signature
    metadata = command(str(signer.parent / 'aapt'), 'dump', 'badging', str(apk))
    assert "name='io.github.xgl34222220.hetu' versionCode='2083' versionName='0.12.13-v20-ui'" in metadata
    (out / 'reused-apk-signature.txt').write_text(signature + '\n')
    (out / 'reused-apk-metadata.txt').write_text(metadata + '\n')
    report = {'applicationCommit': APP_COMMIT, 'installationTestCommit': head,
              'androidAppTree': tree, 'buildRun': BUILD_RUN, 'buildAttempt': 1,
              'buildJob': 111542001292, 'apkArtifact': 11315943930,
              'apkSha256': APK_SHA, 'apkBytes': apk.stat().st_size,
              'sourceCompilationUnchanged': True, 'reusedUnitTests': counts,
              'runtimePayloadsVerified': 23, 'abiConstructors': 687,
              'certificateSha256': CERT_SHA, 'changedTestInfrastructure': sorted(changes),
              'rootMutationActions': 0,
              'boundary': 'Build evidence belongs to the fixed application commit; this continuation executes current installation tests only.'}
    (out / 'reused-build-proof.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
