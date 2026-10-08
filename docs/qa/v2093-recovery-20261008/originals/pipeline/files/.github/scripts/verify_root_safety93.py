#!/usr/bin/env python3
"""Re-read retained current/before shell identities, source bytes and exact unittest output."""
import ast
import hashlib
import json
from pathlib import Path
import re

from continuity93_source_scope import ROOT, BASE_COMMIT, ROOT_SCRIPT, AUTOSTART_SCRIPT, HOST_NEW_TEST, committed_bytes, digest
from run_root_safety93_evidence import (ARCHIVED_HELPER, EXPECTED_BEFORE,
    CANDIDATE_BEFORE_ASSETS, CANDIDATE_BEFORE_FOLDER, CANDIDATE_BEFORE_HELPER,
    CANDIDATE_BEFORE_HELPER_SHA256, CANDIDATE_BEFORE_ASSET_SHA256,
    EXPECTED_CANDIDATE_BEFORE, verify_candidate_before)


def test_identities(source):
    tree = ast.parse(source)
    prefix = 'tools.qa.test_root_boot_safety93.'
    names = []
    for cls in (node for node in tree.body if isinstance(node, ast.ClassDef)):
        for method in (node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name.startswith('test_')):
            names.append(prefix + cls.name + '.' + method.name)
    assert names and len(set(names)) == len(names)
    return sorted(names)


def verify_log(raw, expected, status, failure_count=0):
    observed = re.findall(r'^test_[^\n]* \((tools\.qa\.test_root_boot_safety93\.[^)]+)\) \.\.\. (\w+)\s*$', raw, re.M)
    assert observed == [(identity, status) for identity in expected], 'Retained shell log did not execute the exact identities'
    assert re.search(r'^Ran ' + str(len(expected)) + r' tests in [0-9.]+s$', raw, re.M)
    if failure_count:
        assert re.search(r'^FAILED \(failures=' + str(failure_count) + r'\)$', raw, re.M)
    else:
        assert re.search(r'^OK$', raw, re.M) and not re.search(r'^FAILED|^ERROR:|^FAIL:', raw, re.M)


def verify_candidate_control(folder, recorded, final, root):
    assert recorded['sourceIsPinnedBaseCommit'] is False
    assert recorded['beforeAssetSha256'] == CANDIDATE_BEFORE_ASSET_SHA256
    assert recorded['currentAssetSha256'] == {name: final[name] for name in CANDIDATE_BEFORE_ASSET_SHA256}
    assert recorded['sameHelperSha256BeforeAndAfter'] == digest(root / HOST_NEW_TEST)
    assert recorded['retainedOriginalObservationHelperSha256'] == CANDIDATE_BEFORE_HELPER_SHA256
    assert recorded['retainedObservedReportSha256'] == digest(root / CANDIDATE_BEFORE_FOLDER / 'report.json')
    assert recorded['retainedObservedLogSha256'] == digest(root / CANDIDATE_BEFORE_FOLDER / 'actual-tests.log')
    helper = root / CANDIDATE_BEFORE_HELPER
    assert digest(helper) == CANDIDATE_BEFORE_HELPER_SHA256
    assert (folder / 'candidate-before-fault-helper.py').read_bytes() == helper.read_bytes()
    assert (folder / 'candidate-control-same-fault-helper.py').read_bytes() == (root / HOST_NEW_TEST).read_bytes()
    for name, expected in CANDIDATE_BEFORE_ASSET_SHA256.items():
        path = root / CANDIDATE_BEFORE_ASSETS / Path(name).name
        assert digest(path) == expected
        assert (folder / ('candidate-before-' + Path(name).name)).read_bytes() == path.read_bytes()
    before = verify_candidate_before(recorded['before'])
    after = recorded['currentSameHelper']
    expected = before['expectedIdentities']
    assert set(expected) <= set(test_identities((root / HOST_NEW_TEST).read_text()))
    assert after['expectedIdentities'] == expected and after['tests'] == 4
    assert after['successful'] and not after['failures'] and not after['errors'] and not after['skipped']
    before_log = (folder / 'candidate-before-actual-tests.log').read_text()
    after_log = (folder / 'candidate-control-current-actual-tests.log').read_text()
    verify_log(before_log, expected, 'FAIL', 4)
    verify_log(after_log, expected, 'ok')
    for trace in before['failures'].values(): assert trace in before_log
    assert before['actualWallSeconds'] > 0 and after['actualWallSeconds'] > 0
    return {'candidateBeforeBehavioralFailures': 4, 'sameHelperCurrentActualShellTests': 4,
            'sourceIsPinnedBaseCommit': False, 'helperSha256': recorded['sameHelperSha256BeforeAndAfter'],
            'retainedOriginalObservationHelperSha256': CANDIDATE_BEFORE_HELPER_SHA256}


