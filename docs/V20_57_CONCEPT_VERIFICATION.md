# V20.57 concept reconstruction candidate

This is a test candidate, not a claim that all 173 states have passed pixel-by-pixel acceptance.

## Reference and implementation scope

Five supplied concept PDFs: Home14, Panel29, ToolsA49, ToolsB48, Settings33. The companion design atlas specifies entry, back, cancel and save boundaries. System font remains in use; production reads real runtime/user data, never mockup annotations or sample telemetry.

- Native Home IP/LAN and resources routes, actual missing-sample chart gaps, direct-target forms, start/restart presentation and active-tab reselect.
- Panel strategy cards, node/group operations, filters/layout, connection metadata/rates, subscription remaining percentage, rule-set states and log layouts.
- Config import/subscription forms and editor conflict states; file/script/log/adblock surfaces; shared network, CNIP, diagnostics and network matching.
- Native settings hierarchy, fields, choices, theme, notifications, startup and license screens.
- Three primary core-manager cards, Mihomo/Xray/sing-box in reference order. Name click opens family variants plus a separately labeled independent-core section. Selection changes only the card's management target, not the active runtime profile.
- Web-management sheets, protected WebView container boundaries, Sub-Store states, and built-in WebUI views/select/cancel/failure handling.

## Data safety fixes

Backup restore reuses identical configurations and imports different-content name collisions as unique copies. Restored selected-file identity follows the copied name. A backup without a selected-file setting preserves the prior selection. Failed restore rolls selection back. No automatic proxy restart.

Configuration editor checks selected-source identity and external changes before save. Native runtime editor keeps the existing expected-digest guard. Test fixtures never enter production data paths.

## Verified locally

- Kotlin and Java compilation succeeded.
- 73 focused Android checks passed, zero skipped/failing: runtime31; Home6; panel6; settings3; backup3; tools3; core manager4; native screen captures17.
- Built-in WebUI13 controlled JavaScript behavior checks passed, including failed/unconfirmed selection retaining the prior node and repeated-write blocking.
- Full remote source-reconstruction sequence was reproduced. Fixed the stale native-title assertion that searched Java code using Kotlin syntax. The V20.57 delta is SHA-256 guarded and must apply cleanly after V20.56.
- All22 original runtime payload digests were verified against the preserved UI15 APK. No core binary is replaced by this UI pass.
- Additional controlled multi-state QA passed15 scenarios, including child-settings routes hiding/restoring the dock, visible connection-sheet close, filter dismissal, draft cancellation, and variant-picker runtime isolation. Broader173-state mapping is still being expanded.
- Native screenshots are exported as CI verification artifacts. The focused suites use real production components and controlled fixtures, not reference-image overlays.

Robolectric's generic Espresso idler stalled on a second Compose dialog window despite completed layout. Dialog tests therefore drive native-window frames and invoke actual production semantics actions; validation, cancel and save assertions remain in place.

## Pending acceptance

All173 states still need complete rendered comparison and device interaction coverage. The current screenshots cover broad routes and selected states; a passing focused suite is not full visual acceptance. Hardware blur, device fonts/scaling, Root networking, reboot recovery and long-term behavior have not been newly phone-tested.

Chromium launch was blocked by this execution environment's socket policy, so built-in WebUI behavior was checked with an isolated DOM/API fixture rather than claimed browser-pixel validation. Android WebView/device rendering remains to be checked.

The CI run for the exact pushed commit must succeed and its APK identity/signature verified before APK delivery. No production release or main-branch merge is authorized by this test-candidate change.
