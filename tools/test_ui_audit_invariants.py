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
assert 'item(key = "home-shortcuts")' in main, "Reference home requires the two compact shortcut cards"
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
theme = (src / "ui/HetuTheme.kt").read_text()
crystal = (src / "ui/CrystalMaterial.kt").read_text()
liquid = (src / "LiquidWorkspace.kt").read_text()
editor = (src / "EditorWorkbench.kt").read_text()
monitoring = (src / "MonitoringWorkspace.kt").read_text()
refhome = (src / "ReferenceProxyActivity.kt").read_text()
nonhome = (src / "NonHomeWorkspace.kt").read_text()
instrument = (src / "AlignedInstrumentPanel.kt").read_text()
icon = (src / "ConfiguredGroupIcon.kt").read_text()
dock = (src / "ui/HetuGlassDock.kt").read_text()

# 156785.mp4 visual contract.
assert "Color(0xFFEEEDFB)" in theme and "Color(0xFFE2DEFF)" in theme
assert 'item(key = "home-shortcuts")' in refhome
assert 'title = "WebUI"' in refhome and 'title = "日志"' in refhome
assert '.height(120.dp)' in refhome and '.size(116.dp)' in refhome
assert '"少于 1 分钟"' in refhome
assert '.height(52.dp)' in refhome and 'label = "重载"' in refhome and 'label = "重启"' in refhome
assert 'Text("延迟"' in refhome and 'Icons.Rounded.Tune' in refhome and 'Icons.Rounded.Refresh' in refhome
assert '.height(88.dp)' in refhome
assert 'ReferenceDashboardCard' in instrument and 'Text("WAN"' in instrument and 'Text("网速"' in instrument
assert 'Text("订阅"' in instrument and 'Text("资源占用"' in instrument
assert 'home-usage-progress' in instrument and 'home-cpu-progress' in instrument and '.height(3.dp)' in instrument
assert 'fontSize = 32.sp' in refhome and 'RoundedCornerShape(14.dp)' in refhome
assert '.horizontalScroll(rememberScrollState())' in refhome
assert '.height(80.dp)' in liquid and '.height(78.dp)' in liquid
assert 'if (width < 292.dp' in liquid, "Phone strategy grid should use two columns when space permits"
assert 'TextAutoSize.StepBased' in liquid, "Long node names must shrink instead of becoming ellipsis-heavy"
assert 'ConfiguredGroupIcon(group, Modifier.size(32.dp))' in liquid
assert 'ReferenceDelayPill' in liquid and 'Color(0xFFE2E8FA)' in liquid
assert 'crystalMaterial(shape, depth = CrystalDepth.Sunken)' not in liquid, "Expanded nodes must sit directly on the page"
assert 'Modifier.padding(horizontal = 25.dp)' in dock and '.height(64.dp' in dock
assert 'Color(0xFFDEDEEA)' in dock, "Dock active lens must be neutral, not blue plastic"
assert 'private fun RefSectionLabel' in refhome and 'Spacer(Modifier.height(2.dp))' in refhome
assert 'fontSize = 32.sp' in refhome
assert "Color(0xFFF9F8FE)" in theme and "Color(0xFFF5F3FD)" in theme
assert "drawRect(t.pageBackground)" not in crystal and "return background(t.pageBackground)" in crystal
assert "BoxProxy Design System" not in (src / "ThemeSettingsActivity.kt").read_text()
assert ".height(42.dp).testTag(\"yaml-accessory\")" in editor
assert "UTF-8 · YAML" in editor
assert "ticket-progress:" in monitoring
assert "builtInBrandKey" in icon and "BuiltInBrandIcon" in icon
for brand in ("openai","google","github","telegram","youtube"):
    assert f'"{brand}"' in icon, f"Missing multicolor brand fallback: {brand}"
print("UI audit source/runtime invariants passed")
