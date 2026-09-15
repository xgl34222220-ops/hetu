# Proxy Panel Override

The proxy panel is a dense real-time workspace. It should prioritize scan speed, state clarity and touch reliability over decorative consistency with other pages.

## Information density

- Tabs: keep Overview / Nodes / Subscriptions / Connections / Rules predictable; avoid adding decorative secondary navigation.
- Strategy groups: compact header + current node + delay + expand affordance.
- Node grid/list: use tighter cards than the home page, with obvious selected state and stable latency placement.
- “全部测速” is a first-class action near sorting/filtering; single-node testing remains available on the latency cell.
- Failed probes show “超时” only for a fresh failure with no valid recent result; do not paint an entire provider red because one generic probe endpoint failed.

## Visual treatment

- Normal node cards: flat/tinted surface, 0dp shadow.
- Selected node: primary-tinted surface + check icon/state marker; do not rely on background color alone.
- Latency: success/normal uses readable neutral or green only when truly healthy; timeout/error uses danger red.
- Keep group cards and node cards on opaque/tinted fills. Avoid stacked translucent Surface/clip layers that can cause OEM white-rectangle artifacts.
- Use 18–20dp radii for groups and 14–17dp for dense node cells; do not make every item a 28–30dp pill.

## Motion

- Expand/collapse and testing indicators should be subtle and layout-stable.
- Sorting can animate item position only if it stays smooth; otherwise prefer immediate stable reorder.
- Node press feedback should not resize the surrounding grid.

## Touch and accessibility

- Delay test target >=48dp even if the number itself is smaller.
- Expand/collapse state should be semantically exposed.
- Icon-only refresh/sort/test controls need accessible labels.
