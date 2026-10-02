#!/usr/bin/env python3
"""Own one official AOSP emulator; use existing KVM access, never change it.
No guest Root/Magisk, network rules, device chmod/chown, group or ACL changes.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


def kvm_state():
 s=os.stat('/dev/kvm')
 return {'uid':s.st_uid,'gid':s.st_gid,'mode':s.st_mode & 0o7777,'rdev':s.st_rdev}


def supervise(binary, report):
 expected=(Path(os.environ['ANDROID_HOME'])/'emulator/emulator').resolve()
 if Path(binary).resolve()!=expected:raise RuntimeError('Only the official SDK emulator is allowed')
 child=None
 result={'emulator_started':False,'emulator_reaped':False}
 def stop(signum, frame):raise SystemExit(128+signum)
 signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
 try:
  child=subprocess.Popen([str(expected),'-avd','hetu-smoke','-port','5554','-no-window',
   '-gpu','swiftshader_indirect','-no-snapshot','-noaudio','-no-boot-anim',
   '-camera-back','none','-accel','on','-verbose'])
  result.update(emulator_started=True,pid=child.pid)
  return child.wait()
 finally:
  signal.signal(signal.SIGTERM,signal.SIG_IGN);signal.signal(signal.SIGINT,signal.SIG_IGN)
  if child is not None:
   if child.poll() is None:child.terminate()
   try:child.wait(timeout=20)
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
   result.update(emulator_reaped=True,returncode=child.returncode)
  Path(report).write_text(json.dumps(result,indent=2)+'\n')


def main():
 parser=argparse.ArgumentParser();parser.add_argument('--owned-supervisor',action='store_true')
 parser.add_argument('--binary');parser.add_argument('--cleanup-report');args=parser.parse_args()
 if args.owned_supervisor:return supervise(args.binary,args.cleanup_report)
 output=Path('out/android-smoke').resolve();output.mkdir(parents=True,exist_ok=True)
 sdk=Path(os.environ['ANDROID_HOME']).resolve();binary=sdk/'emulator/emulator';adb=str(sdk/'platform-tools/adb')
 if not binary.is_file():raise RuntimeError('Official SDK emulator missing')
 report={'result':'FAIL','kvm_before':kvm_state(),'runner_uid':os.getuid(),'runner_groups':os.getgroups()}
 privileged=not (os.access('/dev/kvm',os.R_OK) and os.access('/dev/kvm',os.W_OK))
 # Read-only checks of this runner's already-provisioned sudo access. Failure
 # stops here; there is deliberately no permission-modification fallback.
 if privileged:
  subprocess.run(['sudo','-n','test','-r','/dev/kvm'],check=True)
  subprocess.run(['sudo','-n','test','-w','/dev/kvm'],check=True)
 report['privileged_emulator_process']=privileged
 home=Path(os.environ['RUNNER_TEMP'])/'hetu-emulator-home';home.mkdir(exist_ok=True)
 env=dict(os.environ,HOME=str(home),ANDROID_SDK_ROOT=str(sdk))
 prefix=['sudo','-n','env','HOME='+str(home),'ANDROID_AVD_HOME='+os.environ['ANDROID_AVD_HOME'],
  'ANDROID_HOME='+str(sdk),'ANDROID_SDK_ROOT='+str(sdk)] if privileged else []
 command=prefix+[sys.executable,str(Path(__file__).resolve()),'--owned-supervisor',
  '--binary',str(binary),'--cleanup-report',str(output/'emulator-cleanup.json')]
 launcher=None
 def stop(signum,frame):raise SystemExit(128+signum)
 signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
 try:
  subprocess.run([adb,'start-server'],check=True,timeout=30)
  (output/'acceleration.txt').write_text('on; existing sudo process access\n' if privileged else 'on; existing runner access\n')
  with (output/'emulator.log').open('w') as log:
   launcher=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=env)
   checked=subprocess.run([sys.executable,str(Path(__file__).with_name('smoke_hetu_apk.py'))],timeout=1800)
   report['smoke_exit']=checked.returncode
   if checked.returncode:raise RuntimeError('Installed APK smoke failed; inspect original evidence')
   report['result']='PASS'
 except Exception as error:report['error']=str(error)
 finally:
  signal.signal(signal.SIGTERM,signal.SIG_IGN);signal.signal(signal.SIGINT,signal.SIG_IGN)
  if launcher is not None:
   try:subprocess.run([adb,'-s','emulator-5554','emu','kill'],capture_output=True,timeout=10)
   except subprocess.TimeoutExpired:pass
   if launcher.poll() is None:launcher.terminate()
   try:launcher.wait(timeout=45)
   except subprocess.TimeoutExpired:report.update(result='FAIL',error='Owned emulator supervisor did not finish cleanup')
   report['launcher_reaped']=launcher.poll() is not None
  report['kvm_after']=kvm_state()
  if report['kvm_before']!=report['kvm_after']:report.update(result='FAIL',error='KVM security metadata changed')
  cleanup=output/'emulator-cleanup.json'
  if not cleanup.exists() or not json.loads(cleanup.read_text()).get('emulator_reaped'):
   report.update(result='FAIL',error='No confirmed emulator cleanup evidence')
  (output/'runner-lifecycle.json').write_text(json.dumps(report,indent=2)+'\n')
 return 0 if report['result']=='PASS' else 1

if __name__=='__main__':raise SystemExit(main())
