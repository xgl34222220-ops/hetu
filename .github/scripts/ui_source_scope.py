"""Narrow source boundary for the V20.83 presentation update."""
from pathlib import Path

BASE_COMMIT = 'aa599a53856fb26b4d256ba345a496b2a178b3b8'
BASE_RUN = 37211142058
PREVIOUS_INPUTS_SHA256 = '2ed0a46bd92aff2567ce6d8a31a2460da918e12837ba82eb38abaa85f8c9511c'
PACKAGE = 'android-app/app/src/main/java/io/github/xgl34222220/hetu/'
UI_FOLDERS = ('app/', 'home/', 'panel/', 'tools/', 'settings/')
UI_BRIDGES = ('ToolsConfigBridge.kt', 'ToolsRuntimeBridge.kt',
              'ProxyAdvancedSettingsActivity.kt', 'ProxyLogViewerActivity.kt',
              'ProxyScriptsActivity.kt', 'ProxySubStoreActivity.kt',
              'ProxyNetworkAutomationActivity.kt')


def allowed_change(name):
    return (name == 'android-app/app/build.gradle.kts'
            or name.startswith('android-app/app/src/test/')
            or name.startswith('android-app/app/src/androidTest/')
            or name in {PACKAGE + bridge for bridge in UI_BRIDGES}
            or any(name.startswith(PACKAGE + folder) for folder in UI_FOLDERS))


def validate_scope(changes):
    blocked = sorted(name for name in changes if not allowed_change(name))
    assert not blocked, 'Presentation patch changes protected input: ' + str(blocked)
    assert all(not Path(name).is_absolute() and '..' not in Path(name).parts
               for name in changes), 'Source path escapes the checkout'


def validate_version_only(before, after):
    expected = before.replace('versionCode = 2082', 'versionCode = 2083')
    expected = expected.replace('versionName = "0.12.12-v20-newui"',
                                'versionName = "0.12.13-v20-ui"')
    assert after == expected, 'Build inputs changed beyond the V20.83 version'
