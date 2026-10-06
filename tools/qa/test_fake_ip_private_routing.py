#!/usr/bin/env python3
"""Run shipped Root functions without any host/device network operation.

All network entry points are shell recording functions; temporary files hold
private YAML/session state. HETU_FAKE_IP_SOURCE permits the identical semantic
regressions to run against a preserved, pre-fix script.
"""
import contextlib
import ipaddress
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

import test_root_health as health_fixture

REPO = Path(__file__).resolve().parents[2]
SCRIPT = Path(os.environ.get('HETU_FAKE_IP_SOURCE',
                            REPO/'android-app/app/src/main/assets/hetu-root.sh'))
PREFIX = SCRIPT.read_text().rsplit('\ncase "${1:-status}" in', 1)[0]
LAN4 = '0.0.0.0/8,10.0.0.0/8,100.64.0.0/10,127.0.0.0/8,169.254.0.0/16,172.16.0.0/12,192.168.0.0/16,224.0.0.0/4,240.0.0.0/4'
LAN6 = '::1/128,fc00::/7,fe80::/10,ff00::/8'
FAKE4 = '10.42.0.0/16'
FAKE6 = 'fdfe:dcba:9876::/64'
MAC = '02:11:22:33:44:55'


def minus(lan, fake):
    excluded = ipaddress.ip_network(fake) if fake else None
    result = []
    for part in lan.split(','):
        net = ipaddress.ip_network(part)
        if excluded and net.overlaps(excluded):
            if not net.subnet_of(excluded): result.extend(net.address_exclude(excluded))
        else: result.append(net)
    return ','.join(map(str, result))


def metadata(fake4=FAKE4, fake6=FAKE6):
    return ('# HETU_FAKE_IP_POLICY=1\n# HETU_FAKE_IP_V4='+fake4+
            '\n# HETU_FAKE_IP_V6='+fake6+
            '\n# HETU_LAN_RETURN_V4='+minus(LAN4, fake4)+
            '\n# HETU_LAN_RETURN_V6='+minus(LAN6, fake6)+'\n')


