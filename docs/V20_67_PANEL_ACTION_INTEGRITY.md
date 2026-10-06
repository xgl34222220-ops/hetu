# V20.67 internal test candidate

## Correctness changes
- Strategy search preserves real group totals/current-node metadata and the complete group latency target. Multi-word searches use consistent AND-by-term matching.
- Missing/malformed group-delay entries retain earlier measurements; explicit integer results remain authoritative.
- Node selection requires a fresh controller readback. Stopped, removed, duplicate and stale actions cannot claim success; pending flags clear on failures/cancellation.
- Late refreshes cannot overwrite newer state across controller, runtime, provider, version and history suspension points. A subsequent fresh refresh remains allowed.

## Checks
Two current-UI search regressions reproduced before the fix; the search/expansion and existing model subset passed15 checks afterward. The new loopback controller suite passed27 checks after reproducing missing-result, unconfirmed-selection and stopped-state races. No test touches real Root or user subscriptions. CI now runs213 selected Android checks plus existing script/browser gates and lint. The historical full suite is not claimed green; many old routes are superseded and one render exception remains unresolved.

## Installed APK gate
V20.66 rendered a real native homepage but failed with an app ANR under software emulation. High system CPU pressure is recorded, not assumed to exonerate the app. This candidate uses an owned official emulator process with existing runner/sudo KVM access, leaves device security metadata unchanged, confirms cleanup, and collects full bugreport/ANR diagnostics on failure. App ANRs remain hard failures. No guest Root, Magisk, permission/group/ACL/SELinux changes or real proxy start is added.

The concept direction and runtime147 shell/payloads are unchanged. Shipped-core URLTest/Fallback pinning and K80 behavior remain unverified; controlled HTTP tests do not establish them. No intermediate APK delivery or full173-state pixel-parity claim.
