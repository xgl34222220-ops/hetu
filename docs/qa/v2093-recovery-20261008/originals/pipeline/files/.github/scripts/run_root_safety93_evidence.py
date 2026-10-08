#!/usr/bin/env python3
"""Actual isolated shipped-shell faults; pinned before helper and current helper stay distinct."""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

from continuity93_source_scope import (ROOT, BASE_COMMIT, ROOT_SCRIPT, AUTOSTART_SCRIPT,
    HOST_NEW_TEST, committed_bytes, digest, validate_checkout)

ARCHIVED_HELPER = 'docs/qa/20261008-root-safety93-before-test-source.py'
ARCHIVED_RECORD = 'docs/qa/20261008-root-safety93-before.json'
ARCHIVED_BINDING = 'docs/qa/20261008-root-safety93-before-root-binding.json'
EXPECTED_BEFORE = {
    'test_existing_core_is_adopted_only_after_owned_network_repair_and_recheck': "self.assertEqual(['repair'], self.lines('repair-calls'))",
    'test_existing_core_with_broken_data_plane_never_reports_boot_success': 'self.assertNotEqual(0, code)',
    'test_initial_owner_publication_race_cannot_run_two_start_plans': "self.assertEqual(1, len(self.lines('starts')))",
    'test_permanent_start_failure_has_six_attempts_and_backoff': "self.assertEqual(6, len(self.lines('starts')))",
    'test_stop_never_reports_success_when_owned_firewall_cleanup_fails': 'self.assertNotEqual(0, result.returncode)',
}
CANDIDATE_BEFORE_ASSETS = 'docs/qa/20261008-root-safety93-takeover-initial-20261008T154455Z/pre-fix-source/android-app/app/src/main/assets'
CANDIDATE_BEFORE_FOLDER = 'docs/qa/20261008-root-safety93-current-candidate-before-faults-fixture3'
CANDIDATE_BEFORE_HELPER = CANDIDATE_BEFORE_FOLDER + '/fault-helper.py'
CANDIDATE_BEFORE_HELPER_SHA256 = 'e695cb35f95bfbdc30780e2203d4b3a6bcf3b3156a20206468fd1e1f7883abdc'
CANDIDATE_BEFORE_ASSET_SHA256 = {
    ROOT_SCRIPT: '129d72af28e19a486fb90771183fe9469354e10b02db9f2f062997f18a353247',
    AUTOSTART_SCRIPT: '64a8adbcfddff0127a3823b29405a8b57fe20f77a0999d0aaea91d4d55f2df4b',
}
EXPECTED_CANDIDATE_BEFORE = {
    'test_boot_budget_tail_never_starts_a_transaction_without_rollback_reserve': 'self.assertNotEqual(0, code)',
    'test_outer_expiry_at_core_readiness_drains_detached_core_and_keeps_kill_guard': 'detached owned core survived failed start',
    'test_outer_expiry_during_hook_install_drains_detached_core_and_keeps_kill_guard': 'KILL=1 rollback did not establish a guard',
    'test_outer_expiry_after_guardian_launch_drains_owned_sessions_and_keeps_kill_guard': 'detached owned core survived failed start',
}


def execute(helper, assets, selected=None):
    spec = importlib.util.spec_from_file_location('tools.qa.test_root_boot_safety93', helper)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.ASSETS = Path(assets)
    suite = (unittest.defaultTestLoader.loadTestsFromModule(module) if selected is None else
             unittest.defaultTestLoader.loadTestsFromNames(
                 ['IsolatedRoot.' + name for name in sorted(selected)], module=module))
    methods = sorted(test.id() for group in suite for test in group)
    assert methods and len(set(methods)) == len(methods)
    stream = io.StringIO()
    started = time.monotonic()
    result = unittest.TextTestRunner(stream=stream, verbosity=2).run(suite)
    return {'tests': result.testsRun, 'expectedIdentities': methods,
            'failures': {test.id(): text for test, text in result.failures},
            'errors': {test.id(): text for test, text in result.errors},
            'skipped': {test.id(): reason for test, reason in result.skipped},
            'successful': result.wasSuccessful(), 'actualWallSeconds': time.monotonic() - started}, stream.getvalue()


