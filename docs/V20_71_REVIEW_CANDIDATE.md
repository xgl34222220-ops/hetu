# V20.71 frozen review candidate

This final bounded delta builds on the preserved successfulV20.70 candidate; it does not introduce a new design or change the system font.

## Frozen scope
- Home startup/restart keeps its action label alongside the busy spinner and remains disabled. The File Manager topbar menu records its own touch anchor and opens the reference dropdown; other topbar actions and keyboard fallback remain unchanged.
- Search inputs retain focus/edit/clear behavior in a flat pale capsule with circular clear affordance. A320dp/1.4font-scale long-query case verifies reachable clearing and re-entry without truncating saved text.
- Blue selection is scoped to strategy-layout pills and app-routing active text. Only direct-target restore and individual connection-close gain the reference outline.
- Configuration dropdowns use measured152dp width,13sp header and15sp actions in32dp rows. File/folder menus retain their43/36dp contracts and long-name/cancel coverage.
- Only the new-file dialog gets a26sp left-aligned title. Startup-error and diagnostics text sheets show visible“复制”; license actions/full text stay intact.

## Checks
Thirteen distinct affected UI regressions passed locally in bounded groups, including preference persistence, search filtering/clearing, narrow/large-font input, menu row height, new-file title alignment, copy-label visibility and cancelled destructive actions. The initial density assertion caught34dp instead of32dp and was corrected through scoped padding, not weakened. Two structural failures were reproduced before correction; an earlier combined red run lost its Gradle daemon, was retained, and isolated red/green runs established the results.

The existing232Android-test gate, lint, browser checks and16native navigation/DOM checks remain mandatory. Five populated native WebView screenshots must be distinct and visually reviewed. V20.70 already completed those native checks after retrying a failed official system-image download; its correct five-state evidence is preserved. V20.71 uses an explicit workflow expected-version2071 so a different APK cannot pass installation smoke.

## Acceptance boundaries
All173 reference states have paired evidence and a structural review, not certified pixel equality. Real values, current version/attribution, unavailable data and system status/navigation bars are not replaced by mockup data. System-font rendering and hardware glass can still vary; editor/scriptAPI28 raster captures do not establish physical GPU behavior. K80 display, real core/Root boot and OEM/vendor-firewall handling require separate evidence; the vendor capability guard is unchanged.

WholeAPK bytes, fixed signing certificate, version and root-script/payload integrity must be independently checked from one successful run before delivery. This is a test-branch review candidate, not a main merge or formal release.
