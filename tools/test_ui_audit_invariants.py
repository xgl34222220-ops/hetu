#!/usr/bin/env python3
"""Runtime fences and view lifecycle invariants; pixels/interactions are tested with Compose."""
from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parents[1]
src = root / "android-app/app/src/main/java/io/github/xgl34222220/hetu"
for name, expected in json.loads((root / "tests/ui-runtime-baseline.json").read_text()).items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    assert actual == expected, f"UI change modified protected runtime file: {name}"
main = (src / "ReferenceProxyActivity.kt").read_text()
cards = (src / "WorkspaceCards.kt").read_text()
icon = (src / "ConfiguredGroupIcon.kt").read_text()
liquid = (src / "LiquidWorkspace.kt").read_text()
assert "LiquidStrategyCard(group" in cards and "ConfiguredGroupIcon(group" in liquid
assert 'testTag("configured-icon:' in icon and "ContentScale.Fit" in icon
assert "upgradeRuntimeApplyAttempted" not in main
assert main.count("controller.restart {") == 1, "Only explicit user restart may call controller.restart"
assert "proxyRootRuntimeRefreshPending" in main, "Manual upgrade notice must remain visible"
assert "LocalHetuDockHeight provides" in main and "onSizeChanged" in main
assert "idleWave" not in main and "kotlin.math.sin" not in main, "No synthetic traffic traces"
assert "BoxProxy Design System" not in (src / "ThemeSettingsActivity.kt").read_text()
assert "setEditorLanguage(HetuYamlLanguage())" in (src / "ProxySubscriptionActivity.kt").read_text()
assert "RuleMetricSummary(" in (src / "ProxyAdblockChainActivity.kt").read_text()
assert 'item(key = "home-shortcuts")' in main, "Home must expose the WebUI/log control shortcuts"
assert "ProxyLocalWebUiActivity::class.java" in main and 'title = "运行日志"' in main
assert 'Text("网络与广告过滤"' not in main
assert "expandedGroup ?: closingGroup" in main
assert "LiquidConfigIndicator(config.selected)" in (src / "ProxySubscriptionActivity.kt").read_text()
for modal_free in ("ReferenceProxyActivity.kt", "AlignedInstrumentPanel.kt", "ReferenceFileManagerActivity.kt", "CompactMainActivity.kt"):
    assert "AlertDialog(" not in (src / modal_free).read_text(), f"Legacy centered AlertDialog remains in {modal_free}"
for kt in src.rglob("*.kt"):
    text = kt.read_text()
    assert "AlertDialog(" not in text, f"Legacy centered AlertDialog remains in {kt.name}"
assert "proxyDirectGids" in (src / "ProxyRuntimeSettings.java").read_text()
assert "GID 规则" in (src / "ProxyAppSelectionActivity.kt").read_text()
root_shell = (root / "android-app/app/src/main/assets/hetu-root.sh").read_text()
assert "--gid-owner" in root_shell and "DIRECT_GIDS" in root_shell
assert 'icon = Icons.Rounded.Sort' in main
controller = (src / "ProxyComposeController.kt").read_text()
root_manager = (src / "RootProxyManager.java").read_text()
assert '.putString("proxyRootAppliedSettings", ProxyRuntimeSettings.signature(prefs))' in controller
assert '.putString("proxyRootAppliedSettings",ProxyRuntimeSettings.signature(prefs))' in root_manager
assert root_manager.count('.putString("proxyRootAppliedSettings",p.settingsSignature)') == 1, "hot reload may record its validated prepared signature once"
runtime_settings = (src / "ProxyRuntimeSettings.java").read_text()
boot = (src / "BootReceiver.java").read_text()
assert 'proxyRootSettingsDirty' in runtime_settings and 'markDirty' in runtime_settings
assert '.remove("proxyRootRuntimeRefreshPending")' in boot
assert '.putBoolean("proxyRootRuntimeRefreshPending", true)' not in boot
assert '运行设置已修改，重启后生效' in main
print("UI audit source/runtime invariants passed")
