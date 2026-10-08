#!/usr/bin/env python3
"""Independently consume raw additional native proof, original65, timings and phase boundaries."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from feedback_smoke93 import (PKG, REQUIRED_CASES, STARTUP, MAIN_ACTIVITY, STARTUP_ACTIVITY,
    OFFICIAL_LAUNCHER, LONG_LINES, MAX_FIXTURE_BYTES, sha, canonical_component,
    validate_original_navigation, parse_am_start, resumed_component, activity_matches, assert_no_app_crash, eof_geometry)
from native_scroll_bounds import _dock_top


def observed_app_text(path):
    root = ET.parse(path).getroot()
    assert not any(n.get('class') == 'android.webkit.WebView' for n in root.iter('node'))
    return root, '\n'.join(n.get('text', '') + '\n' + n.get('content-desc', '')
                           for n in root.iter('node') if n.get('package') == PKG)


def verify_observed_routes(output):
    """Re-read actual hierarchies, independently of the producer's PASS fields."""
    output = Path(output)
    short = (output / 'fixture-short.yaml').read_text()
    short_nonce = short.splitlines()[0].removeprefix('# ')
    assert short_nonce.startswith('HETU-FEEDBACK93-SHORT-') and len(short_nonce) > len('HETU-FEEDBACK93-SHORT-')
    expected = {'startup-config-missing-file': '尚未生成启动配置',
                'startup-config-short-file': short_nonce,
                'startup-config-long-file': 'HETU-FEEDBACK93-LONG-BEGIN'}
    for kind, text in expected.items():
        for stage, required in (('settings', '基础代理配置'), ('basic', '查看启动配置'),
                                ('viewer', text), ('back-to-basic', '查看启动配置'),
                                ('back-to-settings', '基础代理配置')):
            root, actual = observed_app_text(output / (kind + '-' + stage + '.xml'))
            assert required in actual, ('Wrong actual startup route hierarchy', kind, stage, required)
            if stage == 'viewer':
                assert '启动配置' in actual and _dock_top(root, PKG) is None
        _, actual = observed_app_text(output / (kind + '-returned-home-ready.xml'))
        assert any(title in actual.splitlines() for title in ('河图', 'Hetu', '河圖'))
    _, actual = observed_app_text(output / 'startup-config-long-file-last-line.xml')
    assert 'HETU-FEEDBACK93-LONG-END' in actual
    for name in ('cold', 'warm'):
        for stage in ('home-ready', 'interactive-returned-home-ready'):
            _, actual = observed_app_text(output / (name + '-' + stage + '.xml'))
            assert any(title in actual.splitlines() for title in ('河图', 'Hetu', '河圖')), ('Wrong home hierarchy', name, stage)
        root, actual = observed_app_text(output / (name + '-resource-detail.xml'))
        assert all(label in actual for label in ('资源占用', 'CPU', '内存')), ('Wrong resource detail hierarchy', name)
        assert _dock_top(root, PKG) is None and not any(n.get('package') == PKG and n.get('content-desc') == '首页'
                                                       for n in root.iter('node')), ('Resource detail still has home dock', name)


