# V20.70 internal UI validation candidate

## Core manager
The three approved cards and their variant selector are retained. Card-relative geometry was normalized by393/425 against references03A/034–035; glyph offset18/20dp and busy-button offset118dp follow measured reference positions. Secondary actions remain pale, busy actions show disabled“处理中”, and progress displays actual transferred/total bytes. Unknown totals stay unknown. Status never claims running for a stopped proxy. Network paths, imports, deletion and core runtime are unchanged.

## Collapsed root pages
Tools/Settings retain visible navigation after scrolling, matching03A/002 and04/002. Other root-page scroll behavior and child-page dock hiding remain intact. Settings uses its first group's measured height as extra trailing scroll clearance, so the appearance group can reach the collapsed toolbar instead of the first group being permanently trapped onscreen. The red tests demonstrated both an offscreen dock and insufficient scroll range before correction; regression also navigates into a child page and returns.

Eight scoped tests passed after the scroll correction and initial core geometry. The final busy-only spacing adjustment passed its exact capture/behavior test again. This is followed by the existing232-test CI gate, lint and browser tests.

## Native WebView evidence
V20.69's real Android navigation/DOM checks passed16 checkpoints with2.393s cold start, no recorded ANR, unchanged KVM permissions and confirmed process/forward cleanup. Independent artifact hashing and image inspection found connections and node-dialog PNGs byte-identical to the prior groups frame despite correct DOM. These two images were therefore not visually accepted.

V20.70 waits for two WebView animation frames, rechecks DOM after a compositor-settle interval, and rejects missing or duplicate populated-state screenshots. Three host guard checks pass; the new guard also rejects the actual V20.69 stale screenshots. Three CDP host protocol checks pass. New native pixels still require CI and independent review; these checks do not replace visual acceptance.

No claim of173-state pixel equality, K80 or real core/Root validation. Vendor-firewall capability limits and runtime payload remain unchanged. No main merge or release.
