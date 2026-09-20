# test.100 — startup and connection-lifecycle test build

No UI, source YAML, node choices, DNS upstreams, IPv6 policy, native core or matching-order changes from test.99.

- A new Android default network no longer causes age-based bulk DELETE of existing connections. Android says the previous best network may still be available. The callback now records the change and schedules the existing bounded rule-following reachability observation. It does not claim a per-connection failure from a callback alone; actual transport reconnect remains the core/app's responsibility.
- Background process/integrity/reachability results have an observation-generation ticket. Results spanning a manual control transaction are discarded, rather than overwriting a newly started/stopped state. Recovery Start requests do not queue behind an active manual transaction.
- Root preflight now acquires the transaction lock before mutating its shared probe chains, including cached starts. Existing locking is reentrant within the same script.
- Readiness uses one full listening socket snapshot per sample, with protocol-aware exact local-port matching, instead of multiple ss/netstat/proc scans. The 90-second startup-listener deadline uses monotonic elapsed time; socket-scan work counts against it rather than adding 900 times on top. It is not a promise of a measured device startup speed.
- Cleanup treats only an explicit kernel missing-table error as absent. Permission/lock/unknown errors are not treated as empty. Missing optional IPv6 tables no longer cause repeated pointless per-chain commands during cleanup.

Verification: existing network integrity/optional IPv6 NAT/kernel-packet suites, Android compilation and UI regression, plus focused polling, deadline, preflight concurrency and stale-result publication tests. Host tests are not a ColorOS device test or proof of every intermittent failure's root cause.

Install using the fixed preview certificate, then explicitly Restart once in Hetu to deploy the new script. Opening the app does not restart the core. If stopped, Start instead. Keep the existing configuration. Diagnostics now include default-network observation time and connections-preserved reason.

Reference: https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback#onAvailable(android.net.Network)
