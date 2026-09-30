# V20.47 UI and configuration safety integration

Based on verified V20.47 effective source (a0b05271). Preserves advanced DNS, resource, firewall, export and rename capabilities.

## Changes
- Configuration objects are managed in Tools → Configuration and subscriptions; the runtime/basic page no longer duplicates the list. Export and rename remain available in the unified manager.
- Editor retains explicit keyboard support, refuses empty editing after a failed read, and compares file identity/content before saving. Input is locked while a save is pending.
- Configuration rename shares the same transaction lock as edit, import, delete and subscription changes. Stale editors cannot recreate renamed files.
- CI builds materialized source directly, preserves the reviewed runtime shell script, and checks APK script bytes against source. Historical binaries remain hash-pinned.

## Validation
- 94 production configuration-library checks using real temporary files passed.
- 10 snapshot guards passed.
- Android input, save failure, cancellation, dark/light screenshots and complete app tests pending CI.
- No claim of actual phone Root networking, GPU effects or upgrade validation until tested.
