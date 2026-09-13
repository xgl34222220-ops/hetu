# 0.4.0-test.3: proxy interface and observation improvements

Based on delivered test.2 / 7e6e3c82223875dc79023fb512ec8ebd82995ef7.

## Implemented
- Native proxy console split into Overview / Nodes / Connections / Configuration. Shared light/dark appearance, green layered cards, floating navigation, 48dp touch targets, proper system/keyboard insets. Original launcher artwork unchanged.
- Search strategy groups and nodes. Highlight actual selected node, allow individual delay tests, refresh selector after success, avoid treating automatic policy groups as manual selectors.
- Stable, reusable connection rows with domain/IP/protocol/rule/chain search and details. Clear old live snapshots on stop or session change. Periodic reads do not consume the user-action busy flag.
- Optional local observations sampled every two seconds by the service and capped to 300 unique connection IDs. OFF by default. Clear/off invalidates in-flight snapshots by epoch; no foreground-app guessing, subscription credentials, packet payloads, or new upload endpoints.
- Configuration metadata contains no source text/tokens. Identical subscription data preserves rollback; a changed revision rejects stale download commits. Existing atomic YAML/URL storage and native configuration preservation remain.

## Accurate boundaries
Recent observations are sampled live connections, NOT complete history, DNS logs or REJECT events. Short-lived connections may be missed. Existing legacy per-app exclusion list is NOT claimed to govern Mihomo. Native filtering, protocols and Root TPROXY/eBPF remain as in test.2.

## Validation
Run original protocol/module/UI regressions and original separate-UID SOCKS/TUN traffic checks, then additional production storage and light/dark UI checks with clearly marked fixtures. Only final successful CI is acceptance. Target OEM hardware, private subscriptions, physical network switching, Root restoration and leak certification remain untested.

Native libraries are reused byte-for-byte from verified test.2 artifact 10312001288, not silently downloaded from an unpinned latest release. Bridge/upstream source is unchanged. Workflow documents exact checksums and build provenance. App preview code403 is signed locally with the existing test certificate; private key is never sent to CI or repository.

UI references:
https://developer.android.com/develop/ui/views/layout/edge-to-edge
https://developer.android.com/guide/topics/ui/accessibility/views/apps-views
