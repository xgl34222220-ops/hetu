#!/usr/bin/env python3
"""Execute the four new HTTP regressions against only the original instance epoch.

This intentionally failing comparison lives in a separate source/build directory.
The current checkout and its full acceptance results are never overwritten.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from continuity91_source_scope import (ROOT, BASE_COMMIT, PACKAGE, TEST_ROOT, LAYER_FOLDER,
    committed_bytes, digest, validate_checkout)

CLASS = 'io.github.xgl34222220.hetu.CrossRepositoryMutation91Test'
PRODUCTION = PACKAGE + 'ProxyDashboardRepository.kt'
TEST_SOURCE = TEST_ROOT + 'java/io/github/xgl34222220/hetu/CrossRepositoryMutation91Test.kt'
EXPECTED_FAILURES = {
    'otherRepositorySelectionsAndProviderUpdatesRejectLateWebsiteWavesOnlyAfterSuccess':
        ('java.lang.AssertionError', "Other repository's confirmed select rejects the old website wave"),
    'otherRepositorySelectionsAndProviderUpdatesRejectLateSelectorMapsOnlyAfterSuccess':
        ('java.lang.AssertionError', "Other repository's confirmed select rejects the old Selector map"),
    'otherRepositorySuccessfulSelectionRefreshesNestedShortCacheAndFailedSelectionPreservesIt':
        ('java.lang.AssertionError', "The other page's successful PUT must refresh Outer -> Nested expected:<74> but was:<65>"),
    'otherRepositorySingleAndBulkSubscriptionUpdatesRefreshShortCacheButRejectedUpdatesRetainIt':
        ('java.io.IOException', '节点已更新，请刷新策略组'),
}


def require_original_instance_generation(source):
    assert source.count('private val probeMutationEpoch = AtomicLong()') == 1, 'Before source lacks the original instance generation'
    assert 'private companion object' not in source, 'Before source still contains the new shared generation'


def verify_regression_results(results):
    results = Path(results)
    paths = list(results.glob('TEST-*.xml'))
    assert len(paths) == 1 and paths[0].name == 'TEST-' + CLASS + '.xml', 'Before comparison suite differs'
    suite = ET.parse(paths[0]).getroot()
    cases = list(suite.iter('testcase'))
    assert suite.get('name') == CLASS and len(cases) == int(suite.get('tests', 0)) == 4
    assert int(suite.get('skipped', 0)) == 0
    assert int(suite.get('failures', 0)) + int(suite.get('errors', 0)) == 4
    observed = {}
    for case in cases:
        name = case.get('name')
        assert case.get('classname') == CLASS and name in EXPECTED_FAILURES and name not in observed
        failures = [*case.findall('failure'), *case.findall('error')]
        assert len(failures) == 1 and case.find('skipped') is None, 'Before testcase did not reproduce its failure'
        error = failures[0]
        expected_type, expected_message = EXPECTED_FAILURES[name]
        assert error.get('type') == expected_type, ('Unexpected before failure type', name, error.attrib)
        assert expected_message in error.get('message', ''), ('Unexpected before failure reason', name, error.attrib)
        observed[name] = {'type': error.get('type'), 'message': error.get('message')}
    assert set(observed) == set(EXPECTED_FAILURES)
    return {'result': 'EXPECTED_REGRESSION_REPRODUCED', 'suite': CLASS, 'testcaseFailures': observed,
            'xmlSha256': digest(paths[0]), 'tests': 4, 'skipped': 0}


def run(root=ROOT, output=None):
    root = Path(root)
    output = root / 'out/verification/before-comparison91' if output is None else Path(output)
    assert not output.exists(), 'Preserve prior before-comparison evidence'
    final = validate_checkout(root, allow_external_missing=False)
    before = committed_bytes(PRODUCTION, root)
    assert hashlib.sha256(before).hexdigest() != final[PRODUCTION]
    require_original_instance_generation(before.decode())
    output.mkdir(parents=True)
    (output / 'before-ProxyDashboardRepository.kt').write_bytes(before)
    report = {'result': 'FAIL', 'observedRepositoryCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
              'baseCommit': BASE_COMMIT, 'changedProductionSourceOnly': PRODUCTION,
              'beforeSourceSha256': hashlib.sha256(before).hexdigest(), 'currentSourceSha256': final[PRODUCTION],
              'currentTestSourceSha256': final[TEST_SOURCE], 'currentCheckoutUnmodified': False}
    try:
        with tempfile.TemporaryDirectory(prefix='hetu-before91-', dir=os.environ.get('RUNNER_TEMP')) as directory:
            fixture = Path(directory)
            shutil.copytree(root / 'android-app', fixture / 'android-app',
                            ignore=shutil.ignore_patterns('build', '.gradle'))
            (fixture / PRODUCTION).write_bytes(before)
            # Freeze every other compilation input, including the real current HTTP tests.
            from continuity91_source_scope import compilation_inputs
            actual = compilation_inputs(fixture)
            assert actual == set(final)
            assert all(digest(fixture / name) == (report['beforeSourceSha256'] if name == PRODUCTION else value)
                       for name, value in final.items()), 'Before fixture changed more than the repository generation'
            command = ['gradle', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8',
                       ':app:testDebugUnitTest', '--tests', CLASS, '--stacktrace']
            with (output / 'gradle-before.log').open('w') as log:
                result = subprocess.run(command, cwd=fixture / 'android-app', stdout=log, stderr=subprocess.STDOUT)
            assert result.returncode != 0, 'Original instance generation unexpectedly passed all four regressions'
            shutil.copytree(fixture / 'android-app/app/build/test-results/testDebugUnitTest', output / 'test-results')
            regression = verify_regression_results(output / 'test-results')
            report.update(regression)
            report['gradleExit'] = result.returncode
            report['currentCheckoutUnmodified'] = validate_checkout(root, allow_external_missing=False) == final
            assert report['currentCheckoutUnmodified']
            report['limitations'] = 'Controlled before/after real HTTP JVM fixture, not a phone, Root, radio or Google account result.'
    finally:
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    print(json.dumps(run(output=parser.parse_args().output)))
