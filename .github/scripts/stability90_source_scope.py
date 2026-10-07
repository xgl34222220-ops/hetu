#!/usr/bin/env python3
"""Additive V20.90 source and test identities over all verified predecessor layers.

Old layers are validated at their own byte-exact inputs. No historical validator
is weakened to make an authorized later change pass an earlier freeze.
"""
import fnmatch
import json
from pathlib import Path
import re
import subprocess

from functionfix_source_scope import ROOT, previous_files as function_previous, validate_layer as validate_function
from tools_intake_source_scope import compilation_inputs, digest, EXTERNAL_PAYLOAD_FILES

BASE_COMMIT = '610a523ea524dff521b5d01e7aa78c421399594a'
ORIGINAL_TEST_HEAD = '9769663edcda3a1c33f2eb2594a6b4fd9b4e2a6f'
VERSION_CODE, VERSION_NAME = 2090, '0.12.17-v20-glass'
PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST_ROOT = 'android-app/app/src/test/'
BUILD_FILE = 'android-app/app/build.gradle.kts'
ROOT_SCRIPT = 'android-app/app/src/main/assets/hetu-root.sh'
AUTOSTART_SCRIPT = 'android-app/app/src/main/assets/hetu-autostart.sh'
LAYER_FILE = ROOT / 'updates/v2090-stability-glass/inputs.json'
PREDECESSORS = (
    ('v2086-functionfix', 'runtime.patch', None),
    ('v2087-home-panel-refactor', 'ui.patch', 'homepanel87_source_scope'),
    ('v2088-ui-refactor', 'ui.patch', 'ui88_source_scope'),
    ('v2089-runtime153', 'runtime.patch', 'runtime153_source_scope'),
)
PROTECTED_FLAGS = ('manifestAndPermissionsUnchanged', 'dependenciesUnchanged', 'signingUnchanged',
                   'nativePayloadsUnchanged', 'autostartPayloadUnchanged', 'allBaselineTestMethodsPreserved')
RUNTIME_FILES = frozenset(PACKAGE + n for n in (
    'RootProxyManager.java', 'ProxyNetworkMatchService.java', 'ProxyNetworkState.java',
    'ProxyNetworkHandover.java', 'NetworkEpoch.java', 'ProxyComposeController.kt',
    'ProxyDashboardRepository.kt', 'MihomoControllerClient.java', 'ProxyRuntimeSettings.java',
    'MihomoVpnService.java', 'ProxyRestoreScheduler.java', 'ProxyStatusBridge.kt',
    'ProxyObservations.java', 'DnsTakeoverProbe.java', 'ProxyControlEpoch.java',
    'ProxyTaskCoalescer.java', 'ProxyContinuity.java', 'ProxyReachabilityProbe.java',
    'ProxyDataPlaneProbe.java', 'ProxyNetworkHealth.java', 'ProxyHealthMonitor.java',
    'ProxyUi.java', 'RootTproxyActivity.java', 'NativeHomePolish.kt',
))


def predecessor_layers(root=ROOT):
    root = Path(root)
    previous = function_previous(root)
    layers = []
    for folder, patch_name, module in PREDECESSORS:
        inputs = root / 'updates' / folder / 'inputs.json'
        spec = json.loads(inputs.read_text())
        if module is None:
            validate_function(spec, previous, root)
        else:
            from importlib import import_module
            import_module(module).validate_layer(spec, previous, root)
        patch = inputs.with_name(patch_name)
        assert digest(patch) == spec['patchSha256'], 'Predecessor patch changed: ' + folder
        current = {**previous, **spec['changedOrAddedFiles']}
        layers.append((spec, patch, previous, current))
        previous = current
    return layers


def previous_files(root=ROOT):
    return predecessor_layers(root)[-1][3]


def historical_files(root=ROOT):
    root = Path(root)
    from functionfix_source_scope import HISTORICAL
    result = dict(HISTORICAL)
    for folder, patch_name, _ in PREDECESSORS:
        for name in ('inputs.json', patch_name):
            rel = 'updates/' + folder + '/' + name
            result[rel] = digest(root / rel)
    result['UI92_RUNTIME146_INPUTS.json'] = digest(root / 'UI92_RUNTIME146_INPUTS.json')
    return result


def allowed_change(name):
    if name in {BUILD_FILE, ROOT_SCRIPT} | RUNTIME_FILES:
        return True
    if name.startswith(TEST_ROOT):
        return True
    if not name.startswith(PACKAGE):
        return False
    rel = name[len(PACKAGE):]
    return any(rel.startswith(folder) for folder in ('app/', 'home/', 'panel/', 'tools/', 'ui/'))


def validate_version(before, after):
    expected = before.replace('versionCode = 2086', 'versionCode = 2090', 1)
    expected = expected.replace('versionName = "0.12.16-v20-fix"', 'versionName = "0.12.17-v20-glass"', 1)
    assert before.count('versionCode = 2086') == before.count('versionName = "0.12.16-v20-fix"') == 1
    assert after == expected, 'V20.90 altered build inputs beyond its version values'


def test_methods(source):
    count = len(re.findall(r'@(?:org\.junit\.)?Test\b', source))
    matched = re.findall(r'@(?:org\.junit\.)?Test\b[\s\S]*?(?:fun\s+(`[^`]+`|\w+)|(?:public\s+)?void\s+(\w+))\s*\(', source)
    result = [str(a or b).strip('`') for a, b in matched]
    assert len(result) == count and len(set(result)) == count, 'Ambiguous or duplicate test identity'
    return result


