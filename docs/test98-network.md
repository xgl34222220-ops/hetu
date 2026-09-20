# test.98 — intermittent connectivity repair

Network-only change on test.97. No UI, node selection, user source YAML, matching order,
DNS upstream servers or keepalive/core version changes.

## Local forwarding integrity

A live process is not proof of working TPROXY. Each successful start now records a
private, checksum-verified manifest bound to the live PID and immutable session settings.
The audit compares full owned-chain rule order and hooks, including PREROUTING even
when hotspot sharing is off, the exact fwmark/mask/priority/table policy rule, the local
route, and per-protocol API/DNS/proxy listeners. Failed reads or an active transaction
are unknown, not an instruction to restart. Missing or corrupt manifests need an explicit
restart; they are never reconstructed from potentially edited preferences.

The Root watchdog checks about every 12 seconds. A confirmed structural fault is checked
again under the existing transaction lock and repaired in place, at most once per 30 seconds.
Only the session's own chains are restored with iptables-restore --noflush; unrelated
Android/OEM/other-app chains are preserved. Routing is repaired before interception.
Core PID, fake-IP cache, sockets and selected proxies are not restarted/reset. Unknown
identity, stale PID/session, missing listeners or a priority conflict do not trigger a blind
rebuild. Failed atomic table restore has no unsafe line-by-line fallback.

A local-integrity result does not claim DNS upstreams or all Internet sites are reachable.
Every 90 seconds the service separately tests HTTP 204 endpoints through the existing
loopback Mihomo listener, following current routing rules (not a forced DIRECT probe).
Failure records a separate warning, without restarting the core, switching nodes or
changing routing. The diagnostic report includes integrity faults, repair records and
metadata of affected common proxy apps; no message payloads or credentials are collected.

## IPv6 compatibility

Removed writes to all/default/interface disable_ipv6 from startup and the watchdog.
Legacy journals are still restored on explicit restart/stop, including per-interface values.
Disable mode blocks native IPv6 from application UIDs, including app bypass entries;
loopback, ICMPv6 network maintenance and Android system UIDs 0–9999 remain available for
network infrastructure, IMS and CLAT. It is intentionally NOT a promise that the underlying
phone IPv6 protocol stack has been removed. Source/runtime ipv6:false and dns.ipv6:false
remain in force. Transparent disable mode redirects both IPv4/IPv6 system DNS to Mihomo;
external IPv6 port 53 is rejected before the infrastructure exemption. Shared-client
IPv6 forwarding remains rejected. Proxy transport and system infrastructure are not
mislabelled as native application IPv6 leaks.

## Upgrade and tests

The UI is unchanged. Install with the fixed preview signing key and press Restart once
to deploy the new script and create the session manifest. Opening the app does not restart it.

Tests include stateful fault injection (absent OUTPUT/PREROUTING, policy/local routes,
empty/deleted chains, DNS/IPv6 guards, duplicate hooks, read failures, stale sessions,
atomic restore failure/backoff, listener failure), existing process/IPv6/continuity tests,
and a disposable Linux network namespace gate that exercises real TPROXY TCP packets
before and after removing routes/hooks/chains. The Linux gate is not Android OEM testing.

No claim that every cause of the user's intermittent outage is fixed without device logs.
References: https://docs.kernel.org/networking/tproxy.html ; Android iptables-restore
supports --noflush and bounded xtables lock waits.
