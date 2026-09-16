# Reference Video UI Contract

The uploaded BoxProxy reference is a structure and interaction reference, not merely a color reference.

## Home
- Compact status hero with runtime, core/mode/config and direct reload/stop/restart controls.
- WebUI and logs as first-class compact actions.
- Latency is a dedicated three-column panel: current / average / fastest.
- Network and resource cards stay compact; subscription status remains visible without large decorative slabs.

## Panel
- Six compact tabs: Overview / Nodes / Subscriptions / Connections / Rules / RuleSets.
- Strategy groups use a two-column, single-layer warm-glass treatment with soft vertical tint and sub-1dp outline.
- Strategy group selection opens a large rounded modal sheet instead of inline expansion.
- The sheet includes group title, test-all action, glass node rows, per-node latency, selected check state and a full-width confirm action.

## Tools and settings
- Grouped list hierarchy, compact 12–16dp corners and restrained spacing.
- File manager is available for the Bichen proxy runtime directory.
- Application management, core management, subscriptions, base proxy configuration, WebUI and runtime logs are first-class entries.

## WebUI
- Browser-like top bar with back, address/name, refresh, dashboard switch and cache clear.
- Dashboard switcher is a bottom sheet.
- Built-ins: local dashboard, Zashboard, MetaCubeXD and Sing-Box Dashboard; custom dashboard URLs can be added and removed.
- Only the loopback-local dashboard receives the controller secret automatically. Remote dashboard origins do not receive the secret from Bichen.

## Quality bar
- Reproduce the reference's information hierarchy, spacing, glass depth and interaction model rather than copying only its colors.
- Improve touch targets, latency feedback, loading/error states and Android OEM rendering stability.
- Avoid stacked translucent RenderNode surfaces; use one glass layer plus a subtle outline for dense cards.
