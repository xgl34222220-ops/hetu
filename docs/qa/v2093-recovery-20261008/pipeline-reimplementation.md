# V20.93 recovery pipeline implementation

This is a new pipeline implementation over `cffeff08ee38c7220922bdb36de81b621c935daf`, with the original partial-recovery archive pinned to `5af355d19fc41c3ae09902ca45fa1d70562ac402`. It is not the missing final pipeline from the interrupted workspace. The 12 recovered Python originals remain untouched in `originals/pipeline`; their individual provenance and partial original SHA coverage remain as recorded there.

The current layer is generated from the exact predecessor tree. Every effective Android input is hashed, every change is explicitly recorded, and every unmodified input retains its prior hash. All historical patch layers and QA records remain bound to their original bytes. Original Android test sources, including the 644 selected tests and separate 36 supplemental tests, are preserved; the one declared test change is the runtime revision contract expectation 153 to 154. Native payload changes are limited to the two authorized shell scripts; the other 21 runtime assets, dependencies, package, manifest, permissions and signing identity are preserved.

The historical V20.92 workflow is retained for manual execution. Its automatic push trigger moves to `v2093-build.yml`, which retains all original build, lint, fixed certificate, runtime payload, MaterialKolor ABI, historical source/before, XML identity, supplemental36, AOSP API35/36 and sustained host/native 900-second stages. The exact V20.92 historical view is reconstructed for old source gates; historical before results are explicitly attributed to the original predecessor, never to the current candidate. The new native suite is `tools/qa/test_root_safety93.py`. New Android classes are selected by their actual source identities rather than a guessed total. Current UI previews include `outputs/ui93`.

Reconstructed native and Android regressions must execute on the final same-SHA candidate. Missing original final 93 fixture bodies, original raw logs and missing final before/delivery/runner files are not fabricated. Retained historical receipts do not count as current execution. The workflow produces test evidence and a candidate artifact; its success alone does not authorize user delivery or establish HyperOS/ColorOS boot, temperature, Google/GMS connectivity, phone Root privileges or real firewall correctness. The unresolved safety failures must still pass their actual current regressions and source review.

## Local execution boundary observed

The recovered workspace has OpenJDK 17 runtime with the compiler module but no `javac` launcher, Gradle, Android SDK, adb, sdkmanager or Gradle cache. Host Java can use `java -m jdk.compiler/com.sun.tools.javac.Main`; Android tests, lint, APK construction, signing and AOSP installation require the existing CI toolchain: JDK21, Gradle9.5.0 and Android37/build-tools37.0.0. The four ignored native binaries are restored in CI only from the pinned V20.81 effective-source archive and verified against their existing hashes. No substitute binaries or signing key are introduced.

## Freeze and run

After coordinating a stable source snapshot:

```sh
python3 .github/scripts/generate_continuity93_source_layer.py
python3 .github/scripts/test_continuity93_evidence.py -v
python3 .github/scripts/run_continuity93_baseline_hosts.py
```

Commit that source and its generated layer together to the original test branch. A later repair regenerates the layer and must obtain its own complete same-SHA CI evidence. Do not count an earlier candidate's tests as the later candidate's tests.

## Evidence locations in the candidate run

- `Hetu-V20.93-UI-verification`: actual primary JUnit XML, Android lint, `out/verification/continuity93-tests.json`, effective-source proof, native safety logs, historical before evidence and UI PNGs. The primary result JSON records observed `git HEAD`, validates it against `GITHUB_SHA`, and hashes the complete current source layer.
- `Hetu-V20.93-supplemental-36-tests`: separate original36 JUnit XML and source-identity verification.
- `Hetu-V20.93-Android-35-smoke` / `Hetu-V20.93-Android-36-smoke`: actual same-build APK installation, navigation/auth/real WebView evidence, owned-process cleanup and the API36 native900 evidence.
- `Hetu-V20.74-palette-crash-baseline-Android-36`: explicitly historical crash reproduction.
- `Hetu-V20.93-effective-source`, APK manifest and six parts: source/APK transport identity only, not phone acceptance or permission to distribute an unverified build.

`out/verification/historical-before92/historical-attribution.json` binds the V20.92 reproduction to `cffeff08ee38c7220922bdb36de81b621c935daf`, records the current candidate separately, and declares `usedAsCurrent93Acceptance: false`. Its nested V20.91 reproduction remains attributed to `d7295d1aec1ffb17a0076d4dc9a8a4122d467447`.
