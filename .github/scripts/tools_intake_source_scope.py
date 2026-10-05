#!/usr/bin/env python3
"""Exact additive boundary for the V20.85 tools source intake.

The historic PDF layer stays immutable.  Two adapters may change, one new
Activity Result consent helper may be added, and two new test sources
must be added.  Every other effective compilation input remains byte-exact.
"""
import hashlib
import json
from pathlib import Path
import re

BASE_COMMIT = '2730954591393c1b81f747cef72adb8940c5a82c'
BASE_ANDROID_APP_TREE = '930994157562814a8e22aeeb1873d8eeb0a51f64'
VERSION_CODE = 2085
VERSION_NAME = '0.12.15-v20-pdf'
BASE_TESTS = 479
EXPECTED_TESTS = 487
BASE_TEST_XML_FILES = 49
EXPECTED_TEST_XML_FILES = 51
PREVIOUS_INPUTS_SHA256 = '46cbff55a3621b544acb10bd72c0fffe1ffd17469bb856630ea32683ae08024f'
PREVIOUS_PATCH_SHA256 = '09e5f3c1fec4ddcd86e546f6270bf687cbf14aa518764e640c7ff5f77e966d66'
HISTORICAL_FILES_SHA256 = {
    'updates/v2082-new-ui/inputs.json': '2ed0a46bd92aff2567ce6d8a31a2460da918e12837ba82eb38abaa85f8c9511c',
    'updates/v2082-new-ui/integration.patch': '3f08f86d8413c9a905de1eae0c5347e8f7c5dd4032ee4a8e6181d439abda7002',
    'updates/v2083-ui-polish/inputs.json': 'd5e3c4811f3b3ff579a6b12abb6543c4b4a0c8631fa29f461c711ca198a4b081',
    'updates/v2083-ui-polish/ui.patch': 'a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514',
    'updates/v2084-controller-auth/inputs.json': '9ac175a21429d547c7f69e8765d719e4eb391cadd63278d5e58a6c2d6568c911',
    'updates/v2084-controller-auth/runtime.patch': '327983504205ff1ec26a3c6804a83ae08802a7d4f723de280a92af708e3ea4ee',
    'updates/v2085-pdf-tools-settings/inputs.json': PREVIOUS_INPUTS_SHA256,
    'updates/v2085-pdf-tools-settings/runtime.patch': PREVIOUS_PATCH_SHA256,
}
PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/'
TEST_PACKAGE = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/'
EXISTING_PRODUCTION_FILES = frozenset(PACKAGE + name for name in (
    'ToolsFeatureAdapter.kt', 'ToolsFeatureRoute.kt',
))
OPTIONAL_HELPER = PACKAGE + 'ToolsVpnConsent.kt'
CONSENT_PLATFORM_IMPORTS = frozenset((
    'android.app.Activity', 'android.content.Intent',
    'androidx.activity.result.ActivityResultLauncher',
    'androidx.activity.result.ActivityResultRegistry',
    'androidx.activity.result.contract.ActivityResultContracts',
))
EXTERNAL_PAYLOAD_FILES = frozenset((
    'android-app/app/src/main/assets/mihomo-root/arm64-v8a/mihomo',
    'android-app/app/src/main/assets/mihomo-root/x86_64/mihomo',
    'android-app/app/src/main/jniLibs/arm64-v8a/libhetu_core.so',
    'android-app/app/src/main/jniLibs/x86_64/libhetu_core.so',
))
NEW_TEST_COUNTS = {'tools.ToolsDnsConsentIntakeTest': 5, 'tools.ToolsDiagIntakeTest': 3}
TEST_FILES = frozenset(TEST_PACKAGE + name.rsplit('.', 1)[1] + '.kt' for name in NEW_TEST_COUNTS)
REQUIRED_FILES = EXISTING_PRODUCTION_FILES | TEST_FILES
ALLOWED_FILES = REQUIRED_FILES | {OPTIONAL_HELPER}
PROTECTED_FLAGS = (
    'homeAndPanelUnchanged', 'runtimePayloadsUnchanged', 'dependenciesUnchanged',
    'manifestAndPermissionsUnchanged', 'signingUnchanged',
    'unchangedOutsideIntakeScope', 'reproducedAvailableSourceMatchesCheckout',
)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def compilation_inputs(root):
    root = Path(root)
    return {str(path.relative_to(root)) for path in (root / 'android-app').rglob('*')
            if path.is_file() and '/build/' not in str(path.relative_to(root))
            and '/.gradle/' not in str(path.relative_to(root))
            and path.name != 'local.properties'}


def validate_scope(changes):
    names = set(changes)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               and '\\' not in name for name in names), 'Intake path escapes checkout'
    assert REQUIRED_FILES <= names, 'Intake must include both adapters and both new tests'
    assert names <= ALLOWED_FILES, 'Intake changes protected input: ' + str(sorted(names - ALLOWED_FILES))


