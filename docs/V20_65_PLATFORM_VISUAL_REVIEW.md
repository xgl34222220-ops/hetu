# V20.65 internal platform and visual pass

## Local verification

The selected Android aggregate passed all 160 tests before the metadata-only 2065 renumber needed to preserve the concurrent 2064 update with zero failures/errors/skips. Full `lintDebug` also completed successfully: zero errors, 271 warnings and 4 hints remain. These warnings are not represented as a clean warning-free audit.

The seven previously blocking lint findings were handled narrowly:
- Three API-27 navigation-bar style items now live in qualified v27 resources; API-26 base themes no longer reference them.
- Two composable date-format paths observe the configuration locale.
- Existing user-initiated Wi-Fi identity permission requests now pair coarse/fine location on Android 12+, rather than requesting fine alone. No permission is automatically granted and no background-location permission is added.
- The pre-existing all-packages permission is retained for per-app Root routing, including packages without launcher activities. Only its specific store-policy lint finding is annotated and explained at that manifest declaration; no global lint baseline or ignore-all is added. Store-distribution compliance would require its own review.

## Reference refinements

Tools card rows use the measured 72dp minimum and reference-aligned label inset. Titles remain 18sp; subtitles are 15sp. Tools use outline/category glyphs rather than unrelated filled cloud/location symbols. Root refresh has two directional arcs.

Configuration confirmation footers are state-specific: text-only configuration delete, pale subscription delete, vertical draft-discard actions. Import source tabs retain their original two actions but now have the reference icons/card geometry. Main action corners are rounded rectangles. File-download validation marks and explains only the relevant field; it no longer incorrectly marks a valid filename as an invalid URL.

The five WebUI content states were rendered in the successful V20.63 CI, not inferred from source. This pass makes small measured text-size corrections; pixel-exact acceptance and physical K80 behavior are still open.

## New bounded native smoke gate

CI installs this run's signed APK into a fresh official AOSP API-35 x86_64 emulator, resolves its actual launcher alias, and checks Home, stopped Panel, Tools, Settings, About and native WebView navigation/back behavior. It never enables a proxy, edits network rules, grants app permissions or changes host KVM permissions. Existing KVM access is used only if already available; otherwise software acceleration is requested. A missing image/license, boot failure, crash or missing screen fails the gate and preserves evidence.

This gate has only been syntax-checked locally; its real execution is pending CI. It does not substitute for Root boot, populated WebView fixtures, K80 hardware or full 173-state pixel acceptance. No intermediate user APK delivery is intended.

The concurrent V20.64 tools-row overlay is preserved before this delta.
