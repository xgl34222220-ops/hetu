#!/usr/bin/env python3
"""Runtime 153: the core's own identity, system-resolver DNS takeover and the tuning controls.

Every xtables entry point is a shell recording function; nothing here touches host rules.
Set HETU_R152_SOURCE to a preserved runtime-152 script to also prove that, without the
separate core identity, the installed rules are the ones runtime 152 installed.
"""
import itertools
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'android-app/app/src/main/assets/hetu-root.sh'
SOURCE = SCRIPT.read_text()
PREFIX = SOURCE.rsplit('\ncase "${1:-status}" in', 1)[0]
FAKE4 = '198.18.0.0/16'
FAKE6 = 'fdfe:dcba:9876::/64'
LAN4 = '10.0.0.0/8,192.168.0.0/16'
CORE = '-m owner --uid-owner 0 --gid-owner 3005 -j RETURN'

RECORDERS = r'''
xt4(){ printf 'xt4 %s\n' "$*" >> "$CALLS"; case " $* " in *" $XT4_FAIL "*) return 1;; esac; return 0; }
xt6(){ printf 'xt6 %s\n' "$*" >> "$CALLS"; case " $* " in *" $XT6_FAIL "*) return 1;; esac; return 0; }
xt4q(){ xt4 "$@"; }
xt6q(){ xt6 "$@"; }
route4(){ :; }
route6(){ :; }
v6supported(){ return 0; }
local_destination_return(){ :; }
XT4_FAIL="${XT4_FAIL:-<none>}"; XT6_FAIL="${XT6_FAIL:-<none>}"
'''

# busybox stand-in: `busybox setuidgid SPEC busybox id -u|-g`
BUSYBOX = r'''#!/bin/sh
[ "$1" = setuidgid ] || exit 64
SPEC="$2"; shift 3
case ",$BB_REFUSE," in *",$SPEC,"*) exit 1;; esac
[ "$1" = id ] || exit 65
case "$2" in -u) printf '%s\n' "${BB_UID:-0}";; -g) printf '%s\n' "${BB_GID:-3005}";; *) exit 66;; esac
'''


class Harness(unittest.TestCase):
    source = PREFIX

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-core-identity-qa-')
        self.base = Path(self.temp.name)
        self.calls = self.base / 'calls'
        self.functions = self.base / 'functions.sh'
        self.functions.write_text(self.source + '\n')

    def tearDown(self): self.temp.cleanup()

    def sh(self, body, env=None, functions=None):
        harness = self.base / 'harness.sh'
        harness.write_text('. ' + shlex.quote(str(functions or self.functions)) + '\n' +
                           'BASE=' + shlex.quote(str(self.base / 'base')) + '; RUN="$BASE/run"; mkdir -p "$RUN"\n' +
                           'CALLS=' + shlex.quote(str(self.calls)) + '\n' + RECORDERS + body + '\n')
        return subprocess.run(['sh', str(harness)], env=dict(os.environ, **(env or {})),
                              text=True, capture_output=True, timeout=20)

    def rules(self, body, env=None, functions=None):
        if self.calls.exists(): self.calls.unlink()
        result = self.sh(body, env, functions)
        self.assertEqual((result.returncode, result.stderr), (0, ''), result.stdout + result.stderr)
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    @staticmethod
    def state(captured, protos='tcp udp'):
        gid, dns = ('3005', 'captured') if captured else ('', 'exempt')
        return (f'CORE_GID={gid}; SYSTEM_DNS={dns}; FAKE_IP_V4={FAKE4}; FAKE_IP_V6={FAKE6}; '
                f'DNS_PROTOS="{protos}"\n')

    @staticmethod
    def install(mode='tproxy', scope='blacklist', uids='10100', share=0, direct='10200', gids='', family='4'):
        lan = LAN4 if family == '4' else 'fc00::/7'
        return (f'install_mangle{family} 7893 {mode} 1 1 tproxy {scope} "{uids}" {share} "{lan}" "" "{direct}" "{gids}" ""\n'
                f'install_redirect{family} 7892 {mode} 1 {scope} "{uids}" {share} "{lan}" "" "{direct}" "{gids}" ""\n'
                f'install_dns_redirect{family} 1053 {scope} "{uids}" {share} "" ""\n')

    def chain(self, lines, tool, table, name):
        head = f'{tool} -t {table} -A {name} '
        return [x[len(head):] for x in lines if x.startswith(head)]