def verify_before(before):
    prefix = 'tools.qa.test_root_boot_safety93.IsolatedRoot.'
    assert before['tests'] == 5 and before['expectedIdentities'] == sorted(prefix + method for method in EXPECTED_BEFORE)
    assert not before['successful'] and not before['errors'] and not before['skipped']
    assert set(before['failures']) == set(before['expectedIdentities'])
    for method, asserted_line in EXPECTED_BEFORE.items():
        trace = before['failures'][prefix + method]
        assert asserted_line in trace and 'AssertionError:' in trace, ('Unexpected old shell failure', method, trace)
        assert not any(reason in trace for reason in ('SyntaxError:', 'ImportError:', 'CalledProcessError:', 'TimeoutExpired:'))
    return before


def verify_candidate_before(before):
    prefix = 'tools.qa.test_root_boot_safety93.IsolatedRoot.'
    expected = sorted(prefix + name for name in EXPECTED_CANDIDATE_BEFORE)
    assert before['tests'] == 4 and before['expectedIdentities'] == expected
    assert not before['successful'] and not before['errors'] and not before['skipped']
    assert set(before['failures']) == set(expected)
    for name, line in EXPECTED_CANDIDATE_BEFORE.items():
        trace = before['failures'][prefix + name]
        assert line in trace and 'AssertionError:' in trace, ('Unexpected candidate control failure', name, trace)
        assert not any(reason in trace for reason in ('SyntaxError:', 'ImportError:', 'CalledProcessError:', 'TimeoutExpired:'))
    return before


def candidate_control(root, final, output):
    observed_helper = root / CANDIDATE_BEFORE_HELPER
    assert digest(observed_helper) == CANDIDATE_BEFORE_HELPER_SHA256
    # The final boundary adapter also mocks atomic iptables-restore. Keep the
    # retained original observation helper distinct; replay both source views
    # with the exact same final helper rather than running an incomplete old
    # adapter against a new production command.
    helper = root / HOST_NEW_TEST
    helper_sha256 = digest(helper)
    assets = root / CANDIDATE_BEFORE_ASSETS
    expected_assets = {name: digest(assets / Path(name).name) for name in CANDIDATE_BEFORE_ASSET_SHA256}
    assert expected_assets == CANDIDATE_BEFORE_ASSET_SHA256
    observed = json.loads((root / CANDIDATE_BEFORE_FOLDER / 'report.json').read_text())
    assert observed['sourceSha256'] == {**expected_assets, HOST_NEW_TEST: CANDIDATE_BEFORE_HELPER_SHA256}
    assert observed['tests'] == 4 and not observed['successful'] and not observed['errors']
    assert observed['actualMethods'] == list(EXPECTED_CANDIDATE_BEFORE)
    for name in EXPECTED_CANDIDATE_BEFORE:
        trace = observed['failures']['tools.qa.test_root_boot_safety93.IsolatedRoot.' + name]
        assert EXPECTED_CANDIDATE_BEFORE[name] in trace and 'AssertionError:' in trace
    for name in expected_assets:
        (output / ('candidate-before-' + Path(name).name)).write_bytes((assets / Path(name).name).read_bytes())
    (output / 'candidate-before-fault-helper.py').write_bytes(observed_helper.read_bytes())
    (output / 'candidate-control-same-fault-helper.py').write_bytes(helper.read_bytes())
    before, log = execute(helper, assets, selected=EXPECTED_CANDIDATE_BEFORE)
    (output / 'candidate-before-actual-tests.log').write_text(log)
    verify_candidate_before(before)
    after, log = execute(helper, root / 'android-app/app/src/main/assets', selected=EXPECTED_CANDIDATE_BEFORE)
    (output / 'candidate-control-current-actual-tests.log').write_text(log)
    assert after['successful'] and not after['failures'] and not after['errors'] and not after['skipped']
    assert after['tests'] == 4 and after['expectedIdentities'] == before['expectedIdentities']
    assert digest(helper) == helper_sha256, 'Same-helper candidate control changed during execution'
    return {'sourceIsPinnedBaseCommit': False, 'sourceBoundary': 'Exact uncommitted candidate assets before current-diff native transaction repair; never attributed to cff.',
            'beforeAssetSha256': expected_assets, 'currentAssetSha256': {name: final[name] for name in expected_assets},
            'sameHelperSha256BeforeAndAfter': helper_sha256,
            'retainedOriginalObservationHelperSha256': CANDIDATE_BEFORE_HELPER_SHA256,
            'retainedObservedReportSha256': digest(root / CANDIDATE_BEFORE_FOLDER / 'report.json'),
            'retainedObservedLogSha256': digest(root / CANDIDATE_BEFORE_FOLDER / 'actual-tests.log'),
            'before': before, 'currentSameHelper': after}


