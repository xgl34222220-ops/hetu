# Hetu Design System — Master

> Scope: 河图 Android App / Jetpack Compose. This is the global visual source of truth. Page files under `pages/` may override it when the information density or interaction model is different.

## Product direction

Hetu is a privacy/network utility, not a showcase app. The visual direction combines:

- **Minimalism / Swiss-style hierarchy** for status, settings, rules and node data.
- **Restrained glass** only for persistent navigation or floating chrome.
- **High-contrast semantic state** for connection, protection, warnings and failures.
- **Dense but breathable mobile utility layouts** rather than oversized decorative cards.

Do not mechanically copy LuoShu. Keep the good parts of its spacing, motion and floating dock, but let Hetu use a calmer network/security-tool hierarchy.

## Visual hierarchy

1. **Page background**: softly tinted blue-gray in light mode; deep navy/OLED-adjacent in dark mode.
2. **Content surface**: near-background cards, usually flat with tonal separation instead of large shadows.
3. **Control surface**: slightly stronger tone for icon buttons, filters and compact controls.
4. **Selected surface**: primary-tinted surface; selection must not rely on color alone when the state matters.
5. **Navigation chrome**: the only place where the floating glass language is emphasized.

Avoid huge pure-white slabs, stacked translucent white overlays, and multiple shadow layers. They are visually noisy and have also been unreliable on some Android 16/OEM compositors.

## Color roles

- Primary/trust: use Material theme primary, biased toward restrained shield/network blue when a fixed fallback is needed.
- Connected/protected/success: green only for real healthy state.
- Warning/degraded: amber.
- Failed/disconnected/destructive: red.
- Secondary text: neutral and readable in both themes; never use low-contrast gray-on-gray for important data.

Color should reinforce a label/icon, not be the only carrier of meaning.

## Typography

- Page title: 26sp / 34sp, Bold.
- Section title: 17sp / 24sp, SemiBold.
- Item title: 15sp / 21sp, SemiBold.
- Body: 14.5–15.5sp with 21–23sp line height.
- Caption/metadata: 12–12.5sp; avoid going below 11sp.
- Numeric latency/rate/status values should stay tabular-looking and visually stable; do not enlarge every number into a hero metric.

## Spacing and shape

Use a 4/8dp rhythm.

- Phone horizontal gutter: 20–22dp.
- Related control gap: 8–12dp.
- Card internal padding: 14–20dp depending on density.
- Section gap: 16–24dp.
- Main card radius: 18–22dp.
- Large hero/navigation radius: 24–28dp.
- Small control radius: 10–14dp.

Android interactive hit targets must be at least **48dp** even if the visible icon is smaller.

## Elevation and borders

- Normal data cards: 0dp shadow; tonal surface separation first.
- Selected/emphasized card: up to 1dp.
- Floating dock: stronger shadow is acceptable because it is persistent chrome.
- Avoid a visible border around every card. Use subtle outline only where separation would otherwise disappear in light/dark mode.

## Motion

Motion should explain state, not decorate it.

- Press feedback: roughly 80–150ms.
- Small state changes: roughly 160–240ms.
- Navigation selection: spring motion is allowed, but keep scale changes subtle and layout-stable.
- Loading/testing: show clear progress without shifting surrounding layout.
- Avoid animating width/height of long lists when opacity/translation/state replacement is enough.

## Navigation

- Bottom navigation stays at four primary destinations.
- Keep predictable back behavior and page state.
- Persistent bottom chrome must respect navigation-bar insets and never cover list content.
- Secondary functions belong inside the relevant page rather than creating more top-level tabs.

## Compose implementation rules

- Keep UI state hoisted and composables focused.
- Prefer stable keys in lazy lists.
- Keep expensive parsing/network work out of composables.
- Use semantic theme tokens instead of per-screen raw colors.
- Avoid deep Box/Surface nesting unless content actually overlaps.
- Accessibility labels are required for icon-only controls; visible text + decorative icons should not duplicate spoken labels.

## Page routing

- `pages/home.md`: status-first, trust/health overview.
- `pages/proxy-panel.md`: dense real-time proxy/node workspace.
- `pages/apps-rules.md`: utility lists for app bypass, ad-block rules and activity.

If a page has no override, use this master file directly.
