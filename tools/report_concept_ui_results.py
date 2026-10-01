#!/usr/bin/env python3
"""Report the current launcher tests separately without hiding the complete suite result."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
results = root / "android-app/app/build/test-results/testDebugUnitTest"
required = ("HetuConceptRootTest", "HomeConceptUiTest", "ConfigEditorSafetyTest", "CpuSampleTrackerTest", "RuntimeCpuSamplingTest", "LauncherIconTest", "GoogleDiagnosticsIntegrationTest", "ConfigApplyQueueTest", "SettingsBackupRestoreTest", "ConfigWorkflowConceptTest", "NodeSelectionRootContractTest")
suites = []
for name in required:
    path = results / f"TEST-io.github.xgl34222220.hetu.{name}.xml"
    if not path.is_file():
        suites.append({"name": name, "status": "not_run", "tests": 0})
        continue
    suite = ET.parse(path).getroot()
    counts = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    failed = [{"name": case.get("name"), "message": problem.get("message", "")}
              for case in suite.findall("testcase")
              for problem in list(case) if problem.tag in ("failure", "error")]
    suites.append({"name": name, "status": "passed" if counts["tests"] > 0 and not any(counts[k] for k in ("failures", "errors", "skipped")) else "failed", **counts, "failed_cases": failed})

screenshots = [str(p.relative_to(root)) for folder in ("hetu-concept-root", "hetu-ui-consolidation")
               for p in sorted((root / "android-app/app/build/outputs" / folder).glob("*.png"))]
report = {"status": "passed" if all(suite["status"] == "passed" for suite in suites) else "failed",
          "scope": "Actual HetuRoot, HomeScreen, details, native editor and configuration workflows, strict node selection confirmation, CPU sample continuity, launcher resources, diagnostic assembly, queued config application and safe backup restoration; the complete regression suite remains authoritative and may independently fail.",
          "suites": suites, "screenshots": screenshots,
          "limits": "Robolectric renderer with isolated IO. Screenshots require visual review; this does not validate device installation, real Root/VPN, GPU glass, or frame rate."}
out = root / "out/concept-ui-result.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
for suite in suites:
    print(f"{suite['name']}: {suite['status']} ({suite['tests']} tests)")
print(f"Captured {len(screenshots)} screenshots; visual review remains separate")
sys.exit(0 if report["status"] == "passed" else 1)
