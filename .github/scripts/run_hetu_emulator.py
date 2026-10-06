#!/usr/bin/env python3
"""Own one official AOSP emulator; use existing KVM access, never change it.
No guest Root/Magisk, network rules, device chmod/chown, group or ACL changes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import uuid



def prepare_owned_avd_identity(config):
 """Set the name on this job's newly created AVD; never repair a foreign identity."""
 config=Path(config)
 if config.name!='config.ini' or config.parent.name!='hetu-smoke.avd':
  raise RuntimeError('Unexpected disposable AVD config path')
 raw=config.read_text();values={}
 for line in raw.splitlines():
  if '=' not in line or line.lstrip().startswith(('#',';')):continue
  key,value=(part.strip() for part in line.split('=',1))
  if key in values and values[key]!=value:raise RuntimeError('Conflicting AVD configuration')
  values[key]=value
 for key in ('AvdId','avd.id','avd.name'):
  allowed={'hetu-smoke'} if key=='AvdId' else {'hetu-smoke','<build>'}
  if key in values and values[key] not in allowed:
   raise RuntimeError('Foreign AVD identity: '+key)
 if values.get('tag.id')!='default' or values.get('abi.type')!='x86_64':
  raise RuntimeError('Unexpected AOSP AVD configuration')
 # SDK-generated <build> placeholders are not proof of identity. Provision the
 # explicit name given to avdmanager, then retain the existing runtime verifier.
 lines=[line for line in raw.splitlines() if line.split('=',1)[0].strip() not in ('AvdId','avd.id','avd.name')]
 config.write_text('\n'.join(lines)+'\nAvdId=hetu-smoke\navd.id=hetu-smoke\navd.name=hetu-smoke\n')


def process_identity(pid, proc_root=Path('/proc')):
 """Read only a live Linux process identity; start ticks prevent PID reuse."""
 pid=int(pid)
 if pid<=0:raise RuntimeError('Invalid owned process PID')
 folder=proc_root/str(pid)
 raw=(folder/'stat').read_text()
 end=raw.rfind(')')
 fields=raw[end+2:].split()
 if end<0 or int(raw.split(' ',1)[0])!=pid or len(fields)<20 or fields[0] in ('Z','X'):
  raise RuntimeError('Owned emulator process is not live')
 return {'pid':pid,'ppid':int(fields[1]),'start':int(fields[19]),
  'argv':[part.decode() for part in (folder/'cmdline').read_bytes().split(b'\0') if part]}


def owned_process_tree(pid, proc_root=Path('/proc')):
 """Follow this launcher's children, never discover unrelated host processes."""
 nodes=[];pending=[int(pid)];seen=set()
 while pending:
  current=pending.pop()
  if current in seen:raise RuntimeError('Invalid owned emulator process tree')
  seen.add(current)
  if len(seen)>64:raise RuntimeError('Unexpected owned emulator process tree size')
  node=process_identity(current,proc_root);nodes.append(node)
  children=(proc_root/str(current)/'task'/str(current)/'children').read_text().split()
  for child in children:
   child=int(child)
   if process_identity(child,proc_root)['ppid']!=current:
    raise RuntimeError('Owned emulator child identity changed')
   pending.append(child)
 return nodes


