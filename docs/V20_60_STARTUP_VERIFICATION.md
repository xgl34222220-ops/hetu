# Boot/startup hotfix verification

## Confirmed defect

The Java root manager passes 30 payload fields (31 including `start`). The actual packaged UI15 runtime script, restored after UI overlays by the build workflow, only accepts 23/24 total arguments. An inert execution of its real dispatcher with 30 anonymous payload fields reproduces exit 1 and `参数错误`, before any core launch.

The effective source also retains the old dispatcher. Its extra 113 lines of DNS/process/OEM controls have never reached the packaged script. This patch uses the actual packaged controller as the safety baseline, adds only the protocol compatibility gate and keeps the previously shipped networking implementation.

## Changes

- Accept old 22/23-field payloads and current 30-field payloads, preserving empty fields and quoting.
- New fields must request the existing behavior: DNS TCP and UDP on; performance/OEM cleanup off; CPU, memory and IO controls empty. Non-default extensions return a specific unsupported-control error before root/network mutation. Their actual values are absent from the user's diagnostic; this fix is not a promise of universal startup recovery.
- Never count an unknown root process observation plus a previous boot's stored running flag as boot restore success.
- Retry control-lock contention without revoking the recovery intent.
- Clear stale boot-success timestamps on an authorized restore attempt, and invalidate stale health on confirmed DEAD startup observation.
- Runtime revision 147 records the changed script separately from the unchanged native core token. Existing UI code disables runtime-upgrade prompts; that behavior is unchanged.

## Verification

- Real-dispatcher regression: `python3 tools/qa/test_boot_startup_protocol.py`, 19 checks passed. Current/legacy dispatch, exact payload round trip, empty/quoted fields, each unsupported extra control, invalid counts, preflight and POSIX syntax.
- Original packaged dispatcher reproduced exit 1/参数错误 with the same current arity.
- Five new Android boot receiver tests cover boot, unlock, opted-out, explicit stop and upgrade-no-restart behavior. All five passed; the 31 original RuntimeYaml15Test/RuntimeStartup15Test/AdblockSnifferTest cases also passed (36/36 total). Production Kotlin/Java and test compilation completed.
- Older `tools/test_root_controller.py` and `tools/test_root_ipv6.py` are not passing on the unchanged UI15 payload either: fixtures lack conntrack/connmark matching and the newer TCP capability mock. Identical failures reproduced on original and patched payload. These are disclosed, not skipped or counted as passes.
- No phone interaction or actual Root/network operation was performed. Device reboot, vendor background policy and real network behavior remain unverified.

## Packaging requirement

Preserve the fixed script after validating the original source payload hashes. Keep the other 21 payload files byte-identical. Compare the final APK's script bytes against the tested SHA-256 in manifest.json. Export effective source only after final payload assembly. Keep the established signing identity; no generated replacement key.

## Integration and delivery

- This candidate retains all remote V20.58/V20.59 UI overlays through commit `921ada3ddda219e9a3c94c645ec50b76240ab3dd`. The separate ongoing concept-coverage changes are not bundled into this startup hotfix.
- Fixes the V20.59 scale-picker test to scroll the lazy container until the target exists, then open it and assert an actual scale option. No assertion is removed to conceal a production failure.
- Version code 2060 / version name 0.11.0-v20. The existing package ID and fixed signing identity remain unchanged.
- The pipeline pins the tested shell SHA256 `96654eacde9009f0ad3f5ad31cd423147667548fd1a510b8bdef986a175ea7cb`; all other 21 original payloads remain byte-identical. It verifies these hashes again inside the built APK.
- The effective-source artifact is exported after the final payload assembly, so its shell matches the APK rather than being overwritten afterward.
- A full APK artifact is retained. Additional ordered 24 MiB transport chunks include per-chunk length/SHA256, total count, full APK length/SHA256 and package/signature reports. Chunks are only for tool-size compatibility; incomplete or inconsistent chunks must never be delivered as an installable package.
- Final integrated V20.59-plus-hotfix source compiled locally; the repaired scale-picker test and all five new boot tests passed (6/6). The broader 88-case integrated selection is the CI gate.
- Packaging negative regression rejects the original unpatched APK shell. Transport helper roundtrip passed, and an altered chunk was rejected before creating a deliverable.
