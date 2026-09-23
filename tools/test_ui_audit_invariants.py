#!/usr/bin/env python3
"""Runtime fences and view lifecycle invariants; pixels/interactions are tested with Compose."""
from pathlib import Path
import hashlib, json, re
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
assert "shared_mac_returns" in root_shell and "--mac-source" in root_shell
assert 'SHARED_BYPASS_MACS=%s' in root_shell
shared_ui = (src / "ProxyFocusedSettingsActivities.kt").read_text()
assert 'proxySharedBypassMacs' in shared_ui and '"下游设备 / MAC"' in shared_ui and '"接口管理"' in shared_ui
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

# Liquid Glass visual contract (design.md is the source of truth).
design = (root / "design.md").read_text()
liquid_system = (src / "ui/HetuLiquidGlassSystem.kt").read_text()
ui_kit = (src / "ui/HetuUiKit.kt").read_text()

assert "iOS / VisionOS 极简液态浮岛" in design
assert "HetuGlassRadius" in liquid_system and "HetuMotionSpec" in liquid_system
assert "LiquidStatusCapsule" in liquid_system and "SegmentedLiquidActionPill" in liquid_system
assert "LiquidSelectionIndicator" in liquid_system
assert "LiquidGlassTextField" in liquid_system and "glassInputWell" in liquid_system
assert "LocalOverscrollFactory provides if (motionEnabled)" in theme
assert "pageEnter.snapTo(1f)" in refhome

# Cold-air canvas + translucent islands replace the previous lavender flat field.
assert "Color(0xFFF5F7FB)" in theme
assert "Color(0xA6FFFFFF)" in theme
assert "Color(0xFF0E1014)" in theme
assert "Brush.radialGradient" in crystal
assert "Color(0xFF82DCFF)" in crystal and "Color(0xFFB4A0FF)" in crystal
assert "HazeStyle(" in crystal and "hazeEffect" in crystal

# Home must use the dock-derived Liquid Glass language, not the old giant hero.
assert 'item(key = "home-shortcuts")' in refhome
assert 'title = "WebUI"' in refhome and 'title = "日志"' in refhome
assert "LiquidStatusCapsule(" in refhome
assert "SegmentedLiquidActionPill(" in refhome
assert 'Modifier.testTag("home-liquid-actions")' in refhome
assert '.height(120.dp)' not in refhome and '.size(116.dp)' not in refhome
assert '"少于 1 分钟"' in refhome
assert '"延迟"' in refhome and 'Icons.Rounded.Tune' in refhome and 'Icons.Rounded.Refresh' in refhome
assert "LatencyChip(" in refhome

# Four dashboard tiles share glass material and stable tabular number rendering.
assert 'ReferenceDashboardCard' in instrument and '"WAN"' in instrument and '"网速"' in instrument
assert '"订阅"' in instrument and '"资源占用"' in instrument
assert 'crystalMaterial(shape, depth = CrystalDepth.Card)' in instrument
assert 'HetuNumber(' in instrument and 'monospaced = true' in instrument
assert 'home-usage-progress' in instrument and 'home-cpu-progress' in instrument

# Strategy/node selections use the same glass selection language as the floating dock.
assert 'strategyHeight' in liquid and 'nodeHeight' in liquid and '80.dp' in liquid and '78.dp' in liquid
assert 'if (width < 292.dp' in liquid, "Phone strategy grid should use two columns when space permits"
assert 'TextAutoSize.StepBased' in liquid, "Long node names must shrink instead of becoming ellipsis-heavy"
assert 'ConfiguredGroupIcon(group, Modifier.size(32.dp))' in liquid
assert 'LatencyChip(' in liquid
assert 'selection = expanded' in liquid
assert 'LiquidSelectionIndicator(' in liquid and 'node-selection-indicator:' in liquid
assert 'animateDpAsState(' in liquid and 'nodeSelectionX' in liquid and 'nodeSelectionY' in liquid
assert 'selection = active' not in liquid
assert 'Color(0xFFE8E6F7)' not in liquid

# Floating dock geometry and content clearance are shared tokens, not duplicated literals.
assert 'HetuBottomBarMetrics.FloatingHorizontal' in dock
assert 'HetuBottomBarMetrics.FloatingBottom' in dock
assert '.height(72.dp' in dock and 'itemHeight = 60.dp' in dock
assert 'refractionHeight = 17.dp.toPx()' in dock and 'chromaticAberration = .045f' in dock, "Dock shell must retain LuoShu liquid-glass optics"
assert 'HetuBottomBarMetrics.ContentGap' in ui_kit

# API/config inputs must use inset glass wells.
assert refhome.count("LiquidGlassTextField(") >= 4
assert 'label = "Secret"' in refhome and 'label = "测速 URL"' in refhome

# Phase 2 full-app Liquid Glass settings/tool surfaces.
phase2_pages = {
    "advanced": (src / "ProxyAdvancedSettingsActivity.kt").read_text(),
    "network": (src / "ProxyNetworkAutomationActivity.kt").read_text(),
    "subscriptions": (src / "ProxySubscriptionActivity.kt").read_text(),
    "adblock": (src / "ProxyAdblockChainActivity.kt").read_text(),
    "files": (src / "ReferenceFileManagerActivity.kt").read_text(),
}
for name, text in phase2_pages.items():
    assert not re.search(r"\bSwitch\(", text), f"Legacy Material Switch remains in {name}"
    assert "OutlinedTextField(" not in text, f"Legacy outlined input remains in {name}"
    assert ".background(t.pageBackground)" not in text and ".background(pageBg)" not in text, f"Flat page field remains in {name}"

