# CI fixture repair and evidence transport

These files belong to the follow-up after the failing f25ef407 build. Production Android/native files and runtime.patch are unchanged. The two host fixture models are bound into inputs.json; all original 39 test methods and process budgets remain unchanged.

The historical-host log contains 114 checks across 11 historical source views. Those are historical pipeline continuity checks, not current V20.93 Android or device acceptance. The PNG round-trip is a private synthetic transport check, not an actual rendered UI screenshot.

The fixed allowlist transport adds exact PNG bytes with producer SHA, path, dimensions, ordered chunks and SHA256 to the existing authorized CI job logs. It does not replace the artifact upload, change permissions, generate an image, or promote missing screenshots to passing tests. This supplements the artifact download path that returned HTTP403 locally.
