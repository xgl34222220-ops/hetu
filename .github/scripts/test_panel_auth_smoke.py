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
 def footer(self,row_top=721,missing=False):
  root=self.overview()
  selected=ET.SubElement(root,'node',{'package':self.module.PKG,'selected':'true','bounds':'[14,136][74,184]'})
  selected.append(self.node('概览','[27,148][61,173]',package=self.module.PKG))
  scroll=ET.SubElement(root,'node',{'package':self.module.PKG,'scrollable':'true','enabled':'true','bounds':'[0,0][394,852]'})
  if not missing:
   row=ET.SubElement(scroll,'node',{'package':self.module.PKG,'bounds':f'[14,{row_top}][380,{row_top+131}]'})
   row.extend([self.node('未知应用',f'[82,{row_top+77}][154,{row_top+103}]',package=self.module.PKG),self.node('18 条连接',f'[301,{row_top+90}][362,{row_top+110}]',package=self.module.PKG)])
  dock=ET.SubElement(root,'node',{'package':self.module.PKG,'bounds':'[28,736][366,792]'})
  for label,left,right in [('首页',28,112),('面板',112,196),('工具',196,281),('设置',281,366)]:
   tab=ET.SubElement(dock,'node',{'package':self.module.PKG,'bounds':f'[{left},736][{right},792]'})
   tab.append(self.node('',f'[{left},736][{right},792]',package=self.module.PKG,**{'content-desc':label}))
  return root
 def nav(self):return 'InsetsSource: {2 mType=navigationBars mFrame=[0,804][394,852] mVisible=true mFlags=[]}'
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
 def test_retry_readiness_waits_for_native_enabled_state_without_input(self):
  disabled=self.failure();disabled[-1].set('enabled','false');ready=self.failure()
  with patch.object(self.module,'ui',return_value=(ready,'')) as ui,patch.object(self.module.time,'sleep') as sleep,patch.object(self.module,'adb') as adb:
   self.assertIs(ready,self.module.wait_panel_retry_ready(disabled))
  ui.assert_called_once();sleep.assert_called_once_with(.25);adb.assert_not_called()
 def test_disabled_retry_timeout_never_taps_or_fabricates_success(self):
  disabled=self.failure();disabled[-1].set('enabled','false')
  with patch.object(self.module.time,'monotonic',side_effect=[10,12]),patch.object(self.module,'adb') as adb,patch.object(self.module,'ui') as ui:
   with self.assertRaisesRegex(AssertionError,'stayed disabled'):self.module.wait_panel_retry_ready(disabled,timeout=1)
  adb.assert_not_called();ui.assert_not_called();self.assertEqual([],self.module.checks)
 def test_retry_readiness_never_accepts_overview_or_webview_as401(self):
  for root in [self.overview(),self.failure()]:
   if '运行概况' not in self.module.panel_labels(root):root.append(self.node('',**{'class':'android.webkit.WebView'}))
   with patch.object(self.module,'adb') as adb:
    with self.assertRaises(AssertionError):self.module.wait_panel_retry_ready(root)
    adb.assert_not_called()
 def test_footer_uses_actual_dock_and_navigation_bounds_and_detects_occlusion(self):
  before=self.module.panel_footer_geometry(self.footer(),804)
  self.assertFalse(before['entireRowUnobscured']);self.assertEqual(736,before['dockTop']);self.assertEqual((0,188,394,732),before['gestureViewport'])
  self.assertTrue(self.module.panel_footer_geometry(self.footer(430),804)['entireRowUnobscured'])
  self.assertFalse(self.module.panel_footer_geometry(self.footer(680),710)['entireRowUnobscured'])
 def test_footer_missing_duplicate_or_failure_state_never_passes_geometry(self):
  for root in [self.footer(missing=True),self.footer()]:
   if root.find('.//node[@text="未知应用"]') is not None:
    if 'HTTP 401' not in self.module.panel_labels(root):root.append(self.node('未知应用','[10,400][100,430]'))
   with self.assertRaises(AssertionError):self.module.panel_footer_geometry(root,804)
  root=self.footer();root.append(self.node('HTTP 401'))
  with self.assertRaisesRegex(AssertionError,'controller failure'):self.module.panel_footer_geometry(root,804)
  root=self.footer();root.find('.//node[@selected="true"]').set('selected','false')
  with self.assertRaisesRegex(AssertionError,'selected overview'):self.module.panel_footer_geometry(root,804)
 def test_navigation_frame_requires_observed_visible_full_width_insets(self):
  self.assertEqual((0,804,394,852),self.module.panel_navigation_frame(self.nav(),(0,0,394,852)))
  same_line='InsetsSource: {1 mType=statusBars mFrame=[0,0][394,24] mVisible=true}, '+self.nav()
  self.assertEqual((0,804,394,852),self.module.panel_navigation_frame(same_line,(0,0,394,852)))
  with self.assertRaises(AssertionError):self.module.panel_navigation_frame(same_line.replace('mType=navigationBars mFrame=[0,804][394,852] mVisible=true','mType=navigationBars mFrame=[0,804][394,852] mVisible=false'),(0,0,394,852))
  rect=self.nav().replace('[0,804][394,852]','Rect(0, 804 - 394, 852)')
  self.assertEqual((0,804,394,852),self.module.panel_navigation_frame(rect,(0,0,394,852)))
  for raw in ['',self.nav().replace('true','false'),self.nav().replace('394','200'),self.nav()+'\n'+self.nav().replace('804','790')]:
   with self.assertRaises(AssertionError):self.module.panel_navigation_frame(raw,(0,0,394,852))
 def test_footer_sends_native_input_recaptures_visible_row_and_returns_actual_counts(self):
  report={};before=self.footer();visible=self.footer(430)
  with patch.object(self.module,'capture',side_effect=[before,visible]) as capture,patch.object(self.module,'capture_panel_when',return_value=before) as returned,patch.object(self.module,'adb',return_value=self.nav()) as adb,patch.object(self.module.time,'sleep'):
   self.module.capture_panel_footer_scroll({'策略':12,'规则':3,'当前连接':18},report)
  self.assertEqual('PASS',report['footerScroll']['result']);self.assertEqual(2,len(report['footerScroll']['inputs']))
  swipes=[c for c in adb.call_args_list if c.args[:3]==('shell','input','swipe')]
  self.assertEqual(2,len(swipes));self.assertTrue(all(c.args[-1]=='400' for c in swipes))
  self.assertEqual(['panel-controller-200-footer-before','panel-controller-200-footer-after-1'],[c.args[0] for c in capture.call_args_list])
  returned.assert_called_once_with(['运行概况'],'panel-controller-200-footer-returned',{'策略':12,'规则':3,'当前连接':18})
  self.assertTrue((self.output/'panel-controller-200-footer-insets.txt').is_file());self.assertTrue((self.output/'panel-controller-200-footer-scroll.json').is_file())
 def test_footer_occlusion_is_bounded_and_missing_after_swipe_never_fabricates_pass(self):
  for afters,expected_swipes in [([self.footer(),self.footer()],2),([self.footer(missing=True)],1)]:
   report={}
   with patch.object(self.module,'capture',side_effect=[self.footer(),*afters]),patch.object(self.module,'adb',return_value=self.nav()) as adb,patch.object(self.module,'capture_panel_when') as returned,patch.object(self.module.time,'sleep'):
    with self.assertRaises(AssertionError):self.module.capture_panel_footer_scroll({'策略':12,'规则':3,'当前连接':18},report)
   self.assertEqual('FAIL',report['footerScroll']['result']);self.assertEqual(expected_swipes,len([c for c in adb.call_args_list if c.args[:3]==('shell','input','swipe')]))
   returned.assert_not_called();self.assertEqual([],self.module.checks)
 def test_footer_return_requires_actual_counters_and_no_reappearing401(self):
  for returned in [self.overview('0'),self.failure()]:
   report={}
   with patch.object(self.module,'capture',side_effect=[self.footer(),self.footer(430)]),patch.object(self.module,'capture_panel_when',return_value=returned),patch.object(self.module,'adb',return_value=self.nav()),patch.object(self.module.time,'sleep'):
    with self.assertRaises(AssertionError):self.module.capture_panel_footer_scroll({'策略':12,'规则':3,'当前连接':18},report)
   self.assertEqual('FAIL',report['footerScroll']['result']);self.assertEqual([],self.module.checks)
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