def verified_previous_files(root):
    """Pin all four prior layers, then return their complete effective SHA map."""
    root = Path(root)
    for name, sha in HISTORICAL_FILES_SHA256.items():
        assert digest(root / name) == sha, 'Historic layer changed: ' + name
    spec = json.loads((root / 'updates/v2082-new-ui/inputs.json').read_text())
    previous = {**spec['baselineFiles'], **spec['integratedFiles']}
    for folder in ('v2083-ui-polish', 'v2084-controller-auth', 'v2085-pdf-tools-settings'):
        layer = json.loads((root / 'updates' / folder / 'inputs.json').read_text())
        previous.update(layer['changedOrAddedFiles'])
    return previous


def validate_layer(layer, previous_files):
    assert layer['schema'] == 1
    assert layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == BASE_ANDROID_APP_TREE
    assert layer['previousInputsSha256'] == PREVIOUS_INPUTS_SHA256
    assert layer['previousPatchSha256'] == PREVIOUS_PATCH_SHA256
    assert layer['historicalFilesSha256'] == HISTORICAL_FILES_SHA256
    assert type(layer['versionCode']) is int and layer['versionCode'] == VERSION_CODE
    assert layer['versionName'] == VERSION_NAME
    assert type(layer['baselineUnitTests']) is int and layer['baselineUnitTests'] == BASE_TESTS
    assert type(layer['expectedUnitTests']) is int and layer['expectedUnitTests'] == EXPECTED_TESTS
    assert layer['baselineTestXmlFiles'] == BASE_TEST_XML_FILES
    assert layer['expectedTestXmlFiles'] == EXPECTED_TEST_XML_FILES
    assert layer['newTestClasses'] == list(NEW_TEST_COUNTS)
    assert layer['newTestCounts'] == NEW_TEST_COUNTS
    assert layer['externalPayloadFiles'] == {name: previous_files[name] for name in EXTERNAL_PAYLOAD_FILES}, 'External native payload SHA map changed'
    changes = layer['changedOrAddedFiles']
    validate_scope(changes)
    assert EXISTING_PRODUCTION_FILES <= previous_files.keys()
    assert not (TEST_FILES | {OPTIONAL_HELPER}) & previous_files.keys(), 'New intake sources already exist in baseline'
    frozen = layer['frozenFiles']
    assert frozen == {name: sha for name, sha in previous_files.items() if name not in changes}, 'Incomplete or changed frozen compilation input map'
    assert not frozen.keys() & changes.keys()
    for name, sha in changes.items():
        assert isinstance(sha, str) and re.fullmatch(r'[0-9a-f]{64}', sha), 'Invalid source SHA: ' + name
        assert sha != previous_files.get(name), 'Unchanged source recorded as delta: ' + name
    assert isinstance(layer['patchSha256'], str) and re.fullmatch(r'[0-9a-f]{64}', layer['patchSha256'])
    assert all(layer[name] is True for name in PROTECTED_FLAGS), 'Protected scope guarantee missing'


def validate_new_source_files(root, layer):
    root = Path(root)
    for class_name, count in NEW_TEST_COUNTS.items():
        short = class_name.rsplit('.', 1)[1]
        source = (root / (TEST_PACKAGE + short + '.kt')).read_text()
        assert re.search(r'^package io\.github\.xgl34222220\.hetu\.tools\s*$', source, re.MULTILINE)
        assert re.search(r'\bclass\s+' + short + r'\b', source), 'Missing intake test class: ' + class_name
        assert len(re.findall(r'@(?:org\.junit\.)?Test\b', source)) == count, 'Wrong intake test source count: ' + class_name
    if OPTIONAL_HELPER in layer['changedOrAddedFiles']:
        source = (root / OPTIONAL_HELPER).read_text()
        assert re.search(r'^package io\.github\.xgl34222220\.hetu\.tools\s*$', source, re.MULTILINE)
        imports = set(re.findall(r'^import\s+((?:android|androidx)\.[\w.]+)', source, re.MULTILINE))
        assert imports <= CONSENT_PLATFORM_IMPORTS, 'Consent helper platform scope exceeds Activity Result consent'
        body = re.sub(r'^import\s+[^\n]+\n', '', source, flags=re.MULTILINE)
        assert not re.search(r'\b(?:android|androidx)\.', body), 'Consent helper must use only its explicitly allowed platform imports'
        assert not re.search(r'\b(?:java\.net|java\.io|kotlin\.io|ProcessBuilder|Runtime\.getRuntime|RootBridge|rootShell|RootProxyManager|DnsVpnService|MihomoControllerClient)\b', source), 'Consent helper may not perform runtime, network or Root I/O'


def validate_checkout_matches_generated(work, checkout, expected):
    """Compare every final input, including PDF-frozen files superseded by intake."""
    work, checkout = Path(work), Path(checkout)
    for name, sha in expected.items():
        assert digest(work / name) == sha, 'Generated source input regressed: ' + name
        assert digest(checkout / name) == sha, 'Checkout differs from generated source: ' + name
    for root, label in ((work, 'Generated source'), (checkout, 'Checkout')):
        actual = compilation_inputs(root)
        assert actual == set(expected), label + ' compilation input set differs: ' + str(sorted(actual ^ set(expected)))
