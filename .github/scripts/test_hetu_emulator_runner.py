import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import Mock,patch

spec=importlib.util.spec_from_file_location('runner',Path(__file__).with_name('run_hetu_emulator.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class AvdProvisioningTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.config=Path(self.temp.name)/'hetu-smoke.avd/config.ini'
  self.config.parent.mkdir();self.raw='avd.id=<build>\navd.name=<build>\ntag.id=default\nabi.type=x86_64\nimage.sysdir.1=system-images/android-35/default/x86_64/\n'
  self.config.write_text(self.raw)
 def tearDown(self):self.temp.cleanup()
 def test_observed_sdk_placeholders_are_provisioned_without_changing_image(self):
  runner.prepare_owned_avd_identity(self.config)
  text=self.config.read_text();self.assertIn('AvdId=hetu-smoke\n',text);self.assertIn('avd.name=hetu-smoke\n',text)
  self.assertIn('image.sysdir.1=system-images/android-35/default/x86_64/',text)
 def test_existing_owned_identity_is_idempotent(self):
  runner.prepare_owned_avd_identity(self.config);before=self.config.read_bytes()
  runner.prepare_owned_avd_identity(self.config);self.assertEqual(before,self.config.read_bytes())
 def test_foreign_identity_is_refused_without_write(self):
  for key in ('AvdId','avd.id','avd.name'):
   with self.subTest(key=key):
    raw=self.raw.replace(key+'=<build>',key+'=retained-user-avd') if key!='AvdId' else self.raw+'AvdId=retained-user-avd\n'
    self.config.write_text(raw)
    with self.assertRaisesRegex(RuntimeError,'Foreign'):runner.prepare_owned_avd_identity(self.config)
    self.assertEqual(raw,self.config.read_text())
 def test_conflicting_duplicate_is_refused_without_write(self):
  raw=self.raw+'avd.id=hetu-smoke\n';self.config.write_text(raw)
  with self.assertRaisesRegex(RuntimeError,'Conflicting'):runner.prepare_owned_avd_identity(self.config)
  self.assertEqual(raw,self.config.read_text())
 def test_foreign_path_is_refused(self):
  with self.assertRaisesRegex(RuntimeError,'path'):runner.prepare_owned_avd_identity(self.config.parent/'foreign.ini')
 def test_non_aosp_is_refused_without_write(self):
  raw=self.raw.replace('tag.id=default','tag.id=google_apis');self.config.write_text(raw)
  with self.assertRaisesRegex(RuntimeError,'AOSP'):runner.prepare_owned_avd_identity(self.config)
  self.assertEqual(raw,self.config.read_text())

class OwnedEmulatorTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  self.sdk=self.root/'sdk';self.binary=self.sdk/'emulator/emulator';self.binary.parent.mkdir(parents=True);self.binary.touch()
  self.cwd=os.getcwd();os.chdir(self.root)
  self.env=patch.dict(os.environ,{'ANDROID_HOME':str(self.sdk),'ANDROID_AVD_HOME':str(self.root/'avd'),'RUNNER_TEMP':str(self.root)})
  self.env.start();self.signals=patch.object(runner.signal,'signal');self.signals.start()
 def tearDown(self):
  self.signals.stop();self.env.stop();os.chdir(self.cwd);self.temp.cleanup()
 def test_supervisor_reaps_only_its_child_after_termination_and_timeout(self):
  child=Mock(pid=42,returncode=-9);child.poll.return_value=None
  child.wait.side_effect=[SystemExit(143),subprocess.TimeoutExpired('owned',20),-9]
  report=self.root/'cleanup.json'
  with patch.object(runner.subprocess,'Popen',return_value=child):
   with self.assertRaises(SystemExit):runner.supervise(str(self.binary),str(report))
  child.terminate.assert_called_once();child.kill.assert_called_once()
  self.assertTrue(json.loads(report.read_text())['emulator_reaped'])
 def test_non_sdk_binary_is_refused_before_process_start(self):
  with patch.object(runner.subprocess,'Popen') as start:
   with self.assertRaisesRegex(RuntimeError,'official SDK'):runner.supervise('/tmp/unrecognized',str(self.root/'cleanup.json'))
   start.assert_not_called()
 def test_missing_existing_sudo_access_never_starts_or_changes_permissions(self):
  with patch.object(runner.sys,'argv',['runner']),patch.object(runner,'kvm_state',return_value={'mode':432}),patch.object(runner.os,'access',return_value=False),patch.object(runner.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['sudo','-n','test'])),patch.object(runner.subprocess,'Popen') as start:
   with self.assertRaises(subprocess.CalledProcessError):runner.main()
   start.assert_not_called()
 def test_successful_smoke_cannot_pass_if_kvm_metadata_changed(self):
  child=Mock();child.poll.return_value=0;child.wait.return_value=0
  def run(command,**kwargs):
   if any(str(x).endswith('smoke_hetu_apk.py') for x in command):
    (self.root/'out/android-smoke/emulator-cleanup.json').write_text('{"emulator_reaped":true}')
   return Mock(returncode=0)
  with patch.object(runner.sys,'argv',['runner']),patch.object(runner,'kvm_state',side_effect=[{'mode':432},{'mode':438}]),patch.object(runner.os,'access',return_value=True),patch.object(runner,'process_identity',return_value={'start':100}),patch.object(runner.subprocess,'run',side_effect=run),patch.object(runner.subprocess,'Popen',return_value=child):
   self.assertEqual(1,runner.main())
  report=json.loads((self.root/'out/android-smoke/runner-lifecycle.json').read_text());self.assertEqual('FAIL',report['result']);self.assertIn('metadata changed',report['error'])

class OwnedEmulatorTargetTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
  self.sdk=self.root/'sdk';self.binary=self.sdk/'emulator/emulator'
  self.qemu=self.sdk/'emulator/qemu/linux-x86_64/qemu-system-x86_64'
  for binary in (self.binary,self.qemu):
   binary.parent.mkdir(parents=True,exist_ok=True);binary.touch();binary.chmod(0o755)
  self.proc=self.root/'proc';self.avd_home=self.root/'hetu-avd'
  self.config=self.avd_home/'hetu-smoke.avd/config.ini'
  self.config.parent.mkdir(parents=True)
  self.session='a'*32
  self.supervisor_args=[runner.sys.executable,str(Path(runner.__file__).resolve()),'--owned-supervisor',
   '--binary',str(self.binary),'--cleanup-report',str(self.root/'out/emulator-cleanup.json'),
   '--session',self.session]
  self.qemu_args=[str(self.qemu),'-avd','hetu-smoke','-port','5554','-no-snapshot']
  self._build()
 def tearDown(self):self.temp.cleanup()
 def _process(self,pid,ppid,start,args,children=()):
  directory=self.proc/str(pid);directory.mkdir(parents=True,exist_ok=True)
  # Linux stat fields 3..22: state, PPID and a stable process start-time tick.
  fields=['0']*20;fields[0]='S';fields[1]=str(ppid);fields[19]=str(start)
  (directory/'stat').write_text(f'{pid} (fixture-process) '+ ' '.join(fields)+'\n')
  (directory/'cmdline').write_bytes(b'\0'.join(arg.encode() for arg in args)+b'\0')
  task=directory/'task'/str(pid);task.mkdir(parents=True,exist_ok=True)
  (task/'children').write_text(' '.join(str(child) for child in children))
 def _build(self,nested=False):
  self.environment={
   'ANDROID_HOME':str(self.sdk),'ANDROID_AVD_HOME':str(self.avd_home),'RUNNER_TEMP':str(self.root),
   'HETU_OWNED_EMULATOR_SESSION':self.session,
   'HETU_OWNED_EMULATOR_RUNNER_PID':'10','HETU_OWNED_EMULATOR_RUNNER_START':'100',
   'HETU_OWNED_EMULATOR_LAUNCHER_PID':'20','HETU_OWNED_EMULATOR_LAUNCHER_START':'200',
  }
  self._process(10,1,100,['python3',str(Path(runner.__file__).resolve())],(20,))
  if nested:
   self._process(20,10,200,['sudo','-n',*self.supervisor_args],(21,))
   self._process(21,20,210,self.supervisor_args,(22,))
   self._process(22,21,220,self.qemu_args)
  else:
   self._process(20,10,200,self.supervisor_args,(22,))
   self._process(22,20,220,self.qemu_args)
  self.config.write_text('AvdId=hetu-smoke\nimage.sysdir.1=system-images/android-35/default/x86_64/\ntag.id=default\nabi.type=x86_64\n')
 def _verify(self):
  return runner.verify_owned_emulator_target(self.environment,proc_root=self.proc,smoke_parent_pid=10)
 def _refused(self):
  with self.assertRaises((AssertionError,RuntimeError,ValueError,FileNotFoundError)):self._verify()
 def test_live_direct_supervisor_proves_the_owned_aosp_target(self):
  result=self._verify()
  self.assertEqual('hetu-smoke',result['avd']);self.assertEqual(35,result['apiLevel'])
  self.assertEqual(self.session,result['ownedSession'])
 def test_live_sudo_supervisor_chain_proves_the_owned_aosp_target(self):
  self._build(nested=True)
  self.config.write_text(self.config.read_text().replace('android-35','android-36'))
  result=self._verify()
  self.assertEqual('hetu-smoke',result['avd']);self.assertEqual(36,result['apiLevel'])
  self.assertEqual(self.session,result['ownedSession'])
 def test_missing_malformed_or_foreign_session_is_refused(self):
  for session in (None,'not-a-session','b'*32):
   with self.subTest(session=session):
    self._build()
    if session is None:self.environment.pop('HETU_OWNED_EMULATOR_SESSION')
    else:self.environment['HETU_OWNED_EMULATOR_SESSION']=session
    self._refused()
 def test_reused_runner_or_launcher_pid_is_refused(self):
  for key in ('HETU_OWNED_EMULATOR_RUNNER_START','HETU_OWNED_EMULATOR_LAUNCHER_START'):
   with self.subTest(key=key):
    self._build();self.environment[key]='999';self._refused()
 def test_dead_owned_process_is_refused(self):
  for pid in (10,20,22):
   with self.subTest(pid=pid):
    self._build();shutil.rmtree(self.proc/str(pid));self._refused()
 def test_foreign_runner_or_process_parent_is_refused(self):
  for pid in (10,20,22):
   with self.subTest(pid=pid):
    self._build()
    if pid==10:self.environment['HETU_OWNED_EMULATOR_RUNNER_PID']='11'
    else:self._process(pid,999,200 if pid==20 else 220,self.supervisor_args if pid==20 else self.qemu_args,(22,) if pid==20 else ())
    self._refused()
 def test_different_avd_process_or_config_is_refused(self):
  for source in ('process','config'):
   with self.subTest(source=source):
    self._build()
    if source=='process':
     args=self.qemu_args.copy();args[args.index('-avd')+1]='retained-user-avd'
     self._process(22,20,220,args)
    else:self.config.write_text(self.config.read_text().replace('AvdId=hetu-smoke','AvdId=retained-user-avd'))
    self._refused()
 def test_qemu_on_another_port_is_refused(self):
  args=self.qemu_args.copy();args[args.index('-port')+1]='5556'
  self._process(22,20,220,args);self._refused()
 def test_non_sdk_qemu_or_supervisor_binary_is_refused(self):
  for source in ('qemu','supervisor'):
   with self.subTest(source=source):
    self._build()
    if source=='qemu':
     args=self.qemu_args.copy();args[0]=str(self.root/'foreign-sdk/qemu-system-x86_64')
     self._process(22,20,220,args)
    else:
     args=self.supervisor_args.copy();args[args.index('--binary')+1]=str(self.root/'foreign-sdk/emulator')
     self._process(20,10,200,args,(22,))
    self._refused()
 def test_non_aosp_or_unsupported_api_config_is_refused(self):
  for before,after in (
   ('android-35','android-34'),('tag.id=default','tag.id=google_apis'),
   ('abi.type=x86_64','abi.type=arm64-v8a'),('/default/x86_64/','/google_apis/x86_64/'),
  ):
   with self.subTest(after=after):
    self._build();self.config.write_text(self.config.read_text().replace(before,after));self._refused()
 def test_avd_home_outside_this_run_is_refused(self):
  self.environment['ANDROID_AVD_HOME']=str(self.root/'retained-avds');self._refused()
 def _converted_engine_args(self):
  engine=self.qemu.with_name('qemu-system-x86_64-headless');engine.touch();engine.chmod(0o755)
  return [str(engine),'-android-ports','5554,5555',
   '-android-hw',str(self.config.parent/'hardware-qemu.ini'),
   '-kernel',str(self.sdk/'system-images/android-35/default/x86_64/kernel-ranchu')]
 def test_sdk_headless_engine_converted_argv_proves_the_owned_target(self):
  self._process(22,20,220,self._converted_engine_args())
  result=self._verify()
  self.assertEqual('hetu-smoke',result['avd']);self.assertEqual(35,result['apiLevel'])
  self.assertEqual(self.session,result['ownedSession'])
 def test_converted_engine_wrong_ports_hardware_kernel_or_sdk_is_refused(self):
  for option,value in (
   ('-android-ports','5556,5557'),
   ('-android-hw',str(self.root/'retained-user-avd/hardware-qemu.ini')),
   ('-kernel',str(self.sdk/'system-images/android-36/default/x86_64/kernel-ranchu')),
   ('engine',str(self.root/'foreign-sdk/qemu-system-x86_64-headless')),
  ):
   with self.subTest(option=option):
    self._build();args=self._converted_engine_args()
    args[0 if option=='engine' else args.index(option)+1]=value
    self._process(22,20,220,args);self._refused()

if __name__=='__main__':unittest.main()
