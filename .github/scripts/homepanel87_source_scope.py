"""Narrow source boundary for the V20.87 home/panel presentation refactor.

The refactor rewrites the home and panel presentation layers (motion kit,
status glyph, region flags, screens, components, tokens) and wires ten
previously unwired ViewModel states into the new UI. It also appends 228
home/panel i18n entries. No new Gradle dependencies, no version, signing,
permission, Manifest or runtime payload changes.

The patch is recorded as updates/v2087-home-panel-refactor/ui.patch and is
applied on top of the verified V20.86 function-fix layer. The checked-in
sources must reproduce the patch output byte-for-byte.
"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

BASE_COMMIT = '13e76ea500849ef551281bcfad09835996758b4f'
BASE_RUN = 37486758158
VERSION_CODE = 2086
VERSION_NAME = '0.12.16-v20-fix'

PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
PRESENTATION_FOLDERS = ('app/', 'home/', 'panel/')
LANGUAGE_BRIDGE = 'ui/HetuLanguage.kt'
TEST_ROOT = 'android-app/app/src/test/'
ANDROID_TEST_ROOT = 'android-app/app/src/androidTest/'
BUILD_FILE = 'android-app/app/build.gradle.kts'

LAYER_DIR = ROOT / 'updates/v2087-home-panel-refactor'
INPUTS_FILE = LAYER_DIR / 'inputs.json'
PATCH_FILE = LAYER_DIR / 'ui.patch'
PREVIOUS_INPUTS_FILE = ROOT / 'updates/v2086-functionfix/inputs.json'
PREVIOUS_PATCH_FILE = ROOT / 'updates/v2086-functionfix/runtime.patch'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def allowed_change(name):
    if name == BUILD_FILE:
        return False  # this layer must not touch the version
    return (name.startswith(TEST_ROOT)
            or name.startswith(ANDROID_TEST_ROOT)
            or name == PACKAGE + LANGUAGE_BRIDGE
            or any(name.startswith(PACKAGE + folder) for folder in PRESENTATION_FOLDERS))


def validate_scope(changes):
    blocked = sorted(name for name in changes if not allowed_change(name))
    assert not blocked, 'Home/panel refactor changes protected input: ' + str(blocked)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               for name in changes), 'Source path escapes the checkout'


def validate_layer(layer, previous, root=ROOT):
    root = Path(root)
    assert layer['schema'] == 1, 'Unknown home/panel layer schema'
    assert layer['baseCommit'] == BASE_COMMIT, 'Home/panel patch base commit changed'
    assert layer['baseRun'] == BASE_RUN, 'Home/panel patch base run changed'
    assert layer['versionCode'] == VERSION_CODE, 'Home/panel layer must keep versionCode 2086'
    assert layer['versionName'] == VERSION_NAME, 'Home/panel layer must keep versionName 0.12.16-v20-fix'
    assert layer['previousInputsSha256'] == digest(PREVIOUS_INPUTS_FILE), \
        'V20.86 function-fix inputs changed under the home/panel layer'
    assert layer['previousPatchSha256'] == digest(PREVIOUS_PATCH_FILE), \
        'V20.86 function-fix patch changed under the home/panel layer'
    assert digest(PATCH_FILE) == layer['patchSha256'], \
        'Home/panel patch changed without input update'
    changes = layer['changedOrAddedFiles']
    assert changes, 'Home/panel layer records no changes'
    validate_scope(changes)
    for name, sha in changes.items():
        assert isinstance(sha, str) and len(sha) == 64 and all(
            c in '0123456789abcdef' for c in sha), 'Bad sha256 for ' + name
        assert sha != previous.get(name), 'Unchanged input recorded as a delta: ' + name
    assert layer['protectedRuntimeUnchanged'] is True
    assert layer['reproducedSourceMatchesCheckout'] is True
