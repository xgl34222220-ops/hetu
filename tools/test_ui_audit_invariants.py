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
assert "ConfiguredGroupIcon(group" in cards
assert 'testTag("configured-icon:' in icon and "ContentScale.Fit" in icon
assert "upgradeRuntimeApplyAttempted" not in main
assert main.count("controller.restart {") == 1, "Only explicit user restart may call controller.restart"
assert "proxyRootRuntimeRefreshPending" in main, "Manual upgrade notice must remain visible"
assert "LocalHetuDockHeight provides" in main and "onSizeChanged" in main
assert "idleWave" not in main and "kotlin.math.sin" not in main, "No synthetic traffic traces"
assert "BoxProxy Design System" not in (src / "ThemeSettingsActivity.kt").read_text()
assert "setEditorLanguage(HetuYamlLanguage())" in (src / "ProxySubscriptionActivity.kt").read_text()
assert "RuleMetricSummary(" in (src / "ProxyAdblockChainActivity.kt").read_text()
print("UI audit source/runtime invariants passed")
