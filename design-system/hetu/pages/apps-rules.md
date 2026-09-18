# Apps / Rules / Activity Override

These pages are utility lists. They should feel faster and denser than the home page.

## App bypass / ad-block app list

- Always prefer the local PackageManager icon and real app label/package name.
- Use a compact list-card or grouped-list rhythm; no giant tiles.
- Search stays pinned near the top of the content hierarchy.
- System-app / selected-only filters use compact chips, not full-width cards.
- Toggle state must be visually and semantically clear, with the whole row optionally tappable only if it does not conflict with the switch.

## Rules

- Put total effective rule count and profile/update state in one summary surface.
- Rule sources should read like settings rows: icon, name, short metadata, switch/action.
- Keep destructive/remove actions visually separated from update/enable actions.
- Long source URLs or package/domain strings truncate gracefully; never force horizontal scrolling.

## Activity / statistics

- Use data density over decoration: timestamp, app/domain, action and count should align consistently.
- Status colors support text/icon labels; color is never the only differentiator.
- Empty/loading/error states get dedicated inline feedback instead of blank space.

## Visual treatment

- 18–20dp row/card radius.
- 12–16dp internal vertical padding depending on density.
- 0dp shadow for normal rows; tonal separation from the page background.
- Real brand/app icons keep their proportions; do not recolor arbitrary app icons.
- Use 48dp minimum touch areas for switches, row actions, refresh and filters.
