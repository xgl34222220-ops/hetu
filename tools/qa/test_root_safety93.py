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

if __name__=='__main__': unittest.main(verbosity=2)