def verify(folder, commit, apk_sha, api):
    folder = Path(folder)
    paths = list(folder.rglob('feedback93-proof.json'))
    assert len(paths) == 1, 'Missing/ambiguous additional native feedback proof'
    proof_path = paths[0]
    output = proof_path.parent
    assert output.name == 'feedback93'
    root = output.parent
    report = json.loads(proof_path.read_text())
    assert report['result'] == 'PASS' and report['apiLevel'] == api
    assert report['observedRepositoryCommit'] == commit and report['apkSha256'] == apk_sha
    assert report['rootMutationActions'] == report['rootSetupInputs'] == 0
    assert report['rootReadAttemptsMeasured'] is False
    original_path = root / 'results.json'
    original = json.loads(original_path.read_text())
    validate_original_navigation(original)
    assert original['apiLevel'] == api
    assert report['originalNavigationChecks'] == 65 and report['originalResultsUnchanged'] is True
    assert report['originalResultsSha256'] == sha(original_path.read_bytes())
    observed = {}
    for case in report['checks']:
        assert case['name'] in REQUIRED_CASES and case['name'] not in observed
        assert case['result'] == 'passed'
        observed[case['name']] = case
    assert set(observed) == REQUIRED_CASES
    actual_files = {str(path.relative_to(output)): sha(path.read_bytes()) for path in output.rglob('*')
                    if path.is_file() and path != proof_path}
    assert actual_files == report['filesSha256'], 'Retained raw feedback evidence changed'
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in actual_files)
    for path in output.glob('*.png'):
        assert path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
    phase = report['originalPhase']
    assert phase['nativePeriodDoesNotCoverSubsequentColdWarmLaunches'] is True
    for name, digest in phase['originalEvidenceSha256'].items():
        assert sha((root / name).read_bytes()) == digest, 'Original native/65 acceptance evidence changed'
    assert {'results.json', 'launch.txt', 'first-frame-ready.json', 'logcat.txt'} <= phase['originalEvidenceSha256'].keys()
    assert_no_app_crash((root / 'logcat.txt').read_text())
    initial = report['additionalPhaseInitialIdentity']
    assert set(initial) == {'pid', 'startTicks'} and all(isinstance(v, int) and v > 0 for v in initial.values())
    assert report['ownedTarget']['apiLevel'] == api and report['ownedTarget']['nonRootShell'] is True
    assert report['ownedTarget']['serial'] == 'emulator-5554'
    assert datetime.fromisoformat(phase['originalAcceptanceReturnedUtc']) <= datetime.fromisoformat(report['additionalPhaseStartedUtc'])
    if api == 36:
        app = json.loads((root / 'post-native-application.json').read_text())
        native = json.loads((root / 'native-soak90/results.json').read_text())
        assert phase['nativeApplicable'] is True and report['nativeSoakObservationWasCompletedBeforeAdditionalCases'] is True
        assert phase['native']['result'] == app['result'] == native['result'] == 'PASS'
        assert phase['native']['applicationBefore'] == phase['native']['applicationAfter'] == app['before'] == app['after']
        assert initial == {key: app['after'][key] for key in ('pid', 'startTicks')}
        assert native['observedRepositoryCommit'] == app['observedRepositoryCommit'] == commit
        assert app['apkSha256'] == apk_sha
        assert phase['native']['actualContinuousSeconds'] == native['actualContinuousSeconds'] == app['nativeSoakActualSeconds'] >= 900
        for key in ('continuousStartedUtc', 'continuousEndedUtc', 'finishedUtc'):
            assert phase['native'][key] == native[key]
        assert datetime.fromisoformat(native['continuousEndedUtc']) <= datetime.fromisoformat(native['finishedUtc']) <= datetime.fromisoformat(phase['originalAcceptanceReturnedUtc'])
        assert_no_app_crash((root / 'post-native-application-logcat.txt').read_text())
    else:
        assert api == 35 and phase['nativeApplicable'] is False
        assert report['nativeSoakObservationWasCompletedBeforeAdditionalCases'] is False
        assert phase['native']['result'] == 'NOT_APPLICABLE'
        assert not (root / 'post-native-application.json').exists() and not (root / 'native-soak90/results.json').exists()
    route = ['settings', 'basic-proxy', 'ProxyStartupConfigActivity', 'basic-proxy', 'settings', 'home']
    for kind in ('startup-config-missing-file', 'startup-config-short-file', 'startup-config-long-file'):
        case = observed[kind]
        assert case['route'] == route and case['before'] == case['after'] == initial
        assert case['regenerateOrServiceActions'] == 0
        for stage in ('settings', 'basic', 'viewer', 'back-to-basic', 'back-to-settings', 'returned-home-ready'):
            stem = output / (kind + '-' + stage)
            assert stem.with_suffix('.png').is_file() and stem.with_suffix('.xml').is_file()
        for stage, key, expected in (('before-tap', 'beforeTap', MAIN_ACTIVITY), ('viewer', 'viewer', STARTUP_ACTIVITY),
                                      ('back-to-basic', 'backToBasic', MAIN_ACTIVITY)):
            actual = resumed_component((output / (kind + '-' + stage + '-activity-stack.txt')).read_text())
            assert actual == case['observedResumedActivities'][key] and activity_matches(actual, expected)
    long = observed['startup-config-long-file']
    eof = ET.parse(output / 'startup-config-long-file-last-line.xml').getroot()
    assert long['eofGeometry'] == eof_geometry(eof)
    assert (output / 'startup-config-long-file-last-line.png').is_file()
    assert resumed_component((output / 'startup-config-long-file-last-line-activity-stack.txt').read_text()) == STARTUP_ACTIVITY
    fixtures = report['startupFixtures']
    assert [f['kind'] for f in fixtures] == ['short', 'long']
    for fixture in fixtures:
        data = (output / ('fixture-' + fixture['kind'] + '.yaml')).read_bytes()
        assert fixture['path'] == STARTUP and fixture['originalAbsent'] is True
        assert fixture['writeAttempted'] is True and fixture['cleanedUp'] is True
        assert 0 < len(data) == fixture['bytes'] <= MAX_FIXTURE_BYTES
        assert sha(data) == fixture['sha256'] == fixture['afterReadSha256'] == fixture['cleanupObservedSha256']
        if fixture['kind'] == 'short':
            assert data.startswith(b'# HETU-FEEDBACK93-SHORT-') and b'mode: rule\n' in data
        else:
            assert data.startswith(b'# HETU-FEEDBACK93-LONG-BEGIN\n') and data.endswith(b'HETU-FEEDBACK93-LONG-END\n')
            assert data.count(b'# controlled fixture line ') == LONG_LINES
    assert observed['startup-config-fixture-cleanup']['originalAbsentAndAbsentAfter'] is True
    verify_observed_routes(output)
    assert canonical_component(report['queriedLauncherComponent']) == OFFICIAL_LAUNCHER
    assert report['queriedLauncherComponent'] in report['launcherQuery'].splitlines()
    cold, warm = observed['cold-home-interaction'], observed['warm-home-interaction']
    assert cold['before'] == initial and cold['launched'] != initial
    assert warm['before'] == cold['afterInteraction'] == cold['launched']
    assert warm['launched'] == warm['afterInteraction'] == warm['before']
    for name, case in (('cold', cold), ('warm', warm)):
        raw = (output / (name + '-am-start.txt')).read_text()
        assert parse_am_start(raw) == case['androidAmTiming']
        assert case['afterInteraction'] == case['launched']
        assert case['freshInstallOrPageCacheCold'] is False and case['explicitStartPage'] == 'home'
        assert 0 <= case['hostSecondsUntilObservedHome'] <= case['hostSecondsThroughActualInteraction']
        counters = case['instrumentation']
        for key in ('preparationBeforeAm', 'amThroughObservedHome', 'amThroughCompletedInteraction'):
            assert set(counters[key]) == {'uiDumpCalls', 'uiDumpWallSeconds', 'fixedInputSettlingSeconds'}
            assert all(isinstance(v, (int, float)) and v >= 0 for v in counters[key].values())
        assert counters['amThroughObservedHome']['uiDumpWallSeconds'] <= case['hostSecondsUntilObservedHome']
        assert counters['amThroughCompletedInteraction']['uiDumpWallSeconds'] <= case['hostSecondsThroughActualInteraction']
        assert datetime.fromisoformat(report['additionalPhaseStartedUtc']) <= datetime.fromisoformat(case['launchStartedUtc']) <= datetime.fromisoformat(report['additionalPhaseCompletedUtc'])
        actual = resumed_component((output / (name + '-home-ready-activity-stack.txt')).read_text())
        assert actual == case['observedHomeResumedComponent'] and activity_matches(actual, MAIN_ACTIVITY)
        for stage in ('home-ready', 'resource-detail', 'interactive-returned-home-ready'):
            assert (output / (name + '-' + stage + '.xml')).is_file() and (output / (name + '-' + stage + '.png')).is_file()
    inputs = report['inputs']
    assert len([event for event in inputs if event.get('kind') == 'native adb tap' and event.get('label') == '查看启动配置']) == 3
    assert len([event for event in inputs if event.get('kind') == 'native adb tap' and event.get('label') == '查看末尾']) == 1
    assert not any(event.get('label') in ('重新生成', '启动服务', '重启', '开机自启') for event in inputs)
    log = (output / 'global-logcat-after-feedback.txt').read_text()
    assert sha(log.encode()) == report['globalLogcatSha256']
    assert report['globalLogcatCoversAdditionalRoutesAndBothLaunches'] is True
    assert_no_app_crash(log)
    return {'result': 'PASS', 'apiLevel': api, 'oldNavigationChecks': 65, 'additionalCases': len(observed),
            'sourceCommit': commit, 'apkSha256': apk_sha, 'proofSha256': sha(proof_path.read_bytes()),
            'eofGeometry': long['eofGeometry'], 'cold': cold, 'warm': warm, 'originalPhase': phase,
            'limitations': report['limitations']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--apk-sha256', required=True)
    parser.add_argument('--api', type=int, choices=(35, 36), required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.evidence, args.commit, args.apk_sha256, args.api)))
