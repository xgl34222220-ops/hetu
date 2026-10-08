#!/usr/bin/env python3
"""Reimplemented recovery seal over exact V20.92; not the missing final pre-incident seal.

Restored original artifacts stay immutable under docs/qa/v2093-recovery-20261008.
Current source and test execution are bound independently; no historical 93 receipt
is accepted as execution of this reconstructed candidate.
"""
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess

import continuity92_source_scope as prior

ROOT = prior.ROOT
BASE_COMMIT = 'cffeff08ee38c7220922bdb36de81b621c935daf'
ORIGINAL_TEST_HEAD = prior.ORIGINAL_TEST_HEAD
VERSION_CODE, VERSION_NAME = 2093, '0.12.20-v20-glass'
BUILD_FILE, ROOT_SCRIPT, AUTOSTART_SCRIPT = prior.BUILD_FILE, prior.ROOT_SCRIPT, prior.AUTOSTART_SCRIPT
PACKAGE, TEST_ROOT = prior.PACKAGE, prior.TEST_ROOT
EXTERNAL_PAYLOAD_FILES = prior.EXTERNAL_PAYLOAD_FILES
compilation_inputs, digest = prior.compilation_inputs, prior.digest
LAYER_FOLDER = 'updates/v2093-feedback-root-safety'
LAYER_FILE = ROOT / LAYER_FOLDER / 'inputs.json'
PRIOR_FOLDER = 'updates/v2092-rule-mode-continuity'
# The 2026-10-08 authorization covers all UI pages and existing runtime safety.
# Build dependencies, permissions, package, signing and other runtime binaries
# remain outside this source layer, and every actual delta is sealed individually.
AUTHORIZED_FILES = frozenset((
    BUILD_FILE, ROOT_SCRIPT, AUTOSTART_SCRIPT,
    PACKAGE + 'RootAutostart.java', PACKAGE + 'RootProxyManager.java',
    PACKAGE + 'ProxyNetworkMatchService.java', PACKAGE + 'ProxyLatencyHistory.kt',
    PACKAGE + 'ProxyRuntimeSettings.java', PACKAGE + 'ProxyControlEpoch.java',
    PACKAGE + 'StartupConfigViewer.kt', PACKAGE + 'ProxyDashboardRepository.kt',
    PACKAGE + 'MihomoControllerClient.java', PACKAGE + 'LatencyProbeBudget.java',
    PACKAGE + 'LatencyProbeOperation.kt',
    PACKAGE + 'ProxyComposeController.kt', PACKAGE + 'ProxyStatusBridge.kt',
))
REVISION_FILE = PACKAGE + 'ProxyRuntimeSettings.java'
REVISION_TEST = TEST_ROOT + 'java/io/github/xgl34222220/hetu/Runtime146ContractTest.kt'
# Old layers retain their original protected flags. This new layer records the
# explicitly authorized two Root asset deltas instead of claiming unchanged bytes.
PROTECTED_FLAGS = (
    'manifestAndPermissionsUnchanged', 'dependenciesUnchanged', 'signingUnchanged',
    'nativePayloadsUnchanged', 'allBaselineTestMethodsPreserved',
    'allPredecessor644TestMethodsPreserved', 'other21RuntimePayloadsPreserved',
    'runtimeRevisionTestChangedOnly153To154', 'old17BootTestBodiesPreserved',
    'old26RootHealthTestBodiesPreserved', 'oldRecoveryStressAssertionsAndBudgetsPreserved',
    'allOriginalRecoveryArtifactsPreserved',
)
HOST_FIXTURE = 'tools/qa/test_root_autostart.py'
HOST_NEW_TEST = 'tools/qa/test_root_safety93.py'
HEALTH_FIXTURE = 'tools/qa/test_root_health.py'
STRESS_FIXTURE = 'tools/qa/test_network_recovery_stress.py'
ADDITIONAL_HOST_FIXTURES = ('tools/qa/test_core_identity_dns.py', 'tools/qa/test_fake_ip_private_routing.py')
RECOVERY_COMMIT = '5af355d19fc41c3ae09902ca45fa1d70562ac402'
RECOVERY_FOLDER = 'docs/qa/v2093-recovery-20261008'


def validate_revision(before, after):
    original = 'static final int RUNTIME_REVISION = 153;'
    assert before.count(original) == 1
    assert after == before.replace(original, 'static final int RUNTIME_REVISION = 154;', 1)


def validate_revision_test(before, after):
    original = 'assertEquals(153, ProxyRuntimeSettings.RUNTIME_REVISION)'
    assert before.count(original) == 1
    assert after == before.replace(original, 'assertEquals(154, ProxyRuntimeSettings.RUNTIME_REVISION)', 1)


