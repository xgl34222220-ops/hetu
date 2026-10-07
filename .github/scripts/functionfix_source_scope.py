#!/usr/bin/env python3
"""Bounded function fixes after all six immutable presentation/intake layers."""
import hashlib
import json
from pathlib import Path
import re

from tools_intake_source_scope import EXTERNAL_PAYLOAD_FILES, compilation_inputs, digest
from pdf85_final_source_scope import HISTORICAL as EARLIER_HISTORY

ROOT = Path(__file__).resolve().parents[2]
BASE_COMMIT = '1dbe40cc4e312c230524092dfe32a2c6cfb5f25d'
BASE_ANDROID_APP_TREE = 'c5f2c1a9e070fe4c6e1940b5d6729ccc4810fdaa'
VERSION_CODE, VERSION_NAME = 2086, '0.12.16-v20-fix'
BASE_TESTS, BASE_TEST_XML_FILES = 487, 51
PREVIOUS_INPUTS_SHA256 = '2707c22d2d27110e204777b04eb97223769ca6721c214ea58cbb763c0888c7ce'
PREVIOUS_PATCH_SHA256 = '564581886f89da03b19602a67047016051e9b39abf04047f4b51e00d6b5d9066'
HISTORICAL = {**EARLIER_HISTORY,
    'updates/v2085-pdf-final-geometry/inputs.json': PREVIOUS_INPUTS_SHA256,
    'updates/v2085-pdf-final-geometry/runtime.patch': PREVIOUS_PATCH_SHA256,
    '.github/scripts/pdf85_final_transforms.json': 'fe4b7fbfb049ba2bf1ec2dca129bdd7c69ace73d2069dcc1e3d01b6a9479626d',
}
PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST_PACKAGE = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
BUILD_FILE = 'android-app/app/build.gradle.kts'
REVISION_FILE = PACKAGE + 'ProxyRuntimeSettings.java'
CONTRACT_FILE = TEST_PACKAGE + 'Runtime146ContractTest.kt'
ROOT_SCRIPT = 'android-app/app/src/main/assets/hetu-root.sh'
AUTOSTART_SCRIPT = 'android-app/app/src/main/assets/hetu-autostart.sh'
AUTOSTART_SHA256 = '3a1215f4e9093ae2298803020d697da0c14c35ee5cf28f84f2a81608093783f9'
RUNTIME_INPUTS_FILE = 'UI92_RUNTIME146_INPUTS.json'
RUNTIME_INPUTS_SHA256 = 'c14b5eac12dd01471580944cdee56f183f226f92d6495e7c68ea9473c1cdfdbf'
NEW_HELPER = PACKAGE + 'RootFakeIpRanges.java'
NEW_TEST_HELPER = TEST_PACKAGE + 'PanelRequestOwnershipShadows.java'
PRODUCTION_FILES = frozenset(PACKAGE + name for name in (
    'panel/PanelScreen.kt', 'RootProxyManager.java', 'ProxyRuntimeSettings.java',
    'ProxyDashboardRepository.kt', 'app/HetuViewModel.kt', 'ProxyConfigLibrary.java',
)) | {ROOT_SCRIPT, NEW_HELPER}
TEST_PLAN_PATH = Path(__file__).with_name('functionfix_test_plan.json')
PROTECTED_FLAGS = ('allUnlistedInputsFrozen', 'allOriginal487TestCasesPreserved',
                   'manifestAndPermissionsUnchanged', 'dependenciesUnchanged',
                   'signingUnchanged', 'nativePayloadsUnchanged', 'startAndAutostartProtocolPreserved')


def test_plan():
    plan = json.loads(TEST_PLAN_PATH.read_text())
    assert plan and 'io.github.xgl34222220.hetu.PanelExpansionAnchorTest' in plan
    for cls, item in plan.items():
        assert re.fullmatch(r'io\.github\.xgl34222220\.hetu\.[A-Za-z][A-Za-z0-9_]*', cls)
        assert set(item) == {'source', 'tests'}
        assert type(item['tests']) is int and item['tests'] > 0
        assert item['source'] in (TEST_PACKAGE + cls.rsplit('.', 1)[1] + '.kt',
                                  TEST_PACKAGE + cls.rsplit('.', 1)[1] + '.java')
    assert plan['io.github.xgl34222220.hetu.PanelExpansionAnchorTest']['tests'] == 4
    return plan


