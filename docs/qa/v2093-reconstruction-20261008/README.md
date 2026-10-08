# V20.93 recovery continuation and reconstructed implementation

This work continues the original `test/v20.76-new-ui` branch from the recovery archive at `5af355d19fc41c3ae09902ca45fa1d70562ac402`. The effective production predecessor is `cffeff08ee38c7220922bdb36de81b621c935daf`. Original archive bytes and their provenance remain unchanged.

The restored 13 sealed whole files are documented in the original archive and application reports. Some have subsequently received separately identified fixes. The lost final root/autostart/manager code, touch and readiness tests, and final pipeline were **newly implemented**, not falsely identified as exact recovery. Historical docs-only checkpoint commits preserve interim patches; they are not APK or production acceptance.

## Implemented candidate

- Native health checks, bounded automatic recovery, cancellation receipts, generation-safe watchdog records, transaction rollback, truthful incomplete-stop results, and physical bootstrap-rule revocation before atomic restoration.
- App manual and automatic intent validation, shared recovery admission, staged deadlines, durable cancellation, and compensation that retains unknown state when native cleanup cannot be confirmed.
- Early identity-checked first snapshot, protected in-flight latency measurements, separate failure/timeout feedback, and startup configuration rendering of large content.
- Shared low-saturation diffuse glass, consistent panel cards and dock clearance, long-name layout, state-driven touch feedback, and reduced-motion/low-memory/power-saver rendering gates.

## Evidence at source submission

`native-final/verification.json` identifies the independent snapshot: all original 60 test bodies retained and passing, plus 26 new passing host checks. The separate pre-existing legacy dispatcher failure and exact-baseline reproduction are retained, not labelled passing. `app-recovery-host.json` contains the 11-check method harness and the pinned predecessor failures. It isolates privileged/Android boundaries and does not execute a full Android deployment. Latency host evidence is in `../v2093-recovery-20261008/LATENCY_RECOVERY_CONTINUATION.md`.

The final `RootStartTransaction93Test.kt` contains 7 authored tests, SHA256 `b469c64b5a71880fbe5237bf24b243c332fc99984dfe9df90ec78c86e0ac7166`. An earlier verbal count said 7 while the file had only 6; the missing cancellation transport handling and seventh empty-receipt race test were subsequently added before production submission. This count is not an Android execution result.

The local environment has a Java compiler module but no complete Android SDK/Gradle toolchain. Earlier candidate notes describing a missing compiler refer to the absent executable, not absence of that module. Same-commit Android build, full XML identity checks, lint, AOSP API35/36 and visual review remain required after this submission. Historical successful CI is not acceptance of this code.

## Device and provenance boundaries

No HyperOS or ColorOS phone was accessed. Phone boot, actual Google/Play connectivity, GPU power cost and the overheating cause are unverified. No request for hot-phone reproduction, charging, repeated reboot or logs was made. The two supplied Library JPEG transfers returned HTTP403; no claim of inspecting their pixels or matching their appearance is made. UI screenshot coverage and actual visual findings will be recorded after CI.

Native automatic admission remains conservative per boot: a manual success does not refill an exhausted automatic ledger. Host command-timeout behavior cannot guarantee scheduling under an unresponsive device kernel. The old uncommitted workspace loss and any responsibility for cleanup are not independently established.