def verify_owned_emulator_target(environment, *, proc_root=Path('/proc'), smoke_parent_pid=None):
 """Require this live supervisor's SDK AVD before fixture preferences are written."""
 session=environment.get('HETU_OWNED_EMULATOR_SESSION','')
 if len(session)!=32 or any(c not in '0123456789abcdef' for c in session):
  raise RuntimeError('Missing current owned emulator session')
 runner_pid=int(environment['HETU_OWNED_EMULATOR_RUNNER_PID'])
 parent=os.getppid() if smoke_parent_pid is None else smoke_parent_pid
 if runner_pid!=parent:raise RuntimeError('Foreign owned emulator runner')
 runner=process_identity(runner_pid,proc_root)
 if runner['start']!=int(environment['HETU_OWNED_EMULATOR_RUNNER_START']):
  raise RuntimeError('Owned emulator runner PID was reused')
 launcher_pid=int(environment['HETU_OWNED_EMULATOR_LAUNCHER_PID'])
 launcher=process_identity(launcher_pid,proc_root)
 if launcher['ppid']!=runner_pid or launcher['start']!=int(environment['HETU_OWNED_EMULATOR_LAUNCHER_START']):
  raise RuntimeError('Stale or foreign owned emulator launcher')
 sdk=Path(environment['ANDROID_HOME']).resolve()
 avd_home=Path(environment['ANDROID_AVD_HOME']).resolve()
 if avd_home!=(Path(environment['RUNNER_TEMP'])/'hetu-avd').resolve():
  raise RuntimeError('AVD is outside this disposable runner directory')
 config=avd_home/'hetu-smoke.avd/config.ini'
 raw=config.read_bytes();values={}
 for line in raw.decode().splitlines():
  if '=' not in line or line.lstrip().startswith(('#',';')):continue
  key,value=(part.strip() for part in line.split('=',1))
  if key in values and values[key]!=value:raise RuntimeError('Conflicting AVD configuration')
  values[key]=value
 if values.get('AvdId')!='hetu-smoke' or values.get('tag.id')!='default' or values.get('abi.type')!='x86_64':
  raise RuntimeError('Unexpected AOSP AVD configuration')
 image=Path(values.get('image.sysdir.1',''))
 if not image.is_absolute():image=sdk/image
 image=image.resolve()
 valid_images={api:(sdk/f'system-images/android-{api}/default/x86_64').resolve() for api in (35,36)}
 api=next((api for api,path in valid_images.items() if image==path),None)
 if api is None:raise RuntimeError('Unexpected owned AVD API or system image')
 nodes=owned_process_tree(launcher_pid,proc_root)
 def option(argv,name,value):
  return argv.count(name)==1 and argv.index(name)+1<len(argv) and argv[argv.index(name)+1]==value
 binary=str(sdk/'emulator/emulator')
 script=str(Path(__file__).resolve())
 supervisors=[node for node in nodes if len(node['argv'])>1 and node['argv'][0]==sys.executable
  and node['argv'][1]==script and '--owned-supervisor' in node['argv']
  and option(node['argv'],'--binary',binary) and option(node['argv'],'--session',session)]
 if len(supervisors)!=1:raise RuntimeError('Missing unique current owned emulator supervisor')
 supervisor=supervisors[0]
 children=owned_process_tree(supervisor['pid'],proc_root)[1:]
 engines={str(sdk/'emulator/qemu/linux-x86_64'/name) for name in
  ('qemu-system-x86_64','qemu-system-x86_64-headless')}
 def expected_engine(node):
  argv=node['argv']
  if not argv:return False
  original=(argv[0] in engines|{binary} and option(argv,'-avd','hetu-smoke')
   and option(argv,'-port','5554') and argv.count('-no-snapshot')==1)
  # SDK 37 also reports transformed headless QEMU arguments. Tie those to the
  # exact owned AVD and selected AOSP image, not just the executable's name.
  transformed=(argv[0] in engines and option(argv,'-android-ports','5554,5555')
   and option(argv,'-android-hw',str(config.parent/'hardware-qemu.ini'))
   and argv.count('-kernel')==1 and argv.index('-kernel')+1<len(argv)
   and Path(argv[argv.index('-kernel')+1]).resolve()==image/'kernel-ranchu')
  return original or transformed
 emulators=[node for node in children if expected_engine(node)]
 if not emulators:raise RuntimeError('Missing live owned SDK emulator with the expected AVD and port')
 # Recheck after the configuration/tree reads; retained evidence cannot authorize a dead/reused launch.
 if process_identity(launcher_pid,proc_root)['start']!=launcher['start']:
  raise RuntimeError('Owned emulator launcher identity changed')
 return {'avd':'hetu-smoke','apiLevel':api,'ownedSession':session,
  'runnerPid':runner_pid,'launcherPid':launcher_pid,'launcherStartTicks':launcher['start'],
  'supervisorPid':supervisor['pid'],'emulatorPids':[node['pid'] for node in emulators],
  'avdConfig':str(config),'avdConfigSha256':hashlib.sha256(raw).hexdigest(),
  'proof':'live supervisor process ancestry, SDK argv and disposable AOSP AVD configuration'}


def kvm_state():
 s=os.stat('/dev/kvm')
 return {'uid':s.st_uid,'gid':s.st_gid,'mode':s.st_mode & 0o7777,'rdev':s.st_rdev}


def supervise(binary, report, session=None):
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
 parser.add_argument('--binary');parser.add_argument('--cleanup-report');parser.add_argument('--session');args=parser.parse_args()
 if args.owned_supervisor:return supervise(args.binary,args.cleanup_report,args.session)
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
 session=uuid.uuid4().hex
 command=prefix+[sys.executable,str(Path(__file__).resolve()),'--owned-supervisor',
  '--binary',str(binary),'--cleanup-report',str(output/'emulator-cleanup.json'),'--session',session]
 launcher=None
 def stop(signum,frame):raise SystemExit(128+signum)
 signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
 try:
  subprocess.run([adb,'start-server'],check=True,timeout=30)
  (output/'acceleration.txt').write_text('on; existing sudo process access\n' if privileged else 'on; existing runner access\n')
  with (output/'emulator.log').open('w') as log:
   launcher=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT,env=env)
   runner_identity=process_identity(os.getpid());launcher_identity=process_identity(launcher.pid)
   smoke_env=dict(os.environ,HETU_OWNED_EMULATOR_SESSION=session,
    HETU_OWNED_EMULATOR_RUNNER_PID=str(os.getpid()),HETU_OWNED_EMULATOR_RUNNER_START=str(runner_identity['start']),
    HETU_OWNED_EMULATOR_LAUNCHER_PID=str(launcher.pid),HETU_OWNED_EMULATOR_LAUNCHER_START=str(launcher_identity['start']))
   checked=subprocess.run([sys.executable,str(Path(__file__).with_name('smoke_hetu_apk.py'))],timeout=1800,env=smoke_env)
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
