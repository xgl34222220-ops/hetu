#!/usr/bin/env python3
"""Execute shipped start/health/repair functions with isolated network fixtures.

The core is an owned sleep process. Every Android/network operation is replaced;
no device, host firewall, global Root path or live proxy is accessed.
"""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
SOURCE = (REPO / 'android-app/app/src/main/assets/hetu-root.sh').read_text()
PREFIX = SOURCE.rsplit('\ncase "${1:-status}" in', 1)[0]
MANGLE = ('-N HETU_MOUT\n-N HETU_MPRE\n'
          '-A HETU_MOUT -j RETURN\n-A HETU_MPRE -j RETURN\n'
          '-A OUTPUT -j HETU_MOUT\n-A PREROUTING -j HETU_MPRE\n')
NAT = '-N HETU_DNSOUT\n-A HETU_DNSOUT -j RETURN\n-A OUTPUT -j HETU_DNSOUT\n'
FOREIGN = '-N fw_OUTPUT\n-A fw_OUTPUT -m owner --uid-owner 10200 -j REJECT\n'
RULE = '14500: from all fwmark 0x200000/0x200000 lookup 20260\n'
ROUTE = 'local default dev lo scope host\n'
SOCKETS = ('tcp LISTEN 0 128 127.0.0.1:29090 0.0.0.0:*\n'
           'tcp LISTEN 0 128 0.0.0.0:7893 0.0.0.0:*\n'
           'udp UNCONN 0 0 0.0.0.0:7893 0.0.0.0:*\n'
           'tcp LISTEN 0 128 0.0.0.0:1053 0.0.0.0:*\n'
           'udp UNCONN 0 0 0.0.0.0:1053 0.0.0.0:*\n')

MOCK = r'''import json, os, pathlib, shlex, sys
p=pathlib.Path(os.environ['HEALTH_FIXTURE']); tool=sys.argv[1]; args=sys.argv[2:]
with (p/'calls').open('a') as out: out.write(json.dumps([tool,*args])+'\n')
if tool=='xt4':
    assert len(args)==3 and args[0]=='-t' and args[2]=='-S', args
    if (p/'read-failure').exists(): sys.exit(1)
    print((p/('4-'+args[1])).read_text(),end='')
elif tool=='ip':
    assert args[0]=='-4', args
    if args[1:]==['rule','show']: print((p/'rule').read_text(),end='')
    elif args[1:]==['route','show','table','20260']: print((p/'route').read_text(),end='')
    elif args[1:]==['route','replace','local','0.0.0.0/0','dev','lo','table','20260']:
        (p/'route').write_text('local default dev lo scope host\n')
    elif args[1:]==['rule','add','pref','14500','fwmark','0x200000/0x200000','table','20260']:
        with (p/'rule').open('a') as out: out.write('14500: from all fwmark 0x200000/0x200000 lookup 20260\n')
    else: raise AssertionError(args)
elif tool=='iptables-restore':
    assert args==['-w','2','--noflush'], args
    batch=sys.stdin.read().splitlines(); assert batch[-1]=='COMMIT', batch
    file=p/('4-'+batch[0][1:]); rules=file.read_text().splitlines()
    for line in batch[1:-1]:
        words=shlex.split(line); action,chain=words[:2]
        assert chain.startswith('HETU_') or (action in ('-A','-I','-D') and chain in ('OUTPUT','PREROUTING','FORWARD') and words[2]=='-j' and words[3].startswith('HETU_')), line
        if action=='-N': rules.append(line)
        elif action=='-F': rules=[r for r in rules if not r.startswith('-A '+chain+' ')]
        elif action=='-D': rules.remove(line.replace('-D ','-A ',1))
        elif action in ('-A','-I'): rules.append(line.replace('-I ','-A ',1))
        else: raise AssertionError(line)
    file.write_text('\n'.join(rules)+'\n')
else: raise AssertionError(tool)
'''


