# Proxy Panel Override

The proxy panel is a dense real-time workspace. It should prioritize scan speed, state clarity and touch reliability over decorative consistency with other pages.

## Information density

- Tabs: keep Overview / Nodes / Subscriptions / Connections / Rules predictable; avoid adding decorative secondary navigation.
- Strategy groups: compact two-column cards with group name, current node and delay badge. Tapping a group opens a focused node-selection sheet instead of expanding a long inline grid.
- Node selection sheet: group title + node count + “测试全部节点” + compact one-column node rows + a fixed confirm action.
- “全部测速” is a first-class action near the group list; single-node testing remains available on each latency badge.
- Failed probes show “超时” only for a fresh failure with no valid recent result; do not paint an entire provider red because one generic probe endpoint failed.

## Visual treatment — reference video

- Do not reduce the reference to its color palette. The important part is the layered surface treatment and hierarchy.
- Strategy group cards use a **single-layer frosted/glass look**: translucent warm-gray gradient, very subtle hairline edge, no heavy Material elevation, compact 14–15dp radius.
- Selected/active strategy cards use a warm primary-tinted glass gradient plus a check marker; selection is not communicated by color alone.
- Node rows inside the selection sheet use the same soft glass language, with a slightly clearer selected tint and check marker.
- Latency is a small rounded badge anchored at the right edge, not loose text floating in the card.
- Avoid nested translucent Surface + clip stacks. Keep the glass effect on one drawing layer so Android/OEM partial invalidation cannot leave white rectangular artifacts.
- The bottom sheet uses a soft dim scrim and a large rounded top edge, matching the reference hierarchy rather than expanding nodes directly inside the grid.

## Motion

- Sheet open/close, selection and testing indicators should be subtle and layout-stable.
- Sorting can animate item position only if it stays smooth; otherwise prefer immediate stable reorder.
- Node press feedback should not resize the surrounding grid.

## Touch and accessibility

- Delay test target >=48dp even if the number itself is smaller.
- Selection state should be semantically exposed and also visible with a check marker.
- Icon-only refresh/sort/test controls need accessible labels.
