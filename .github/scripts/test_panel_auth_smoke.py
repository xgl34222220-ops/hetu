"""Host-only guards for the fresh AOSP NativeCompose401 smoke; no adb/emulator runs."""
import ast
from contextlib import contextmanager
import json
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET

class PanelAuthSmokeTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.output=Path(self.temp.name)
  source=Path(__file__).with_name('smoke_hetu_apk.py').read_text()
  definitions=[n for n in ast.parse(source).body if isinstance(n,(ast.Import,ast.ImportFrom,ast.Assign,ast.FunctionDef))]
  self.module=types.ModuleType('panel_smoke_definitions')
  with patch.dict(os.environ,{'ANDROID_HOME':str(self.output/'sdk'),'HETU_SMOKE_OUT':str(self.output)}):
   exec(compile(ast.Module(body=definitions,type_ignores=[]),'panel-smoke-definitions','exec'),self.module.__dict__)
  self.original=b'<?xml version="1.0"?><map><string name="proxyControllerSecret">original-local-key</string><boolean name="networkMatchEnabled" value="true"/><string name="unrelated">keep</string></map>'
 def tearDown(self):self.temp.cleanup()
 def node(self,text,bounds='[0,0][1,1]',**attrs):
  return ET.Element('node',{'text':text,'bounds':bounds,'class':'android.widget.TextView',**attrs})
 def failure(self):
  root=ET.Element('hierarchy')
  root.extend([self.node('无法读取面板'),self.node('自定义控制接口 认证失败（HTTP 401）'),self.node('重试',clickable='true',enabled='true')])
  return root
 def overview(self,strategy='12'):
  root=ET.Element('hierarchy')
  root.extend([self.node('运行概况'),self.node('策略','[50,50][100,70]'),
   self.node(strategy,'[60,130][90,165]'),self.node('策略','[55,172][95,192]'),
   self.node('3','[188,130][205,165]'),self.node('规则','[174,172][220,192]'),
   self.node('18','[305,130][335,165]'),self.node('当前连接','[286,172][356,192]')])
  return root
 def test_preferences_preserve_local_secret_and_unrelated_settings_disable_automation(self):
  result=ET.fromstring(self.module.panel_fixture_preferences(self.original,29184))
  values={n.get('name'):n.text if n.tag=='string' else n.get('value') for n in result}
  self.assertEqual('original-local-key',values['proxyControllerSecret'])
  self.assertEqual('keep',values['unrelated'])
  self.assertEqual('HETU-SYNTHETIC-PANEL-AUTH-FIXTURE',values['proxyCustomApiSecret'])
  for name in ('proxyRootRuntimeRunning','proxyRootWanted','hetuCiSyntheticCachedRuntimeHint'):
   self.assertEqual('true',values[name])
  for name in ('proxyRootAutoStart','networkMatchEnabled','proxyStatusNotificationEnabled'):
   self.assertEqual('false',values[name])
  self.assertEqual('0',values['latencyAutoRefreshSeconds'])
  self.assertNotIn('proxyRootDataPlaneHealthy',values)
 def test_malformed_existing_preferences_fail_before_any_write(self):
  with patch.object(self.module,'adb',return_value='not XML'),patch.object(self.module,'write_fixture_preferences') as write:
   with self.assertRaises(ET.ParseError):
    with self.module.panel_preferences_fixture(29184,{}):pass
  write.assert_not_called()
 def test_preferences_restore_original_bytes_after_ui_failure(self):
  stored=[self.original];report={}
  def adb(*args,**kwargs):return stored[0].decode()
  def write(data):stored[0]=data
  with patch.object(self.module,'adb',side_effect=adb),patch.object(self.module,'write_fixture_preferences',side_effect=write):
   with self.assertRaisesRegex(RuntimeError,'real UI failed'):
    with self.module.panel_preferences_fixture(29184,report):
     self.assertNotEqual(self.original,stored[0]);raise RuntimeError('real UI failed')
  self.assertEqual(self.original,stored[0]);self.assertTrue(report['originalPreferencesRestored'])
 def test_physical_target_is_refused_before_other_adb_calls(self):
  with patch.object(self.module,'adb',return_value='phone-serial') as adb:
   with self.assertRaisesRegex(AssertionError,'owned fresh emulator'):self.module.panel_fixture_target()
  self.assertEqual([(('get-serialno',),{})],[(c.args,c.kwargs) for c in adb.call_args_list])
 def test_other_avd_is_refused(self):
  with patch.object(self.module,'verify_owned_emulator_target',return_value={'apiLevel':35}),patch.object(self.module,'adb',side_effect=['emulator-5554','retained-user-avd\nOK']):
   with self.assertRaisesRegex(AssertionError,'Unexpected AVD'):self.module.panel_fixture_target()
 def test_guest_root_shell_is_refused(self):
  with patch.object(self.module,'verify_owned_emulator_target',return_value={'apiLevel':35}),patch.object(self.module,'adb',side_effect=['emulator-5554','hetu-smoke\nOK','1','0']):
   with self.assertRaisesRegex(AssertionError,'non-root AOSP shell'):self.module.panel_fixture_target()
 def test_empty_console_reply_requires_and_accepts_live_owned_host_proof(self):
  proof={'apiLevel':35,'avd':'hetu-smoke','ownedSession':'current-owned-session'}
  with patch.object(self.module,'verify_owned_emulator_target',return_value=proof) as verify,patch.object(self.module,'adb',side_effect=['emulator-5554','','1','2000','35']),patch.object(self.module,'write_fixture_preferences') as write:
   target=self.module.panel_fixture_target()
  verify.assert_called_once_with(os.environ)
  self.assertEqual('current-owned-session',target['ownedSession'])
  self.assertEqual([],target['consoleAvdReply']);self.assertTrue(target['nonRootShell'])
  write.assert_not_called()
 def test_missing_host_proof_refuses_target_before_console_or_preference_write(self):
  with patch.object(self.module,'verify_owned_emulator_target',side_effect=RuntimeError('Missing current owned emulator session')),patch.object(self.module,'adb',return_value='emulator-5554') as adb,patch.object(self.module,'write_fixture_preferences') as write:
   with self.assertRaisesRegex(RuntimeError,'owned emulator session'):self.module.panel_fixture_target()
  self.assertEqual([(('get-serialno',),{})],[(call.args,call.kwargs) for call in adb.call_args_list])
  write.assert_not_called()
 def test_owned_avd_and_guest_api_must_match_before_preference_write(self):
  with patch.object(self.module,'verify_owned_emulator_target',return_value={'apiLevel':35}),patch.object(self.module,'adb',side_effect=['emulator-5554','','1','2000','36']),patch.object(self.module,'write_fixture_preferences') as write:
   with self.assertRaisesRegex(AssertionError,'differs from owned AOSP AVD'):self.module.panel_fixture_target()
  write.assert_not_called()
 def test_failure_requires_native401_and_never_accepts_zero_overview(self):
  self.module.assert_panel_read_failure(self.failure())
  root=self.failure();root.append(self.node('运行概况'));root.append(self.node('0'))
  with self.assertRaisesRegex(AssertionError,'overview/counter'):self.module.assert_panel_read_failure(root)
  root=self.failure();root[1].set('text','controller unavailable')
  with self.assertRaises(AssertionError):self.module.assert_panel_read_failure(root)
 def test_webview_is_not_accepted_as_native_panel_evidence(self):
  root=self.failure();root.append(self.node('',**{'class':'android.webkit.WebView'}))
  with self.assertRaisesRegex(AssertionError,'NativeCompose'):self.module.assert_panel_read_failure(root)
 def test_counts_pair_actual_columns_and_ignore_duplicate_strategy_tab(self):
  root=self.overview()
  self.assertEqual({'策略':12,'规则':3,'当前连接':18},{label:self.module.panel_counter(root,label) for label in ('策略','规则','当前连接')})
  root=self.overview('0');root.append(self.node('12','[250,130][280,165]'))
  self.assertEqual(0,self.module.panel_counter(root,'策略'))
 def test_capture_rechecks_counter_after_readiness_before_claiming_success(self):
  with patch.object(self.module,'ui',return_value=(self.overview(),'')),patch.object(self.module,'capture',return_value=self.overview('0')):
   with self.assertRaises(AssertionError):self.module.capture_panel_when(['运行概况'],'not-a-real-native-capture',{'策略':12})
  self.assertEqual([],self.module.checks)
 def test_retry_is_real_native_input_and_disabled_control_is_refused(self):
  root=self.failure();root[-1].set('bounds','[20,200][100,240]')
  with patch.object(self.module,'adb') as adb:
   proof=self.module.tap_panel_retry(root)
  adb.assert_called_once_with('shell','input','tap','60','220')
  self.assertEqual('native adb tap',proof['input'])
  root[-1].set('enabled','false')
  with patch.object(self.module,'adb') as adb:
   with self.assertRaises(AssertionError):self.module.tap_panel_retry(root)
   adb.assert_not_called()
 def test_scenario_failure_restores_preferences_and_removes_only_owned_reverse(self):
  stored=[self.original];reverses=['emulator-5554 tcp:29999 tcp:49999'];calls=[]
  def adb(*args,**kwargs):
   calls.append(args)
   if args==('reverse','--list'):return '\n'.join(reverses)
   if args[:2]==('reverse','--remove'):
    reverses[:]=[row for row in reverses if args[2] not in row.split()];return ''
   if args[0]=='reverse':reverses.append('emulator-5554 '+args[1]+' '+args[2]);return ''
   if args[:4]==('shell','run-as',self.module.PKG,'cat'):return stored[0].decode()
   return ''
  @contextmanager
  def fixture():yield 40000,[],Mock()
  with patch.object(self.module,'panel_fixture_target',return_value={'serial':'emulator-5554'}),patch.object(self.module,'panel_controller_fixture',fixture),patch.object(self.module,'adb',side_effect=adb),patch.object(self.module,'write_fixture_preferences',side_effect=lambda data:stored.__setitem__(0,data)),patch.object(self.module,'capture_panel_when',side_effect=AssertionError('real UI failed')),patch.object(self.module,'capture'),patch.object(self.module,'wait_for_home'):
   with self.assertRaisesRegex(AssertionError,'real UI failed'):self.module.panel_controller_auth_cases('fixture/launcher')
  self.assertEqual(self.original,stored[0]);self.assertEqual(['emulator-5554 tcp:29999 tcp:49999'],reverses)
  self.assertIn(('reverse','--remove','tcp:29184'),calls)
  self.assertNotIn(('reverse','--remove-all'),calls)
  report=json.loads((self.output/'panel-controller-auth-fixture.json').read_text())
  self.assertEqual('FAIL',report['result']);self.assertTrue(report['originalPreferencesRestored']);self.assertTrue(report['adbReverseRestored'])
  self.assertEqual([],self.module.checks)

if __name__=='__main__':unittest.main()