def previous_files(root=ROOT):
    root = Path(root)
    assert digest(root / RUNTIME_INPUTS_FILE) == RUNTIME_INPUTS_SHA256, 'Original runtime payload provenance changed'
    for name, sha in HISTORICAL.items():
        assert digest(root / name) == sha, 'Historic function baseline changed: ' + name
    from pdf85_final_source_scope import previous_files as before_final, validate_layer as validate_final
    before = before_final(root)
    final = json.loads((root / 'updates/v2085-pdf-final-geometry/inputs.json').read_text())
    validate_final(final, before, root)
    previous = {**before, **final['changedOrAddedFiles']}
    assert len(previous) == 379
    return previous


def validate_scope(changes, plan=None):
    plan = test_plan() if plan is None else plan
    names = set(changes)
    tests = {item['source'] for item in plan.values()}
    allowed = PRODUCTION_FILES | {BUILD_FILE, CONTRACT_FILE, NEW_TEST_HELPER} | tests
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name for name in names)
    assert names <= allowed, 'Function fix changes unapproved compilation input: ' + str(sorted(names - allowed))
    assert tests | {BUILD_FILE, CONTRACT_FILE, REVISION_FILE, NEW_HELPER} <= names, 'Required function/version/test delta missing'
    if 'io.github.xgl34222220.hetu.PanelRequestOwnershipTest' in plan:
        assert NEW_TEST_HELPER in names, 'Ownership test requires its explicit zero-suite shadow helper'


def validate_version_only(before, after):
    assert before.count('versionCode = 2085') == 1
    assert before.count('versionName = "0.12.15-v20-pdf"') == 1
    expected = before.replace('versionCode = 2085', 'versionCode = 2086', 1)
    expected = expected.replace('versionName = "0.12.15-v20-pdf"', 'versionName = "0.12.16-v20-fix"', 1)
    assert after == expected, 'Build input changed beyond the two function-fix version values'


def validate_revision_only(before, after):
    original = 'static final int RUNTIME_REVISION = 151;'
    assert before.count(original) == 1
    # v2086: 151->152; v2089 r153 layer: 151->153 (via 152). Both are bounded.
    assert after == before.replace(original, 'static final int RUNTIME_REVISION = 152;', 1) or \
           after == before.replace(original, 'static final int RUNTIME_REVISION = 153;', 1), \
           'Runtime revision input changed beyond 151 to 152/153'


def validate_contract_only(before, after):
    original = 'assertEquals(151, ProxyRuntimeSettings.RUNTIME_REVISION)'
    assert before.count(original) == 1
    assert after == before.replace(original, 'assertEquals(152, ProxyRuntimeSettings.RUNTIME_REVISION)', 1) or \
           after == before.replace(original, 'assertEquals(153, ProxyRuntimeSettings.RUNTIME_REVISION)', 1), \
           'Original runtime contract assertion changed beyond its pinned revision'


def validate_new_tests(root, plan=None):
    plan = test_plan() if plan is None else plan
    for cls, item in plan.items():
        source = (Path(root) / item['source']).read_text()
        assert re.search(r'^package io\.github\.xgl34222220\.hetu\s*;?\s*$', source, re.MULTILINE)
        assert re.search(r'\bclass\s+' + cls.rsplit('.', 1)[1] + r'\b', source)
        assert len(re.findall(r'@(?:org\.junit\.)?Test\b', source)) == item['tests'], 'New function test count changed: ' + cls
    helper = Path(root) / NEW_TEST_HELPER
    if helper.exists():
        assert not re.search(r'@(?:org\.junit\.)?Test\b', helper.read_text()), 'Ownership shadow helper is not a test suite'


