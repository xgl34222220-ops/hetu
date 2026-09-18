# Home Page Override

The home page is the trust/status page. It should answer three questions immediately: **am I protected, is the proxy healthy, and is anything wrong?**

## Layout

- Keep one clear protection hero at the top; do not stack several equally large cards.
- Show protection state, current mode and the primary on/off action before secondary metrics.
- Put rule count, historical blocks and block rate in one compact metric row.
- Proxy control and device/protection details are secondary shortcuts below the hero.
- Maintenance text and diagnostics should use low-emphasis surfaces instead of another hero card.

## Visual treatment

- Healthy state: green label/icon plus explicit text such as “正在保护”.
- Disabled/degraded state: amber; actual failure/destructive state: red.
- Hero can use a softly selected/tinted surface, but not a bright pure-white card with a large shadow.
- Primary action gets the strongest button emphasis on the page.
- Keep decorative glass out of the content area; reserve it for the bottom dock.

## Interaction

- Protection toggle must show loading/disabled state immediately.
- No layout jump when status text or progress changes.
- Refresh remains a 48dp touch target.