def host_test_bodies(text):
    parsed = ast.parse(text)
    lines = text.splitlines(keepends=True)
    return {node.name: ''.join(lines[node.lineno - 1:node.end_lineno]) for node in ast.walk(parsed)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith('test_')}


def host_process_timeouts(text):
    return [(node.name, ast.dump(keyword.value))
            for node in ast.walk(ast.parse(text)) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            for call in ast.walk(node) if isinstance(call, ast.Call)
            for keyword in call.keywords if keyword.arg == 'timeout']


def validate_host_fixture(root=ROOT):
    original = host_test_bodies(committed_bytes(HOST_FIXTURE, root).decode())
    assert len(original) == 17
    assert host_test_bodies((Path(root) / HOST_FIXTURE).read_text()) == original, 'Old17 boot test method bytes changed'
    old_health = committed_bytes(HEALTH_FIXTURE, root).decode()
    current_health = (Path(root) / HEALTH_FIXTURE).read_text()
    health = host_test_bodies(old_health)
    assert len(health) == 26
    assert host_test_bodies(current_health) == health, 'Old26 Root health test method bytes changed'
    assert host_process_timeouts(old_health) == host_process_timeouts(current_health), 'Old Root health process timeout budgets changed'
    google = 'tools/qa/test_google_firewall.py'
    assert (Path(root) / google).read_bytes() == committed_bytes(google, root), 'Old17 Google firewall fixtures/assertions changed'
    for name in ADDITIONAL_HOST_FIXTURES:
        original = committed_bytes(name, root).decode()
        actual = (Path(root) / name).read_text()
        old_methods, new_methods = host_test_bodies(original), host_test_bodies(actual)
        assert all(new_methods.get(method) == body for method, body in old_methods.items()), 'Prior host assertions changed: ' + name
        old_timeouts = host_process_timeouts(original)
        original_names = {node.name for node in ast.walk(ast.parse(original)) if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        assert [item for item in host_process_timeouts(actual) if item[0] in original_names] == old_timeouts, 'Prior host timeout budget changed: ' + name


def validate_stress_fixture(root=ROOT):
    before = committed_bytes(STRESS_FIXTURE, root).decode()
    current = (Path(root) / STRESS_FIXTURE).read_text()
    marker = '  final Map<String,Object> values=new HashMap<>();\n'
    assert before.count(marker) == 1
    compatible = before.replace(marker, marker + '  boolean contains(String k){return values.containsKey(k);}\n', 1)
    assert current in (before, compatible), 'Recovery stress fixture altered beyond the Android contains API shim'
    assert host_test_bodies(current) == host_test_bodies(before)
    assert host_process_timeouts(current) == host_process_timeouts(before)


def host_fixture_deltas(root=ROOT):
    return {name: {'before': hashlib.sha256(committed_bytes(name, root)).hexdigest(),
                   'after': digest(Path(root) / name)} for name in (HOST_FIXTURE, HEALTH_FIXTURE, STRESS_FIXTURE, *ADDITIONAL_HOST_FIXTURES)}


def recovered_artifacts(root=ROOT):
    names = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', RECOVERY_COMMIT, '--', RECOVERY_FOLDER], cwd=root).decode().splitlines()
    expected = {name: hashlib.sha256(subprocess.check_output(['git', 'show', RECOVERY_COMMIT + ':' + name], cwd=root)).hexdigest() for name in names}
    assert expected and all(digest(Path(root) / name) == value for name, value in expected.items()), 'Original recovery bytes changed'
    return expected


def validate_baseline_test_sources(root=ROOT):
    root = Path(root)
    predecessor, previous = predecessor_layer(root)
    # Preserve every pre-existing Android test file, including unselected old
    # suites. The exact runtime revision contract is the only explicit exception.
    for name in sorted(name for name in previous if name.startswith(TEST_ROOT)):
        before = committed_bytes(name, root)
        current = (root / name).read_bytes()
        if name == REVISION_TEST:
            validate_revision_test(before.decode(), current.decode())
        else:
            assert current == before, 'Retained 644 test source/fixture/assertion changed: ' + name
    for name in ('CompactHomeDashboardTest.kt', 'Ui92IntegrationTest.kt', 'NodeSelectionContinuityTest.kt'):
        path = TEST_ROOT + 'java/io/github/xgl34222220/hetu/' + name
        assert (root / path).read_bytes() == committed_bytes(path, root), 'Supplemental36 source changed: ' + path


def evidence_sources(root=ROOT):
    root = Path(root)
    names = {str(path.relative_to(root)) for pattern in ('*continuity93*.py', '*feedback*93*.py')
             for path in (root / '.github/scripts').glob(pattern)}
    names.update(('.github/workflows/v2093-build.yml', '.github/scripts/prepare_new_ui_source.py',
                  '.github/workflows/v2092-build.yml', HOST_FIXTURE, HEALTH_FIXTURE, STRESS_FIXTURE, HOST_NEW_TEST))
    names.update(ADDITIONAL_HOST_FIXTURES)
    names.add('.github/scripts/transport_ui93_pngs.py')
    names.add('.github/scripts/transport_continuity93_failures.py')
    names.update(str(p.relative_to(root)) for p in (root / 'tools/qa').glob('*93*.py'))
    return {name: digest(root / name) for name in sorted(names)}


def other_payloads(previous, root=ROOT):
    manifest = json.loads((Path(root) / 'UI92_RUNTIME146_INPUTS.json').read_text())['original146_payload']
    names = {'android-app/app/src/main/jniLibs/' + name.removeprefix('lib/') if name.startswith('lib/')
             else 'android-app/app/src/main/' + name for name in manifest}
    names.add(AUTOSTART_SCRIPT)
    assert len(names) == 23 and {ROOT_SCRIPT, AUTOSTART_SCRIPT} <= names
    return {name: previous[name] for name in sorted(names - {ROOT_SCRIPT, AUTOSTART_SCRIPT})}


def committed_bytes(name, root=ROOT):
    return subprocess.check_output(['git', 'show', BASE_COMMIT + ':' + name], cwd=root)


def predecessor_layer(root=ROOT):
    root = Path(root)
    path = root / PRIOR_FOLDER / 'inputs.json'
    layer = json.loads(path.read_text())
    assert path.read_bytes() == committed_bytes(PRIOR_FOLDER + '/inputs.json', root), 'Pinned V20.92 inputs changed'
    patch = path.with_name('runtime.patch')
    assert patch.read_bytes() == committed_bytes(PRIOR_FOLDER + '/runtime.patch', root), 'Pinned V20.92 patch changed'
    previous = prior.previous_files(root)
    prior.validate_layer(layer, previous, root)
    return layer, {**previous, **layer['changedOrAddedFiles']}


def previous_files(root=ROOT):
    return predecessor_layer(root)[1]


def historical_files(root=ROOT):
    root = Path(root)
    names = set(prior.historical_files(root))
    names.update((PRIOR_FOLDER + '/inputs.json', PRIOR_FOLDER + '/runtime.patch'))
    # Bind the prior inputs, source/evidence validators and all retained failure
    # records to their actual pinned predecessor bytes. Workflow ownership is
    # checked separately because only its trigger intentionally changes.
    names.update(subprocess.check_output([
        'git', 'ls-tree', '-r', '--name-only', BASE_COMMIT, '--', 'docs/qa'
    ], cwd=root).decode().splitlines())
    names.update(str(path.relative_to(root)) for path in (root / '.github/scripts').glob('*continuity92*.py'))
    return {name: hashlib.sha256(committed_bytes(name, root)).hexdigest() for name in sorted(names)}


def allowed_change(name):
    return (name in AUTHORIZED_FILES or name.startswith(TEST_ROOT) or
            any(name.startswith(PACKAGE + folder) for folder in ('app/', 'home/', 'panel/', 'tools/', 'ui/')))


def validate_version(before, after):
    expected = before.replace('versionCode = 2092', 'versionCode = 2093', 1)
    expected = expected.replace('versionName = "0.12.19-v20-glass"', 'versionName = "0.12.20-v20-glass"', 1)
    assert before.count('versionCode = 2092') == before.count('versionName = "0.12.19-v20-glass"') == 1
    assert after == expected, 'V20.93 changed Gradle inputs beyond the two version values'


def selected_tests(root=ROOT, extra_sources=()):
    predecessor, _ = predecessor_layer(root)
    return prior.selected_tests(root, extra_sources=[
        *extra_sources, *(entry['source'] for entry in predecessor['finalUnitTestMethods'].values())
    ])


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    predecessor, expected_previous = predecessor_layer(root)
    assert previous == expected_previous, 'V20.93 must follow every byte-exact V20.92 effective input'
    assert layer['schema'] == 1 and layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == subprocess.check_output(['git', 'rev-parse', BASE_COMMIT + ':android-app'], cwd=root).decode().strip()
    assert layer['originalTestHead'] == ORIGINAL_TEST_HEAD and layer['versionCode'] == VERSION_CODE and layer['versionName'] == VERSION_NAME
    assert layer['previousInputsSha256'] == digest(root / PRIOR_FOLDER / 'inputs.json')
    assert layer['previousPatchSha256'] == digest(root / PRIOR_FOLDER / 'runtime.patch')
    assert layer['historicalFilesSha256'] == historical_files(root)
    assert all(digest(root / name) == value for name, value in layer['historicalFilesSha256'].items()), 'Historical source/evidence bytes changed'
    changes = layer['changedOrAddedFiles']
    assert changes and BUILD_FILE in changes and all(allowed_change(name) for name in changes)
    assert layer['authorizedFiles'] == sorted(changes)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in changes)
    assert re.fullmatch(r'[0-9a-f]{64}', layer['patchSha256'])
    assert all(re.fullmatch(r'[0-9a-f]{64}', value) and value != previous.get(name) for name, value in changes.items())
    assert layer['frozenFiles'] == {name: value for name, value in previous.items() if name not in changes}
    assert layer['externalPayloadFiles'] == {name: previous[name] for name in sorted(EXTERNAL_PAYLOAD_FILES)}
    assert layer['runtimePayloadCount'] == 23
    assert layer['other21RuntimePayloadFiles'] == other_payloads(previous, root)
    assert not set(layer['other21RuntimePayloadFiles']) & set(changes)
    assert layer['rootScriptSha256'] == changes.get(ROOT_SCRIPT, previous[ROOT_SCRIPT]) and layer['autostartScriptSha256'] == changes.get(AUTOSTART_SCRIPT, previous[AUTOSTART_SCRIPT])
    assert layer['authorizedRootPayloadDeltas'] == {name: {'before': previous[name], 'after': changes.get(name, previous[name])}
                                                 for name in (ROOT_SCRIPT, AUTOSTART_SCRIPT)}
    assert layer['runtimeRevision'] == 154 and layer['predecessorRuntimeRevision'] == 153
    assert layer['evidenceSourcesSha256'] == evidence_sources(root)
    validate_host_fixture(root)
    validate_stress_fixture(root)
    validate_baseline_test_sources(root)
    assert layer['recoveredArtifactHashes'] == recovered_artifacts(root)
    assert layer['recoveryPipelineProvenance'] == 'reimplemented after partial source recovery; historical final pipeline unavailable'
    assert layer['authorizedHostFixtureDeltas'] == host_fixture_deltas(root)
    assert all(layer[name] is True for name in PROTECTED_FLAGS)
    baseline, final = layer['baselineUnitTestMethods'], layer['finalUnitTestMethods']
    assert baseline == predecessor['finalUnitTestMethods']
    assert len(baseline) == layer['baselineTestXmlFiles'] == 67
    assert sum(len(item['methods']) for item in baseline.values()) == layer['baselineUnitTests'] == 644
    assert set(baseline) <= set(final)
    for cls, entry in baseline.items():
        assert final[cls] == entry, 'V20.93 altered a retained predecessor test identity: ' + cls
    for cls, entry in final.items():
        assert entry['source'].startswith(TEST_ROOT) and entry['methods'] and len(set(entry['methods'])) == len(entry['methods'])
        if cls not in baseline:
            assert allowed_change(entry['source']), 'Unapproved new test source'
    assert layer['expectedUnitTests'] == sum(len(item['methods']) for item in final.values()) > 644
    assert layer['expectedTestXmlFiles'] == len(final) > 67


