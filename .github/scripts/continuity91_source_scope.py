#!/usr/bin/env python3
"""Add the bounded V20.91 continuation to the byte-exact verified V20.90 layer."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

import stability90_source_scope as prior

ROOT = prior.ROOT
BASE_COMMIT = '9fe4931b6e37a1a7f7f78fd27835069e2b354aa7'
ORIGINAL_TEST_HEAD = prior.ORIGINAL_TEST_HEAD
VERSION_CODE, VERSION_NAME = 2091, '0.12.18-v20-glass'
BUILD_FILE, ROOT_SCRIPT, AUTOSTART_SCRIPT = prior.BUILD_FILE, prior.ROOT_SCRIPT, prior.AUTOSTART_SCRIPT
PACKAGE, TEST_ROOT = prior.PACKAGE, prior.TEST_ROOT
EXTERNAL_PAYLOAD_FILES = prior.EXTERNAL_PAYLOAD_FILES
compilation_inputs, digest = prior.compilation_inputs, prior.digest
LAYER_FOLDER = 'updates/v2091-continuity-glass'
LAYER_FILE = ROOT / LAYER_FOLDER / 'inputs.json'
PRIOR_FOLDER = 'updates/v2090-stability-glass'
AUTHORIZED_FILES = frozenset((
    BUILD_FILE,
    PACKAGE + 'ProxyDashboardRepository.kt',
    PACKAGE + 'app/SettingsReferenceComponents.kt',
    TEST_ROOT + 'java/io/github/xgl34222220/hetu/CrossRepositoryMutation91Test.kt',
    TEST_ROOT + 'java/io/github/xgl34222220/hetu/SettingsGlassContinuationTest.kt',
))
PROTECTED_FLAGS = prior.PROTECTED_FLAGS + ('rootPayloadUnchanged', 'allPredecessor631TestMethodsPreserved')


def committed_bytes(name, root=ROOT):
    return subprocess.check_output(['git', 'show', BASE_COMMIT + ':' + name], cwd=root)


def predecessor_layer(root=ROOT):
    root = Path(root)
    path = root / PRIOR_FOLDER / 'inputs.json'
    layer = json.loads(path.read_text())
    assert path.read_bytes() == committed_bytes(PRIOR_FOLDER + '/inputs.json', root), 'Verified V20.90 inputs changed'
    patch = path.with_name('runtime.patch')
    assert patch.read_bytes() == committed_bytes(PRIOR_FOLDER + '/runtime.patch', root), 'Verified V20.90 patch changed'
    previous = prior.previous_files(root)
    prior.validate_layer(layer, previous, root)
    return layer, {**previous, **layer['changedOrAddedFiles']}


def previous_files(root=ROOT):
    return predecessor_layer(root)[1]


def historical_files(root=ROOT):
    root = Path(root)
    names = set(prior.historical_files(root))
    names.update((PRIOR_FOLDER + '/inputs.json', PRIOR_FOLDER + '/runtime.patch'))
    # Preserve all eight failed candidates and the successful predecessor's
    # source/evidence validators; current workflow trigger ownership is checked
    # separately because it intentionally exits this branch's push trigger.
    names.update(subprocess.check_output([
        'git', 'ls-tree', '-r', '--name-only', BASE_COMMIT, '--', 'docs/qa'
    ], cwd=root).decode().splitlines())
    names.update(str(p.relative_to(root)) for p in (root / '.github/scripts').glob('*stability90*.py'))
    names.update(('tools/qa/run_network_soak90.py', 'tools/qa/run_mihomo_soak90.py', 'tools/qa/test_mihomo_soak90.py'))
    return {name: hashlib.sha256(committed_bytes(name, root)).hexdigest() for name in sorted(names)}


def allowed_change(name):
    return name in AUTHORIZED_FILES


def validate_version(before, after):
    expected = before.replace('versionCode = 2090', 'versionCode = 2091', 1)
    expected = expected.replace('versionName = "0.12.17-v20-glass"', 'versionName = "0.12.18-v20-glass"', 1)
    assert before.count('versionCode = 2090') == before.count('versionName = "0.12.17-v20-glass"') == 1
    assert after == expected, 'V20.91 changed Gradle inputs beyond the two version values'


def selected_tests(root=ROOT, extra_sources=()):
    predecessor, _ = predecessor_layer(root)
    return prior.selected_tests(root, extra_sources=[
        *extra_sources, *(entry['source'] for entry in predecessor['finalUnitTestMethods'].values())
    ])


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    predecessor, expected_previous = predecessor_layer(root)
    assert previous == expected_previous, 'V20.91 must follow every byte-exact V20.90 effective input'
    assert layer['schema'] == 1 and layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == subprocess.check_output(['git', 'rev-parse', BASE_COMMIT + ':android-app'], cwd=root).decode().strip()
    assert layer['originalTestHead'] == ORIGINAL_TEST_HEAD and layer['versionCode'] == VERSION_CODE and layer['versionName'] == VERSION_NAME
    assert layer['previousInputsSha256'] == digest(root / PRIOR_FOLDER / 'inputs.json')
    assert layer['previousPatchSha256'] == digest(root / PRIOR_FOLDER / 'runtime.patch')
    assert layer['historicalFilesSha256'] == historical_files(root)
    assert all(digest(root / name) == value for name, value in layer['historicalFilesSha256'].items()), 'Historical source/evidence bytes changed'
    changes = layer['changedOrAddedFiles']
    assert changes and BUILD_FILE in changes and set(changes) <= AUTHORIZED_FILES
    assert layer['authorizedFiles'] == sorted(changes)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts for name in changes)
    assert re.fullmatch(r'[0-9a-f]{64}', layer['patchSha256'])
    assert all(re.fullmatch(r'[0-9a-f]{64}', value) and value != previous.get(name) for name, value in changes.items())
    assert layer['frozenFiles'] == {name: value for name, value in previous.items() if name not in changes}
    assert layer['externalPayloadFiles'] == {name: previous[name] for name in sorted(EXTERNAL_PAYLOAD_FILES)}
    assert layer['runtimePayloadCount'] == 23
    assert layer['rootScriptSha256'] == previous[ROOT_SCRIPT] and layer['autostartScriptSha256'] == previous[AUTOSTART_SCRIPT]
    assert all(layer[name] is True for name in PROTECTED_FLAGS)
    baseline, final = layer['baselineUnitTestMethods'], layer['finalUnitTestMethods']
    assert baseline == predecessor['finalUnitTestMethods']
    assert len(baseline) == layer['baselineTestXmlFiles'] == 64
    assert sum(len(item['methods']) for item in baseline.values()) == layer['baselineUnitTests'] == 631
    assert set(baseline) <= set(final)
    for cls, entry in baseline.items():
        assert final[cls] == entry, 'V20.91 altered a retained predecessor test identity: ' + cls
    for cls, entry in final.items():
        assert entry['source'].startswith(TEST_ROOT) and entry['methods'] and len(set(entry['methods'])) == len(entry['methods'])
        if cls not in baseline:
            assert entry['source'] in AUTHORIZED_FILES, 'Unapproved new test source'
    assert layer['expectedUnitTests'] == sum(len(item['methods']) for item in final.values()) > 631
    assert layer['expectedTestXmlFiles'] == len(final) > 64


def validate_checkout(root=ROOT, layer=None, allow_external_missing=True):
    root = Path(root)
    layer = json.loads((root / LAYER_FOLDER / 'inputs.json').read_text()) if layer is None else layer
    previous = previous_files(root)
    validate_layer(layer, previous, root)
    assert digest(root / LAYER_FOLDER / 'runtime.patch') == layer['patchSha256'], 'Current V20.91 patch bytes changed'
    final = {**previous, **layer['changedOrAddedFiles']}
    actual = compilation_inputs(root)
    missing = set(final) - actual
    assert missing <= (EXTERNAL_PAYLOAD_FILES if allow_external_missing else set())
    assert actual == set(final) - missing, 'Unrecorded V20.91 compilation input'
    assert all(digest(root / name) == final[name] for name in actual), 'Checkout differs from the V20.91 recorded source'
    assert selected_tests(root, extra_sources=[item['source'] for item in layer['finalUnitTestMethods'].values()]) == layer['finalUnitTestMethods']
    validate_version(committed_bytes(BUILD_FILE, root).decode(), (root / BUILD_FILE).read_text())
    return final