class RuleShapes(Harness):
    def test_without_the_core_identity_every_root_socket_stays_out_of_dns_takeover(self):
        lines = self.rules(self.state(False) + self.install())
        dns = self.chain(lines, 'xt4', 'nat', 'HETU_DNSOUT')
        self.assertIn('-m owner --uid-owner 0 -j RETURN', dns)
        self.assertFalse([x for x in lines if '--gid-owner 3005' in x or '! -d' in x], lines)
        self.assertIn('-m owner --uid-owner 0-9999 -j RETURN', self.chain(lines, 'xt4', 'mangle', 'HETU_MOUT'))

    def test_captured_exempts_only_the_core_and_lets_fake_ip_flows_through(self):
        lines = self.rules(self.state(True) + self.install())
        dns = self.chain(lines, 'xt4', 'nat', 'HETU_DNSOUT')
        self.assertIn(CORE, dns)
        self.assertNotIn('-m owner --uid-owner 0 -j RETURN', dns)
        self.assertEqual(dns[-2:], ['-p tcp --dport 53 -j REDIRECT --to-ports 1053',
                                    '-p udp --dport 53 -j REDIRECT --to-ports 1053'])
        mout = self.chain(lines, 'xt4', 'mangle', 'HETU_MOUT')
        self.assertEqual(mout[1], CORE, mout)
        self.assertIn(f'! -d {FAKE4} -m owner --uid-owner 0-9999 -j RETURN', mout)
        self.assertIn(f'! -d {FAKE4} -m owner --uid-owner 10200 -j RETURN', mout)   # bypassed application
        self.assertIn(f'! -d {FAKE4} -m owner --uid-owner 10100 -j RETURN', mout)   # blacklisted application
        self.assertFalse([x for x in mout if '-m owner' in x and x.endswith('-j RETURN') and x != CORE and '! -d' not in x], mout)

    def test_captured_ipv6_uses_the_ipv6_fake_range(self):
        lines = self.rules(self.state(True) + self.install(family='6'))
        mout = self.chain(lines, 'xt6', 'mangle', 'HETU_MOUT')
        self.assertEqual(mout[1], CORE, mout)
        self.assertIn(f'! -d {FAKE6} -m owner --uid-owner 0-9999 -j RETURN', mout)
        self.assertIn(CORE, self.chain(lines, 'xt6', 'nat', 'HETU_DNSOUT'))
        self.assertFalse([x for x in lines if FAKE4 in x], lines)

    def test_group_exemption_keeps_its_direct_path_for_real_addresses_only(self):
        lines = self.rules(self.state(True) + self.install(gids='3003'))
        self.assertIn(f'! -d {FAKE4} -m owner --gid-owner 3003 -j RETURN', self.chain(lines, 'xt4', 'mangle', 'HETU_MOUT'))

    def test_selected_applications_scope_gets_a_resolver_redirect_and_fake_ip_entries(self):
        lines = self.rules(self.state(True) + self.install(scope='whitelist'))
        dns = self.chain(lines, 'xt4', 'nat', 'HETU_DNSOUT')
        for proto in ('tcp', 'udp'):
            self.assertIn(f'-m owner --uid-owner 10100 -p {proto} --dport 53 -j REDIRECT --to-ports 1053', dns)
            self.assertIn(f'-m owner --uid-owner 0 -p {proto} --dport 53 -j REDIRECT --to-ports 1053', dns)
        self.assertLess(dns.index(CORE), dns.index('-m owner --uid-owner 0 -p tcp --dport 53 -j REDIRECT --to-ports 1053'))

    def test_selected_applications_scope_marks_use_the_script_mark(self):
        mark = '0x2024/0xffff'
        lines = self.rules(self.state(True) + 'MARK=0x2024; MASK=0xffff\n' + self.install(scope='whitelist'))
        mout = self.chain(lines, 'xt4', 'mangle', 'HETU_MOUT')
        self.assertIn(f'-d {FAKE4} -p tcp -j MARK --set-xmark {mark}', mout)
        self.assertIn(f'-d {FAKE4} -p udp -j MARK --set-xmark {mark}', mout)
        self.assertFalse([x for x in mout if '--uid-owner 0-9999' in x], mout)

    def test_selected_applications_redirect_mode_redirects_fake_ip_tcp(self):
        lines = self.rules(self.state(True) + self.install(mode='redirect', scope='whitelist'))
        self.assertIn(f'-d {FAKE4} -p tcp -j REDIRECT --to-ports 7892', self.chain(lines, 'xt4', 'nat', 'HETU_NOUT'))

    def test_selected_applications_scope_is_untouched_while_the_resolver_is_exempt(self):
        lines = self.rules(self.state(False) + self.install(scope='whitelist'))
        self.assertFalse([x for x in lines if FAKE4 in x or '--uid-owner 0 -p' in x], lines)

    def test_dns_protocol_switches_select_the_redirected_protocols(self):
        for protos, kept, dropped in (('udp', 'udp', 'tcp'), ('tcp', 'tcp', 'udp')):
            lines = self.rules(self.state(True, protos) + self.install(share=1))
            for name in ('HETU_DNSOUT', 'HETU_DNSPRE'):
                dns = self.chain(lines, 'xt4', 'nat', name)
                self.assertIn(f'-p {kept} --dport 53 -j REDIRECT --to-ports 1053', dns)
                self.assertFalse([x for x in dns if f'-p {dropped} ' in x], dns)

    def test_dot_guard_refuses_only_the_resolver_probe(self):
        lines = self.rules(self.state(True) + 'install_dot_guard xt4\n')
        self.assertEqual(lines, ['xt4 -t filter -N HETU_DOTOUT',
                                 'xt4 -t filter -A HETU_DOTOUT ' + CORE,
                                 'xt4 -t filter -A HETU_DOTOUT -m owner --uid-owner 0 -p tcp --dport 853 -j REJECT --reject-with tcp-reset',
                                 'xt4 -t filter -I OUTPUT 1 -j HETU_DOTOUT'])

    def test_dot_guard_chain_is_owned_cleaned_and_recorded(self):
        self.assertIn('DOTOUT=HETU_DOTOUT', SOURCE)
        for function in ('cleanup4', 'cleanup6'):
            body = SOURCE.split('\n' + function + '(){', 1)[1].split('\n}\n', 1)[0]
            self.assertIn('$DOTOUT', body, function)
        start = SOURCE.split('\nstart(){', 1)[1]
        self.assertLess(start.index("printf 'CORE_GID=%s"), start.index('GOOGLE_FIREWALL_CLEAN='))


