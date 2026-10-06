#!/usr/bin/env python3
"""Exact measured presentation delta after the independently pinned tools intake."""
import hashlib
import json
from pathlib import Path

BASE_COMMIT = 'ec211b9ad81748dee30fec43f0a25849559e4623'
BASE_ANDROID_APP_TREE = 'c00b30fa7fdabb4516a60708e7fa61fea057c913'
TRANSFORMS_PATH = Path(__file__).with_name('pdf85_final_transforms.json')
TRANSFORMS = json.loads(TRANSFORMS_PATH.read_text())
ALLOWED = frozenset((
    'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt',
    'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ToolScreens.kt',
    'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsAdblockScreen.kt',
    'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsDesign.kt',
))
assert set(TRANSFORMS) == ALLOWED, 'Final PDF transforms exceed four authorized presentation inputs'
ROOT = Path(__file__).resolve().parents[2]
HISTORICAL = {'updates/v2082-new-ui/inputs.json': '2ed0a46bd92aff2567ce6d8a31a2460da918e12837ba82eb38abaa85f8c9511c', 'updates/v2082-new-ui/integration.patch': '3f08f86d8413c9a905de1eae0c5347e8f7c5dd4032ee4a8e6181d439abda7002', 'updates/v2083-ui-polish/inputs.json': 'd5e3c4811f3b3ff579a6b12abb6543c4b4a0c8631fa29f461c711ca198a4b081', 'updates/v2083-ui-polish/ui.patch': 'a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514', 'updates/v2084-controller-auth/inputs.json': '9ac175a21429d547c7f69e8765d719e4eb391cadd63278d5e58a6c2d6568c911', 'updates/v2084-controller-auth/runtime.patch': '327983504205ff1ec26a3c6804a83ae08802a7d4f723de280a92af708e3ea4ee', 'updates/v2085-pdf-tools-settings/inputs.json': '46cbff55a3621b544acb10bd72c0fffe1ffd17469bb856630ea32683ae08024f', 'updates/v2085-pdf-tools-settings/runtime.patch': '09e5f3c1fec4ddcd86e546f6270bf687cbf14aa518764e640c7ff5f77e966d66', 'updates/v2085-tools-intake/inputs.json': '7604e646cad209c2fc2a606196d478f76cd41005f5d47ad3e37dbc84ac58339c', 'updates/v2085-tools-intake/runtime.patch': '108ae764ec0a8cf9a5cafc827d093bedd0f039f6944f7cd9848304e0a2fb7b3b'}


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_presentation(before, after, name):
    assert name in ALLOWED, 'Final PDF delta exceeds four measured presentation inputs'
    expected = before
    for item in TRANSFORMS[name]:
        assert expected.count(item['before']) == item['count'], 'Ambiguous final PDF transform: ' + name
        expected = expected.replace(item['before'], item['after'])
    assert after == expected, 'Final PDF source changed outside measured presentation: ' + name


def previous_files(root):
    from tools_intake_source_scope import verified_previous_files, validate_layer as validate_intake
    root = Path(root)
    previous = verified_previous_files(root)
    intake = json.loads((root / 'updates/v2085-tools-intake/inputs.json').read_text())
    validate_intake(intake, previous)
    return {**previous, **intake['changedOrAddedFiles']}


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    assert set(TRANSFORMS) == ALLOWED, 'Final PDF transforms exceed four authorized presentation inputs'
    assert layer['schema'] == 1 and layer['baseCommit'] == BASE_COMMIT
    assert layer['baseAndroidAppTree'] == BASE_ANDROID_APP_TREE
    assert layer['versionCode'] == 2085 and layer['versionName'] == '0.12.15-v20-pdf'
    assert layer['expectedUnitTests'] == 487 and layer['expectedTestXmlFiles'] == 51
    assert set(layer['changedOrAddedFiles']) == ALLOWED and len(ALLOWED) == 4
    assert layer['frozenFiles'] == {p: h for p, h in previous.items() if p not in ALLOWED}
    assert len(previous) == 379 and len(layer['frozenFiles']) == 375
    for name, sha in layer['changedOrAddedFiles'].items():
        assert len(sha) == 64 and sha != previous[name]
    assert layer['historicalFilesSha256'] == HISTORICAL
    for name, sha in HISTORICAL.items():
        assert digest(root / name) == sha, 'Prior layer changed: ' + name
    assert layer['transformsSha256'] == digest(TRANSFORMS_PATH)
    assert all(layer[name] is True for name in (
        'homeAndPanelUnchanged', 'runtimePayloadsUnchanged', 'dependenciesUnchanged',
        'manifestAndPermissionsUnchanged', 'signingUnchanged', 'allExistingTestsPreserved'))
