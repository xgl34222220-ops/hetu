#!/usr/bin/env python3
"""7.0.0 transaction-lock regression for hetu-root.sh (fake Android/xtables tools; no Root, no device).

Field report (6.6.6, OnePlus PLK110 / ColorOS): tapping 立即重启 answered "Root 命令失败（退出码 1）：另一个代理网络
事务正在执行，请稍后重试", and the home card read 接管检查异常 / 数据面健康检查未完整通过 meanwhile. A start waits
5 s for the transaction lock (acquire_lock: 100 x 0.05 s), and health reports unknown:transaction-in-progress
while anyone holds it. The watchdog's minute pass of the Google vendor-chain cleanup held the lock for its whole
run: pm / cmd package, eight iptables -S and one awk per vendor rule (239 in the 6.0.0 report), plus, since 6.6.6
repaired its awk, one iptables -D per Google rule.

* google_firewall_maintain releases the lock after its bookkeeping; the scan runs without it.
* Deletions take the lock again and stop as soon as another process waits in acquire_lock (txn_waiter, the
  .txn.want.<pid> marker acquire_lock leaves while waiting); the rest waits for the next pass (deferred=N).
* Nothing is deleted when the core or the switch changed between the scan and the deletions.

HETU_TEST_OLD_ROOT=<6.6.6 hetu-root.sh> also runs the start-behind-the-pass case on that script, where it fails.
"""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'android-app/app/src/main/assets/hetu-root.sh'

# Like test_google_firewall's tools, plus a per-tool delay and whether the transaction lock was held at each call.
MOCK = r'''#!/usr/bin/env python3
import json, os, pathlib, shlex, sys, time
p=pathlib.Path(os.environ['FIXTURE']); args=sys.argv[1:]; tool=pathlib.Path(sys.argv[0]).name
held=os.path.isdir(os.environ['LOCK'])
with (p/'calls').open('a') as log: log.write(json.dumps([tool,held,*args])+'\n')
if tool in ('pm','cmd'):
    time.sleep(float(os.environ.get('PM_DELAY','0')))
    if args==['list','users']:
        print('Users:'); print('UserInfo{0:Owner:13} running')
    else:
        print('package:com.google.android.gms uid:10123\npackage:com.android.vending uid:10124\npackage:other.app uid:10200')
elif tool in ('iptables','ip6tables'):
    action,chain=args[4:6]
    family='4' if tool=='iptables' else '6'; file=p/(family+'-'+chain)
    if not file.exists(): sys.exit(1)
    if action=='-S':
        time.sleep(float(os.environ.get('SCAN_DELAY','0')))
        hook=os.environ.get('ON_SCAN')
        if hook: exec(hook)
        print(file.read_text(),end='')
    else:
        time.sleep(float(os.environ.get('DELETE_DELAY','0')))
        lines=file.read_text().splitlines(); expected=['-A',chain,*args[6:]]
        for i,line in enumerate(lines):
            if shlex.split(line)==expected:
                del lines[i]; file.write_text('\n'.join(lines)+'\n'); break
        else: sys.exit(1)
else: raise AssertionError(tool)
'''


def functions(text):
    return text.rsplit('\ncase "${1:-status}" in', 1)[0]


