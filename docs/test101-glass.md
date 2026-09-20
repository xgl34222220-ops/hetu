# test.101 — shared frosted material and same-window menu

UI-only follow-up to test.100. The protected runtime baseline is NOT updated: Root, startup, DNS, IPv6, node selection, source YAML and connection management retain the test.100 implementation.

## Changes

- The home more-menu now uses an activity-level overlay after the page capture, rather than a separate Popup window. It is anchored using window coordinates, clamped within the viewport, supports outside/back dismissal and restores focus. Page contents remain in place; opening the menu never calls proxy control. This removes the cross-window rendering boundary; it is not a claim that every OEM blur implementation was tested.
- Home/tools/settings no longer draw a second opaque page fill over their existing ambient background. Shared material uses 20dp card blur, 24dp popover blur, .96/.85 card wash, .75/.65 menu wash, fine inner/outer directional rims and contact plus diffuse shadows. The Haze layer has no additional opaque background wash. Blur disabled still retains gradients and rims.
- Group expansion and inactive config cards now use the shared 12dp sunken material: .78 blue-gray wash, 20dp corner, short inner top shadow. The 16dp gap, two-column nodes, overlay selection badge and existing expand/collapse behavior remain.
- 32dp configuration-icon trays and original URLs/colors remain. Resetting the icon-load state before early returns prevents a changed/removed URL retaining the prior bitmap. Network downloads are not changed; unknown remote failures are not hidden with substitute brand logos.
- Existing transparent config selector, tonal import action, equal-width home buttons, sans data numerals, transparent untested latency and measured dock+30dp spacing remain unchanged.

## Verification boundary

Focused tests open the actual menu over production components and test one activity root, actions, back/outside dismissal, RTL/edge positioning, distinct cached images and URL removal. Existing light/dark, large-font, number, node-baseline and transparent-selector regressions remain enabled, as do all network/lifecycle/kernel gates. Rendered images use fixture data; hardware ColorOS blur, scroll smoothness and the user's remote image server still need device validation. No synthetic traffic or signal-strength data is added.

Install with the existing fixed preview certificate. This is not a new network-fix claim and does not require restarting a working test.100 proxy just to see the UI.
