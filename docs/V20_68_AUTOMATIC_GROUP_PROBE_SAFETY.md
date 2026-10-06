# V20.68 internal validation candidate

## Preserve automatic-group choices
The upstream revision named by both packaged core build records (ab405bad5beeeac8b003bb01f60f134f6df54471) clears fixed choices on non-Selector groups in `/group/{name}/delay`. Both binaries report `vcs.modified=true`; reading the matching upstream source does not prove binary identity.

Automatic/non-Selector group testing now uses the existing bounded, provider-aware leaf-probe path instead of that endpoint. Selector groups retain their parallel group endpoint. No temporary unfix/reselect PUT or core replacement is added. Missing/malformed individual responses retain earlier measurements. Large automatic groups may take longer; busy state remains truthful. Core-internal failover and modified-binary behavior are not claimed experimentally verified.

Primary source: https://github.com/MetaCubeX/mihomo/blob/ab405bad5beeeac8b003bb01f60f134f6df54471/hub/route/groups.go

Three targeted tests failed before the change and passed afterward. The full operation-safety and provider/latency subset passes46 tests. CI expands to232 selected Android regressions.

## Native test progress
V20.67's KVM comparison installed and cold-started the real APK in2.840seconds with no ANR since boot. Device security metadata stayed unchanged and the owned emulator/launcher were reaped. This does not establish all device behavior. The test stopped because its first accessibility dump had not produced a file; immediate failure capture then obtained the real home PNG/XML.

Snapshot reads now retry readiness with unique filenames, so failed dumps cannot reuse stale UI. File access denials stop instead of escalating; three host tests cover readiness, denial and Root-error text inside valid XML. The language picker assertion now checks its actual Theme settings destination. App ANRs remain hard failures, including recorded ANRs during navigation. Native navigation and the five WebView states still require this run's evidence.

No concept direction, signing identity, Root payload, vendor-firewall guard or main/release branch change.