def validate_checkout(root=ROOT, layer=None, allow_external_missing=True):
    root = Path(root)
    layer = json.loads((root / LAYER_FOLDER / 'inputs.json').read_text()) if layer is None else layer
    previous = previous_files(root)
    validate_layer(layer, previous, root)
    assert digest(root / LAYER_FOLDER / 'runtime.patch') == layer['patchSha256'], 'Current V20.93 patch bytes changed'
    final = {**previous, **layer['changedOrAddedFiles']}
    actual = compilation_inputs(root)
    missing = set(final) - actual
    assert missing <= (EXTERNAL_PAYLOAD_FILES if allow_external_missing else set())
    assert actual == set(final) - missing, 'Unrecorded V20.93 compilation input'
    assert all(digest(root / name) == final[name] for name in actual), 'Checkout differs from the V20.93 recorded source'
    assert selected_tests(root, extra_sources=[item['source'] for item in layer['finalUnitTestMethods'].values()]) == layer['finalUnitTestMethods']
    validate_version(committed_bytes(BUILD_FILE, root).decode(), (root / BUILD_FILE).read_text())
    validate_revision(committed_bytes(REVISION_FILE, root).decode(), (root / REVISION_FILE).read_text())
    validate_revision_test(committed_bytes(REVISION_TEST, root).decode(), (root / REVISION_TEST).read_text())
    return final
