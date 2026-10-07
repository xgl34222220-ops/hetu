#!/usr/bin/env python3
"""Exercise the shipped shell with fake Android/xtables tools; never touch host rules."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / 'android-app/app/src/main/assets/hetu-root.sh').read_text()
PREFIX, DISPATCH = SOURCE.rsplit('\ncase "${1:-status}" in', 1)
CHAINS = ('fw_INPUT', 'fw_OUTPUT', 'fw_OUTPUT_oplus_dns', 'zte_fw_gms')

MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, shlex, sys
p=pathlib.Path(os.environ['FIXTURE']); args=sys.argv[1:]; tool=pathlib.Path(sys.argv[0]).name
with (p/'calls').open('a') as log: log.write(json.dumps([tool,*args])+'\n')
if tool in ('pm','cmd'):
    if args==['list','users']:
        print('Users:'); print('UserInfo{0:Owner:13} running'); print('UserInfo{10:Work:30} running')
    else:
        if tool=='cmd' and os.environ.get('CMD_MISSING')=='1': sys.exit(127)
        user=args[args.index('--user')+1]
        print((p/('packages-'+user)).read_text())
elif tool in ('iptables','ip6tables'):
    assert args[:4]==['-w','1','-t','filter'], args
    action,chain=args[4:6]
    assert action in ('-S','-D') and chain in ('fw_INPUT','fw_OUTPUT','fw_OUTPUT_oplus_dns','zte_fw_gms'), args
    family='4' if tool=='iptables' else '6'; file=p/(family+'-'+chain)
    if not file.exists(): sys.exit(1)
    if action=='-S': print(file.read_text(),end='')
    elif os.environ.get('DELETE_FAIL')=='1': sys.exit(1)
    else:
        lines=file.read_text().splitlines(); expected=['-A',chain,*args[6:]]
        for i,line in enumerate(lines):
            if shlex.split(line)==expected:
                del lines[i]; file.write_text('\n'.join(lines)+'\n'); break
        else: sys.exit(1)
else: raise AssertionError(tool)
'''


class GoogleFirewall(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.fixture = self.base / 'fixture'; self.fixture.mkdir()
        self.run = self.base / 'run'; self.run.mkdir()
        self.bin = self.base / 'bin'; self.bin.mkdir()
        self.functions = self.base / 'functions.sh'; self.functions.write_text(PREFIX + '\n')
        for tool in ('pm', 'cmd', 'iptables', 'ip6tables'):
            file = self.bin / tool; file.write_text(MOCK); file.chmod(0o755)
        (self.fixture / 'packages-0').write_text('package:com.google.android.gms uid:10123\npackage:com.android.vending uid:10124\npackage:com.google.android.gsf uid:10125\npackage:com.google.android.gms.fake uid:19999\npackage:other.app uid:10200\n')
        (self.fixture / 'packages-10').write_text('package:com.google.android.gms uid:1010123\n')
        self.env = dict(os.environ, FIXTURE=str(self.fixture), TEST_RUN=str(self.run),
                        PATH=str(self.bin) + ':' + os.environ['PATH'])

    def tearDown(self): self.temp.cleanup()

    def shell(self, body, extra=None, args=(), dispatch=False):
        harness = self.base / 'harness.sh'
        harness.write_text('. ' + shlex.quote(str(self.functions)) + '\n' +
                           'RUN="$TEST_RUN"; SESSION="$RUN/session"; PIDFILE="$RUN/pid"; '
                           'LOCK_DIR="$RUN/lock"; START_ERROR="$RUN/error"\n' + body + '\n' +
                           ('case "${1:-status}" in' + DISPATCH if dispatch else ''))
        result = subprocess.run(['sh', str(harness), *args], env=dict(self.env, **(extra or {})),
                                text=True, capture_output=True, timeout=15)
        self.assertEqual(result.stderr, '', result.stderr)
        return result

    def rule(self, family, chain, lines):
        (self.fixture / f'{family}-{chain}').write_text('\n'.join(lines) + '\n')

    def calls(self):
        file = self.fixture / 'calls'
        return [json.loads(x) for x in file.read_text().splitlines()] if file.exists() else []

    def deletes(self): return [x for x in self.calls() if '-D' in x]

    def enable(self): (self.run / 'session').write_text('GOOGLE_FIREWALL_CLEAN=1\n')

    def maintain(self, now=100, before='', extra_body=''):
        return self.shell('printf "%s\\n" "$$" > "$PIDFILE"\n'
                          'pidcore(){ [ "$1" = "$$" ]; }\n'
                          f'monotonic_seconds(){{ MONO_SECONDS={now}; }}\n' + before + '\n' +
                          'google_firewall_maintain "$$"\n' + extra_body)

    def test_current_uid_resolution_includes_work_profile_and_exact_package_names(self):
        result = self.shell('google_firewall_uids')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.splitlines(), ['10123', '10124', '10125', '1010123'])

    def test_older_oem_package_tool_fallback(self):
        result = self.shell('google_firewall_uids', {'CMD_MISSING': '1'})
        self.assertIn('1010123', result.stdout.splitlines())

    def test_uid_is_resolved_again_after_package_reinstall(self):
        (self.fixture / 'packages-0').write_text('package:com.google.android.gms uid:10777\n')
        self.rule(4, 'fw_OUTPUT', ['-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT', '-A fw_OUTPUT -m owner --uid-owner 10777 -j REJECT'])
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertEqual(len(self.deletes()), 1)
        self.assertIn('10777', self.deletes()[0])

    def test_both_families_google_reject_and_drop_removed_by_exact_spec(self):
        for family in (4, 6):
            for chain in CHAINS:
                self.rule(family, chain, [f'-A {chain} -o wlan+ -m owner --uid-owner 10123 -j REJECT', f'-A {chain} -m owner --uid-owner 1010123 -j DROP'])
        result = self.shell('google_firewall_cleanup')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertEqual(len(self.deletes()), 16)
        self.assertEqual((self.run / 'google-firewall-status').read_text(), 'checked=8 removed=16 failed=0\n')
        self.assertIn('wlan+', self.deletes()[0])

    def test_unrelated_uid_accept_inverted_range_and_quoted_rules_preserved(self):
        protected = ['-A fw_OUTPUT -m owner --uid-owner 10200 -j REJECT',
                     '-A fw_OUTPUT -m owner --uid-owner 10123 -j ACCEPT',
                     '-A fw_OUTPUT -m owner ! --uid-owner 10123 -j REJECT',
                     '-A fw_OUTPUT -m owner --uid-owner 10123-10125 -j DROP',
                     '-A fw_OUTPUT -m comment --comment "leave intact" -m owner --uid-owner 10123 -j DROP',
                     '-A fw_OUTPUT -j DROP']
        self.rule(4, 'fw_OUTPUT', protected + ['-A fw_OUTPUT -m owner --uid-owner 10123 -j DROP'])
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertEqual((self.fixture / '4-fw_OUTPUT').read_text().splitlines(), protected)
        self.assertEqual(len(self.deletes()), 1)

    def test_no_google_packages_no_firewall_operation(self):
        for user in ('0', '10'): (self.fixture / ('packages-' + user)).write_text('package:other.app uid:10200\n')
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertFalse([c for c in self.calls() if c[0] in ('iptables', 'ip6tables')])

    def test_unavailable_ipv6_is_nonfatal(self):
        self.rule(4, 'fw_INPUT', ['-A fw_INPUT -m owner --uid-owner 10125 -j REJECT'])
        self.assertEqual(self.shell('google_firewall_cleanup').returncode, 0)
        self.assertEqual(len(self.deletes()), 1)
        self.assertEqual((self.run / 'google-firewall-status').read_text(), 'checked=1 removed=1 failed=0\n')

    def test_failed_exact_delete_is_reported_without_flush_fallback(self):
        self.rule(4, 'fw_OUTPUT', ['-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT'])
        self.assertEqual(self.shell('google_firewall_cleanup', {'DELETE_FAIL': '1'}).returncode, 0)
        self.assertEqual((self.run / 'google-firewall-status').read_text(), 'checked=1 removed=0 failed=1\n')
        self.assertIn('REJECT', (self.fixture / '4-fw_OUTPUT').read_text())

    def test_repeated_cleanup_is_idempotent(self):
        self.rule(4, 'fw_OUTPUT', ['-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT'])
        self.assertEqual(self.shell('google_firewall_cleanup; google_firewall_cleanup').returncode, 0)
        self.assertEqual(len(self.deletes()), 1)

    def test_globbing_state_and_wildcard_interface_are_preserved(self):
        self.rule(4, 'fw_OUTPUT', ['-A fw_OUTPUT -o wlan+ -m owner --uid-owner 10123 -j REJECT'])
        result = self.shell('set -f; google_firewall_cleanup; case $- in *f*) echo preserved;; *) exit 1;; esac')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout, 'preserved\n')

    def test_disabled_session_never_enters_maintenance(self):
        (self.run / 'session').write_text('GOOGLE_FIREWALL_CLEAN=0\n')
        self.assertEqual(self.maintain().returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_changed_core_pid_never_changes_firewall(self):
        self.enable()
        self.assertEqual(self.maintain(before='echo 123 > "$PIDFILE"').returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_sixty_second_rate_limit_and_oem_rule_reinsertion(self):
        self.enable(); rule = '-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT'
        self.rule(4, 'fw_OUTPUT', [rule]); self.assertEqual(self.maintain(100).returncode, 0)
        self.rule(4, 'fw_OUTPUT', [rule]); self.assertEqual(self.maintain(159).returncode, 0)
        self.assertEqual(len(self.deletes()), 1)
        self.assertEqual(self.maintain(160).returncode, 0)
        self.assertEqual(len(self.deletes()), 2)
        self.assertFalse((self.run / 'lock').exists())

    def test_failed_transaction_lock_never_changes_firewall(self):
        self.enable()
        self.assertEqual(self.maintain(before='acquire_lock(){ return 1; }').returncode, 0)
        self.assertEqual(self.calls(), [])

    def test_healthy_hetu_rules_still_check_oem_google_rules(self):
        self.enable(); self.rule(4, 'fw_OUTPUT', ['-A fw_OUTPUT -m owner --uid-owner 10123 -j REJECT'])
        result = self.shell('echo "$$" > "$PIDFILE"\npidcore(){ [ "$1" = "$$" ]; }\n'
                            'monotonic_seconds(){ MONO_SECONDS=100; }\nhealth_collect(){ H_STATE=healthy; }\n'
                            'health_repair "$$"')
        self.assertEqual(result.returncode, 0); self.assertEqual(len(self.deletes()), 1)

    def protocol(self, count=31, vendor='0', perf='0'):
        args = ['start'] + [''] * (count - 1)
        if count == 31: args[24:] = ['1', '1', perf, '', '', '', vendor]
        return self.shell('root(){ :; }\nstart(){ printf "accepted:%s:%s\\n" "$#" "${30:-0}"; }', args=args, dispatch=True)

    def test_start_protocol_accepts_legacy_and_supported_google_flag(self):
        for count in (23, 24, 31):
            result = self.protocol(count); self.assertEqual(result.returncode, 0); self.assertIn('accepted:', result.stdout)
        self.assertEqual(self.protocol(vendor='1').stdout, 'accepted:30:1\n')

    def test_start_protocol_rejects_invalid_flags_and_accepts_extended_controls(self):
        result = self.protocol(vendor='2'); self.assertEqual(result.returncode, 1)
        self.assertFalse(json.loads(result.stdout)['ok'])
        # Runtime 153 implements the extended controls; they used to block every start.
        self.assertEqual(self.protocol(vendor='1', perf='1').stdout, 'accepted:30:1\n')
        result = self.protocol(vendor='1', perf='2'); self.assertEqual(result.returncode, 1)
        self.assertIn('性能模式', json.loads(result.stdout)['message'])


if __name__ == '__main__': unittest.main(verbosity=2)