class RootHealth(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='hetu-health-qa-')
        self.base = Path(self.temp.name)
        self.run = self.base/'run'; self.run.mkdir()
        self.fixture = self.base/'fixture'; self.fixture.mkdir()
        self.functions = self.base/'functions.sh'; self.functions.write_text(PREFIX+'\n')
        self.mock = self.base/'mock.py'; self.mock.write_text(MOCK)
        self.commands = self.base/'commands'; self.commands.mkdir()
        restore = self.commands/'iptables-restore'
        restore.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' '+shlex.quote(str(self.mock))+' iptables-restore "$@"\n')
        restore.chmod(0o700)
        self.core = self.base/'core'; self.core.write_text('#!/bin/sh\nexec /bin/sleep 300\n'); self.core.chmod(0o700)
        self.config = self.base/'config.yaml'; self.config.write_text('fixture: true\n')
        for file, text in {'4-mangle':MANGLE, '4-nat':NAT, '4-filter':FOREIGN,
                           'rule':RULE, 'route':ROUTE, 'sockets':SOCKETS}.items():
            (self.fixture/file).write_text(text)
        self.env = dict(os.environ, HEALTH_FIXTURE=str(self.fixture),
                        PATH=str(self.commands)+':'+os.environ['PATH'])
        self.setup = ('RUN='+shlex.quote(str(self.run))+'\nBASE="$RUN/base"\n'
                      'PIDFILE="$RUN/pid"; MODEFILE="$RUN/mode"; SESSION="$RUN/session"; '
                      'NET_STATE="$RUN/net"; LOG="$RUN/core.log"; LOCK_DIR="$RUN/lock"; '
                      'START_STATE="$RUN/stage"; START_ERROR="$RUN/error"; START_TIMING="$RUN/timing"; '
                      'CRASH_STATE="$RUN/crash"; WATCHDOG_PID="$RUN/watchdog"\n'
                      'has(){ [ "$1" != ip6tables ]; }\n'
                      'xt4(){ '+shlex.quote(sys.executable)+' '+shlex.quote(str(self.mock))+' xt4 "$@"; }\n'
                      'xt4q(){ xt4 "$@"; }; xt6(){ return 1; }; xt6q(){ return 1; }\n'
                      'ip(){ '+shlex.quote(sys.executable)+' '+shlex.quote(str(self.mock))+' ip "$@"; }\n'
                      'ss(){ cat "$HEALTH_FIXTURE/sockets"; }\n'
                      'pidcore(){ [ "$1" = "$(cat "$PIDFILE")" ]; }\n'
                      'monotonic_seconds(){ MONO_SECONDS=100; }\n'
                      'select_dns6_policy(){ START_DNS6=off; }\n'
                      'markused(){ return 1; }\n'
                      'allocnet(){ MARK=0x200000; MASK=0x200000; TABLE=20260; PREF=14500; savenet; }\n'
                      'google_firewall_cleanup(){ echo cleanup >> "$HEALTH_FIXTURE/google"; }\n'
                      'google_firewall_maintain(){ :; }\n')
        for name in ('preflight','validatecfg','stopwatchdog','cleanup','restorev6','stopcore',
                     'check_start_ports','wait_ready','install_mangle4','install_redirect4',
                     'install_dns_redirect4','install_udp_leak_guard4','install_quic4','start_watchdog'):
            self.setup += name+'(){ :; }\n'

    def tearDown(self):
        pidfile = self.run/'pid'
        if pidfile.exists():
            try: os.kill(int(pidfile.read_text()), signal.SIGTERM)
            except (ProcessLookupError, ValueError): pass
        self.temp.cleanup()

    def shell(self, body, prefix=None):
        if prefix is not None: self.functions.write_text(prefix+'\n')
        harness = self.base/'harness.sh'
        harness.write_text('. '+shlex.quote(str(self.functions))+'\n'+self.setup+body+'\n')
        result = subprocess.run(['sh',str(harness)], env=self.env, text=True,
                                capture_output=True, timeout=10)
        self.assertEqual('',result.stderr,result.stderr)
        self.assertEqual(0,result.returncode,result.stdout)
        return result.stdout

    def start(self, vendor='0', prefix=None):
        args = [str(self.core),str(self.config),'tproxy','7893','7892','bypass',
                '1','1','redirect','0','1053','29090','core','','0','0','','','',
                '0','0','','','1','1','0','','','',vendor]
        return self.shell('start '+' '.join(map(shlex.quote,args)),prefix)

    def health(self):
        return self.shell('health_collect; printf "%s:%s\\n" "$H_STATE" "$H_REASON"').strip()

    def repair(self):
        self.shell('health_repair "$(cat "$PIDFILE")"')

    def calls(self):
        path = self.fixture/'calls'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def test_real_start_google_disabled_has_complete_healthy_baseline(self):
        self.assertTrue(json.loads(self.start())['ok'])
        self.assertEqual('healthy:',self.health())
        self.assertEqual((self.run/'session').read_bytes(),(self.run/'network-manifest/session').read_bytes())
        self.assertIn('GOOGLE_FIREWALL_CLEAN=0\n',(self.run/'session').read_text())
        self.assertFalse((self.fixture/'google').exists())

    def test_real_start_google_enabled_keeps_baseline_and_runs_cleanup(self):
        self.assertTrue(json.loads(self.start('1'))['ok'])
        self.assertEqual('healthy:',self.health())
        self.assertIn('GOOGLE_FIREWALL_CLEAN=1\n',(self.run/'network-manifest/session').read_text())
        self.assertEqual('cleanup\n',(self.fixture/'google').read_text())

    def test_previous_ordering_reproduces_reported_metadata_fault(self):
        # Replay the shipped start with only the pre-fix ordering restored.
        line = next(line for line in PREFIX.splitlines(True) if "printf 'GOOGLE_FIREWALL_CLEAN=%s" in line)
        old = PREFIX.replace(line,'').replace('  start_stage "start-watchdog"\n',
                                             '  start_stage "start-watchdog"\n'+line,1)
        self.start(prefix=old)
        self.assertEqual('upgrade-required:session-manifest-missing',self.health())
        (self.fixture/'route').write_text('')
        self.repair()
        self.assertEqual('',(self.fixture/'route').read_text())

    def test_missing_route_and_policy_rule_are_detected_and_repaired(self):
        self.start(); (self.fixture/'route').write_text(''); (self.fixture/'rule').write_text('')
        self.assertIn('ipv4-policy-rule',self.health()); self.assertIn('ipv4-local-route',self.health())
        pid = (self.run/'pid').read_text(); self.repair()
        self.assertEqual('healthy:',self.health()); self.assertEqual(pid,(self.run/'pid').read_text())
        self.assertEqual(FOREIGN,(self.fixture/'4-filter').read_text())

    def test_missing_owned_chain_and_duplicate_hook_are_repaired(self):
        self.start(); (self.fixture/'4-nat').write_text(FOREIGN+'-A OUTPUT -j HETU_DNSOUT\n'*2)
        self.assertIn('4-nat-HETU_DNSOUT',self.health()); self.assertIn('4-nat-hook',self.health())
        self.repair(); self.assertEqual('healthy:',self.health())
        self.assertTrue((self.fixture/'4-nat').read_text().startswith(FOREIGN))
        self.assertEqual(1,(self.fixture/'4-nat').read_text().count('-A OUTPUT -j HETU_DNSOUT\n'))
        self.assertTrue(any(call[0]=='iptables-restore' for call in self.calls()))

    def test_missing_listener_stays_degraded_and_is_not_hidden(self):
        self.start(); (self.fixture/'sockets').write_text(SOCKETS.replace('tcp LISTEN 0 128 0.0.0.0:1053 0.0.0.0:*\n',''))
        self.assertEqual('degraded:listener-1053-tcp',self.health()); self.repair()
        self.assertEqual('degraded:listener-1053-tcp',self.health())
        self.assertFalse(any(call[0]=='iptables-restore' for call in self.calls()))

    def test_session_tampering_still_invalidates_manifest(self):
        self.start(); path=self.run/'session'; path.write_text(path.read_text()+'SHARE=1\n')
        self.assertEqual('upgrade-required:session-manifest-missing',self.health())

    def test_pid_tampering_still_invalidates_manifest(self):
        self.start(); path=self.run/'network-manifest/pid'; path.write_text('999999\n')
        self.assertEqual('upgrade-required:session-manifest-missing',self.health())

    def test_snapshot_tampering_still_invalidates_checksum(self):
        self.start(); path=self.run/'network-manifest/4-nat'; path.write_text('')
        self.assertEqual('upgrade-required:session-manifest-missing',self.health())

    def test_unknown_table_read_does_not_mutate_network(self):
        self.start(); (self.fixture/'read-failure').touch(); (self.fixture/'route').write_text('')
        self.assertTrue(self.health().startswith('unknown:')); self.repair()
        self.assertEqual('',(self.fixture/'route').read_text())
        self.assertFalse(any(call[0]=='iptables-restore' or 'replace' in call for call in self.calls()))

    def test_other_owner_at_priority_does_not_get_overwritten(self):
        self.start(); occupied='14500: from all fwmark 0x400000/0x400000 lookup 20300\n'
        (self.fixture/'rule').write_text(occupied); (self.fixture/'route').write_text('')
        self.assertIn('ipv4-policy-rule',self.health()); self.repair()
        self.assertEqual(occupied,(self.fixture/'rule').read_text())
        self.assertEqual('',(self.fixture/'route').read_text())


if __name__ == '__main__': unittest.main(verbosity=2)