class FakeIpPrivateRouting(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-fake-ip-qa-')
        self.base = Path(self.temp.name)
        self.run = self.base/'run'; self.run.mkdir()
        self.functions = self.base/'functions.sh'; self.functions.write_text(PREFIX+'\n')
        self.config = self.base/'private.yaml'; self.config.write_text('mode: rule\n'+metadata())
        self.calls = self.base/'calls'
        self.setup = ('RUN='+shlex.quote(str(self.run))+'\nBASE="$RUN/base"\n'
                      'PIDFILE="$RUN/pid"; MODEFILE="$RUN/mode"; SESSION="$RUN/session"; '
                      'NET_STATE="$RUN/net"; LOG="$RUN/core.log"; LOCK_DIR="$RUN/lock"; '
                      'START_STATE="$RUN/stage"; START_ERROR="$RUN/error"; START_TIMING="$RUN/timing"; '
                      'CRASH_STATE="$RUN/crash"; WATCHDOG_PID="$RUN/watchdog"; WATCHDOG_LOG="$RUN/watchdog.log"\n'
                      'CALLS='+shlex.quote(str(self.calls))+'\n'
                      'record(){ printf "%s" "$1" >> "$CALLS"; shift; '
                      'for arg do printf " %s" "$arg" >> "$CALLS"; done; printf "\\n" >> "$CALLS"; }\n'
                      'xt4(){ record xt4 "$@"; }; xt6(){ record xt6 "$@"; }\n'
                      'xt4q(){ xt4 "$@"; }; xt6q(){ xt6 "$@"; }\n'
                      'ip(){ record ip "$@"; }; route4(){ record route4; }; route6(){ record route6; }\n'
                      'has(){ return 0; }; v6supported(){ return 0; }; root(){ :; }\n'
                      'MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500\n')
        self.load = 'load_start_fake_ip_policy '+shlex.quote(str(self.config))+' || exit 7\n'
        # The route regressions also run on old scripts that have no metadata parser.
        self.policy = ('LAN_RETURN_V4='+shlex.quote(minus(LAN4,FAKE4))+'\n'
                       'LAN_RETURN_V6='+shlex.quote(minus(LAN6,FAKE6))+'\n')

    def tearDown(self): self.temp.cleanup()

    def shell(self, body, code=0):
        harness = self.base/'harness.sh'
        harness.write_text('. '+shlex.quote(str(self.functions))+'\n'+self.setup+body+'\n')
        result = subprocess.run(['sh',str(harness)], cwd=self.base, text=True, capture_output=True, timeout=8)
        self.assertEqual('',result.stderr)
        self.assertEqual(code,result.returncode,result.stdout)
        return result.stdout

    def rules(self, family, chain):
        return [shlex.split(line)[1:] for line in self.calls.read_text().splitlines()
                if line.startswith('xt'+str(family)+' ') and ' -A '+chain+' ' in line]

    def destination_returns(self, family, chain):
        return [ipaddress.ip_network(r[r.index('-d')+1],strict=False)
                for r in self.rules(family,chain) if '-d' in r and r[-2:]==['-j','RETURN']]

    def assert_fake_reaches_proxy(self, family, chain, fake, ordinary):
        ranges = self.destination_returns(family,chain)
        self.assertFalse(any(ipaddress.ip_address(fake) in net for net in ranges), ranges)
        self.assertTrue(any(ipaddress.ip_address(ordinary) in net for net in ranges), ranges)

    def test_custom_ipv4_fake_ip_survives_default_lan_bypass_in_real_tproxy_chain(self):
        self.shell(self.policy+'install_mangle4 7893 tproxy 1 1 redirect core "" 1 "" "" "" "" ""')
        self.assert_fake_reaches_proxy(4,'HETU_MOUT','10.42.0.7','10.43.0.7')
        self.assertTrue(any('TPROXY' in r for r in self.rules(4,'HETU_MPRE')))

    def test_ipv6_fake_ip_survives_default_ula_bypass_in_real_tproxy_chain(self):
        self.shell(self.policy+'install_mangle6 7893 tproxy 1 1 redirect core "" 1 "" "" "" "" ""')
        self.assert_fake_reaches_proxy(6,'HETU_MOUT','fdfe:dcba:9876::7','fdfe:dcba:9877::7')
        self.assertTrue(any('TPROXY' in r for r in self.rules(6,'HETU_MPRE')))

    def test_explicit_cidr_uid_gid_interface_mac_returns_keep_priority(self):
        self.shell(self.load+'install_mangle6 7893 tproxy 1 1 redirect core "" 1 '
                   '"fdfe:dcba:9876::/64" wlan0 10200 3003 '+MAC)
        out=self.rules(6,'HETU_MOUT'); pre=self.rules(6,'HETU_MPRE')
        mark=next(i for i,r in enumerate(out) if '--set-xmark' in r and 'MARK' in r)
        for option,value in [('-d',FAKE6),('--uid-owner','10200'),('--gid-owner','3003'),('-o','wlan0')]:
            self.assertTrue(any(option in r and r[r.index(option)+1]==value and r[-2:]==['-j','RETURN'] for r in out[:mark]))
        proxy=next(i for i,r in enumerate(pre) if 'TPROXY' in r)
        self.assertTrue(any('--mac-source' in r and MAC in r for r in pre[:proxy]))

    def test_udp_quic_and_kill_guards_do_not_return_fake_destinations(self):
        body=self.load
        for name in ('install_udp_leak_guard','install_quic','install_kill'):
            for family in (4,6): body+=name+str(family)+' core "" 1 "" "" "" "" ""\n'
        self.shell(body)
        for family,fake,ordinary in [(4,'10.42.0.7','10.43.0.7'),(6,'fdfe:dcba:9876::7','fdfe:dcba:9877::7')]:
            for chain in ('HETU_WROUT','HETU_QUICOUT','HETU_KOUT'):
                self.assert_fake_reaches_proxy(family,chain,fake,ordinary)
                self.assertTrue(any('REJECT' in r for r in self.rules(family,chain)))

    def test_strict_ipv4_keeps_default_ula_policy_and_explicit_bypasses(self):
        self.config.write_text('mode: rule\n'+metadata(FAKE4,''))
        self.shell(self.load+'install_v6_strict core "" 1 "2001:db8::/32" wlan0 10200 3003 '+MAC)
        self.assertIn(ipaddress.ip_network('fc00::/7'),self.destination_returns(6,'HETU_V6OUT'))
        self.assertIn(ipaddress.ip_network('2001:db8::/32'),self.destination_returns(6,'HETU_V6OUT'))
        self.assertTrue(any('REJECT' in r for r in self.rules(6,'HETU_V6OUT')))

    def test_tun_and_ebpf_do_not_install_transparent_mangle_or_redirect(self):
        for mode in ('tun','ebpf'):
            self.shell(self.load+'install_mangle4 7893 '+mode+' 1 1 redirect core "" 1 "" "" "" "" ""\n'
                       'install_redirect4 7892 '+mode+' 1 core "" 1 "" "" "" "" ""')
        self.assertFalse(self.calls.exists())

    def test_legacy_config_without_metadata_retains_original_private_returns(self):
        self.config.write_text('mode: rule\n')
        self.shell(self.load+'bypass4 HETU_NOUT nat ""\nbypass6 HETU_NOUT nat ""')
        self.assertEqual(set(map(ipaddress.ip_network,LAN4.split(','))),set(self.destination_returns(4,'HETU_NOUT')))
        self.assertEqual(set(map(ipaddress.ip_network,LAN6.split(','))),set(self.destination_returns(6,'HETU_NOUT')))

    def test_legal_scalar_and_fake_body_metadata_cannot_override_terminal_block(self):
        self.config.write_text('literal: |\n  # HETU_FAKE_IP_POLICY=99\n  # HETU_FAKE_IP_V6=::/0\n'
                               '# HETU_FAKE_IP_V6=::/0\nmode: rule\n'+metadata())
        result=self.shell(self.load+'printf "%s:%s" "$FAKE_IP_V4" "$FAKE_IP_V6"')
        self.assertEqual(FAKE4+':'+FAKE6,result)

    def test_malformed_duplicate_or_nonterminal_tail_fails_before_network_calls(self):
        for bad in [metadata().replace('POLICY=1','POLICY=9'),
                    metadata().replace('# HETU_FAKE_IP_V6=','# HETU_FAKE_IP_V4='),
                    metadata()+'mode: rule\n', metadata().replace(FAKE6,'10.0.0.0/8')]:
            with self.subTest(tail=bad):
                self.config.write_text('mode: rule\n'+bad)
                self.shell(self.load+'bypass4 HETU_NOUT nat ""',code=7)
                self.assertFalse(self.calls.exists())

    def test_literal_cidr_validation_never_resolves_or_executes_metadata(self):
        for bad in ['example.com/24','256.0.0.1/8','10.0.0.0/33','10.0.0.0/8,$(touch stolen)',
                    ':fd::/8','fd:::1/64','::1::/64','::/129']:
            family=6 if ':' in bad else 4
            with self.subTest(cidr=bad): self.shell('fake_ip_cidrs_valid '+str(family)+' '+shlex.quote(bad),code=1)
        self.assertFalse((self.base/'stolen').exists()); self.assertFalse(self.calls.exists())

    def test_canonical_embedded_ipv6_metadata_loads_without_changing_yaml(self):
        produced=os.environ.get('HETU_FAKE_IP_EMBEDDED_CONFIG')
        if produced:
            private_yaml=Path(produced).read_text() # Actual host JVM helper output.
        else:
            private_yaml=('dns: {enable: true, enhanced-mode: fake-ip, ipv6: true, '
                          'fake-ip-range6: "::ffff:192.0.2.1/128"}\n'+metadata(FAKE4,'::ffff:c000:201/128'))
        self.config.write_text(private_yaml)
        before=self.config.read_bytes()
        self.assertIn(b'::ffff:192.0.2.1/128',before)
        self.assertEqual('::ffff:c000:201/128',self.shell(self.load+'printf "%s" "$FAKE_IP_V6"'))
        self.assertEqual(before,self.config.read_bytes())

    def start_body(self):
        # The benign core exits immediately; listeners/health are controlled here.
        # Every operation preceding/following launch is fixture-only.
        body=''
        for name in ('acquire_lock','start_stage','preflight','validatecfg','stopwatchdog','restorev6','stopcore',
                     'check_start_ports','wait_ready','install_mangle4','install_mangle6','install_redirect4',
                     'install_redirect6','install_dns_redirect4','install_udp_leak_guard4','install_udp_leak_guard6',
                     'install_quic4','install_quic6','start_watchdog','health_record'):
            body+=name+'(){ :; }\n'
        body+='cleanup(){ record cleanup; }; select_dns6_policy(){ START_DNS6=redirect; }; markused(){ return 1; }\n'
        body+='allocnet(){ :; }\n'
        args=['/bin/true',str(self.config),'tproxy','7893','7892','enable','1','1','redirect','0',
              '1053','29090','core','','1','0','','','','1','1','',MAC]
        return body+'start '+' '.join(map(shlex.quote,args))

    def test_real_start_passes_shared_mac_to_ipv6_dns_return_before_redirect(self):
        self.config.write_text('mode: rule\n') # Also valid for the preserved old script.
        self.shell(self.start_body())
        rules=self.rules(6,'HETU_DNSPRE')
        first_redirect=next(i for i,r in enumerate(rules) if 'REDIRECT' in r)
        self.assertTrue(any('--mac-source' in r and MAC in r for r in rules[:first_redirect]),rules)

    def test_real_start_rejects_bad_policy_before_cleanup_or_core_launch(self):
        self.config.write_text('mode: rule\n'+metadata().replace(FAKE4,'10.0.0.0/99'))
        self.shell(self.start_body(),code=1)
        self.assertFalse(self.calls.exists()); self.assertFalse((self.run/'pid').exists())

    @contextlib.contextmanager
    def healthy_session(self):
        fixture=health_fixture.RootHealth(methodName='runTest'); fixture.setUp()
        try:
            fixture.config.write_text('fixture: true\n'+metadata(FAKE4,''))
            fixture.start(prefix=PREFIX)
            yield fixture
        finally: fixture.tearDown()

    def test_saved_policy_roundtrip_uses_immutable_health_checksum(self):
        with self.healthy_session() as fixture:
            self.assertEqual('healthy:',fixture.health())
            result=fixture.shell('load_session_fake_ip_policy || exit 7; printf "%s" "$LAN_RETURN_V4"')
            self.assertEqual(minus(LAN4,FAKE4),result)
            fixture.shell('load_session_fake_ip_policy || exit 7; xt4(){ :; }; install_kill4 core "" 0 "" "" ""')
            self.assertEqual('healthy:',fixture.health())

    def test_tampered_session_or_baseline_cannot_supply_watchdog_policy(self):
        with self.healthy_session() as fixture:
            session=fixture.run/'session'; original=session.read_bytes()
            session.write_bytes(original.replace(FAKE4.encode(),b'10.0.0.0/8'))
            fixture.shell('load_session_fake_ip_policy',expected_code=1)
            session.write_bytes(original)
            (fixture.run/'network-manifest/session').write_bytes(original+b'LAN_RETURN_V4=0.0.0.0/0\n')
            fixture.shell('load_session_fake_ip_policy',expected_code=1)

    def test_old_session_without_projection_remains_compatible(self):
        (self.run/'session').write_text('MODE=tproxy\nIPV6=bypass\n')
        result=self.shell('load_session_fake_ip_policy; printf "%s" "$LAN_RETURN_V4"')
        self.assertEqual(LAN4,result)


if __name__ == '__main__': unittest.main(verbosity=2)