def verify(folder, commit, final, root=ROOT):
    folder, root = Path(folder), Path(root)
    report = json.loads((folder / 'report.json').read_text())
    assert report['result'] == 'PASS' and report['candidateCommit'] == commit and report['baseCommit'] == BASE_COMMIT
    assert report['currentCheckoutUnmodified'] is True
    actual = {str(path.relative_to(folder)): digest(path) for path in folder.rglob('*') if path.is_file() and path.name != 'report.json'}
    assert actual == report['rawFilesSha256']
    assert report['currentAssetSha256'] == {name: final[name] for name in (ROOT_SCRIPT, AUTOSTART_SCRIPT)}
    for name in (ROOT_SCRIPT, AUTOSTART_SCRIPT):
        original = committed_bytes(name, root)
        assert report['oldAssetSha256'][name] == hashlib.sha256(original).hexdigest()
        assert (folder / ('before-' + Path(name).name)).read_bytes() == original
    assert report['oldHelperSha256'] == digest(root / ARCHIVED_HELPER)
    assert report['currentHelperSha256'] == digest(root / HOST_NEW_TEST)
    before, current = report['before'], report['current']
    expected_old = test_identities((root / ARCHIVED_HELPER).read_text())
    expected_current = test_identities((root / HOST_NEW_TEST).read_text())
    assert before['expectedIdentities'] == expected_old and before['tests'] == len(expected_old) == 5
    assert current['expectedIdentities'] == expected_current and current['tests'] == len(expected_current)
    assert set(expected_old) <= set(expected_current)
    assert not before['successful'] and not before['errors'] and not before['skipped']
    assert set(before['failures']) == set(expected_old)
    assert current['successful'] is True and not current['failures'] and not current['errors'] and not current['skipped']
    before_log = (folder / 'before-actual-tests.log').read_text()
    current_log = (folder / 'current-actual-tests.log').read_text()
    verify_log(before_log, expected_old, 'FAIL', 5)
    verify_log(current_log, expected_current, 'ok')
    for identity, trace in before['failures'].items():
        assert trace in before_log
        method = identity.rsplit('.', 1)[1]
        assert method in EXPECTED_BEFORE and EXPECTED_BEFORE[method] in trace and 'AssertionError:' in trace
        assert not any(reason in trace for reason in ('SyntaxError:', 'ImportError:', 'CalledProcessError:', 'TimeoutExpired:'))
    assert before['actualWallSeconds'] > 0 and current['actualWallSeconds'] > 0
    candidate_control = verify_candidate_control(folder, report['candidateBeforeControl'], final, root)
    return {'result': 'PASS', 'beforeBehavioralFailures': 5, 'currentActualShellTests': len(expected_current),
            'currentDiffNativeControl': candidate_control,
            'beforeHelperSha256': report['oldHelperSha256'], 'currentHelperSha256': report['currentHelperSha256'],
            'beforeSourceCommit': BASE_COMMIT, 'candidateCommit': commit,
            'limits': 'Controlled actual shell execution on owned host fixtures; no phone Root/firewall or incident causality claim.'}
