"""Narrow source boundary for the V20.88 tools/settings UI unification.

This layer unifies the tools and settings pages with the home/panel design
language: tools/ components and app/Hx* + Settings* components become thin
wrappers over home/ design components. It also carries the cumulative
home/panel refactor (from v2087) plus a compile fix (vararg Color -> List).

New files: app/HxLucide.kt (Lucide linear icons), home/HomeListKit.kt
(shared list components). 348 new i18n entries for tools/settings pages.

No new Gradle dependencies, no version, signing, permission, Manifest or
runtime payload changes. The patch is recorded as
updates/v2088-ui-refactor/ui.patch and is applied on top of the verified
v2087 home/panel layer. The checked-in sources must reproduce the patch
output byte-for-byte.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

BASE_COMMIT = 'a5d5cc06fd8f294e17495e132ecf1f5de31135f8'
BASE_RUN = 37556952110
VERSION_CODE = 2086
VERSION_NAME = '0.12.16-v20-fix'

PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
PRESENTATION_FOLDERS = ('app/', 'home/', 'panel/', 'tools/', 'ui/')
# Package-root presentation files (file editor, web panel, feedback bar)
PACKAGE_ROOT_FILES = ('EditorWorkbench.kt', 'ProxyReferenceExtras.kt',
                      'RuntimeEditorScreen.kt', 'UiFeedback.kt',
                      'ProxyAdvancedSettingsActivity.kt', 'ProxyLogViewerActivity.kt',
                      'ProxyScriptsActivity.kt', 'ProxySubStoreActivity.kt',
                      'ProxyNetworkAutomationActivity.kt')
TEST_ROOT = 'android-app/app/src/test/'
ANDROID_TEST_ROOT = 'android-app/app/src/androidTest/'
BUILD_FILE = 'android-app/app/build.gradle.kts'

LAYER_DIR = ROOT / 'updates/v2088-ui-refactor'
INPUTS_FILE = LAYER_DIR / 'inputs.json'
PATCH_FILE = LAYER_DIR / 'ui.patch'
PREVIOUS_INPUTS_FILE = ROOT / 'updates/v2087-home-panel-refactor/inputs.json'
PREVIOUS_PATCH_FILE = ROOT / 'updates/v2087-home-panel-refactor/ui.patch'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def allowed_change(name):
    if name == BUILD_FILE:
        return False  # this layer must not touch the version
    if name.startswith(TEST_ROOT) or name.startswith(ANDROID_TEST_ROOT):
        return True
    if not name.startswith(PACKAGE):
        return False
    rel = name[len(PACKAGE):]
    if '/' not in rel:
        return rel in PACKAGE_ROOT_FILES
    return any(rel.startswith(folder) for folder in PRESENTATION_FOLDERS)


def validate_scope(changes):
    blocked = sorted(name for name in changes if not allowed_change(name))
    assert not blocked, 'UI unification changes protected input: ' + str(blocked)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               for name in changes), 'Source path escapes the checkout'


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    assert layer['schema'] == 1, 'Unknown UI layer schema'
    assert layer['baseCommit'] == BASE_COMMIT, 'UI patch base commit changed'
    assert layer['baseRun'] == BASE_RUN, 'UI patch base run changed'
    assert layer['versionCode'] == VERSION_CODE, 'UI layer must keep versionCode 2086'
    assert layer['versionName'] == VERSION_NAME, 'UI layer must keep versionName 0.12.16-v20-fix'
    assert layer['previousInputsSha256'] == digest(PREVIOUS_INPUTS_FILE), \
        'v2087 inputs changed under the UI layer'
    assert layer['previousPatchSha256'] == digest(PREVIOUS_PATCH_FILE), \
        'v2087 patch changed under the UI layer'
    assert digest(PATCH_FILE) == layer['patchSha256'], \
        'UI patch changed without input update'
    changes = layer['changedOrAddedFiles']
    assert changes, 'UI layer records no changes'
    validate_scope(changes)
    for name, sha in changes.items():
        assert isinstance(sha, str) and len(sha) == 64 and all(
            c in '0123456789abcdef' for c in sha), 'Bad sha256 for ' + name
        assert sha != previous.get(name), 'Unchanged input recorded as a delta: ' + name
    assert layer['protectedRuntimeUnchanged'] is True
    assert layer['reproducedSourceMatchesCheckout'] is True