def run(root=ROOT, output=None):
    root = Path(root)
    output = root / 'out/verification/root-safety93' if output is None else Path(output)
    assert not output.exists(), 'Preserve Root safety evidence'
    final = validate_checkout(root, allow_external_missing=False)
    output.mkdir(parents=True)
    old_record = json.loads((root / ARCHIVED_RECORD).read_text())
    binding = json.loads((root / ARCHIVED_BINDING).read_text())
    assert old_record['parent'] == binding['parent'] == BASE_COMMIT
    assert digest(root / ARCHIVED_HELPER) == old_record['sourceSha256'][HOST_NEW_TEST]
    original = {name: committed_bytes(name, root) for name in (ROOT_SCRIPT, AUTOSTART_SCRIPT)}
    old_hashes = {name: hashlib.sha256(data).hexdigest() for name, data in original.items()}
    assert binding['root_independently_checked'] == old_hashes
    assert {name: old_record['sourceSha256'][name] for name in original} == old_hashes
    report = {'result': 'FAIL', 'candidateCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
              'baseCommit': BASE_COMMIT, 'oldAssetSha256': old_hashes, 'currentAssetSha256': {name: final[name] for name in original},
              'oldHelperSha256': digest(root / ARCHIVED_HELPER), 'currentHelperSha256': digest(root / HOST_NEW_TEST),
              'scope': 'Actual shipped shell with isolated owned host command/proc/clock faults; no su, phone, real firewall, GMS or overheating-causality claim.'}
    try:
        with tempfile.TemporaryDirectory(prefix='hetu-root93-old-assets-') as directory:
            assets = Path(directory)
            for name, data in original.items():
                (assets / Path(name).name).write_bytes(data)
                (output / ('before-' + Path(name).name)).write_bytes(data)
            before, log = execute(root / ARCHIVED_HELPER, assets)
            (output / 'before-actual-tests.log').write_text(log)
            report['before'] = verify_before(before)
        current, log = execute(root / HOST_NEW_TEST, root / 'android-app/app/src/main/assets')
        (output / 'current-actual-tests.log').write_text(log)
        report['current'] = current
        assert current['successful'] and not current['failures'] and not current['errors'] and not current['skipped']
        assert set(before['expectedIdentities']) <= set(current['expectedIdentities'])
        report['candidateBeforeControl'] = candidate_control(root, final, output)
        assert validate_checkout(root, allow_external_missing=False) == final
        report['currentCheckoutUnmodified'] = True
        report['result'] = 'PASS'
    finally:
        report['rawFilesSha256'] = {str(path.relative_to(output)): digest(path) for path in output.rglob('*') if path.is_file()}
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    print(json.dumps(run(output=parser.parse_args().output)))