assert "GroupedInsetSection" in phase2_pages["advanced"]
assert "GroupedInsetSection" in phase2_pages["network"]
assert "GroupedInsetSection" in phase2_pages["files"]
assert "LiquidSwitch" in phase2_pages["advanced"] and "LiquidSwitch" in phase2_pages["network"] and "LiquidSwitch" in phase2_pages["adblock"]
assert "LiquidGlassTextField" in phase2_pages["advanced"]
assert "LiquidGlassTextField" in phase2_pages["network"]
assert "LiquidGlassTextField" in phase2_pages["subscriptions"]
assert "liquidSheetMaterial()" in phase2_pages["advanced"]
assert "liquidSheetMaterial()" in phase2_pages["network"]
assert "liquidSheetMaterial()" in phase2_pages["subscriptions"]
assert "liquidSheetMaterial()" in phase2_pages["files"]

# Compact filters and mode choices must also use the liquid component family.
adblock = (src / "ProxyAdblockChainActivity.kt").read_text()
assert "LiquidChoicePill" in liquid_system
assert "FilterChip(" not in refhome, "Material FilterChip remains in main proxy UI"
assert "OutlinedTextField(" not in refhome, "Legacy outlined field remains in main proxy UI"
assert "LiquidChoicePill(" in refhome
assert "FilterChip(" not in adblock, "Material FilterChip remains in adblock UI"
assert "LiquidChoicePill(" in adblock

# Phase 3 auxiliary tool pages use the same Liquid Glass controls.
phase3_pages = {
    "apps": (src / "ProxyAppSelectionActivity.kt").read_text(),
    "focused": (src / "ProxyFocusedSettingsActivities.kt").read_text(),
    "logs": (src / "ProxyLogViewerActivity.kt").read_text(),
    "scripts": (src / "ProxyScriptsActivity.kt").read_text(),
}
for name, text in phase3_pages.items():
    assert not re.search(r"\bSwitch\(", text), f"Legacy Material Switch remains in Phase 3 {name}"
    assert "OutlinedTextField(" not in text, f"Legacy outlined input remains in Phase 3 {name}"
    assert "FilterChip(" not in text, f"Legacy Material filter remains in Phase 3 {name}"
    assert ".background(t.pageBackground)" not in text and ".background(pageBg)" not in text, f"Flat page field remains in Phase 3 {name}"

assert "LiquidChoicePill" in phase3_pages["apps"]
assert "LiquidGlassTextField" in phase3_pages["apps"]
assert "LiquidSwitch" in phase3_pages["focused"]
assert "LiquidGlassTextField" in phase3_pages["focused"]
assert "crystalPageBackground()" in phase3_pages["logs"]
assert "LiquidGlassTextField" in phase3_pages["scripts"]
assert "liquidSheetMaterial()" in phase3_pages["scripts"]

# Phase 4 final reachable surfaces and launcher boundary.
core_ui = (src / "ProxyCoreActivity.kt").read_text()
theme_ui = (src / "ThemeSettingsActivity.kt").read_text()
web_ui = (src / "ProxyLocalWebUiActivity.kt").read_text()
manifest = (root / "android-app/app/src/main/AndroidManifest.xml").read_text()

assert "crystalPageBackground()" in core_ui
assert "GroupedInsetSection" in core_ui
assert "HetuGlassRadius.Card" in core_ui and "CrystalDepth.InsetItem" in core_ui
assert ".background(tokens.pageBackground)" not in core_ui
assert "selection = selected" in theme_ui and "HetuGlassRadius.Input" in theme_ui
assert "--bg:#f5f7fb" in web_ui and "--bg:#0e1014" in web_ui
assert "backdrop-filter:blur(20px)" in web_ui and "border-radius:24px" in web_ui
assert manifest.count('android.intent.action.MAIN') == 2, "Two switchable launcher aliases are expected"
assert 'android:name=".LauncherOfficial"' in manifest and 'android:name=".LauncherClassic"' in manifest
assert manifest.count('android:targetActivity=".ReferenceProxyActivity"') == 2
assert 'android:enabled="true"' in manifest and 'android:enabled="false"' in manifest
assert "CompactMainActivity::class" not in refhome and "HetuMainActivity::class" not in refhome

# Existing productivity/accessibility contracts remain.
assert 'private fun RefSectionLabel' in refhome and 'Spacer(Modifier.height(2.dp))' in refhome
assert ".height(42.dp).testTag(\"yaml-accessory\")" in editor
assert "UTF-8 · YAML" in editor
assert "ticket-progress:" in monitoring
assert "builtInBrandKey" in icon and "BuiltInBrandIcon" in icon
for brand in ("openai","google","github","telegram","youtube"):
    assert f'"{brand}"' in icon, f"Missing multicolor brand fallback: {brand}"
print("UI audit source/runtime invariants passed")
