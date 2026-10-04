"""Exact source boundary for the V20.84 controller authentication update."""
from pathlib import Path

BASE_COMMIT = 'a93a756d8578cf3afa60ee090405535ad097e49e'
BASE_RUN = 37240041766
BASE_ANDROID_APP_TREE = '2c698fb681f647e4c3a1d0050bf806a9f4cc5de2'
PREVIOUS_INPUTS_SHA256 = 'd5e3c4811f3b3ff579a6b12abb6543c4b4a0c8631fa29f461c711ca198a4b081'
PREVIOUS_PATCH_SHA256 = 'a466e4fce0e2e4ec9564760a1089b250f24d90032b123faea90fcee4c241e514'
VERSION_CODE = 2084
VERSION_NAME = '0.12.14-v20-auth'
BASE_TESTS = 392
BASE_TEST_XML_FILES = 42
PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
TEST_PACKAGE = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/'
AUTH_FILES = frozenset(PACKAGE + name for name in (
    'MihomoControllerClient.java',
    'LegacyAppMigrator.java',
    'ProxyComposeController.kt',
    'app/HetuViewModel.kt',
    'app/HetuActivity.kt',
    'panel/PanelModels.kt',
    'panel/PanelScreen.kt',
    'panel/PanelAdapter.kt',
    'panel/PanelOverlays.kt',
))
NEW_TEST_CLASSES = (
    'ToastFeedbackTest',
    'ControllerAuthenticationTest',
    'LegacyControllerCredentialTest',
    'ControllerReadStateTest',
    'PanelControllerErrorTest',
)
NEW_TEST_COUNTS = {
    'ToastFeedbackTest': 6,
    'ControllerAuthenticationTest': 11,
    'LegacyControllerCredentialTest': 3,
    'ControllerReadStateTest': 6,
    'PanelControllerErrorTest': 4,
}
EXPECTED_TESTS = BASE_TESTS + sum(NEW_TEST_COUNTS.values())
TEST_FILES = frozenset(TEST_PACKAGE + name + extension
                       for name in NEW_TEST_CLASSES for extension in ('.java', '.kt'))
BUILD_FILE = 'android-app/app/build.gradle.kts'


def validate_scope(changes):
    names = set(changes)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               for name in names), 'Authentication source path escapes the checkout'
    blocked = sorted(names - AUTH_FILES - TEST_FILES - {BUILD_FILE})
    assert not blocked, 'Authentication patch changes protected input: ' + str(blocked)


def validate_version_only(before, after):
    assert before.count('versionCode = 2083') == 1, 'Expected the verified V20.83 build input'
    assert before.count('versionName = "0.12.13-v20-ui"') == 1
    expected = before.replace('versionCode = 2083', 'versionCode = 2084')
    expected = expected.replace('versionName = "0.12.13-v20-ui"',
                                'versionName = "0.12.14-v20-auth"')
    assert after == expected, 'Build inputs changed beyond the V20.84 version'


def validate_layer(layer):
    assert layer['schema'] == 1
    assert layer['baseCommit'] == BASE_COMMIT and layer['baseRun'] == BASE_RUN
    assert layer['baseAndroidAppTree'] == BASE_ANDROID_APP_TREE
    assert layer['previousInputsSha256'] == PREVIOUS_INPUTS_SHA256
    assert layer['previousPatchSha256'] == PREVIOUS_PATCH_SHA256
    assert layer['versionCode'] == VERSION_CODE and layer['versionName'] == VERSION_NAME
    assert type(layer['expectedUnitTests']) is int
    assert layer['expectedUnitTests'] == EXPECTED_TESTS
    assert layer['baselineUnitTests'] == BASE_TESTS
    assert layer['newTestClasses'] == list(NEW_TEST_CLASSES)
    assert layer['newTestCounts'] == NEW_TEST_COUNTS
    changes = layer['changedOrAddedFiles']
    validate_scope(changes)
    assert BUILD_FILE in changes, 'Version update is missing from authentication layer'
    assert any(name in AUTH_FILES for name in changes), 'No authentication production delta'
    for name in NEW_TEST_CLASSES:
        assert sum(TEST_PACKAGE + name + extension in changes
                   for extension in ('.java', '.kt')) == 1, 'Missing or duplicate new test: ' + name
