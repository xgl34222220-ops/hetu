# test.103 — native editing and coherent monitoring

Implements the user's supplied comparison checklist against test.102. Only the older 156417.mp4 was available in the conversation file listing; this change does not claim a fresh frame-by-frame comparison of two new recordings.

## Actual changes
- Remove the repeated Hero throughput row. Keep the baseline-aligned one-piece home panel, add matching 14dp title icons and one compact accessory per header. No fake health, bandwidth or signal-strength values.
- Preserve 32dp configured-image trays, original URLs, cached colors and two-column in-place group wells. Move the expansion arrow beside a compact latency chip. Image download policy is unchanged; device-side remote failures remain unverified. The current renderer already has no tune fallback, so this update does not claim to have newly removed one.
- Extend the existing Sora 0.24.6 native editor rather than replacing it with a text field: dedicated undo/redo/search/outline/save strip, literal asynchronous search, full Tab/symbol row, and live native cursor Ln/Col. Tab inserts spaces or indents an explicit selection using native undo. Plain symbols insert literally. Save validation and explicit-save behavior remain unchanged. Editor content is never copied into Compose on every keystroke.
- Shared subscription ticket: large remaining quota, blue percentage badge, real used/total progress, expiry/update time and expandable upload/download detail. Missing quota remains missing.
- Overview: actual strategy count, controller rule-row count and current connections, with a ranking by current connection count. Rule count is fetched only when entering/refreshing overview, not per frame. No rule or node is modified by inspection.
- Transparent selector, same-window frosted menu, shared material and measured dock+30dp spacing remain. Root, DNS, IPv6, source configuration, node selection and connection lifecycle are not changed.

## Verification boundary
Keep the existing baseline, actual glyph-alignment, light/dark, large-font, transparent-corner, cached-image, kernel-network and lifecycle tests. Add native symbol/indent/undo and literal-search tests plus workbench and overview renders. All screenshots use fixtures. ColorOS hardware keyboard/IME/blur behavior, sustained FPS and live remote image hosts still require device testing.
