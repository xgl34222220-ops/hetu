# V20.63 internal visual refinement

The V20.62 CI at commit ed3f05b passed 160 Android tests, 13 WebUI logic checks, 19 shell protocol checks and 12 real Chromium interaction checks. Its five rendered WebUI reference states were inspected. They are browser supplements, not Android WebView/device acceptance. The packaged version was verified in CI as 2062 / 0.11.2-v20 with the existing signing identity.

This pass corrects measured remaining differences:
- DNS strategy dialog: width 65% of the viewport, reference-scaled type/rows and centered content rather than a full-width Material dialog. Selection, DNS-forwarding preference relationship and cancellation remain unchanged.
- Home IP address: explicit trailing alignment and reference-sized IP label/value.
- Connections: card spacing 9→10dp and vertical padding 9→11dp; no traffic or identity changes.
- WebUI: sampled pale background/surface colors, larger secondary/selector/radio text and tighter node-dialog rows/footer. Existing real API/state handling is unchanged.

All 160 selected Android regressions passed again locally with no failures/errors/skips. All 13 isolated WebUI logic checks passed. Revised Chromium rendering is pending this build; no claim of full 173-state visual acceptance is made. CI emits a merged native/browser evidence inventory while retaining pixel_exact_pass=false for every state.

The vendor-firewall capability gate and signed runtime payload remain unchanged. This is an internal test build, not an intermediate user APK delivery.
