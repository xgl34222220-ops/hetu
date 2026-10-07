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


def verify_panel_footer_scroll(folder):
    """Bind real bounded input to retained native XML, PNG and window insets."""
    proof = json.loads(locate(folder, 'panel-controller-200-footer-scroll.json').read_text())
    auth = json.loads(locate(folder, 'panel-controller-auth-fixture.json').read_text())
    assert proof == auth['footerScroll'] and proof['result'] == auth['result'] == 'PASS'
    assert proof['rootMutationActions'] == auth['rootMutationActions'] == 0
    assert auth['continuityServiceObserved'] is False
    expected = {'策略': 12, '规则': 3, '当前连接': 18}
    assert proof['returnedCounts'] == auth['observedNativeCounts'] == expected
    inputs, observations = proof['inputs'], proof['observations']
    assert 2 <= len(inputs) <= 3 and len(observations) == len(inputs)
    assert [entry['direction'] for entry in inputs] in (['up', 'down'], ['up', 'up', 'down'])
    assert all(entry['input'] == 'native adb swipe' and entry['durationMs'] == 400 for entry in inputs)
    assert all(a['timeMonotonic'] < b['timeMonotonic'] for a, b in zip(inputs, inputs[1:]))
    assert observations[0]['capture'] == 'panel-controller-200-footer-before'
    assert observations[-1]['entireRowUnobscured'] is True
    frame = proof['navigationFrame']
    raw = locate(folder, 'panel-controller-200-footer-insets.txt').read_text()
    assert raw and len(frame) == 4 and frame[0] >= 0 and frame[2] > frame[0] and frame[3] > frame[1]
    actual_frames = set()
    for source in re.split(r'(?=InsetsSource(?:\s*:|\s*\{))', raw):
        line = source.split('\n', 1)[0]
        if not re.search(r'(?:mType|type)=navigationBars\b', line) or not re.search(r'(?:mVisible|visible)=true\b', line):
            continue
        match = re.search(r'(?:mFrame|frame)=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', line)
        if match is None:
            match = re.search(r'(?:mFrame|frame)=Rect\((-?\d+),\s*(-?\d+)\s*-\s*(-?\d+),\s*(-?\d+)\)', line)
        assert match is not None, 'Visible navigation source lacks actual frame'
        candidate = tuple(map(int, match.groups()))
        view = observations[0]['viewport']
        if candidate[0] <= view[0] and candidate[2] >= view[2] and view[1] < candidate[1] < candidate[3] == view[3]:
            actual_frames.add(candidate)
    assert actual_frames == {tuple(frame)}, 'Retained navigation insets differ from reported frame'
    # WindowManager and app XML are independently retained; re-read actual
    # bounds rather than accepting the smoke runner's geometry result alone.
    from native_scroll_bounds import _dock_top, _node_bounds, scroll_swipe
    package = 'io.github.xgl34222220.hetu'
    for index, observed in enumerate(observations):
        name = 'panel-controller-200-footer-before' if index == 0 else f'panel-controller-200-footer-after-{index}'
        assert observed['capture'] == name and observed['navigationTop'] == frame[1]
        assert locate(folder, name + '.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
        root = ET.parse(locate(folder, name + '.xml')).getroot()
        assert not any(n.get('class') == 'android.webkit.WebView' for n in root.iter('node'))
        assert not any('HTTP 401' in n.get('text', '') or '无法读取面板' in n.get('text', '') for n in root.iter('node'))
        views = [n for n in root.iter('node') if n.get('package') == package and n.get('scrollable') == 'true'
                 and (b := _node_bounds(n, 'footer viewport'))[3] - b[1] > b[2] - b[0]]
        assert len(views) == 1 and list(_node_bounds(views[0], 'footer viewport')) == observed['viewport']
        assert observed['dockTop'] == _dock_top(root, package)
        labels = {label: [n for n in root.iter('node') if n.get('text') == label] for label in ('未知应用', '18 条连接')}
        assert all(len(nodes) == 1 for nodes in labels.values())
        parents = {child: parent for parent in root.iter() for child in parent}
        row = labels['未知应用'][0]
        while labels['18 条连接'][0] not in tuple(row.iter()) and row in parents:
            row = parents[row]
        assert row is not views[0] and labels['18 条连接'][0] in tuple(row.iter())
        ancestor = row
        while ancestor not in (views[0], root) and ancestor in parents:
            ancestor = parents[ancestor]
        assert ancestor is views[0], 'Footer row is outside the actual vertical list'
        bounds = _node_bounds(row, 'footer row')
        assert list(bounds) == observed['rowBounds']
        viewport = observed['gestureViewport']
        tabs = [n for n in root.iter('node') if n.get('text') == '概览']
        assert len(tabs) == 1
        selected = tabs[0]
        while selected.get('selected') != 'true' and selected in parents:
            selected = parents[selected]
        assert selected.get('selected') == 'true'
        assert viewport[:3] == [observed['viewport'][0], max(observed['viewport'][1], _node_bounds(selected, 'overview tab')[3]) + 4, observed['viewport'][2]]
        assert viewport[3] == min(frame[1], observed['dockTop'] if observed['dockTop'] is not None else frame[1]) - 4
        clear = viewport[0] <= bounds[0] < bounds[2] <= viewport[2] and viewport[1] <= bounds[1] < bounds[3] <= viewport[3]
        assert observed['entireRowUnobscured'] is clear
        assert list(scroll_swipe(viewport, reverse=inputs[index]['direction'] == 'down')) == inputs[index]['coordinates']
    returned = 'panel-controller-200-footer-returned'
    assert locate(folder, returned + '.png').read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    root = ET.parse(locate(folder, returned + '.xml')).getroot()
    assert any(n.get('text') == '运行概况' for n in root.iter('node'))
    assert not any(n.get('class') == 'android.webkit.WebView' for n in root.iter('node'))
    assert not any('HTTP 401' in n.get('text', '') or '无法读取面板' in n.get('text', '') for n in root.iter('node'))
    for caption, value in expected.items():
        matches = []
        for label in (n for n in root.iter('node') if n.get('text') == caption):
            x1, y1, x2, _ = _node_bounds(label, 'returned counter caption')
            candidates = []
            for node in root.iter('node'):
                if re.fullmatch(r'\d+', node.get('text', '')):
                    a, _, c, d = _node_bounds(node, 'returned counter')
                    if x1 - 4 <= (a + c) / 2 <= x2 + 4 and 0 <= y1 - d <= 80:
                        candidates.append((y1 - d, int(node.get('text'))))
            if candidates:
                gap = min(entry[0] for entry in candidates)
                nearest = [number for distance, number in candidates if distance == gap]
                assert len(nearest) == 1
                matches.append(nearest[0])
        assert matches == [value], 'Returned actual native counter differs: ' + caption
    return proof


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
        assert report['passed'] == len(report['checks']) >= 65
        assert report['legacyChecksPassed'] >= 59 and report['paletteCasesPassed'] == 21
        assert all(x['result'] == 'passed' for x in report['checks'])
        assert len([x for x in report['checks'] if x['name'] == 'panel-controller-200-footer-scroll']) == 1
        footer = verify_panel_footer_scroll(folder)
        runner = json.loads(locate(folder, 'runner-lifecycle.json').read_text())
        assert runner['result'] == 'PASS' and runner['kvm_before'] == runner['kvm_after']
        assert runner['launcher_reaped'] is True
        cleanup = json.loads(locate(folder, 'emulator-cleanup.json').read_text())
        assert cleanup['emulator_reaped'] is True
        apis[api] = {'checks': report['passed'], 'paletteChecks': report['paletteCasesPassed'], 'rootMutationActions': 0,
                     'footerScroll': footer}
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
    policy = native['nativeTransportPolicy']
    assert policy['allowedStageModes'] == ['700', '777'] and policy['stageExecuted'] is False
    assert policy['finalRequiredOwnershipMode'] == '2000:2000:700'
    assert policy['copyPreservesModeOrOwnership'] is False
    observations = native['guestFileObservations']
    expectations = native['guestFileExpectations']
    transport_directory = observations['beforePrivateCopyDirectory']['path']
    assert observations['transportedStage']['path'] == transport_directory + '/mihomo.transport'
    assert observations['privateExecutable']['path'] == transport_directory + '/mihomo'
    assert native['coreIdentity']['executable'] == transport_directory + '/mihomo'
    for label in ('transportedStage', 'privateExecutable'):
        observed = observations[label]
        expected = expectations[label]
        assert (expected['path'], expected['uid'], expected['gid'], expected['fileType'], expected['sha256']) == (
            observed['path'], 2000, 2000, '0o100000', native['nativePayload']['sha256'])
        assert (observed['uid'], observed['gid'], observed['fileType']) == (2000, 2000, '0o100000')
        assert observed['sha256'] == native['nativePayload']['sha256']
        assert 'rawStat' in observed and 'rawSha256sum' in observed and 'error' not in observed
        raw_stat = re.fullmatch(r'([0-9]+):([0-9]+):([0-7]+):([a-fA-F0-9]+)', observed['rawStat'])
        assert raw_stat is not None
        uid, gid, mode, file_mode = int(raw_stat[1]), int(raw_stat[2]), int(raw_stat[3], 8), int(raw_stat[4], 16)
        assert (uid, gid, raw_stat[3], oct(file_mode & 0o170000)) == (observed['uid'], observed['gid'], observed['mode'], observed['fileType'])
        assert file_mode & 0o7777 == mode
        raw_hash = observed['rawSha256sum'].split()
        assert len(raw_hash) == 2 and raw_hash[0] == observed['sha256'] and raw_hash[1] == observed['path']
    assert observations['transportedStage']['mode'] in ('700', '777')
    assert observations['privateExecutable']['mode'] == '700'
    assert expectations['transportedStage']['allowedModes'] == ['700', '777']
    assert expectations['privateExecutable']['mode'] == '700'
    assert native['guestNativePayloadSha256'] == observations['privateExecutable']['sha256']
    for prefix in ('beforePrivateCopy', 'afterPrivateCopy', 'beforeCleanup'):
        parent, marker = observations[prefix + 'Directory'], observations[prefix + 'Marker']
        assert (parent['uid'], parent['gid'], parent['mode'], parent['fileType']) == (2000, 2000, '700', '0o40000')
        assert (marker['uid'], marker['gid'], marker['mode'], marker['fileType']) == (2000, 2000, '600', '0o100000')
        nonce = re.fullmatch(r'/data/local/tmp/hetu-soak90-([a-f0-9]{32})', parent['path'])
        assert parent['path'] == transport_directory
        assert nonce is not None and marker['path'] == parent['path'] + '/owner'
        assert marker['rawMarker'] == nonce[1] and 'error' not in parent and 'error' not in marker
        for label, observed in ((prefix + 'Directory', parent), (prefix + 'Marker', marker)):
            expected = expectations[label]
            assert all(expected[key] == observed[key] for key in ('path', 'uid', 'gid', 'mode', 'fileType'))
        assert expectations[prefix + 'Marker']['rawMarker'] == nonce[1]
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
