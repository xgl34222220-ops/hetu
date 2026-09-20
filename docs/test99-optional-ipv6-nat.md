# test.99 — optional IPv6 NAT compatibility

Fixes test.98 startup aborting on Android kernels without the legacy IPv6 NAT table.

- Disabled IPv6 mode freshly probes the optional NAT/REDIRECT capability before interrupting the running core, even on the cached-preflight path.
- With NAT and both DNS REDIRECT targets, local DNS redirection remains unchanged. Without NAT or a REDIRECT target, native external IPv6 TCP/UDP port 53 is rejected; IPv4 DNS continues to be redirected into Mihomo. The mandatory IPv6 filter guard is checked, not bypassed.
- This fallback is explicitly reported as `ipv6DnsPolicy=blocked-no-nat` or `blocked-no-redirect`, not described as IPv6 DNS redirection. It needs a usable IPv4 DNS path; a network advertising ONLY IPv6 DNS servers is not claimed supported by this fallback. There is no direct-DNS fallback or automatic editing of the user's upstreams.
- The session manifest no longer reads unused IPv6 NAT/mangle tables. Read failures on required tables still abort startup / prevent unsafe repairs.
- A stopped or rolled-back session reports stopped, not the misleading upgrade-required message.
- Core discovery uses a no-fork inode/comm prefilter before the existing strict executable-path check. Tracked PIDs retain direct verification. It avoids readlink subprocesses for every unrelated Android process, including error rollback; new timing stages allow device measurement. It does not promise a measured phone startup time.

No UI, source YAML, node selection, DNS upstream, core version, or system disable_ipv6 writes were changed. Install with the existing fixed preview signature. Start the proxy if stopped; explicitly restart once if an older script is still running.

Tests add absent NAT/REDIRECT, unknown table read, cached preflight, IPv6 guard loss/repair, sharing, stopped-state and high process count cases. The Linux namespace gate additionally delivers TCP/UDP IPv4 DNS and normal application TCP with IPv6 NAT reported unavailable. Linux tests do not establish OEM Android compatibility or that every earlier intermittent outage is fixed.