@unittest.skipUnless(os.environ.get('HETU_R152_SOURCE'), 'set HETU_R152_SOURCE to compare against runtime 152')
class LegacyParity(Harness):
    def test_rules_without_the_core_identity_match_runtime_152(self):
        old = self.base / 'r152.sh'
        old.write_text(Path(os.environ['HETU_R152_SOURCE']).read_text().rsplit('\ncase "${1:-status}" in', 1)[0] + '\n')
        fake = f'FAKE_IP_V4={FAKE4}; FAKE_IP_V6={FAKE6}\n'
        for mode, scope, share, family in itertools.product(('tproxy', 'redirect', 'enhance'), ('blacklist', 'whitelist'), (0, 1), ('4', '6')):
            body = self.install(mode=mode, scope=scope, share=share, gids='3003', family=family)
            new = self.rules(self.state(False) + body)
            self.assertEqual(new, self.rules(fake + body, functions=old), (mode, scope, share, family))
            self.assertTrue(new)


class CoreIdentity(Harness):
    def setUp(self):
        super().setUp()
        self.magisk = self.base / 'magisk-root'
        (self.magisk / '.magisk').mkdir(parents=True)
        self.busybox = self.magisk / '.magisk/busybox'
        self.busybox.write_text(BUSYBOX); self.busybox.chmod(0o755)
        for fixed in ('/data/adb/ksu/bin/busybox', '/data/adb/ap/bin/busybox', '/data/adb/magisk/busybox'):
            if os.path.exists(fixed): self.skipTest(fixed + ' exists on this host')

    def prepare(self, env=None, before='', v6='bypass'):
        body = ('magisk(){ printf "%s\\n" ' + shlex.quote(str(self.magisk)) + '; }\n' +
                'has(){ [ \"$1\" != ip6tables ] && command -v \"$1\" >/dev/null 2>&1; }\n' +
                f'START_V6={v6}\n' + before + 'core_identity_prepare\n'
                'printf "runner=%s spec=%s dns=%s\\n" "$CORE_RUNNER" "$CORE_SPEC" "$SYSTEM_DNS"\n')
        result = self.sh(body, env)
        self.assertEqual((result.returncode, result.stderr), (0, ''), result.stdout + result.stderr)
        return result.stdout.strip()

    def test_busybox_that_yields_root_net_admin_becomes_the_runner(self):
        self.assertEqual(self.prepare(), f'runner={self.busybox} spec=root:net_admin dns=exempt')

    def test_numeric_spec_is_the_fallback(self):
        self.assertEqual(self.prepare({'BB_REFUSE': 'root:net_admin'}), f'runner={self.busybox} spec=0:3005 dns=exempt')

    def test_wrong_identity_or_missing_applet_keeps_the_old_start(self):
        self.assertEqual(self.prepare({'BB_GID': '0'}), 'runner= spec= dns=exempt')
        self.assertEqual(self.prepare({'BB_UID': '2000'}), 'runner= spec= dns=exempt')
        self.assertEqual(self.prepare({'BB_REFUSE': 'root:net_admin,0:3005'}), 'runner= spec= dns=exempt')

    def test_opt_out_marker_keeps_the_old_start(self):
        before = 'mkdir -p "$BASE/policy"; : > "$BASE/policy/system-dns-direct"\n'
        self.assertEqual(self.prepare(before=before), 'runner= spec= dns=exempt')

    def test_kernel_without_the_combined_owner_match_keeps_the_old_start(self):
        self.assertEqual(self.prepare({'XT4_FAIL': '--gid-owner'}), 'runner= spec= dns=exempt')
        self.assertEqual(self.prepare({'XT6_FAIL': '--gid-owner'}, before='has(){ return 0; }\n', v6='enable'), 'runner= spec= dns=exempt')
        self.assertEqual(self.prepare({'XT6_FAIL': '--gid-owner'}), f'runner={self.busybox} spec=root:net_admin dns=exempt')

    def confirm(self, gid, dns='tproxy', mode='tproxy', runner='/bb', uid_fields=None, gid_fields=None):
        # Model proc credentials explicitly and run the production awk parser.
        # The old single-value awk replacement cannot exercise four-field checks.
        status = self.base / 'modeled-core-status'
        uids = uid_fields if uid_fields is not None else ['0'] * 4
        gids = gid_fields if gid_fields is not None else [gid] * 4
        status.write_text('Uid: ' + ' '.join(uids) + '\nGid: ' + ' '.join(gids) + '\n')
        functions = self.base / 'identity-functions.sh'
        functions.write_text(self.source.replace('"/proc/$1/status"', shlex.quote(str(status))) + '\n')
        body = ('pidcore(){ [ "$1" = 4242 ]; } # Explicit fixture executable identity only.\n' +
                f'CORE_RUNNER={runner}; START_DNS={dns}; START_MODE={mode}\n'
                'core_identity_confirm 4242\nprintf "gid=%s dns=%s\\n" "$CORE_GID" "$SYSTEM_DNS"\n')
        result = self.sh(body, functions=functions)
        self.assertEqual((result.returncode, result.stderr), (0, ''), result.stdout + result.stderr)
        return result.stdout.strip()

    def test_bypass_with_ipv6_available_still_requires_combined_owner_match(self):
        present = 'has(){ return 0; }\n'
        self.assertEqual(self.prepare(before=present), f'runner={self.busybox} spec=root:net_admin dns=exempt')
        self.assertEqual(self.prepare({'XT6_FAIL': '--gid-owner'}, before=present), 'runner= spec= dns=exempt')

    def test_confirmation_requires_all_four_uid_and_gid_fields(self):
        for field in range(4):
            uids = ['0'] * 4; uids[field] = '2000'
            self.assertEqual(self.confirm('3005', uid_fields=uids), 'gid= dns=exempt', ('uid', field))
            gids = ['3005'] * 4; gids[field] = '0'
            self.assertEqual(self.confirm('3005', gid_fields=gids), 'gid= dns=exempt', ('gid', field))

    def test_confirmation_trusts_only_what_the_kernel_reports(self):
        self.assertEqual(self.confirm('3005'), 'gid=3005 dns=captured')
        self.assertEqual(self.confirm('3005', mode='redirect'), 'gid=3005 dns=captured')
        self.assertEqual(self.confirm('0'), 'gid= dns=exempt')
        self.assertEqual(self.confirm(''), 'gid= dns=exempt')
        self.assertEqual(self.confirm('3005', runner=''), 'gid= dns=exempt')

    def test_resolver_capture_needs_dns_takeover_and_an_xtables_mode(self):
        self.assertEqual(self.confirm('3005', dns='off'), 'gid=3005 dns=exempt')
        self.assertEqual(self.confirm('3005', mode='tun'), 'gid=3005 dns=exempt')
        self.assertEqual(self.confirm('3005', mode='ebpf'), 'gid=3005 dns=exempt')


