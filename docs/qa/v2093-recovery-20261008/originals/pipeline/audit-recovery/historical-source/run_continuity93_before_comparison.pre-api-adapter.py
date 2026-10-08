#!/usr/bin/env python3
"""Compile current compatible tests against bounded, byte-exact pinned source substitutions."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

from continuity93_source_scope import (ROOT, BASE_COMMIT, PACKAGE, TEST_ROOT,
    committed_bytes, digest, validate_checkout, compilation_inputs)

UI_CLASS = 'io.github.xgl34222220.hetu.PanelLatencyTouchFeedbackTest'
READINESS_CLASS = 'io.github.xgl34222220.hetu.FirstScreenReadinessFeedbackTest'
NODE_CLASS = 'io.github.xgl34222220.hetu.PanelProbeFailureFeedbackTest'
STARTUP_CLASS = 'io.github.xgl34222220.hetu.StartupConfigViewerFeedbackTest'
MOTION_CLASS = 'io.github.xgl34222220.hetu.PanelDelayMotionFeedbackTest'
APP_BOOT_CLASS = 'io.github.xgl34222220.hetu.BootRecoverySafety93Test'
ROOT_INTENT_CLASS = 'io.github.xgl34222220.hetu.RootStartIntentSafety93Test'
APP_BOOT_FILES = (PACKAGE + 'ProxyNetworkMatchService.java',)
STOP_MANAGER_FILES = (PACKAGE + 'RootProxyManager.java',)
VARIANTS = ('ui-and-startup', 'vm-and-alias', 'motion-only', 'snapshot-alias-order',
            'app-boot-safety', 'root-stop-result', 'direct-node-order')
UI_FILES = (PACKAGE + 'panel/PanelGroupsTab.kt', PACKAGE + 'panel/PanelComponents.kt')
VIEWER_FILES = (PACKAGE + 'app/SettingsStartupConfigScreen.kt', PACKAGE + 'tools/ToolsDiagScreen.kt',
                PACKAGE + 'tools/ToolsFeatureAdapter.kt')
VIEWER_HELPER = PACKAGE + 'StartupConfigViewer.kt'
ALIAS_CONTROL_SOURCE = 'docs/qa/20261008-feedback93-alias-pre-repair.kt'
ALIAS_CONTROL_SHA256 = '6c02bf451f554a16820ab9dc8f96ea4e23520952f892c6c385b0c624061cde1e'
ALIAS_ORDER_METHOD = 'olderControllerSnapshotCannotRevokeALaterAliasMeasurement'
VM_FILES = (PACKAGE + 'app/HetuViewModel.kt', PACKAGE + 'panel/PanelModels.kt', PACKAGE + 'panel/PanelAdapter.kt')
DIRECT_NODE_FILES = (VM_FILES[0], PACKAGE + 'ProxyLatencyHistory.kt')
DIRECT_NODE_CONTROL_SOURCES = {
    DIRECT_NODE_FILES[0]: ('docs/qa/20261008-feedback93-direct-node-pre-repair-vm.kt',
                         'b68897d99e9e59d353540304ebcc11a1b16a83bf8ccec5272104cc66336f575e'),
    DIRECT_NODE_FILES[1]: ('docs/qa/20261008-feedback93-direct-node-pre-repair-latencyHistory.kt',
                         '13ca470d0664088f3e16e3d135d47f6b092c90be6e8c91b1d198550457d04546'),
}
DIRECT_NODE_METHOD = 'sameMillisecondLeafProbeSurvivesOldAndRepeatedCoreHistory'
DIRECT_NODE_FAILURES = {DIRECT_NODE_METHOD:
    ('An older same-millisecond snapshot must not overwrite a later direct leaf probe',)}
STARTUP_METHODS = (
    'realSettingsEntryCreatesThemedActivityAndPreservesShortCopyExport',
    'realSettingsEntryReadsLargeValidYamlAndPreservesAllBytes',
    'realSettingsEntryReadsLongUnicodeLineAndPreservesEof',
    'realSettingsEntryShowsMissingStartupWithoutRegeneration',
    'realSettingsEntryShowsReadIOExceptionWithoutFalseSuccess',
    'actualToolsDiagClickReadsLargeStartupAndCopiesOriginal',
)
CLIPBOARD_FAILURES = {
    'actualSettingsClipboardRejectionAndMissingServiceStayVisibleAndKeepAllBytes':
        ('Settings copy click must not throw when the clipboard rejects the request',),
    'actualToolsClipboardRejectionAndMissingServiceNeverClaimSuccess':
        ('Tools copy click must not throw when the clipboard rejects the request',),
}
UI_FAILURES = {
    'externalLatencyOwnsBothColumnLayoutsAndNestedGroupBusyTouches': ('External latency must test its group instead of expanding it',),
    'compactNodeNameAndDelayKeepIndependentSpaceAndCallbacks': ('Compact node name must have space before its independent latency row',),
    'largeFontCurrentKeepsFullWidthAndSavedColumns': ('Large-font current must be readable at 320dp', 'Expected width to be 292.0.dp'),
    'heldGroupProbeStaysLoadingThroughActualBackAndDockReturn': ('External latency must synchronously start the group loading state',),
    'groupTransportFailureReleasesBusyRetainsOldMeasurementAndShowsFailure': ('External latency must start its failure wave without expanding',),
    'nestedGroupResultIsVisibleOnlyForItsMeasuredSelectionChain': ('Nested external latency must start its group wave',),
}
READINESS_FAILURES = {method: ('The complete controller snapshot must be visible before optional I/O finishes',)
    for method in ('completeControllerStateIsInteractiveWhileInspectorIsHeld',
                   'completeControllerStateIsInteractiveWhileProviderDetailsAreHeld',
                   'completeControllerStateIsInteractiveWhileVersionIsHeld',
                   'lateOptionalProviderReadCannotOverwriteTheReplacementController',
                   'backgroundCancellationRejectsTheHeldOptionalVersionCompletion')}
READINESS_FAILURES[ALIAS_ORDER_METHOD] = ('A new alias response must be visible before the older snapshot finishes',)
NODE_FAILURES = {'heldNodeTransportFailureRetainsTheMeasurementAndEmitsExplicitFailure': ('A current failed node probe must emit one explicit failure',)}
ALIAS_METHOD = 'nestedGroupResultIsVisibleOnlyForItsMeasuredSelectionChain'
ALIAS_FAILURES = {ALIAS_METHOD: ('Nested group response must render its bound alias delay',)}
MOTION_FAILURES = {'actualProbeAndHistoryTransitionsKeepOnePhysicalOwnerAndRespectMotionOff':
                   ('Motion-enabled testing must retain the outgoing measured caption',)}
ORDER_FAILURES = {ALIAS_ORDER_METHOD: ('An older controller snapshot must not revoke a later alias measurement',)}
# Existing service/manager entry points remain callable with the precise old
# production source. These failures concern behavior, never a revision constant.
APP_BOOT_FAILURES = {
    'confirmedDeadRecoveryBudgetSurvivesObserverRecreation': ('observer recreation must not replenish automatic starts',),
    'livingCoreRequiresBothNetworkIntegrityAndDataPlaneBeforeBootSuccess': ('a live PID cannot prove boot restore success',),
    'queuedBootRetryCannotResurrectAnExplicitlyStoppedBoot': ('native stop marker must outlive queued App callbacks',),
    'nativeRestoreOwnsTheOnlyTaskAndAppWaitingHasATotalDeadline': ('periodic guardian must not start beside native restoration',),
}
STOP_MANAGER_FAILURES = {
    'actualRootStopDoesNotClaimCleanupWhenScriptIsMissingOrCleanupFails': ('missing Root script cannot prove cleanup',),
}
ROOT_INTENT_FAILURES = {
    'stopInsideActualWantedNetworkDecisionCannotRepublishWanted':
        ('A Stop inside the real wanted network decision must remain revoked',),
    'changedNetworkAfterActualProbeCannotPublishAlreadyRunningSuccess':
        ('An obsolete network decision cannot publish runtime success',),
}


def production_sources(root, variant):
    if variant == 'direct-node-order':
        result = {}
        for name, (archive, expected) in DIRECT_NODE_CONTROL_SOURCES.items():
            path = Path(root) / archive
            assert digest(path) == expected, 'Direct-node pre-repair candidate source control changed'
            result[name] = path.read_bytes()
        return result
    if variant == 'snapshot-alias-order':
        path = Path(root) / ALIAS_CONTROL_SOURCE
        assert digest(path) == ALIAS_CONTROL_SHA256, 'Pre-repair candidate source control changed'
        return {VM_FILES[0]: path.read_bytes()}
    if variant == 'app-boot-safety':
        return {name: committed_bytes(name, root) for name in APP_BOOT_FILES}
    if variant == 'root-stop-result':
        return {name: committed_bytes(name, root) for name in STOP_MANAGER_FILES}
    assert variant in ('ui-and-startup', 'motion-only', 'vm-and-alias')
    changed = UI_FILES + VIEWER_FILES if variant == 'ui-and-startup' else (UI_FILES[1],) if variant == 'motion-only' else VM_FILES
    return {name: committed_bytes(name, root) for name in changed}


def suite_results(path, cls, expected_methods, expected_failures=None, observed_methods=()):
    """No compilation error, skip, missing method or unrelated runtime error is a defect proof."""
    suite = ET.parse(path).getroot()
    cases = list(suite.iter('testcase'))
    assert suite.get('name') == cls
    assert len(cases) == int(suite.get('tests', 0)) == len(expected_methods)
    assert int(suite.get('skipped', 0)) == 0 and Counter(c.get('name') for c in cases) == Counter(expected_methods)
    observations, failures, errors = {}, 0, 0
    for case in cases:
        method = case.get('name')
        assert case.get('classname') == cls and case.find('skipped') is None
        problems = [*case.findall('failure'), *case.findall('error')]
        assert len(problems) <= 1
        if expected_failures is not None and method in expected_failures:
            assert len(problems) == 1 and problems[0].tag == 'failure'
            problem = problems[0]
            assert problem.get('type') == 'java.lang.AssertionError', ('Unexpected defect type', method, problem.attrib)
            assert any(reason in problem.get('message', '') for reason in expected_failures[method]), ('Unexpected defect reason', method, problem.attrib)
        elif expected_failures is not None and method not in observed_methods:
            assert not problems, ('Before control must pass', method, [p.attrib for p in problems])
        if problems:
            problem = problems[0]
            failures += problem.tag == 'failure'
            errors += problem.tag == 'error'
            observations[method] = {'result': 'FAIL' if problem.tag == 'failure' else 'ERROR', 'type': problem.get('type'),
                                    'message': problem.get('message'), 'stack': problem.text or ''}
        else:
            observations[method] = {'result': 'PASS'}
    assert int(suite.get('failures', 0)) == failures and int(suite.get('errors', 0)) == errors
    return {'suite': cls, 'tests': len(cases), 'failures': failures, 'errors': errors, 'skipped': 0,
            'testcases': observations, 'xmlSha256': digest(path),
            'interpretation': 'Observed compatible startup baseline plus any independently specified failures; no forced phone crash claim.' if expected_failures is None or observed_methods else 'Exact specified behavioral assertions; compile failures and unrelated errors reject.'}


def specifications(layer, variant):
    final = layer['finalUnitTestMethods']
    if variant == 'ui-and-startup':
        methods = (*STARTUP_METHODS, *CLIPBOARD_FAILURES)
        assert set(methods) <= set(final[STARTUP_CLASS]['methods'])
        return [(UI_CLASS, final[UI_CLASS]['methods'], UI_FAILURES), (STARTUP_CLASS, methods, CLIPBOARD_FAILURES)]
    if variant == 'direct-node-order':
        assert DIRECT_NODE_METHOD in final[READINESS_CLASS]['methods']
        return [(READINESS_CLASS, [DIRECT_NODE_METHOD], DIRECT_NODE_FAILURES)]
    if variant == 'motion-only':
        return [(MOTION_CLASS, final[MOTION_CLASS]['methods'], MOTION_FAILURES)]
    if variant == 'snapshot-alias-order':
        return [(READINESS_CLASS, [ALIAS_ORDER_METHOD], ORDER_FAILURES)]
    if variant == 'app-boot-safety':
        assert len(APP_BOOT_FAILURES) == 4 and len(STOP_MANAGER_FAILURES) == 1, 'App source owner before contract has not been sealed'
        assert set(APP_BOOT_FAILURES) | set(STOP_MANAGER_FAILURES) == set(final[APP_BOOT_CLASS]['methods'])
        return [(APP_BOOT_CLASS, final[APP_BOOT_CLASS]['methods'], APP_BOOT_FAILURES)]
    if variant == 'root-stop-result':
        assert len(STOP_MANAGER_FAILURES) == 1, 'Root stop result before contract has not been sealed'
        assert set(ROOT_INTENT_FAILURES) <= set(final[ROOT_INTENT_CLASS]['methods'])
        return [(APP_BOOT_CLASS, list(STOP_MANAGER_FAILURES), STOP_MANAGER_FAILURES),
                (ROOT_INTENT_CLASS, list(ROOT_INTENT_FAILURES), ROOT_INTENT_FAILURES)]
    assert variant == 'vm-and-alias'
    return [(READINESS_CLASS, [method for method in final[READINESS_CLASS]['methods'] if method != DIRECT_NODE_METHOD], READINESS_FAILURES),
            (NODE_CLASS, final[NODE_CLASS]['methods'], NODE_FAILURES),
            (UI_CLASS, [ALIAS_METHOD], ALIAS_FAILURES)]


def verify_variant(folder, layer, variant):
    folder = Path(folder)
    expected = specifications(layer, variant)
    assert {p.name for p in (folder / 'test-results').glob('TEST-*.xml')} == {'TEST-' + cls + '.xml' for cls, _, _ in expected}
    observed = {cls: suite_results(folder / 'test-results' / ('TEST-' + cls + '.xml'), cls, methods, failures,
                                  STARTUP_METHODS if variant == 'ui-and-startup' and cls == STARTUP_CLASS else ())
                for cls, methods, failures in expected}
    images = ('external-2-columns', 'compact-readable-393dp', 'large-readable-320dp',
              'held-group-loading', 'failed-group-loading', 'nested-held-loading') if variant == 'ui-and-startup' else (
                  ('testing-transition',) if variant == 'motion-only' else () if variant in ('snapshot-alias-order', 'app-boot-safety', 'root-stop-result', 'direct-node-order') else ('nested-bound-completed',))
    for name in images:
        path = folder / ('panel-motion' if variant == 'motion-only' else 'panel-feedback') / (name + '.png')
        assert path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Missing actual before interaction image: ' + name
    if variant in ('vm-and-alias', 'snapshot-alias-order'):
        names = ('alias-new-wave-before-old-snapshot',) if variant == 'vm-and-alias' else (
            'alias-new-wave-before-old-snapshot', 'alias-after-old-snapshot')
        for name in names:
            path = folder / 'first-screen-readiness' / (name + '.png')
            assert path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'), 'Missing actual before snapshot ordering image: ' + name
    return observed


def run_variant(root, layer, final, output, variant):
    output.mkdir()
    sources = production_sources(root, variant)
    changed = tuple(sources)
    removed = (VIEWER_HELPER,) if variant == 'ui-and-startup' else ()
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in sources.items()}
    for name, data in sources.items():
        (output / ('before-' + Path(name).name)).write_bytes(data)
    report = {'result': 'FAIL', 'baseCommit': BASE_COMMIT, 'variant': variant,
              'observedRepositoryCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
              'changedProductionSourcesOnly': list(changed), 'removedNewProductionHelpersOnly': list(removed),
              'beforeSourceSha256': hashes, 'currentSourceSha256': {name: final[name] for name in changed + removed},
              'currentTestSourcesSha256': {entry['source']: final[entry['source']] for cls, _, _ in specifications(layer, variant)
                                          for entry in (layer['finalUnitTestMethods'][cls],)},
              'currentCheckoutUnmodified': False}
    report['retainedCurrentRuntimeRevision'] = 154
    report['fullPinnedPredecessorView'] = False
    report['sourceSubstitutionBoundary'] = 'Only listed production sources are from cff; all other candidate sources, including Root154, remain current.'
    if variant == 'snapshot-alias-order':
        report['sourceSubstitutionBoundary'] = 'Only VM is the retained pre-repair candidate control; all other current candidate sources remain unchanged. This source control was not a committed cff production file.'
        report['preRepairCandidateSourceControl'] = {'path': ALIAS_CONTROL_SOURCE, 'sha256': ALIAS_CONTROL_SHA256,
                                                   'sourceIsPinnedBaseCommit': False}
    if variant == 'direct-node-order':
        report['sourceSubstitutionBoundary'] = 'VM and latency history are retained exact uncommitted candidate controls before direct-leaf provenance repair; all other current candidate sources remain unchanged.'
        report['preRepairCandidateSourceControl'] = {name: {'path': archive, 'sha256': expected,
                                                   'sourceIsPinnedBaseCommit': False}
                                                   for name, (archive, expected) in DIRECT_NODE_CONTROL_SOURCES.items()}
    started = time.monotonic()
    try:
        with tempfile.TemporaryDirectory(prefix='hetu-feedback93-before-', dir=os.environ.get('RUNNER_TEMP')) as directory:
            fixture = Path(directory)
            shutil.copytree(root / 'android-app', fixture / 'android-app', ignore=shutil.ignore_patterns('build', '.gradle'))
            for name, data in sources.items():
                (fixture / name).write_bytes(data)
            for name in removed:
                (fixture / name).unlink()
            actual = compilation_inputs(fixture)
            assert actual == set(final) - set(removed)
            assert all(digest(fixture / name) == hashes.get(name, final[name]) for name in actual), 'Before altered an unapproved input'
            command = ['gradle', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=UTF-8', ':app:testDebugUnitTest']
            for cls, methods, failures in specifications(layer, variant):
                for method in methods:
                    command.extend(('--tests', cls + '.' + method))
            command.append('--stacktrace')
            with (output / 'gradle-before.log').open('w') as log:
                result = subprocess.run(command, cwd=fixture / 'android-app', stdout=log, stderr=subprocess.STDOUT)
            report['gradleExit'] = result.returncode
            # Absence of real JUnit XML is an execution failure, never a reproduced defect.
            shutil.copytree(fixture / 'android-app/app/build/test-results/testDebugUnitTest', output / 'test-results')
            for folder in ('panel-feedback', 'startup-config-feedback', 'panel-motion', 'first-screen-readiness'):
                source = fixture / 'android-app/app/build/outputs' / folder
                if source.is_dir():
                    shutil.copytree(source, output / folder)
            report['suites'] = verify_variant(output, layer, variant)
            assert result.returncode != 0, 'Specified behavioral regressions unexpectedly all passed'
            report['rawFilesSha256'] = {str(p.relative_to(output)): digest(p) for p in output.rglob('*') if p.is_file()}
            report['currentCheckoutUnmodified'] = validate_checkout(root, allow_external_missing=False) == final
            assert report['currentCheckoutUnmodified']
            report['result'] = 'EXPECTED_BEHAVIORAL_REGRESSIONS_AND_BASELINE_OBSERVED'
    finally:
        report['processWallSeconds'] = time.monotonic() - started
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def run(root=ROOT, output=None):
    root = Path(root)
    output = root / 'out/verification/before-comparison93' if output is None else Path(output)
    assert not output.exists(), 'Preserve prior feedback before evidence'
    final = validate_checkout(root, allow_external_missing=False)
    from continuity93_source_scope import LAYER_FOLDER
    layer = json.loads((root / LAYER_FOLDER / 'inputs.json').read_text())
    output.mkdir(parents=True)
    variants = {variant: run_variant(root, layer, final, output / variant, variant)
                for variant in VARIANTS}
    report = {'result': 'PASS', 'candidateCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
              'baseCommit': BASE_COMMIT, 'variants': variants, 'currentCheckoutUnmodified': validate_checkout(root, allow_external_missing=False) == final,
              'limits': 'Current compatible Robolectric tests with explicitly bounded pinned source substitutions. Startup phone crash cause may remain unreproduced. Host Root faults, current JVM results and actual AOSP routes are independently verified.'}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    print(json.dumps(run(output=parser.parse_args().output)))