def validate_layer(layer, previous, root=ROOT):
    root, plan = Path(root), test_plan()
    assert layer['schema'] == 1 and layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == BASE_ANDROID_APP_TREE
    assert layer['versionCode'] == VERSION_CODE and layer['versionName'] == VERSION_NAME
    assert layer['previousInputsSha256'] == PREVIOUS_INPUTS_SHA256
    assert layer['previousPatchSha256'] == PREVIOUS_PATCH_SHA256
    assert layer['historicalFilesSha256'] == HISTORICAL
    for name, sha in HISTORICAL.items():
        assert digest(root / name) == sha, 'Historic layer changed: ' + name
    assert layer['testPlanSha256'] == digest(TEST_PLAN_PATH)
    assert layer['baselineUnitTests'] == BASE_TESTS and layer['baselineTestXmlFiles'] == BASE_TEST_XML_FILES
    assert layer['newTestCounts'] == {name: item['tests'] for name, item in plan.items()}
    assert layer['newTestFiles'] == {name: item['source'] for name, item in plan.items()}
    assert layer['expectedUnitTests'] == BASE_TESTS + sum(layer['newTestCounts'].values())
    assert layer['expectedTestXmlFiles'] == BASE_TEST_XML_FILES + len(plan)
    validate_scope(layer['changedOrAddedFiles'], plan)
    assert not {item['source'] for item in plan.values()} & previous.keys(), 'New tests must not replace existing tests'
    assert NEW_HELPER not in previous
    assert NEW_TEST_HELPER not in previous
    assert layer['frozenFiles'] == {name: sha for name, sha in previous.items() if name not in layer['changedOrAddedFiles']}
    for name, sha in layer['changedOrAddedFiles'].items():
        assert re.fullmatch(r'[0-9a-f]{64}', sha) and sha != previous.get(name)
    assert re.fullmatch(r'[0-9a-f]{64}', layer['patchSha256'])
    assert all(layer[name] is True for name in PROTECTED_FLAGS)
    assert layer['runtimePayloadCount'] == 23
    assert layer['originalRuntimeInputsSha256'] == RUNTIME_INPUTS_SHA256
    assert digest(root / RUNTIME_INPUTS_FILE) == RUNTIME_INPUTS_SHA256
    expected_root = layer['changedOrAddedFiles'].get(ROOT_SCRIPT, previous[ROOT_SCRIPT])
    assert layer['rootScriptSha256'] == expected_root
    assert layer['autostartScriptSha256'] == previous[AUTOSTART_SCRIPT] == AUTOSTART_SHA256
    expected_delta = ({'assets/hetu-root.sh': {'before': previous[ROOT_SCRIPT], 'after': expected_root}}
                      if ROOT_SCRIPT in layer['changedOrAddedFiles'] else {})
    assert layer['runtimePayloadChanges'] == expected_delta, 'Runtime payload scope exceeds the corrected root script'
    assert layer['externalPayloadFiles'] == {name: previous[name] for name in EXTERNAL_PAYLOAD_FILES}


def validate_source_transforms(before_root, after_root):
    # Preserve exact line-ending bytes; text-mode reads would silently allow
    # whole-file LF/CRLF changes in the three narrowly authorized transforms.
    def source(root, name):
        return (Path(root) / name).read_bytes().decode('utf-8')
    for name, validator in ((BUILD_FILE, validate_version_only), (REVISION_FILE, validate_revision_only),
                            (CONTRACT_FILE, validate_contract_only)):
        validator(source(before_root, name), source(after_root, name))
    validate_root_protocols(source(before_root, ROOT_SCRIPT), source(after_root, ROOT_SCRIPT),
                            source(before_root, PACKAGE + 'RootProxyManager.java'),
                            source(after_root, PACKAGE + 'RootProxyManager.java'))


def validate_root_protocols(before_script, after_script, before_manager, after_manager):
    marker = '\ncase "${1:-status}" in'
    assert before_script.count(marker) == after_script.count(marker) == 1
    assert before_script[before_script.index(marker):] == after_script[after_script.index(marker):], 'Root command names/argument protocol changed'
    start, end = '    private String[] autostartArgs(', '    private void installAutostart('
    assert before_manager.count(start) == after_manager.count(start) == 1
    before = before_manager[before_manager.index(start):before_manager.index(end, before_manager.index(start))]
    after = after_manager[after_manager.index(start):after_manager.index(end, after_manager.index(start))]
    assert before == after, 'Existing autostart argument protocol changed'