def selected_tests(root=ROOT, commit=None, extra_sources=()):
    root = Path(root)
    workflow = subprocess.check_output(['git', 'show', ORIGINAL_TEST_HEAD + ':.github/workflows/ui-v76-build.yml'], cwd=root).decode()
    unit = workflow.split('    - name: Runtime unit tests\n', 1)[1].split('    - name: Android lint', 1)[0]
    filters = re.findall(r"'(\*[A-Za-z][A-Za-z0-9_.]*)'", unit)
    filters += list(json.loads((root / '.github/scripts/functionfix_test_plan.json').read_text()))
    if commit:
        names = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', commit, '--', TEST_ROOT], cwd=root).decode().splitlines()
        read = lambda n: subprocess.check_output(['git', 'show', commit + ':' + n], cwd=root).decode()
    else:
        names = [str(p.relative_to(root)) for p in (root / TEST_ROOT).rglob('*') if p.is_file() and p.suffix in ('.kt', '.java')]
        read = lambda n: (root / n).read_text()
    output, extra = {}, set(extra_sources)
    for name in sorted(names):
        source = read(name)
        package = re.search(r'^package\s+([\w.]+)', source, re.MULTILINE)
        if not package:
            continue
        cls = package[1] + '.' + Path(name).stem
        if any(fnmatch.fnmatchcase(cls, f) for f in filters) or name in extra:
            methods = test_methods(source)
            if methods:
                output[cls] = {'source': name, 'methods': methods}
    return output


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    assert layer['schema'] == 1 and layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == subprocess.check_output(
        ['git', 'rev-parse', BASE_COMMIT + ':android-app'], cwd=root).decode().strip()
    assert layer['originalTestHead'] == ORIGINAL_TEST_HEAD and layer['reusedMainHead'] == BASE_COMMIT
    assert layer['versionCode'] == VERSION_CODE and layer['versionName'] == VERSION_NAME
    assert layer['previousInputsSha256'] == digest(root / 'updates/v2089-runtime153/inputs.json')
    assert layer['previousPatchSha256'] == digest(root / 'updates/v2089-runtime153/runtime.patch')
    assert layer['historicalFilesSha256'] == historical_files(root)
    assert all(digest(root / n) == sha for n, sha in layer['historicalFilesSha256'].items())
    changes = layer['changedOrAddedFiles']
    assert changes and BUILD_FILE in changes
    assert all(allowed_change(n) and not Path(n).is_absolute() and '..' not in Path(n).parts for n in changes)
    assert layer['authorizedFiles'] == sorted(changes)
    assert re.fullmatch(r'[0-9a-f]{64}', layer['patchSha256'])
    assert layer['frozenFiles'] == {n: sha for n, sha in previous.items() if n not in changes}
    assert all(re.fullmatch(r'[0-9a-f]{64}', sha) and sha != previous.get(n) for n, sha in changes.items())
    assert layer['externalPayloadFiles'] == {n: previous[n] for n in sorted(EXTERNAL_PAYLOAD_FILES)}
    assert layer['runtimePayloadCount'] == 23
    assert layer['rootScriptSha256'] == changes.get(ROOT_SCRIPT, previous[ROOT_SCRIPT])
    assert layer['autostartScriptSha256'] == previous[AUTOSTART_SCRIPT]
    assert all(layer[n] is True for n in PROTECTED_FLAGS)
    baseline, final = layer['baselineUnitTestMethods'], layer['finalUnitTestMethods']
    assert len(baseline) == layer['baselineTestXmlFiles'] == 56
    assert sum(len(x['methods']) for x in baseline.values()) == layer['baselineUnitTests'] == 545
    assert baseline == selected_tests(root, BASE_COMMIT)
    assert set(baseline) <= set(final)
    for cls, item in baseline.items():
        assert final[cls]['source'] == item['source']
        assert set(item['methods']) <= set(final[cls]['methods']), 'Baseline testcase removed: ' + cls
    assert layer['expectedUnitTests'] == sum(len(x['methods']) for x in final.values())
    assert layer['expectedTestXmlFiles'] == len(final)
    assert layer['expectedUnitTests'] >= 545 and layer['expectedTestXmlFiles'] >= 56
    for cls, item in final.items():
        assert len(set(item['methods'])) == len(item['methods']) and item['methods']
        assert item['source'].startswith(TEST_ROOT)


def validate_checkout(root=ROOT, layer=None, allow_external_missing=True):
    root = Path(root)
    layer = json.loads((root / 'updates/v2090-stability-glass/inputs.json').read_text()) if layer is None else layer
    previous = previous_files(root)
    validate_layer(layer, previous, root)
    assert digest(root / 'updates/v2090-stability-glass/runtime.patch') == layer['patchSha256'], 'Current layer patch bytes changed'
    final = {**previous, **layer['changedOrAddedFiles']}
    actual = compilation_inputs(root)
    missing = set(final) - actual
    assert missing <= (EXTERNAL_PAYLOAD_FILES if allow_external_missing else set())
    assert actual == set(final) - missing, 'Unrecorded compilation source input'
    assert all(digest(root / n) == final[n] for n in actual), 'Checkout differs from recorded source'
    assert selected_tests(root, extra_sources=[x['source'] for x in layer['finalUnitTestMethods'].values()]) == layer['finalUnitTestMethods']
    before_build = subprocess.check_output(['git', 'show', BASE_COMMIT + ':' + BUILD_FILE], cwd=root).decode()
    validate_version(before_build, (root / BUILD_FILE).read_text())
    return final
