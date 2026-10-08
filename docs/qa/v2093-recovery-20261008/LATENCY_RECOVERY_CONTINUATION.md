# Latency recovery continuation

This is a small new implementation on the original test branch. It does not claim to reconstruct missing final V20.93 VM/adapter/model/probe/test originals.

## Source boundaries

- `ProxyLatencyHistory.kt` was copied byte-for-byte from the sealed `originals/latency` file; SHA-256 remains `bbb6e53f52cc3e61c9c431af31a3dcb53e99f22b0f6ae3b3c4666db9967cf286`. No archive bytes were modified.
- `HetuViewModel.kt`, `PanelModels.kt`, `PanelAdapter.kt`, and `ProxyDashboardRepository.kt` are new changes to the checked-in cff baseline. Existing request owner, runtime/config/API identity, partial results and cancellation semantics remain.
- Existing Android test source bodies and assertions were not changed. New tests are `LatencyPresentationRecovery93Test` (4) and `LatencyFeedbackRecovery93Test` (8).

## Concrete changes

1. Ordinary polls cannot apply an old core latency history entry while that node has active probe owners. On completion, the existing measured timestamp still rejects polls started before the result. This applies the recovered history function at the real launcher VM call site.
2. A single-node controller/transport error retains its prior reading and emits a real error message only when that request still owns the node. An old endpoint's error does not show against a replacement request or clear its busy state.
3. Empty group/all waves no longer silently stop or claim a successful `0 / 0` completion. The app reports no valid measurements and explicitly preserves old readings.
4. Core-confirmed failure `-2` renders as failure, distinct from confirmed timeout. Alias/group probes expose their own busy state while their selected leaf has an old reading.
5. Changing the custom latency URL invalidates old repository measurements and cached metadata. Latency target settings are a separate observation-identity field: they do not invalidate an unrelated node-selection or provider transaction on the same controller.

## Actual local evidence

`latency-recovery-host.json` and the adjacent compile/JUnit logs record a host JVM run of the four pure presentation/history tests, all passing. Production History, Models and HomeRegions and the new JUnit test are compiled as real Kotlin source. The only linkage fixture contains the exact production ProxyNodeUi/ProxyGroupUi declarations and the IPv6 URL constant; it does not emulate the history function. The Kotlin/JUnit toolchain was reused read-only from an existing workspace cache.

The before witness uses the exact cff history body with one explicitly disclosed compile-only addition: an unused `protectedNodes` argument. Other model files stay at the candidate. This is not an exact whole-application baseline. The same four tests produce exactly one expected failure: old core history changes a busy node's cached reading from 44 to 900. Candidate history keeps 44 while independently refreshing the idle node. Full logs and the two history hashes are retained.

The eight real VM/repository HTTP tests are authored but have not run locally. They need the same-commit Android build/test gate; this host result is not Android, emulator, Google connectivity, HyperOS/ColorOS, boot-time, heat, or phone-safety evidence. No APK was issued.
