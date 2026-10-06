#!/usr/bin/env python3
"""Regress the source-proof boundary independently of Android tooling."""
import unittest

from ui_source_scope import PACKAGE, validate_scope, validate_version_only


class PresentationScope(unittest.TestCase):
    def test_current_ui_packages_and_named_bridges_are_allowed(self):
        validate_scope([PACKAGE + 'panel/PanelGroupsTab.kt',
                        PACKAGE + 'tools/ToolsScreen.kt',
                        PACKAGE + 'app/SettingsScreen.kt',
                        PACKAGE + 'ToolsRuntimeBridge.kt',
                        PACKAGE + 'ProxyAdvancedSettingsActivity.kt',
                        PACKAGE + 'ProxyLogViewerActivity.kt',
                        PACKAGE + 'ProxyScriptsActivity.kt',
                        PACKAGE + 'ProxySubStoreActivity.kt',
                        PACKAGE + 'ProxyNetworkAutomationActivity.kt',
                        PACKAGE + 'ui/HetuLanguage.kt',
                        'android-app/app/src/test/java/example/Ui83Test.kt'])

    def test_runtime_assets_manifest_and_native_payload_cannot_enter_ui_patch(self):
        for path in ['android-app/app/src/main/AndroidManifest.xml',
                     'android-app/app/src/main/assets/hetu-root.sh',
                     'android-app/app/src/main/jniLibs/x86_64/libhetu_core.so',
                     PACKAGE + 'ProxyNetworkMatchService.java',
                     PACKAGE + 'RootProxyManager.java']:
            with self.subTest(path=path), self.assertRaises(AssertionError):
                validate_scope([path])

    def test_path_traversal_is_rejected_even_with_allowed_ui_prefix(self):
        with self.assertRaises(AssertionError):
            validate_scope([PACKAGE + 'panel/../../RootProxyManager.java'])

    def test_only_expected_version_change_is_allowed_in_build_inputs(self):
        before = 'versionCode = 2082\nversionName = "0.12.12-v20-newui"\nstrictly("5.0.1")\n'
        after = 'versionCode = 2083\nversionName = "0.12.13-v20-ui"\nstrictly("5.0.1")\n'
        validate_version_only(before, after)
        for changed in [after.replace('5.0.1', '2.0.0'), after.replace('2083', '2084')]:
            with self.assertRaises(AssertionError):
                validate_version_only(before, changed)

    def test_unlisted_root_package_bridge_is_rejected(self):
        for path in ['NewRootBridge.kt', 'ui/NewRuntimeBridge.kt']:
            with self.subTest(path=path), self.assertRaises(AssertionError):
                validate_scope([PACKAGE + path])


if __name__ == '__main__':
    unittest.main(verbosity=2)
