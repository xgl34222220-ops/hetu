#!/usr/bin/env python3
"""Exercise real Root controller functions with mocked network commands."""
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'android-app/app/src/main/assets/hetu-root.sh'


def main():
    source = SCRIPT.read_text().split('case "${1:-status}" in', 1)[0]
    checks = 0
    with tempfile.TemporaryDirectory(prefix='hetu-ipv6-') as temp:
        directory = Path(temp)
        functions = directory / 'controller.sh'
        functions.write_text(source)

        def shell(body, **extra):
            environment = dict(os.environ, HETU_TEST_DIR=temp, HETU_TEST_FUNCTIONS=str(functions), **extra)
            result = subprocess.run(['sh'], input='. "$HETU_TEST_FUNCTIONS"\n' + body,
                                    text=True, capture_output=True, env=environment)
            if result.returncode:
                raise AssertionError(f'Root function failed: {result.stderr}\n{result.stdout}')
            return result.stdout

        # This is a real procfs file whose size is zero while its content can be
        # nonempty. No ip/default-route command is needed to detect support.
        if Path('/proc/net/if_inet6').exists():
            assert shell('ip(){ return 1; }; v6supported && printf supported').strip() == 'supported'
            checks += 1

        probe_setup = '''
v6supported(){ return 0; }
has(){ return 1; }
fail(){ printf 'FAIL %s\\n' "$1"; exit 23; }
probetp4(){ printf 'TP4 %s\\n' "$*"; }
probetp6(){ printf 'TP6 %s\\n' "$*"; }
probered4(){ printf 'REDIRECT4 %s\\n' "$*"; }
probered6(){ printf 'REDIRECT6 %s\\n' "$*"; }
'''
        for native_mode in ('tun', 'ebpf'):
            # DNS goes through Mihomo's native device, even with the stored
            # dns=tproxy/redirect choice. Neither IP family needs NAT support.
            for dns_mode in ('tproxy', 'redirect'):
                trace = shell(probe_setup + f'probe_ingress {native_mode} 0 0 enable 1 1 {dns_mode} 0 0 11053 || exit 1\n')
                assert not trace, f'{native_mode} must not probe unused transparent ingress: {trace}'
                checks += 1
            # Native modes still require the selected explicit firewall guards.
            for ipv6_mode, quic, kill in (('strict', 0, 0), ('disable', 0, 0), ('enable', 1, 0), ('enable', 0, 1)):
                trace = shell(probe_setup + f'(probe_ingress {native_mode} 0 0 {ipv6_mode} 1 1 tproxy {quic} {kill} 11053); result=$?; [ "$result" -eq 23 ] || exit 1\n')
                assert 'FAIL' in trace, 'Missing IPv6 privacy guard must not be silently accepted'
                checks += 1
        trace = shell(probe_setup + '''
has(){ return 0; }
probe_ingress tproxy 19898 0 enable 1 1 tproxy 0 0 11053 || exit 1
''')
        for expected in ('TP4 19898', 'TP6 19898', 'REDIRECT4 11053 tcp',
                         'REDIRECT4 11053 udp', 'REDIRECT6 11053 tcp', 'REDIRECT6 11053 udp'):
            assert expected in trace, f'Transparent mode lost required capability check: {expected}'
            checks += 1

        trace_setup = '''
MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500
v6supported(){ return 0; }
ip(){ printf 'ip %s\\n' "$*"; }
xt6(){ printf 'ip6tables %s\\n' "$*"; }
has(){ [ "$1" = ip6tables ]; }
'''
        trace = shell(trace_setup + '''
install_mangle6 19898 tproxy 1 1 redirect core '' 0 '' '' '' || exit 1
install_dns_redirect6 11053 core '' 0 '' || exit 1
install_udp_leak_guard6 core '' 0 '' '' '' || exit 1
''')
        for expected in (
                'ip -6 route replace local ::/0 dev lo table 20260',
                'ip -6 rule add pref 14500 fwmark 0x200000/0x200000 table 20260',
                '-p tcp -j TPROXY --on-port 19898',
                '-p udp -j TPROXY --on-port 19898',
                '-p tcp --dport 53 -j REDIRECT --to-ports 11053',
                '-p udp --dport 53 -j REDIRECT --to-ports 11053',
                '-A HETU_WROUT -p udp -j REJECT'):
            assert expected in trace, f'Missing IPv6 interception: {expected}'
            checks += 1
        assert 'route show default' not in trace
        checks += 1

        trace = shell(trace_setup + "install_v6_strict core '' 1 '' '' '' || exit 1\n")
        for expected in ('-A OUTPUT -j HETU_V6OUT', '-A FORWARD -j HETU_V6FWD'):
            assert expected in trace
            checks += 1

        trace = shell(trace_setup + 'install_v6_disable 1 || exit 1\n')
        for expected in ('-I OUTPUT 1 -j HETU_V6OUT', '-I FORWARD 1 -j HETU_V6FWD',
                         '-A HETU_V6OUT -j REJECT', '-A HETU_V6FWD -j REJECT'):
            assert expected in trace, f'Global IPv6 disable guard missing: {expected}'
            checks += 1
        for forbidden in ('--uid-owner', '--mark', '-d ', '-i ', 'ip -6'):
            assert forbidden not in trace, f'Device-wide disable may not inherit interception exemptions: {forbidden}'
            checks += 1
        assert '-A HETU_V6OUT -o lo -j RETURN' in trace, 'Local-only loopback does not leave the device'
        checks += 1

        # Real shell I/O against an isolated sysctl-shaped filesystem. No host
        # kernel/network settings are changed by this regression suite.
        sysctls = directory / 'conf'
        initial = {'all': '0', 'default': '0', 'lo': '0', 'wlan0': '0', 'rmnet0': '1'}

        def reset_sysctls():
            import shutil
            shutil.rmtree(sysctls, ignore_errors=True)
            for iface, value in initial.items():
                (sysctls / iface).mkdir(parents=True)
                (sysctls / iface / 'disable_ipv6').write_text(value + '\n')
            (directory / 'ipv6-state').unlink(missing_ok=True)

        def values():
            return {p.parent.name: p.read_text().strip() for p in sysctls.glob('*/disable_ipv6')}

        sysctl_setup = '''
RUN="$HETU_TEST_DIR"
IPV6_STATE="$HETU_TEST_DIR/ipv6-state"
V6_CONF="$HETU_TEST_DIR/conf"
SESSION="$HETU_TEST_DIR/session"
LOCK_DIR="$HETU_TEST_DIR/txn.lock"
'''
        reset_sysctls()
        shell(sysctl_setup + 'disablev6 || exit 1\nv6disabled || exit 1\n')
        baseline = (directory / 'ipv6-state').read_text()
        assert all(v == '1' for v in values().values()), values()
        checks += 1
        shell(sysctl_setup + 'disablev6 || exit 1\n')
        assert (directory / 'ipv6-state').read_text() == baseline, 'Repeated enforcement must preserve original sysctls'
        checks += 1
        # Android can re-enable an existing interface while all remains 1.
        (sysctls / 'wlan0' / 'disable_ipv6').write_text('0\n')
        shell(sysctl_setup + 'if v6disabled; then exit 1; fi\n')
        checks += 1
        for iface, value in (('rmnet1', '0'), ('rndis0', '1')):
            (sysctls / iface).mkdir()
            (sysctls / iface / 'disable_ipv6').write_text(value + '\n')
        shell(sysctl_setup + "printf 'IPV6=disable\\n' > \"$SESSION\"\nmaintainv6 || exit 1\n")
        assert all(v == '1' for v in values().values()), values()
        checks += 1
        saved = (directory / 'ipv6-state').read_text()
        assert str(sysctls / 'rmnet1' / 'disable_ipv6') + '\t0' in saved
        assert str(sysctls / 'rndis0' / 'disable_ipv6') + '\t0' in saved, 'Late interface restores original default, not our temporary 1'
        checks += 2
        # Also restore an interface created after the most recent watchdog tick.
        (sysctls / 'eth0').mkdir()
        (sysctls / 'eth0' / 'disable_ipv6').write_text('1\n')
        shell(sysctl_setup + 'restorev6 || exit 1\n')
        assert values() == dict(initial, rmnet1='0', rndis0='0', eth0='0'), values()
        assert not (directory / 'ipv6-state').exists()
        checks += 2

        # Reproduce kernel all=... fan-out semantics on writes. Restoring all
        # last would erase a per-interface original 1; production must write it
        # first and restore each interface afterwards.
        kernel_all = '''
printf(){
  if [ "${V6_P:-}" = "$V6_CONF/all/disable_ipv6" ] && [ "$#" = 2 ] && [ "$1" = '%s\\n' ]; then
    for KERNEL_PATH in "$V6_CONF"/*/disable_ipv6; do command printf '%s\\n' "$2" > "$KERNEL_PATH"; done
  fi
  command printf "$@"
}
'''
        reset_sysctls()
        shell(sysctl_setup + kernel_all + 'disablev6 || exit 1\nrestorev6 || exit 1\n')
        assert values() == initial, 'all restoration must not overwrite distinct saved interface values'
        checks += 1

        # A mid-write failure restores previously changed interfaces and keeps
        # the operation failed. Failure to restore retains the recovery journal.
        reset_sysctls()
        shell(sysctl_setup + '''
printf(){
  if [ "${V6_P:-}" = "$V6_CONF/wlan0/disable_ipv6" ] && [ "$1" = '1\\n' ]; then return 1; fi
  command printf "$@"
}
if disablev6; then exit 1; fi
''')
        assert values() == initial, values()
        assert not (directory / 'ipv6-state').exists()
        checks += 2
        reset_sysctls()
        shell(sysctl_setup + '''
disablev6 || exit 1
printf(){
  if [ "${V6_P:-}" = "$V6_CONF/wlan0/disable_ipv6" ] && [ "$#" = 2 ] && [ "$2" = 0 ]; then return 1; fi
  command printf "$@"
}
if restorev6; then exit 1; fi
[ -f "$IPV6_STATE" ] || exit 1
''')
        checks += 1
        # Restore retry succeeds and a stopped/mode-changed session is never
        # re-disabled by a stale watchdog waiting for the transaction lock.
        shell(sysctl_setup + 'restorev6 || exit 1\n')
        assert values() == initial
        checks += 1
        shell(sysctl_setup + "savev6 || exit 1\nprintf 'IPV6=enable\\n' > \"$SESSION\"\nmaintainv6 || exit 1\n")
        assert values() == initial, 'Stale maintain work must not disable a different session'
        checks += 1
        (directory / 'ipv6-state').unlink()

        trace = shell(trace_setup + '''
v6supported(){ return 1; }
install_mangle6 19898 tproxy 1 1 redirect core '' 0 '' '' '' || exit 1
install_dns_redirect6 11053 core '' 0 '' || exit 1
install_v6_strict core '' 1 '' '' '' || exit 1
''')
        assert not trace, 'IPv4-only kernels should not get unusable IPv6 commands'
        checks += 1

        status_setup = '''
root(){ :; }
pidcore(){ return 0; }
findcorepid(){ return 1; }
v6supported(){ [ "$HETU_TEST_V6" = 1 ]; }
has(){ case "$1" in ip6tables|ss) return 0;; *) return 1;; esac; }
ss(){ printf 'udp UNCONN 0 0 [::]:11053 [::]:*\\n'; }
ip(){ return 1; }
xt4q(){ case "$*" in *HETU_MOUT|*HETU_MPRE|*HETU_DNSOUT) return 0;; *) return 1;; esac; }
xt6q(){
  case "$*" in
    *HETU_V6OUT' -j REJECT') [ "$HETU_TEST_TERMINAL" = 1 ];;
    *HETU_V6FWD' -j REJECT') [ "$HETU_TEST_TERMINAL" = 1 ];;
    *HETU_V6OUT) [ "$HETU_TEST_STRICT" = 1 ];;
    *HETU_V6FWD) [ "$HETU_TEST_FORWARD" = 1 ];;
    *) return 1;;
  esac
}
PIDFILE="$HETU_TEST_DIR/core.pid"; MODEFILE="$HETU_TEST_DIR/mode"
SESSION="$HETU_TEST_DIR/session"; WATCHDOG_PID="$HETU_TEST_DIR/watchdog.pid"
IPV6_STATE="$HETU_TEST_DIR/ipv6-state"; NET_STATE="$HETU_TEST_DIR/net-state"
V6_CONF="$HETU_TEST_DIR/conf"
printf '%s\\n' "$$" > "$PIDFILE"
printf '%s\\n' "$$" > "$WATCHDOG_PID"
printf 'tproxy\\n' > "$MODEFILE"
printf 'IPV6=%s\\nDNS=redirect\\nDNS_PORT=11053\\nSHARE=%s\\nCONTROLLER_PORT=29090\\n' "$HETU_TEST_MODE" "$HETU_TEST_SHARE" > "$SESSION"
status
'''

        def status(mode='strict', strict='1', share='0', forward='0', ipv6='1', terminal='1'):
            return json.loads(shell(status_setup, HETU_TEST_MODE=mode, HETU_TEST_STRICT=strict,
                                    HETU_TEST_SHARE=share, HETU_TEST_FORWARD=forward, HETU_TEST_V6=ipv6,
                                    HETU_TEST_TERMINAL=terminal))

        state = status()
        assert state['ipv6Rules'] and state['dataPlaneHealthy'] and not state['killSwitchActive'], state
        checks += 1
        assert not status(strict='0')['ipv6Rules'], 'Absent strict guard must not report protection'
        checks += 1
        assert not status(share='1')['ipv6Rules'], 'Shared traffic needs the strict FORWARD guard'
        checks += 1
        assert status(share='1', forward='1')['ipv6Rules']
        checks += 1
        state = status(mode='enable')
        assert not state['ipv6Rules'] and not state['dnsIpv6Rule'], state
        checks += 1
        assert status(strict='0', ipv6='0')['ipv6Rules'], 'IPv4-only kernel is not a broken IPv6 guard'
        checks += 1
        assert status(mode='disable', strict='0', ipv6='0')['ipv6Rules'], 'IPv4-only kernel needs no fake sysctl mutation'
        checks += 1
        reset_sysctls()
        (directory / 'ipv6-state').write_text('')
        state = status(mode='disable')
        assert not state['ipv6DisabledByHetu'] and state['ipv6Rules'] and state['dataPlaneHealthy'], state
        assert state['ipv6DisableGuard'] and state['ipv6Mode'] == 'disable', state
        checks += 2
        (directory / 'ipv6-state').unlink()
        shell(sysctl_setup + 'disablev6 || exit 1\n')
        state = status(mode='disable')
        assert state['ipv6Rules'] and state['ipv6DisabledByHetu'] and state['ipv6DisableGuard'], state
        checks += 1
        for overrides in ({'strict': '0'}, {'share': '1', 'forward': '0'}, {'terminal': '0'}):
            state = status(mode='disable', **overrides)
            assert not state['ipv6Rules'] and not state['ipv6DisableGuard'], state
            checks += 1
        state = status(mode='disable', share='1', forward='1')
        assert state['ipv6Rules'] and state['ipv6DisableGuard'], state
        checks += 1
        (sysctls / 'wlan0' / 'disable_ipv6').write_text('0\n')
        state = status(mode='disable')
        assert not state['ipv6DisabledByHetu'] and state['dataPlaneHealthy'], state
        assert state['ipv6Rules'] and state['ipv6DisableGuard'], 'Firewall must keep native IPv6 escape blocked during sysctl reconciliation'
        checks += 2
    print(f'Root IPv6 tests passed: {checks}')


if __name__ == '__main__':
    main()
