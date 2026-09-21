# test.104 — shared non-home layout and inline refresh feedback

Based on the user's supplied checklist. The two named videos 156695.mp4 and 156700.mp4 were not found in the conversation listing or Library lookup; no fresh side-by-side frame analysis is claimed. This does not touch the home instrument layout, icon downloader, source YAML, Root, DNS, IPv6 or connection recovery.

## Implemented
- Strategy cards: left-aligned existing 32dp configured image tray, 15sp title/11sp type/12sp selection; min 96dp and adaptive heights. Protocol labels in node cards are 11sp, names 12.5sp with two reserved lines. The node footer stays aligned for one/two-line names; minimum height is 88dp (not reduced to the 64px web example). UDP labels are shown only for a reported UDP-capable node. Delay and selection remain independent actions.
- Accordion uses one clipped 300ms height transition with cubic(.16,1,.30,1), retains exit content, keeps the 15ms capped node reveal and honours reduced motion. No extra translation competes with the height animation.
- Tickets use an 18sp remaining value, 12sp node count/host/date, 13sp full used/total text, real progress and compact percentage. Refresh owns one fixed 48dp icon slot for idle/loading/success/failure. Per-card failure messages retain previous data. Library bulk refresh preserves the existing 3-provider concurrency limit; it no longer labels swallowed failures as full success.
- Error details expand inline, only on request, with existing redaction and copying. Refresh feedback no longer uses an AlertDialog. Destructive-action confirmations are retained.
- Tools, primary settings, advanced settings, network automation and focused secondary settings share WorkspaceSettingRow: 16dp padding, 36dp icon column, 12dp gap, fixed title start and top baseline, 15/20sp title, 12/18sp body; extra lines grow downward. Group dividers align to the common text inset at .5dp. Existing grouped rules remain grouped and ordered.
- Existing native Sora editor, symbol/Tab bar, search, Ln/Col, undo, explicit save and syntax checks remain. Editor controls, viewport and error rows align at 16dp. No format-on-type or automatic YAML rewriting.
- Overview retains real three-column counts and current-connection ranking. Existing sampled paths gain a faint area fill bounded to observed time; no synthetic samples or load values.

## Verification scope
Existing Android and network gates remain required. New checks cover title/chevron alignment in light/dark/large-font, node footer alignment and UDP semantics, 48dp refresh phases and inline diagnostics, and intermediate accordion heights/reduced motion. Actual device IME behaviour, frame rate, blur and remote icon availability are not established by host renders.
