# V20.62 — internal concept verification candidate

This is an internal verification build, not a declaration that all 173 reference states are pixel-identical or ready for user delivery. The V20.60 runtime safety gate remains unchanged; no OEM firewall clearing, shared UID bypass, mark/cgroup isolation, or other network-rule change is included.

## Verified local changes

- Measured configuration/file typography and spacing, compact filename menus, reference-specific choice-menu scrims/selection markers and separate config/file form footers. System typefaces remain in use; editors use the system monospace face.
- Child-page toolbar actions stay above the large title; root Tools/Settings keep expanded-title actions. This avoids applying root-only geometry to file-manager children.
- Three approved core cards and their existing variant selector remain intact. Backup restore stays nondestructive.
- Reference-shaped confirmation/forms preserve validation and cancellation. Dialog margin dismissal is a sibling, not a clickable ancestor of the title/input semantics.
- Runtime editor jump/discard controls, license wrapping and sheet sizing, and resource graph density were corrected. Missing samples remain gaps; sample values were not fabricated in production.
- About/card artwork uses the user-supplied concept icon unchanged (SHA256 `31573825cac7536b442c67e7a8110fd5584715a347d9cc8e9ef50cc66b29d04c`). Launcher artwork is unchanged.
- Public-IP details copy the first five available fields. Optional organization/address-type/timezone/coordinates come from the existing provider, through the same loopback proxy, timeout, size limit and cache. Missing/invalid fields stay unknown; valid zero coordinates remain valid. No live lookup was performed by the tests. Provider schema: https://ipwhois.io/documentation .

## Evidence and limits

- 160 selected Android tests passed locally, with zero failures/errors/skips, after integrating V20.58/V20.59 UI changes and V20.60 startup repair and the subsequent explicit V20.61 overlays.
- Current inventory: 160 controlled native reference states, 8 current offline native states and 5 WebView-content states awaiting rendered-browser supplementation. See [current 173-state inventory](qa/concept-173-current.md) and its machine-readable JSON. Capture counts are not visual acceptance counts.
- Source fixture data is restricted to tests. Root bridges fail closed; network fixtures are loopback-only and destructive dialogs are cancelled. The narrow 320dp long-name flow and child-toolbar placement are explicitly exercised.
- Scripts/runtime editor need a documented API-28 raster fallback in Robolectric. Device bars, keyboard, hardware shadows/blur and Android WebView rendering are not covered by that fallback.
- CI is configured to render the built-in HTML dashboard in Chromium with isolated API fixtures. Those images supplement the five content states; they do not substitute for Android WebView or K80 hardware verification.
- No intermediate APK is intended for user delivery from this verification pass. Further visible differences found during image review will be corrected before the UI candidate is presented.

- Latest integration preserves remote V20.61 About/core text and multi-window screenshot intent. Selectors now distinguish duplicate text and scroll lazy notification sections before clicking. Implicit sitecustomize mutation is disabled; all changes are hash-pinned overlays.
- Home latency/selected-node/WAN labels and connection host/protocol typography were measured locally and adjusted without changing real measurement paths.
