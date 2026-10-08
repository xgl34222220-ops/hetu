#!/usr/bin/env python3
"""Run current tool-entry HTTP tests against exactly two pinned V20.91 production sources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from continuity92_source_scope import (ROOT, BASE_COMMIT, PACKAGE, TEST_ROOT,
    committed_bytes, digest, validate_checkout, compilation_inputs)

CLASS = 'io.github.xgl34222220.hetu.RuleModeMutation92Test'
PRODUCTION_FILES = (PACKAGE + 'ProxyDashboardRepository.kt', PACKAGE + 'ToolsRuntimeBridge.kt')
TEST_SOURCE = TEST_ROOT + 'java/io/github/xgl34222220/hetu/RuleModeMutation92Test.kt'
EXPECTED_FAILURES = {
    'toolRuleModeSwitchRejectsOldWebsiteWaveOnlyAfterSuccessfulPatch':
        ('java.lang.AssertionError', 'A successful tool PATCH must supersede the old route wave'),
    'successfulPatchInvalidatesOldWaveBeforeHeldReadBackFinishesEvenWhenModeIsUnconfirmed':
        ('java.lang.AssertionError', 'A successful tool PATCH must supersede the old route wave'),
    'successfulPatchWithFailedConfirmationStillInvalidatesOldWebsiteWave':
        ('java.lang.AssertionError', 'A successful tool PATCH must supersede the old route wave'),
    'latePatchFromReplacedApiCannotReadBackOrConfirmThroughReplacement':
        ('java.lang.AssertionError', 'The tool must not confirm an operation from a replaced API'),
    'lateReadBackCannotConfirmAfterApiReplacementEvenWhenOldApiReturnsRule':
        ('java.lang.AssertionError', 'The tool must not confirm an operation from a replaced API'),
}


def require_original_tool_entry(sources):
    repository, bridge = (sources[name].decode() for name in PRODUCTION_FILES)
    assert 'setRuleModeAndConfirm' not in repository
    assert 'val client = MihomoControllerClient(context.applicationContext)' in bridge
    assert 'client.setTrafficMode("rule")' in bridge
    assert 'ProxyDashboardRepository(context.applicationContext).setRuleModeAndConfirm()' not in bridge


def verify_regression_results(results):
    paths = list(Path(results).glob('TEST-*.xml'))
    assert len(paths) == 1 and paths[0].name == 'TEST-' + CLASS + '.xml', 'Before comparison suite differs'
    suite = ET.parse(paths[0]).getroot()
    cases = list(suite.iter('testcase'))
    assert suite.get('name') == CLASS and len(cases) == int(suite.get('tests', 0)) == 5
    assert int(suite.get('skipped', 0)) == 0
    assert int(suite.get('failures', 0)) == 5 and int(suite.get('errors', 0)) == 0
    observed = {}
    for case in cases:
        name = case.get('name')
        assert case.get('classname') == CLASS and name in EXPECTED_FAILURES and name not in observed
        failures = [*case.findall('failure'), *case.findall('error')]
        assert len(failures) == 1 and case.find('skipped') is None
        error = failures[0]
        expected_type, expected_message = EXPECTED_FAILURES[name]
        assert error.get('type') == expected_type, ('Unexpected before failure type', name, error.attrib)
        assert expected_message in error.get('message', ''), ('Unexpected before failure reason', name, error.attrib)
        observed[name] = {'type': error.get('type'), 'message': error.get('message')}
    assert set(observed) == set(EXPECTED_FAILURES)
    return {'result': 'EXPECTED_REGRESSION_REPRODUCED', 'suite': CLASS, 'testcaseFailures': observed,
            'xmlSha256': digest(paths[0]), 'tests': 5, 'skipped': 0}


def run(root=ROOT, output=None):
    root = Path(root)
    output = root / 'out/verification/before-comparison92' if output is None else Path(output)
    assert not output.exists(), 'Preserve prior before-comparison evidence'
    final = validate_checkout(root, allow_external_missing=False)
    sources = {name: committed_bytes(name, root) for name in PRODUCTION_FILES}
    before_hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sources.items()}
    assert all(before_hashes[name] != final[name] for name in PRODUCTION_FILES)
    require_original_tool_entry(sources)
    output.mkdir(parents=True)
    for name, data in sources.items():
        (output / ('before-' + Path(name).name)).write_bytes(data)
    report = {'result': 'FAIL', 'observedRepositoryCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
              'baseCommit': BASE_COMMIT, 'changedProductionSourcesOnly': list(PRODUCTION_FILES),
              'beforeSourceSha256': before_hashes, 'currentSourceSha256': {name: final[name] for name in PRODUCTION_FILES},
              'currentTestSourceSha256': final[TEST_SOURCE], 'currentCheckoutUnmodified': False}
    started = time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix='hetu-before92-', dir=os.environ.get('RUNNER_TEMP')) as directory:
            fixture = Path(directory)
            shutil.copytree(root / 'android-app', fixture / 'android-app', ignore=shutil.ignore_patterns('build', '.gradle'))
            for name, data in sources.items():
                (fixture / name).write_bytes(data)
            assert compilation_inputs(fixture) == set(final)
            assert all(digest(fixture / name) == before_hashes.get(name, value) for name, value in final.items()), 'Before fixture changed another input'
            command = ['gradle', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8',
                       ':app:testDebugUnitTest', '--tests', CLASS, '--stacktrace']
            with (output / 'gradle-before.log').open('w') as log:
                result = subprocess.run(command, cwd=fixture / 'android-app', stdout=log, stderr=subprocess.STDOUT)
            assert result.returncode != 0, 'Pinned tool entry unexpectedly passed all five regressions'
            shutil.copytree(fixture / 'android-app/app/build/test-results/testDebugUnitTest', output / 'test-results')
            report.update(verify_regression_results(output / 'test-results'))
            report['gradleExit'] = result.returncode
            report['currentCheckoutUnmodified'] = validate_checkout(root, allow_external_missing=False) == final
            assert report['currentCheckoutUnmodified']
            report['limitations'] = 'Current V20.92 HTTP JVM regressions against pinned V20.91 source; no phone, Root, radio or Google authentication claim.'
    finally:
        report['processWallSeconds'] = time.monotonic() - started
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    print(json.dumps(run(output=parser.parse_args().output)))