class Tuning(Harness):
    def value(self, call):
        result = self.sh(call + '; printf "rc=%s\\n" "$?"')
        self.assertEqual(result.stderr, '', result.stderr)
        return result.stdout.strip()

    def test_cpu_mask(self):
        for text, mask in (('0', '1'), ('0-3', 'f'), ('0,2,4-6', '75'), ('7', '80'), ('0-7', 'ff'), ('23', '800000'), ('4-7,0', 'f1')):
            self.assertEqual(self.value(f'cpu_mask {shlex.quote(text)}'), mask + '\nrc=0', text)
        for text in ('', 'a', '0-', '-3', '3-1', '24', '0-24', '1-2-3', '00', '0,,1', '0 1', '1;id', '100'):
            self.assertEqual(self.value(f'cpu_mask {shlex.quote(text)}'), 'rc=1', text)

    def test_memory_limit_bytes(self):
        for text, size in (('32M', 33554432), ('128M', 134217728), ('128m', 134217728), ('1G', 1073741824),
                           ('64MiB', 67108864), ('64MB', 67108864), ('65536K', 67108864), ('33554432', 33554432), ('16G', 17179869184)):
            self.assertEqual(self.value(f'mem_bytes {shlex.quote(text)}'), f'{size}\nrc=0', text)
        for text in ('', '31M', '100', '17G', 'abc', '1.5G', '-1G', '128 M', '1T', '128M;id'):
            self.assertEqual(self.value(f'mem_bytes {shlex.quote(text)}'), 'rc=1', text)

    def test_invalid_values_are_named_before_anything_changes(self):
        def valid(cpu='', mem='', io=''):
            return self.value(f'START_CPU={shlex.quote(cpu)}; START_MEM={shlex.quote(mem)}; START_IO={shlex.quote(io)}; '
                              'tuning_valid; RC=$?; printf "%s|%s|%s\\n" "$START_CPU_MASK" "$START_MEM_BYTES" "$TUNE_ERROR"; (exit $RC)')
        self.assertEqual(valid(), '||\nrc=0')
        self.assertEqual(valid('0-3', '128M', '4'), 'f|134217728|\nrc=0')
        self.assertIn('CPU 核心分配格式无效', valid(cpu='9-1'))
        self.assertIn('内存限制格式无效', valid(mem='8M'))
        self.assertIn('磁盘 I/O 权重应为 0-7', valid(io='8'))
        self.assertTrue(valid(io='8').endswith('rc=1'))

    def test_tuning_report_names_each_requested_control(self):
        tools = ('renice(){ printf "renice %s\\n" "$*" >> "$CALLS"; }\n'
                 'taskset(){ printf "taskset %s\\n" "$*" >> "$CALLS"; }\n'
                 'ionice(){ printf "ionice %s\\n" "$*" >> "$CALLS"; [ "${IONICE_FAIL:-0}" != 1 ]; }\n')
        body = tools + 'START_PERF=1; START_CPU_MASK=f; START_IO=3; START_MEM_BYTES=134217728; core_tune $$; cat "$RUN/tuning-status"\n'
        result = self.sh(body)
        self.assertEqual(result.stdout.strip(), 'priority=applied cpu=applied:f io=applied:3 memory=applied:134217728')
        calls = self.calls.read_text().splitlines()
        self.assertTrue(any(x.startswith('renice -n -10 -p ') for x in calls), calls)
        self.assertTrue(any(x.startswith('taskset -ap f ') for x in calls), calls)
        self.assertTrue(any(x.startswith('ionice -c 2 -n 3 -p ') for x in calls), calls)
        self.assertEqual(self.sh(tools + 'START_PERF=0; START_CPU_MASK=; START_IO=; START_MEM_BYTES=; core_tune $$; cat "$RUN/tuning-status"\n').stdout.strip(), 'none')
        failed = self.sh(tools + 'START_PERF=0; START_CPU_MASK=; START_IO=3; START_MEM_BYTES=; core_tune $$; cat "$RUN/tuning-status"\n', {'IONICE_FAIL': '1'})
        self.assertEqual(failed.stdout.strip(), 'io=failed')


if __name__ == '__main__': unittest.main(verbosity=2)