class LockYield(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.base = Path(self.temp.name)
        self.fixture = self.base / 'fixture'; self.fixture.mkdir()
        self.run = self.base / 'run'; self.run.mkdir()
        self.bin = self.base / 'bin'; self.bin.mkdir()
        for tool in ('pm', 'cmd', 'iptables', 'ip6tables'):
            file = self.bin / tool; file.write_text(MOCK); file.chmod(0o755)
        self.lock = self.run / 'lock'
        self.env = dict(os.environ, FIXTURE=str(self.fixture), TEST_RUN=str(self.run), LOCK=str(self.lock),
                        PATH=str(self.bin) + ':' + os.environ['PATH'])
        (self.run / 'session').write_text('GOOGLE_FIREWALL_CLEAN=1\n')
        self.holders = []

    def tearDown(self):
        for p in self.holders:
            p.kill(); p.wait()
        self.temp.cleanup()

    def harness(self, body, source=None, name='harness.sh'):
        fn = self.base / ('functions-' + name); fn.write_text(functions(source or SCRIPT.read_text()) + '\n')
        path = self.base / name
        path.write_text('. ' + shlex.quote(str(fn)) + '\n'
                        'RUN="$TEST_RUN"; SESSION="$RUN/session"; PIDFILE="$RUN/pid"; LOCK_DIR="$RUN/lock"; '
                        'START_ERROR="$RUN/error"\n' + body + '\n')
        return path

    def maintain_body(self, now=100):
        # The core is the harness itself; pidcore accepts the recorded PID.
        return ('printf "%s\\n" "$$" > "$PIDFILE"\npidcore(){ [ "$1" = "$(cat "$PIDFILE" 2>/dev/null)" ]; }\n'
                f'monotonic_seconds(){{ MONO_SECONDS={now}; }}\ngoogle_firewall_maintain "$$"\n')

    def sh(self, body, extra=None, source=None, timeout=60):
        result = subprocess.run(['sh', str(self.harness(body, source))], env=dict(self.env, **(extra or {})),
                                text=True, capture_output=True, timeout=timeout)
        self.assertEqual(result.stderr, '', result.stderr)
        return result

    def rules(self, google=40, others=199):
        lines = [f'-A fw_OUTPUT -o rmnet{i} -m owner --uid-owner {10123 + (i % 2)} -j REJECT' for i in range(google)]
        lines += [f'-A fw_OUTPUT -o rmnet{i} -m owner --uid-owner {20000 + i} -j REJECT' for i in range(others)]
        (self.fixture / '4-fw_OUTPUT').write_text('\n'.join(lines) + '\n')

    def calls(self):
        file = self.fixture / 'calls'
        return [json.loads(x) for x in file.read_text().splitlines()] if file.exists() else []

    def deletes(self): return [c for c in self.calls() if '-D' in c]

    def status(self): return (self.run / 'google-firewall-status').read_text()

    def holder(self):
        p = subprocess.Popen(['sleep', '60']); self.holders.append(p); return p

    def test_scan_runs_without_the_lock_and_only_deletions_hold_it(self):
        self.rules(google=3, others=20)
        self.assertEqual(self.sh(self.maintain_body()).returncode, 0)
        calls = self.calls()
        scan = [c for c in calls if c[0] in ('pm', 'cmd') or '-S' in c]
        self.assertTrue(scan and not any(c[1] for c in scan), scan)
        self.assertEqual(len(self.deletes()), 3)
        self.assertTrue(all(c[1] for c in self.deletes()), 'deletions run under the transaction lock')
        self.assertEqual(self.status(), 'checked=1 removed=3 failed=0\n')
        self.assertFalse(self.lock.exists())
        self.assertEqual(sorted(p.name for p in self.run.iterdir() if p.name.startswith('.')), [])

    def test_a_waiting_start_defers_the_deletions(self):
        self.rules(google=4, others=10)
        waiter = self.holder()
        (self.run / f'.txn.want.{waiter.pid}').write_text('')
        self.assertEqual(self.sh(self.maintain_body()).returncode, 0)
        self.assertEqual(self.deletes(), [])
        self.assertEqual(self.status(), 'checked=1 removed=0 failed=0 deferred=4\n')
        self.assertFalse(self.lock.exists())
        self.assertTrue((self.run / f'.txn.want.{waiter.pid}').exists(), "a live waiter's marker stays")

    def test_marker_of_a_gone_waiter_is_dropped(self):
        self.rules(google=2, others=0)
        gone = subprocess.Popen(['true']); gone.wait()
        (self.run / f'.txn.want.{gone.pid}').write_text('')
        self.assertEqual(self.sh(self.maintain_body()).returncode, 0)
        self.assertEqual(len(self.deletes()), 2)
        self.assertFalse((self.run / f'.txn.want.{gone.pid}').exists())

    def test_core_replaced_between_scan_and_deletions_deletes_nothing(self):
        self.rules(google=2, others=0)
        hook = "pathlib.Path(os.environ['TEST_RUN'],'pid').write_text('1\\n')"
        self.assertEqual(self.sh(self.maintain_body(), {'ON_SCAN': hook}).returncode, 0)
        self.assertEqual(self.deletes(), [])
        self.assertEqual(self.status(), 'checked=1 removed=0 failed=0 deferred=2\n')
        self.assertFalse(self.lock.exists())

    def test_switch_turned_off_between_scan_and_deletions_deletes_nothing(self):
        self.rules(google=2, others=0)
        hook = "pathlib.Path(os.environ['TEST_RUN'],'session').write_text('GOOGLE_FIREWALL_CLEAN=0\\n')"
        self.assertEqual(self.sh(self.maintain_body(), {'ON_SCAN': hook}).returncode, 0)
        self.assertEqual(self.deletes(), [])

    def test_acquire_lock_announces_and_clears_its_wait(self):
        holder = self.holder()
        self.lock.mkdir(); (self.lock / 'pid').write_text(f'{holder.pid}\n')
        p = subprocess.Popen(['sh', str(self.harness('acquire_lock && echo got; release_lock', name='waiter.sh'))],
                             env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = time.monotonic() + 3
        while not list(self.run.glob('.txn.want.*')) and time.monotonic() < deadline: time.sleep(0.02)
        self.assertTrue(list(self.run.glob('.txn.want.*')), 'no wait marker while the lock is held')
        holder.kill(); holder.wait()
        out, err = p.communicate(timeout=10)
        self.assertEqual((out, err), ('got\n', ''))
        self.assertEqual(list(self.run.glob('.txn.want.*')), [])
        self.assertFalse(self.lock.exists())

    def test_waiter_that_gives_up_clears_its_marker(self):
        holder = self.holder()
        self.lock.mkdir(); (self.lock / 'pid').write_text(f'{holder.pid}\n')
        r = self.sh('acquire_lock; echo "rc=$?"', timeout=20)
        self.assertIn('rc=1', r.stdout)
        self.assertEqual(list(self.run.glob('.txn.want.*')), [])

    def start_behind_the_pass(self, source, start_source=None, attempts=''):
        """A start (its acquire_lock) 0.5 s into a minute pass with slow pm/iptables, as on the phone."""
        self.rules(google=40, others=199)
        slow = {'PM_DELAY': '1.0', 'SCAN_DELAY': '0.2', 'DELETE_DELAY': '0.1'}
        env = dict(self.env, **slow)
        maintain = subprocess.Popen(['sh', str(self.harness(self.maintain_body(), source, 'maintain.sh'))], env=env,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        time.sleep(0.5)
        t0 = time.monotonic()
        start = subprocess.run(['sh', str(self.harness(
            f'acquire_lock {attempts} || {{ echo "另一个代理网络事务正在执行，请稍后重试"; exit 1; }}; echo started; release_lock',
            start_source or source, 'start.sh'))], env=env, text=True, capture_output=True, timeout=30)
        waited = time.monotonic() - t0
        maintain.communicate(timeout=120)
        return start, waited

    def test_start_during_the_minute_pass_gets_the_lock(self):
        start, waited = self.start_behind_the_pass(None)
        self.assertEqual((start.returncode, start.stdout.strip()), (0, 'started'), start.stdout + start.stderr)
        self.assertLess(waited, 2.5)
        self.assertFalse(self.lock.exists())

    @unittest.skipUnless(os.environ.get('HETU_TEST_OLD_ROOT'), 'HETU_TEST_OLD_ROOT not set')
    def test_old_script_start_during_the_minute_pass_fails_as_reported(self):
        start, waited = self.start_behind_the_pass(Path(os.environ['HETU_TEST_OLD_ROOT']).read_text())
        self.assertEqual(start.returncode, 1)
        self.assertIn('另一个代理网络事务正在执行，请稍后重试', start.stdout)
        self.assertGreater(waited, 4.5)

    @unittest.skipUnless(os.environ.get('HETU_TEST_OLD_ROOT'), 'HETU_TEST_OLD_ROOT not set')
    def test_first_restart_after_the_update_outwaits_the_old_watchdog_pass(self):
        # The old watchdog keeps running until the restart stops it, and it does not yield.
        start, waited = self.start_behind_the_pass(Path(os.environ['HETU_TEST_OLD_ROOT']).read_text(), SCRIPT.read_text(), '300')
        self.assertEqual((start.returncode, start.stdout.strip()), (0, 'started'), start.stdout + start.stderr)
        self.assertGreater(waited, 4.5)

    def test_start_and_stop_wait_fifteen_seconds_others_five(self):
        text = SCRIPT.read_text()
        self.assertEqual(text.count('acquire_lock 300 || fail "另一个代理网络事务正在执行，请稍后重试"'), 1)
        stop = text.split('\nstop_transaction(){', 1)[1].split('\n}', 1)[0]
        self.assertIn('acquire_lock 300 || return 1', stop)
        self.assertEqual(text.count('acquire_lock 300'), 2)


if __name__ == '__main__':
    unittest.main()
