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
            for ipv6_mode, quic, kill in (('strict', 0, 0), ('enable', 1, 0), ('enable', 0, 1)):
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
    *HETU_V6OUT) [ "$HETU_TEST_STRICT" = 1 ];;
    *HETU_V6FWD) [ "$HETU_TEST_FORWARD" = 1 ];;
    *) return 1;;
  esac
}
PIDFILE="$HETU_TEST_DIR/core.pid"; MODEFILE="$HETU_TEST_DIR/mode"
SESSION="$HETU_TEST_DIR/session"; WATCHDOG_PID="$HETU_TEST_DIR/watchdog.pid"
IPV6_STATE="$HETU_TEST_DIR/ipv6-state"; NET_STATE="$HETU_TEST_DIR/net-state"
printf '%s\\n' "$$" > "$PIDFILE"
printf '%s\\n' "$$" > "$WATCHDOG_PID"
printf 'tproxy\\n' > "$MODEFILE"
printf 'IPV6=%s\\nDNS=redirect\\nDNS_PORT=11053\\nSHARE=%s\\nCONTROLLER_PORT=29090\\n' "$HETU_TEST_MODE" "$HETU_TEST_SHARE" > "$SESSION"
status
'''

        def status(mode='strict', strict='1', share='0', forward='0', ipv6='1'):
            return json.loads(shell(status_setup, HETU_TEST_MODE=mode, HETU_TEST_STRICT=strict,
                                    HETU_TEST_SHARE=share, HETU_TEST_FORWARD=forward, HETU_TEST_V6=ipv6))

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
    print(f'Root IPv6 tests passed: {checks}')


if __name__ == '__main__':
    main()
