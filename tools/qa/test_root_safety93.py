#!/usr/bin/env python3
"""Reimplemented safety regression fixtures; no real network/firewall operations."""
import importlib.util
from pathlib import Path
import subprocess
import json
import shlex
import sys
import time
import unittest

spec = importlib.util.spec_from_file_location('autostart_fixture', Path(__file__).with_name('test_root_autostart.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

class BootSafety93(fixture.BootWorker):
    def test_pid_without_health_never_reports_running(self):
        (self.base/'run/core.pid').write_text(str(self.core.pid))
        (self.base/'hetu-root.sh').write_text('exit 1\n')
        self.assertEqual(1, self.run_worker().returncode)
        self.assertEqual(3, self.starts())
        self.assertNotEqual('running', self.state())
        self.assertFalse((self.dir/'notified').exists())

    def test_permanent_failure_starts_only_three_times(self):
        self.assertEqual(1, self.run_worker(FAIL_STARTS=999).returncode)
        self.assertEqual(3, self.starts())
        self.assertEqual('restore-timeout', self.state())

    def test_initializing_lock_cannot_be_stolen(self):
        lock=self.boot/'restore.lock'; lock.mkdir()
        self.assertEqual(0, self.run_worker().returncode)
        self.assertEqual(0, self.starts())
        self.assertTrue(lock.exists())

    def claim(self, requested='current-boot'):
        source=self.isolate((fixture.ASSETS/'hetu-root.sh').read_text()).rsplit('\ncase "${1:-status}" in',1)[0]
        script=self.dir/'claim.sh'
        script.write_text(source+'\nacquire_lock || exit 2\nrecovery_claim "$1"\n')
        return subprocess.run(['sh',str(script),requested],env=self.env,capture_output=True,text=True,timeout=5)

    def test_shared_budget_persists_and_old_boot_cannot_reset(self):
        for count in range(1,4):
            self.assertEqual(0,self.claim().returncode)
            self.assertEqual(['current-boot',str(count)],(self.boot/'recovery-budget').read_text().split()[:2])
        self.assertEqual(1,self.claim().returncode)
        self.assertEqual(1,self.claim('previous-boot').returncode)
        self.assertEqual(['current-boot','3'],(self.boot/'recovery-budget').read_text().split()[:2])

    def test_shared_budget_cannot_reset_expired_deadline(self):
        ledger=self.boot/'recovery-budget';ledger.write_text('current-boot 1 0 1\n')
        self.assertEqual(1,self.claim().returncode)
        self.assertEqual('current-boot 1 0 1\n',ledger.read_text())

    def test_shared_budget_rejects_cancelled_boot(self):
        (self.boot/'stopped-boot').write_text('current-boot\n')
        self.assertEqual(1,self.claim().returncode)
        self.assertFalse((self.boot/'recovery-budget').exists())

    def root_body(self, body, **environment):
        source=self.isolate((fixture.ASSETS/'hetu-root.sh').read_text()).rsplit('\ncase "${1:-status}" in',1)[0]
        status=self.dir/'modeled-status'
        if status.exists():source=source.replace('\"/proc/$1/status\"',shlex.quote(str(status)))
        script=self.dir/'root-body.sh';script.write_text(source+'\n'+body+'\n')
        return subprocess.run(['sh',str(script)],env=dict(self.env,**environment),capture_output=True,text=True,timeout=5)

    def test_cleanup_residual_rule_is_not_successful_stop(self):
        result=self.root_body('''cleanup(){ :; }
cleanup_snapshot_read(){ printf '%s\\n' '-N HETU_KOUT'; }
has(){ return 1; }
cleanup_confirmed
''')
        self.assertEqual(1,result.returncode,result.stderr)

    def test_cleanup_unknown_snapshot_is_not_successful_stop(self):
        result=self.root_body('''cleanup(){ :; }
cleanup_snapshot_read(){ return 1; }
cleanup_confirmed
''')
        self.assertEqual(1,result.returncode,result.stderr)

    def test_failed_stop_retains_session_for_retry(self):
        (self.base/'run/session.state').write_text('evidence')
        result=self.root_body('''stopwatchdog(){ :; }; cleanup_confirmed(){ return 1; }
stopcore(){ :; }; restorev6(){ :; }
stop_transaction
''')
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertEqual('evidence',(self.base/'run/session.state').read_text())
        self.assertEqual('current-boot\n',(self.boot/'stopped-boot').read_text())

    def test_core_identity_requires_all_four_uid_gid_fields(self):
        status=self.dir/'modeled-status'
        body='CORE_RUNNER=fixture; START_DNS=off; pidcore(){ return 0; }; core_identity_confirm 77123'
        status.write_text('Uid: 0 0 0 0\nGid: 3005 3005 3005 3005\n')
        self.assertEqual(0,self.root_body(body).returncode)
        for line in ['Uid: 0 1 0 0\nGid: 3005 3005 3005 3005\n', 'Uid: 0 0 0 0\nGid: 3005 3005 0 3005\n']:
            status.write_text(line)
            self.assertEqual(1,self.root_body(body).returncode)

    def test_queued_manual_native_start_rejects_stop_before_entry(self):
        (self.base/'run/start-cancel-generation').write_text('new-stop\n')
        result=self.root_body('transaction_begin',HETU_START_CANCEL_TOKEN='old-start')
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertFalse(any((self.base/'run').glob('start-timer.*')))
        self.assertFalse((self.base/'run/core.pid').exists())

    def test_explicit_empty_cancel_ticket_is_not_latest_token(self):
        (self.base/'run/start-cancel-generation').write_text('first-stop\n')
        result=self.root_body('transaction_begin',HETU_START_CANCEL_TOKEN='')
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertFalse(any((self.base/'run').glob('start-timer.*')))

    def test_frontend_term_during_blocked_xtables_is_bounded_before_rollback(self):
        self.write_command('iptables', r'''printf '%s\n' "$*" > "$HETU_TEST/blocked-iptables-args"
trap '' TERM
exec /bin/sleep 30
''')
        before=time.monotonic()
        result=self.root_body(r'''START_ACTIVE=1; START_MUTATED=1; START_KILL=0; START_CANCEL_TOKEN="$ENTRY_CANCEL_TOKEN"
read -r T unused < /proc/uptime; TXN_DEADLINE=$((${T%%.*}+110))
stopwatchdog(){ :; }; stopcore(){ :; }; restorev6(){ :; }
cleanup_confirmed(){ printf complete > "$RUN/blocked-command-rollback"; }
(/bin/sleep 0.15; kill -TERM "$$") &
xt4 -t filter -S
''')
        elapsed=time.monotonic()-before
        self.assertEqual(1,result.returncode,result.stdout+result.stderr)
        self.assertGreater(elapsed,2.5,'The fixture must actually hold the foreground command past TERM')
        self.assertLess(elapsed,5.0,'3s command + 1s forced termination bounds the deferred trap')
        self.assertEqual('complete',(self.base/'run/blocked-command-rollback').read_text())
        self.assertEqual('-w 2 -t filter -S\n',(self.dir/'blocked-iptables-args').read_text())
        print(json.dumps({'test':'foreground_term_boundary','elapsedSeconds':round(elapsed,3),'termSentAfterSeconds':0.15,'commandTimeoutSeconds':3,'killGraceSeconds':1,'rollbackCompleted':True}))

    def test_stopping_inactive_helper_does_not_publish_start_failure(self):
        result=self.root_body('kill -TERM "$$"')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('',result.stdout)
        self.assertFalse((self.base/'run/last-start-error').exists())

    def test_real_deadline_signals_only_owned_birth(self):
        result=self.root_body(f'B=$(health_core_birth {self.core.pid}); transaction_deadline {self.core.pid} "$B" 0 ""')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(-15,self.core.wait(timeout=2))

    def test_old_deadline_birth_cannot_signal_reused_pid(self):
        result=self.root_body(f'transaction_deadline {self.core.pid} 999999999999 0 ""')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertIsNone(self.core.poll())

    def test_cancel_generation_interrupts_future_deadline(self):
        (self.base/'run/start-cancel-generation').write_text('new-stop\n')
        result=self.root_body(f'B=$(health_core_birth {self.core.pid}); transaction_deadline {self.core.pid} "$B" 999999999 "old-start"')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual(-15,self.core.wait(timeout=2))

    def test_old_watchdog_cannot_publish_or_delete_new_pid(self):
        (self.base/'run/generation').write_text('new-generation\n')
        (self.base/'run/watchdog.pid').write_text('77123\n')
        result=self.root_body('''watchdog 77121 0 core '' 0 '' '' '' '' '' old-generation''')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('77123\n',(self.base/'run/watchdog.pid').read_text())

    def test_old_lock_owner_cannot_release_replacement_lock(self):
        lock=self.base/'run/.txn.lock';lock.mkdir();(lock/'pid').write_text('77123\n')
        result=self.root_body('LOCK_HELD=1; release_lock')
        self.assertEqual(0,result.returncode,result.stderr)
        self.assertEqual('77123\n',(lock/'pid').read_text())

    def guard_model(self, fail_restore=False):
        state=self.dir/'guard.json'
        state.write_text(json.dumps(['-N HETU_KOUT','-A HETU_KOUT -m owner --uid-owner 0 --gid-owner 3005 -j RETURN','-A HETU_KOUT -j REJECT','-A OUTPUT -j HETU_KOUT']))
        model=self.dir/'guard.py'
        model.write_text(r'''import json,os,pathlib,shlex,sys
p=pathlib.Path(os.environ['HETU_TEST']); f=p/'guard.json'; rules=json.loads(f.read_text()); a=sys.argv[1:]
with (p/'guard-calls').open('a') as log: log.write(json.dumps(a)+'\n')
if a==['restore']:
 batch=sys.stdin.read().splitlines()
 assert batch[0]=='*filter' and batch[-1]=='COMMIT', batch
 (p/'guard-batch').write_text('\n'.join(batch))
 if (p/'restore-fails').exists():sys.exit(1)
 for line in batch[1:-1]:
  w=shlex.split(line); action,chain=w[:2]
  if action=='-N':rules.append(line.strip())
  elif action=='-F':rules=[r for r in rules if not r.startswith('-A '+chain+' ')]
  elif action=='-A':rules.append(line.strip())
  elif action=='-I':rules.append(' '.join(['-A',chain]+w[3:]))
  else:raise AssertionError(w)
elif a[:2]==['-t','filter']:
 a=a[2:];action=a[0]
 if action=='-S':
  print('\n'.join(r for r in rules if len(a)==1 or r.split()[1]==a[1]));sys.exit(0)
 rule=' '.join(['-A']+a[1:])
 if action=='-C':sys.exit(0 if rule in rules else 1)
 if action=='-D':
  if rule not in rules:sys.exit(1)
  rules.remove(rule)
 else:raise AssertionError(a)
else:raise AssertionError(a)
f.write_text(json.dumps(rules))
''')
        if fail_restore:(self.dir/'restore-fails').touch()
        self.write_command('iptables-restore','exec '+shlex.quote(sys.executable)+' '+shlex.quote(str(model))+' restore')
        return 'xt4(){ '+shlex.quote(sys.executable)+' '+shlex.quote(str(model))+' "$@"; }\nv6supported(){ return 1; }\n'

    def test_atomic_guard_failure_keeps_exact_old_rules(self):
        setup=self.guard_model(fail_restore=True)
        before=(self.dir/'guard.json').read_bytes()
        result=self.root_body(setup+'''START_BOOTSTRAP=0
atomic_kill_guard 4 core '' 0 '' '' '' '' ''
''')
        self.assertEqual(1,result.returncode,result.stderr)
        self.assertEqual(before,(self.dir/'guard.json').read_bytes())
        self.assertIn('COMMIT',(self.dir/'guard-batch').read_text())

    def test_failed_strict_restore_physically_revokes_bootstrap_lane(self):
        setup=self.guard_model(fail_restore=True)
        result=self.root_body(setup+'''START_ACTIVE=1; START_MUTATED=1; START_KILL=1; START_BOOTSTRAP=1
START_SCOPE=core; START_UIDS=''; START_SHARE=0; START_CIDRS=''; START_IFACES=''; START_DIRECT_UIDS=''; START_DIRECT_GIDS=''; START_SHARED_MACS=''
stopwatchdog(){ :; }; stopcore(){ :; }; restorev6(){ :; }
cleanup(){ touch "$RUN/unsafe-cleanup"; }
rollback_start
''')
        self.assertEqual(0,result.returncode,result.stderr)
        rules=json.loads((self.dir/'guard.json').read_text())
        self.assertFalse(any('--gid-owner 3005' in r for r in rules),rules)
        self.assertIn('-A HETU_KOUT -j REJECT',rules)
        self.assertIn('-A OUTPUT -j HETU_KOUT',rules)
        self.assertFalse((self.base/'run/unsafe-cleanup').exists())
        self.assertEqual('rollback-incomplete\n',(self.base/'run/rollback-state').read_text())
        calls=[json.loads(line) for line in (self.dir/'guard-calls').read_text().splitlines()]
        self.assertLess(next(i for i,c in enumerate(calls) if '-D' in c),calls.index(['restore']))

    def test_atomic_bootstrap_guard_allows_only_verified_lane(self):
        setup=self.guard_model()
        result=self.root_body(setup+'''START_BOOTSTRAP=1; CORE_RUNNER=fixture-verified-runner
atomic_kill_guard 4 core '' 0 '' '' '' '' ''
''')
        self.assertEqual(0,result.returncode,result.stderr)
        rules=json.loads((self.dir/'guard.json').read_text())
        self.assertEqual(1,rules.count('-A OUTPUT -j HETU_KOUT'))
        self.assertIn('-A HETU_KOUT -m owner --uid-owner 0 --gid-owner 3005 -j RETURN',rules)
        self.assertIn('-A HETU_KOUT -j REJECT',rules)

for name in fixture.BootWorker.__dict__:
    if name.startswith('test_'):
        setattr(BootSafety93,name,None)

health_spec=importlib.util.spec_from_file_location('health_fixture', Path(__file__).with_name('test_root_health.py'))
health=importlib.util.module_from_spec(health_spec);health_spec.loader.exec_module(health)

class StartRollbackSafety93(health.RootHealth):
    def interrupted_start(self, phase):
        import shlex
        # The fixture core is the actual owned sleep child. Rollback must stop it.
        # Every network operation remains the historical isolated model.
        self.setup += r'''cleanup_confirmed(){ printf 'clean\n' >> "$RUN/cleanup-proof"; }
stopcore(){
  if [ -f "$PIDFILE" ]; then p=$(cat "$PIDFILE"); kill "$p" 2>/dev/null || true; rm -f "$PIDFILE"; fi
}
health_record(){ :; }
'''
        self.setup += phase+'(){ kill -TERM "$$"; }\n'
        args=[str(self.core),str(self.config),'tproxy','7893','7892','bypass','1','1','redirect','0','1053','29090','core','','0','0','','','','0','0','','','1','1','0','','','','0']
        output=self.shell('start '+' '.join(map(shlex.quote,args)),expected_code=1)
        self.assertIn('"ok":false',output)
        self.assertTrue((self.run/'cleanup-proof').exists())
        self.assertFalse((self.run/'pid').exists())
        self.assertFalse((self.run/'session').exists())
        self.assertFalse((self.run/'lock').exists())

    def test_kill_requested_without_verified_runner_preserves_old_runtime(self):
        self.setup += 'core_identity_prepare(){ CORE_RUNNER=""; }\ncleanup(){ touch "$RUN/unsafe-cleanup"; }\n'
        old_core=subprocess.Popen(['sleep','300'],start_new_session=True)
        self.watchdogs.append(old_core)
        (self.run/'pid').write_text(str(old_core.pid)+'\n')
        args=[str(self.core),str(self.config),'tproxy','7893','7892','bypass','1','1','redirect','0','1053','29090','core','','0','1','','','','0','0','','','1','1','0','','','','0']
        output=self.shell('start '+' '.join(map(shlex.quote,args)),expected_code=1)
        self.assertIn('"ok":false',output)
        self.assertFalse((self.run/'unsafe-cleanup').exists())
        self.assertEqual(str(old_core.pid)+'\n',(self.run/'pid').read_text())
        self.assertIsNone(old_core.poll())

    def test_interrupt_after_core_launch_rolls_back(self):
        self.interrupted_start('wait_ready')

    def test_interrupt_after_network_hooks_rolls_back(self):
        self.interrupted_start('install_redirect4')

    def test_interrupt_before_watchdog_ready_rolls_back(self):
        self.interrupted_start('start_watchdog')

for name in health.RootHealth.__dict__:
    if name.startswith('test_'): setattr(StartRollbackSafety93,name,None)


MODEL=Path(__file__).with_name('netfilter_model93.py')
START_GLOBALS=('START_MODE=tproxy; START_TP=7893; START_RP=7892; START_V6=enable; START_TCP=1; START_UDP=1; START_DNS=redirect\n'
               "START_QUIC=1; START_DP=1053; START_SCOPE=core; START_UIDS=''; START_SHARE=1; START_CIDRS=''; START_IFACES=''\n"
               "START_DIRECT_UIDS=''; START_DIRECT_GIDS=''; START_SHARED_MACS=''; START_KILL=0\n")

class NetworkLossRecovery93(unittest.TestCase):
    """Field bug: after the core exited the phone had no network and stop/start did not help.
    The shipped cleanup/stop/status/start code runs against a stateful netfilter + policy
    routing model (tools/qa/netfilter_model93.py); no real firewall is touched."""
    def setUp(self):
        import os, tempfile
        self.temp=tempfile.TemporaryDirectory(prefix='hetu-netloss93-')
        self.dir=Path(self.temp.name); self.base=self.dir/'hetu'; (self.base/'run').mkdir(parents=True); (self.base/'boot').mkdir()
        self.bin=self.dir/'commands'; self.bin.mkdir()
        self.state=self.dir/'net.json'; self.boot_id=self.dir/'boot-id'; self.boot_id.write_text('current-boot\n')
        for tool in ('iptables','ip6tables','iptables-restore','ip6tables-restore','iptables-save','ip6tables-save','ip'):
            c=self.bin/tool; c.write_text('#!/bin/sh\nexec '+shlex.quote(sys.executable)+' '+shlex.quote(str(MODEL))+' '+tool+' "$@"\n'); c.chmod(0o700)
        self.env=dict(os.environ,PATH=str(self.bin)+':'+os.environ['PATH'],HETU_NETMODEL=str(self.state),
                      HETU_NETMODEL_RESTORE_DELAY=str(self.dir/'restore-delay'),HETU_NETMODEL_RESTORE_FAIL=str(self.dir/'restore-fail'))
        source=(fixture.ASSETS/'hetu-root.sh').read_text().replace('/data/adb/hetu',str(self.base)).replace('/proc/sys/kernel/random/boot_id',str(self.boot_id))
        at=source.rindex('case "${1:-status}" in')
        # Fixture authorization and an IPv6-capable kernel; every network effect is the model.
        stubs='root(){ :; }\nv6supported(){ return 0; }\nhealth_collect(){ H_STATE=unknown; H_REASON=fixture; }\n'
        self.script=self.dir/'hetu-root.sh'; self.script.write_text(source[:at]+stubs+source[at:])
        self.functions=self.dir/'functions.sh'; self.functions.write_text(source[:at]+stubs)
    def tearDown(self): self.temp.cleanup()

    def model(self,*args):
        return subprocess.run([sys.executable,str(MODEL),*args],env=self.env,capture_output=True,text=True,timeout=10)
    def dump(self):
        out={}
        for fam,tool in (('4','iptables'),('6','ip6tables')):
            for table in ('mangle','nat','filter'):
                out[fam+'-'+table]=self.model(tool,'-t',table,'-S').stdout.split('\n')
            out[fam+'-rules']=self.model('ip','-'+fam,'rule','show').stdout.split('\n')
            out[fam+'-routes']=self.model('ip','-'+fam,'route','show','table','all').stdout.split('\n')
        return out
    def owned(self):
        found=[]
        for key,lines in self.dump().items():
            for line in lines:
                if 'HETU_' in line or 'BICHEN_' in line or any(' lookup %d'%t in line or ' table %d'%t in line for t in range(20260,20300)):
                    found.append((key,line))
        return found
    def body(self,text,timeout=30):
        script=self.dir/'body.sh'; script.write_text('. '+shlex.quote(str(self.functions))+'\n'+text+'\n')
        return subprocess.run(['sh',str(script)],env=self.env,capture_output=True,text=True,timeout=timeout)
    def dispatch(self,*args,timeout=30):
        return subprocess.run(['sh',str(self.script),*args],env=self.env,capture_output=True,text=True,timeout=timeout)
    def install(self,batched=True,cleanup_first=True):
        return self.body(START_GLOBALS+('cleanup\n' if cleanup_first else '')+
            'allocnet || exit 3\nxt_batch_begin || exit 4\n'
            'XT_BATCH=%s; install_capture_rules; XT_BATCH=0\n'
            'xt_batch_commit || { capture_unwind || exit 6; install_capture_rules; }\n'%('1' if batched else '0'))
    def hooks(self,key,chain):
        return [l for l in self.dump()[key] if l.startswith('-A ') and l.split()[1] in ('OUTPUT','PREROUTING','FORWARD') and l.endswith(' -j '+chain)]

    def test_batched_install_equals_one_by_one_install(self):
        r=self.install(batched=False); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        single=self.dump(); self.state.unlink(); Path(str(self.state)+'.calls').unlink()
        r=self.install(batched=True); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertEqual(single,self.dump())
        calls=[json.loads(l)[0] for l in Path(str(self.state)+'.calls').read_text().splitlines()]
        rules=sum(1 for k,v in single.items() for l in v if l.startswith('-A ') or l.startswith('-N '))
        restores=sum(1 for c in calls if c.endswith('-restore'))
        xtables=sum(1 for c in calls if c in ('iptables','ip6tables'))
        self.assertGreater(rules,60); self.assertGreaterEqual(restores,1)
        self.assertLess(xtables+restores,rules//3,'batched start must not run one iptables process per rule')
        print(json.dumps({'test':'batched_install','installedRules':rules,'iptablesProcesses':xtables,'restoreCommits':restores}))

    def test_refused_batch_falls_back_to_identical_rules(self):
        r=self.install(batched=False); self.assertEqual(0,r.returncode,r.stderr); single=self.dump(); self.state.unlink()
        (self.dir/'restore-fail').write_text('1')
        r=self.install(batched=True,cleanup_first=False); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertEqual(single,self.dump())

    def test_start_twice_never_duplicates_hooks(self):
        for _ in range(2):
            r=self.install(); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        for key,chain in (('4-mangle','HETU_MOUT'),('4-mangle','HETU_MPRE'),('4-nat','HETU_DNSOUT'),('4-nat','HETU_DNSPRE'),('4-filter','HETU_WROUT'),
                          ('4-filter','HETU_QUICOUT'),('6-mangle','HETU_MOUT'),('6-nat','HETU_DNSOUT'),('6-filter','HETU_WROUT')):
            self.assertEqual(1,len(self.hooks(key,chain)),(key,chain,self.dump()[key]))
        dump=self.dump()
        self.assertEqual(1,sum(' lookup 2026' in l for l in dump['4-rules']))
        self.assertEqual(1,sum(' lookup 2026' in l for l in dump['6-rules']))

    def test_duplicated_hooks_and_rules_are_all_removed_without_net_state(self):
        r=self.install(); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        # Field state: a journal-less earlier session duplicated hooks and left its own rule/table.
        r=self.body('iptables -w 2 -t mangle -A OUTPUT -j HETU_MOUT; iptables -w 2 -t mangle -A OUTPUT -j HETU_MOUT\n'
                    'ip6tables -w 2 -t nat -I OUTPUT 1 -j HETU_DNSOUT\n'
                    'ip rule add pref 14501 fwmark 0x400000/0x400000 table 20261; ip route replace local 0.0.0.0/0 dev lo table 20261\n'
                    'ip -6 rule add pref 14501 fwmark 0x400000/0x400000 table 20261; ip -6 route replace local ::/0 dev lo table 20261')
        self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual(3,len(self.hooks('4-mangle','HETU_MOUT')))
        (self.base/'run/net.state').unlink()
        r=self.body('cleanup_confirmed'); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertEqual([],self.owned())
        r=self.body('cleanup_confirmed'); self.assertEqual(0,r.returncode,'cleanup must be idempotent: '+r.stderr)

    def test_stop_without_net_state_restores_rules_routes_and_ipv6(self):
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr)
        (self.base/'run/net.state').unlink()
        v6=self.dir/'v6conf'; (v6/'all').mkdir(parents=True); (v6/'default').mkdir(); (v6/'wlan0').mkdir()
        for name in ('all','default','wlan0'): (v6/name/'disable_ipv6').write_text('1\n')
        (self.base/'run/ipv6.state').write_text('\n'.join('%s/%s/disable_ipv6\t0'%(v6,n) for n in ('all','default','wlan0'))+'\n')
        self.script.write_text(self.script.read_text().replace('V6_CONF=/proc/sys/net/ipv6/conf','V6_CONF='+str(v6),1))
        r=self.dispatch('stop'); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
        self.assertTrue(json.loads(r.stdout)['ok'])
        self.assertEqual([],self.owned())
        self.assertEqual(['0','0','0'],[(v6/n/'disable_ipv6').read_text().strip() for n in ('all','default','wlan0')])
        self.assertFalse((self.base/'run/ipv6.state').exists())
        self.assertFalse((self.base/'run/.txn.lock').exists())

    def test_stop_killed_by_term_then_stop_again_fully_restores(self):
        import time
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr)
        before=len(self.owned()); self.assertGreater(before,10)
        # Every restore now blocks 2.5 s: like the App's `timeout -s TERM -k 1 N`, TERM the
        # dispatcher after 1 s and SIGKILL it after 2 s, in the middle of the cleanup.
        (self.dir/'restore-delay').write_text('2.5')
        started=time.monotonic()
        # --foreground: like Android's toybox/BusyBox applet, signal only the dispatcher PID
        # (GNU timeout would otherwise signal its whole process group).
        killed=subprocess.run(['timeout','--foreground','-s','TERM','-k','1','1','sh',str(self.script),'stop'],env=self.env,capture_output=True,text=True,timeout=30)
        self.assertNotEqual(0,killed.returncode,'fixture must really interrupt the first stop')
        self.assertLess(time.monotonic()-started,4)
        (self.dir/'restore-delay').unlink()
        deadline=time.monotonic()+40
        while (self.base/'run/.txn.lock').exists() and time.monotonic()<deadline: time.sleep(0.1)
        self.assertFalse((self.base/'run/.txn.lock').exists(),'shielded cleanup did not finish')
        self.assertEqual([],self.owned(),'killed stop left capture behind')
        again=self.dispatch('stop'); self.assertEqual(0,again.returncode,again.stdout+again.stderr)
        self.assertEqual([],self.owned())
        print(json.dumps({'test':'stop_term_kill','ownedRulesBefore':before,'firstStopExit':killed.returncode,'ownedRulesAfter':0}))

    def test_status_self_heals_dead_core_with_leftover_capture(self):
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr)
        (self.base/'run/session.state').write_text('MODE=tproxy\nKILL=0\nIPV6=enable\nDNS=redirect\n')
        (self.base/'run/mode').write_text('tproxy\n'); (self.base/'run/core.pid').write_text('999999\n')
        r=self.dispatch('status'); self.assertEqual(0,r.returncode,r.stderr)
        status=json.loads(r.stdout)
        self.assertFalse(status['running']); self.assertTrue(status['recoveredStaleRules'])
        self.assertEqual([],self.owned())
        self.assertFalse((self.base/'run/session.state').exists())
        self.assertIn('self-heal',(self.base/'run/last-crash').read_text())
        self.assertFalse((self.base/'run/.txn.lock').exists())
        r=self.dispatch('status'); self.assertFalse(json.loads(r.stdout)['recoveredStaleRules'])

    def test_status_heals_filter_only_leftovers(self):
        r=self.body(START_GLOBALS+'install_udp_leak_guard4 core "" 0 "" "" "" "" ""'); self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual(1,len(self.hooks('4-filter','HETU_WROUT')))
        r=self.dispatch('status'); self.assertTrue(json.loads(r.stdout)['recoveredStaleRules'])
        self.assertEqual([],self.owned())

    def test_status_never_heals_kill_switch_lock_or_live_core(self):
        import shutil
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr)
        before=self.dump()
        (self.base/'run/session.state').write_text('MODE=tproxy\nKILL=1\n')
        r=self.dispatch('status'); self.assertFalse(json.loads(r.stdout)['recoveredStaleRules']); self.assertEqual(before,self.dump())
        (self.base/'run/session.state').write_text('MODE=tproxy\nKILL=0\n')
        holder=subprocess.Popen(['sleep','30'])
        try:
            lock=self.base/'run/.txn.lock'; lock.mkdir(); (lock/'pid').write_text(str(holder.pid)+'\n')
            r=self.dispatch('status'); self.assertFalse(json.loads(r.stdout)['recoveredStaleRules']); self.assertEqual(before,self.dump())
        finally: holder.terminate(); holder.wait()
        shutil.rmtree(self.base/'run/.txn.lock')
        core=subprocess.Popen(['sleep','30'])
        try:
            (self.base/'run/core.pid').write_text(str(core.pid)+'\n')
            flags=' '.join(f+'=false' for f in ('M4P','N4O','N4P','D4O','D4P','M6O','M6P','N6O','N6P','D6O','D6P','STRICT6'))
            r=self.body('core_maybe_alive(){ [ "$1" = %d ]; }\nKILLV=0; K4=false; K6=false; M4O=true; %s; STATUS_RUNNING=false; status_self_heal'%(core.pid,flags))
            self.assertEqual(1,r.returncode,'a live core must never be cleaned: '+r.stderr); self.assertEqual(before,self.dump())
        finally: core.terminate(); core.wait()

    def test_stop_then_start_cycles_restore_identical_capture(self):
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr); first=self.dump()
        for cycle in range(3):
            r=self.dispatch('stop'); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
            self.assertEqual([],self.owned(),'stop %d left capture'%cycle)
            r=self.install(); self.assertEqual(0,r.returncode,r.stdout+r.stderr)
            self.assertEqual(first,self.dump(),'start after stop %d differs'%cycle)

    def test_kill_switch_guard_survives_preserving_sweep(self):
        r=self.install(); self.assertEqual(0,r.returncode,r.stderr)
        r=self.body('iptables -w 2 -t filter -N HETU_KOUT; iptables -w 2 -t filter -A HETU_KOUT -j REJECT; iptables -w 2 -t filter -I OUTPUT 1 -j HETU_KOUT\n'
                    'PRESERVE_KILL=1; cleanup')
        self.assertEqual(0,r.returncode,r.stderr)
        self.assertEqual(sorted(['-N HETU_KOUT','-A HETU_KOUT -j REJECT','-A OUTPUT -j HETU_KOUT']),sorted(l for _,l in self.owned()))

if __name__=='__main__': unittest.main(verbosity=2)
