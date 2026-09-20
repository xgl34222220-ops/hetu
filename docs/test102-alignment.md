# test.102 — one baseline-aligned home instrument panel

Presentation-only follow-up to test.101. Existing network/runtime baseline, source YAML, node selection, DNS/IPv6, icons, menu and dock code remain unchanged.

The requested basis is the user's three-tier alignment specification and the existing code. The named 156641.jpg was not returned by the file lookup; this change does not claim new pixel measurements from that image.

- One 24dp crystal shell replaces the four separate instrument cards. Only .5dp low-alpha dividers remain. The retired InstrumentCell/InstrumentBento implementation is removed rather than left as another active layout path.
- Each quadrant uses the same header/value/support slots and 2dp bottom rail, with 6dp inter-slot spacing. Actual glyph first baselines are placed explicitly, so monospace IP, sans values, badges and loading text do not shift the other rows. Heights scale with system font size; narrow/large-font layouts use one column instead of clipping.
- Four titles use the same 11sp scale. Main values use 16sp sans tabular numerals, with bounded autosizing where required; IP retains monospace. Standard byte formatting retains a space before B/KB/MB/GB, and CPU is shown with a space before %. The existing binary-byte calculation is not changed.
- To retain full speed units on a phone, downlink is the main speed value and uplink is the supporting line. Memory is the resource main value and CPU its supporting line. Used/total quota, both speed directions, CPU/memory and network region remain available.
- All headers have consistent tonal accessories. Resource says running/stopped based on core state, not unverified healthy/normal. Speed rail is the actual upload/download share, not a made-up bandwidth percentage; quota and CPU rails use their supplied real values. No data means no progress fill. Network rail is a status marker, not signal strength.
- A tap on the network quadrant still switches LAN/egress; a long press opens full addresses, region, check time and failure details, including long IPv6 text. Tapping quota still opens subscriptions. No UI action starts or restarts the core.

Verification includes light/dark at 320/360/412dp, 1.5x fonts, measured title/value/support baselines and rail positions, dynamic-value changes, unknown/zero state, no synthetic health, full-unit high rates, and action separation. Existing rendering and network gates remain required. Rendered fixtures are not a ColorOS hardware test.
