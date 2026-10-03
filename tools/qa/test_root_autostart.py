#!/usr/bin/env python3
"""Execute the shipped boot worker with isolated route/boot/core fixtures.
No Android device, Root framework, real proxy, global paths or firewall changes.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO=Path(__file__).resolve().parents[2]
ASSETS=REPO/'android-app/app/src/main/assets'

class BootWorker(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='hetu-boot-qa-')
        self.dir=Path(self.temp.name)
        self.base=self.dir/'hetu';self.boot=self.base/'boot';self.boot.mkdir(parents=True)
        (self.base/'run').mkdir();(self.dir/'service.d').mkdir()
        self.entry=self.dir/'service.d/hetu-autostart.sh'
        self.boot_id=self.dir/'boot-id';self.boot_id.write_text('current-boot\n')
        self.bin=self.dir/'commands';self.bin.mkdir()
        (self.boot/'enabled').write_text('1')
        self.core=subprocess.Popen(['sleep','300'])
        self.env=dict(os.environ,PATH=str(self.bin)+':'+os.environ['PATH'],HETU_TEST=str(self.dir),HETU_CORE_PID=str(self.core.pid))
        self.write_command('getprop', '''t=$(cat "$HETU_TEST/ticks" 2>/dev/null || echo 0); [ "$t" -lt "${SYSTEM_AFTER:-0}" ] && echo 0 || echo 1''')
        self.write_command('ip', '''t=$(cat "$HETU_TEST/ticks" 2>/dev/null || echo 0); [ "$t" -lt "${NETWORK_AFTER:-0}" ] || echo 'default dev fixture0' ''')
        self.write_command('readlink', '''[ -f "$HETU_TEST/hetu/run/core.pid" ] && printf '%s/bin/core\n' "$HETU_TEST/hetu"''')
        self.write_command('am', '''printf '%s\n' "$*" >> "$HETU_TEST/notified"; exit "${AM_DENIED:-0}"''')
        self.write_command('sleep', '''t=$(cat "$HETU_TEST/ticks" 2>/dev/null || echo 0); t=$((t+1)); printf '%s\n' "$t" > "$HETU_TEST/ticks"
if [ "$t" = "${STOP_AT:--1}" ]; then cat "$HETU_TEST/boot-id" > "$HETU_TEST/hetu/boot/stopped-boot"; fi
if [ "$t" = "${DISABLE_AT:--1}" ]; then rm -f "$HETU_TEST/hetu/boot/enabled"; fi''')
        (self.boot/'start.sh').write_text('''n=$(cat "$HETU_TEST/starts" 2>/dev/null || echo 0); n=$((n+1)); printf '%s\n' "$n" > "$HETU_TEST/starts"
[ "$n" -gt "${FAIL_STARTS:-0}" ] || exit 1
printf '%s\n' "$HETU_CORE_PID" > "$HETU_TEST/hetu/run/core.pid"
''')
        self.entry.write_text(self.isolate((ASSETS/'hetu-autostart.sh').read_text()).replace('PATH=/system/bin:', 'PATH='+str(self.bin)+':/system/bin:'))
    def tearDown(self):
        self.core.terminate();self.core.wait();self.temp.cleanup()
    def isolate(self,text):
        return text.replace('/data/adb/hetu',str(self.base)).replace('/data/adb/service.d',str(self.dir/'service.d')).replace('/system/bin/sh','/bin/sh').replace('/proc/sys/kernel/random/boot_id',str(self.boot_id)).replace('/proc/$OWNER_PID/cmdline',str(self.dir/'mock-proc')+'/$OWNER_PID/cmdline')
    def write_command(self,name,text):
        p=self.bin/name;p.write_text('#!/bin/sh\n'+text+'\n');p.chmod(0o700)
    def run_worker(self,**env):
        result=subprocess.run(['sh',str(self.entry),'--worker'],env=dict(self.env,**{k:str(v) for k,v in env.items()}),capture_output=True,text=True,timeout=10)
        self.assertEqual('',result.stderr,result.stderr)
        return result
    def starts(self):
        path=self.dir/'starts';return int(path.read_text()) if path.exists() else 0
    def state(self):
        p=self.boot/'status';return p.read_text().strip().split(' ',1)[-1] if p.exists() else None
    def test_disabled_never_starts(self):
        (self.boot/'enabled').unlink();self.assertEqual(0,self.run_worker().returncode);self.assertEqual(0,self.starts())
    def test_delayed_system_and_network_still_restore_once(self):
        self.assertEqual(0,self.run_worker(SYSTEM_AFTER=15,NETWORK_AFTER=100).returncode)
        self.assertEqual(1,self.starts());self.assertEqual('running',self.state())
    def test_failed_start_retries_then_succeeds(self):
        self.assertEqual(0,self.run_worker(FAIL_STARTS=2).returncode);self.assertEqual(3,self.starts());self.assertEqual('running',self.state())
    def test_stop_during_network_wait_cancels_this_boot(self):
        self.assertEqual(0,self.run_worker(NETWORK_AFTER=100,STOP_AT=2).returncode);self.assertEqual(0,self.starts());self.assertEqual('cancelled',self.state())
    def test_disable_during_wait_cancels(self):
        self.assertEqual(0,self.run_worker(SYSTEM_AFTER=15,DISABLE_AT=2).returncode);self.assertEqual(0,self.starts());self.assertEqual('cancelled',self.state())
    def test_previous_boot_stop_does_not_block_new_boot(self):
        (self.boot/'stopped-boot').write_text('previous-boot\n');self.assertEqual(0,self.run_worker().returncode);self.assertEqual(1,self.starts())
    def test_stale_previous_boot_lock_does_not_block_restore(self):
        lock=self.boot/'restore.lock';lock.mkdir();(lock/'boot-id').write_text('previous-boot\n');(lock/'pid').write_text(str(self.core.pid))
        self.assertEqual(0,self.run_worker().returncode);self.assertEqual(1,self.starts());self.assertFalse(lock.exists())
    def test_app_launch_denial_does_not_undo_native_start(self):
        self.assertEqual(0,self.run_worker(AM_DENIED=1).returncode);self.assertEqual('running',self.state());self.assertEqual(1,self.starts())
    def test_existing_core_is_adopted_without_restart(self):
        (self.base/'run/core.pid').write_text(str(self.core.pid));self.assertEqual(0,self.run_worker().returncode);self.assertEqual(0,self.starts())
    def test_missing_plan_reports_failure(self):
        (self.boot/'start.sh').unlink();self.assertEqual(1,self.run_worker().returncode);self.assertEqual('missing-plan',self.state())
    def test_network_wait_has_a_bound(self):
        self.assertEqual(1,self.run_worker(NETWORK_AFTER=1000).returncode);self.assertEqual('restore-timeout',self.state());self.assertEqual(0,self.starts())
    def test_active_duplicate_worker_cannot_remove_its_lock(self):
        lock=self.boot/'restore.lock';lock.mkdir();(lock/'boot-id').write_text('current-boot\n');(lock/'pid').write_text('999123')
        proc=self.dir/'mock-proc/999123';proc.mkdir(parents=True)
        (proc/'cmdline').write_bytes(('sh\0'+str(self.entry)+'\0--worker\0').encode())
        self.assertEqual(0,self.run_worker().returncode);self.assertEqual(0,self.starts());self.assertTrue(lock.exists())

class NativeStartGuard(BootWorker):
    # Reuse fixtures, not the inherited worker test inventory.
    def root_start(self,boot='current-boot',alive=False):
        source=self.isolate((ASSETS/'hetu-root.sh').read_text())
        at=source.rindex('case "${1:-status}" in')
        stubs='''root(){ :; }; acquire_lock(){ :; }; release_lock(){ :; }
pidcore(){ [ "${FAKE_ALIVE:-0}" = 1 ]; }
preflight(){ printf 'UNEXPECTED-PREFLIGHT\n' > "$HETU_TEST/preflight-hit"; exit 96; }
'''
        p=self.dir/'root.sh';p.write_text(source[:at]+stubs+source[at:])
        args=['core','config','tproxy','7893','7892','enable','1','1','redirect','0','1053','29090','core','','0','0','','','','0','0','','','1','1','0','','','','0']
        (self.base/'run/core.pid').write_text(str(self.core.pid))
        return subprocess.run(['sh',str(p),'start',*args],env=dict(self.env,HETU_BOOT_RESTORE_ID=boot,FAKE_ALIVE='1' if alive else '0'),capture_output=True,text=True,timeout=5)
    def test_native_guard_rejects_wrong_boot(self):
        r=self.root_start('previous-boot');self.assertEqual(1,r.returncode);self.assertNotIn('UNEXPECTED-PREFLIGHT',r.stdout)
    def test_native_guard_rechecks_explicit_stop_after_lock(self):
        (self.boot/'stopped-boot').write_text('current-boot\n');r=self.root_start();self.assertEqual(1,r.returncode);self.assertNotIn('UNEXPECTED-PREFLIGHT',r.stdout)
    def test_native_guard_rechecks_disabled_setting(self):
        (self.boot/'enabled').unlink();r=self.root_start();self.assertEqual(1,r.returncode);self.assertNotIn('UNEXPECTED-PREFLIGHT',r.stdout)
    def test_native_guard_does_not_restart_a_winning_manual_start(self):
        r=self.root_start(alive=True);self.assertEqual(0,r.returncode);self.assertTrue(json.loads(r.stdout)['ok']);self.assertNotIn('UNEXPECTED-PREFLIGHT',r.stdout)
    def test_normal_manual_start_is_not_subject_to_boot_guard(self):
        (self.boot/'enabled').unlink();r=self.root_start('');self.assertEqual(96,r.returncode);self.assertTrue((self.dir/'preflight-hit').exists())

# Avoid running inherited BootWorker tests a second time.
for name in list(BootWorker.__dict__):
    if name.startswith('test_'):
        setattr(NativeStartGuard,name,None)

if __name__=='__main__': unittest.main(verbosity=2)
