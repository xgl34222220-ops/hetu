import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock,patch

spec=importlib.util.spec_from_file_location('runner',Path(__file__).with_name('run_hetu_emulator.py'))
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

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
  with patch.object(runner.sys,'argv',['runner']),patch.object(runner,'kvm_state',side_effect=[{'mode':432},{'mode':438}]),patch.object(runner.os,'access',return_value=True),patch.object(runner.subprocess,'run',side_effect=run),patch.object(runner.subprocess,'Popen',return_value=child):
   self.assertEqual(1,runner.main())
  report=json.loads((self.root/'out/android-smoke/runner-lifecycle.json').read_text());self.assertEqual('FAIL',report['result']);self.assertIn('metadata changed',report['error'])
if __name__=='__main__':unittest.main()
