#!/usr/bin/env python3
"""Bind the actual GitHub API run, all five expanded jobs and retained artifacts to one SHA."""
import argparse
import json
from pathlib import Path

EXPECTED_JOBS = frozenset(('build', 'before-comparison', 'Installed APK on Android API 35',
                          'Installed APK on Android API 36', 'Reproduce V20.74 custom palette crash on API 36 (36)'))
EXPECTED_ARTIFACTS = frozenset((
    'Hetu-V20.93-effective-source', 'Hetu-V20.93-UI-verification', 'Hetu-V20.93-supplemental-36-tests',
    'Hetu-V20.93-feedback-before-comparison', 'Hetu-V20.93-Android-35-smoke', 'Hetu-V20.93-Android-36-smoke',
    'Hetu-V20.93-APK-manifest', 'Hetu-V20.74-palette-crash-baseline-Android-36',
    *(f'Hetu-V20.93-APK-part{index:02d}' for index in range(6)),
))


# Exact named verification/setup steps in this workflow; artifact upload and generated
# Post/action cleanup steps have their own evidence binding and are not core checks.
EXPECTED_CORE_STEPS = {'build': ('Regress bounded SDK installer retries with controlled processes',
           'Load current layer and fetch exact predecessor objects',
           'Regenerate and verify integrated effective source',
           'Execute shipped Root boot safety controlled fault regressions',
           'Verify presentation patch cannot alter runtime or dependency inputs',
           'Verify PDF layer preserves frozen UI and diagnostic boundaries',
           'Verify uploaded tools intake source and additive test boundaries',
           'Verify measured final PDF presentation preserves intake and shared defaults',
           'Verify independent authentication source and test-evidence boundaries',
           'Execute original function gates on their exact historical source',
           'Verify current source, testcase identities and workflow ownership',
           'Regress actual tool viewport gesture bounds',
           'Regress isolated controller authentication installation fixtures',
           'Regress observed nested WebView document topology without weakening surface guards',
           'Verify built-in WebUI behavior with controlled API fixtures',
           'Render built-in WebUI with isolated browser fixtures',
           'Reproduce cache eviction on old production exporter and verify new exporter',
           'Restore signing identity',
           'Verify signing identity',
           'Prepare Android SDK',
           'Export exact V20.93 effective source',
           'Record resolved MaterialKolor ABI dependencies',
           'Build APK',
           'Verify APK embeds the tested runtime',
           'Verify packaged MaterialKolor constructors',
           'Verify packaged launcher and concept icon bytes',
           'Preserve historical V20.91 and V20.92 regressions in their exact predecessor source views',
           'Runtime unit tests',
           'Android lint compatibility gate',
           'Consolidate native and browser state evidence',
           'Execute all 36 unchanged and applicable supplemental regressions',
           'Verify independent supplemental XML and all 36 testcase identities',
           'Run sustained synthetic network recovery state-machine regression',
           'Verify APK'),
 'before-comparison': ('Load current layer and fetch exact predecessor objects',
                       'Restore exact native inputs and verify same-SHA before source binding',
                       'Restore signing identity',
                       'Verify signing identity',
                       'Prepare Android SDK',
                       'Observe compatible UI VM motion and alias-order defects on precise source '
                       'controls'),
 'Installed APK on Android API 35': ('Prepare official AOSP emulator',
                                     'Run original installed 65 checks and additive startup cold warm '
                                     'feedback observations'),
 'Installed APK on Android API 36': ('Prepare official AOSP emulator',
                                     'Run original installed 65 checks and additive startup cold warm '
                                     'feedback observations'),
 'Reproduce V20.74 custom palette crash on API 36 (36)': ('Download pinned historical V20.74 APK',
                                                          'Prepare official AOSP emulator',
                                                          'Run installed APK navigation and native '
                                                          'WebView smoke')}

def verify(run, jobs, artifacts, commit):
    assert run['head_sha'] == commit and run['head_branch'] == 'test/v20.76-new-ui'
    assert run['status'] == 'completed' and run['conclusion'] == 'success'
    assert run['event'] in ('push', 'workflow_dispatch')
    assert run['path'] == '.github/workflows/v2093-build.yml'
    assert run['repository']['full_name'] == 'xgl34222220-ops/hetu'
    assert run['head_repository']['full_name'] == 'xgl34222220-ops/hetu'
    raw_jobs = jobs['jobs']
    assert jobs['total_count'] == len(raw_jobs) == 5
    assert len({job['id'] for job in raw_jobs}) == 5 and {job['name'] for job in raw_jobs} == EXPECTED_JOBS
    for job in raw_jobs:
        assert job['run_id'] == run['id'] and job['head_sha'] == commit
        assert job['status'] == 'completed' and job['conclusion'] == 'success'
        assert job['started_at'] and job['completed_at'] and job['started_at'] <= job['completed_at']
        assert job['steps'] and all(step['conclusion'] in ('success', 'skipped') for step in job['steps'])
        required_steps = EXPECTED_CORE_STEPS[job['name']]
        named = [step['name'] for step in job['steps']]
        assert all(named.count(name) == 1 for name in required_steps), 'A required verification step is missing or duplicated'
        steps = {step['name']: step['conclusion'] for step in job['steps']}
        assert all(steps.get(name) == 'success' for name in required_steps), 'A required verification step was skipped or failed'
    raw_artifacts = artifacts['artifacts']
    assert artifacts['total_count'] == len(raw_artifacts)
    assert len({item['id'] for item in raw_artifacts}) == len(raw_artifacts)
    by_name = {item['name']: item for item in raw_artifacts}
    assert len(by_name) == len(raw_artifacts) and set(by_name) in (EXPECTED_ARTIFACTS, EXPECTED_ARTIFACTS | {'Hetu-APK'})
    for item in raw_artifacts:
        assert not item['expired'] and item['size_in_bytes'] > 0
        producer = item['workflow_run']
        assert producer['id'] == run['id'] and producer['head_sha'] == commit and producer['head_branch'] == run['head_branch']
    return {'result': 'PASS', 'candidateCommit': commit, 'runId': run['id'], 'actualCompletedJobs': 5,
            'jobIds': {job['name']: job['id'] for job in raw_jobs},
            'artifactIds': {name: item['id'] for name, item in by_name.items()},
            'limits': 'Actual GitHub producer metadata binding; individual downloaded bytes, XML, current/before source, APK and traffic samples are separately verified.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('run', 'jobs', 'artifacts'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    print(json.dumps(verify(json.loads(args.run.read_text()), json.loads(args.jobs.read_text()),
                            json.loads(args.artifacts.read_text()), args.commit), indent=2))
