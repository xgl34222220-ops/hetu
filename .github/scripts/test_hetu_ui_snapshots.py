"""Checks retry freshness/access-denial handling without an emulator or adb."""
import ast
import os
from pathlib import Path
import tempfile
import types
import unittest
from unittest.mock import patch

class UiSnapshotsTest(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.output=Path(self.temp.name)
  source=Path(__file__).with_name('smoke_hetu_apk.py').read_text()
  tree=ast.parse(source)
  definitions=[n for n in tree.body if isinstance(n,(ast.Import,ast.ImportFrom,ast.Assign,ast.FunctionDef))]
  self.module=types.ModuleType('smoke_definitions')
  with patch.dict(os.environ,{'ANDROID_HOME':str(self.output/'sdk')}):
   exec(compile(ast.Module(body=definitions,type_ignores=[]),'smoke-definitions','exec'),self.module.__dict__)
  self.module.OUT=self.output
 def tearDown(self):self.temp.cleanup()
 def test_missing_initial_dump_retries_with_unique_paths(self):
  paths=[]
  def adb(*args,**kwargs):
   if args[:3]==('shell','uiautomator','dump'):
    paths.append(args[3]);return 'ERROR: null root' if len(paths)==1 else 'UI hierarchy dumped'
   if args[:2]==('shell','cat'):
    return 'No such file or directory' if len(paths)==1 else "<?xml version='1.0'?><hierarchy><node text='河图'/></hierarchy>"
   return ''
  with patch.object(self.module,'adb',side_effect=adb),patch.object(self.module.time,'sleep'):
   root,raw=self.module.ui()
  self.assertEqual('河图',root.find('node').get('text'));self.assertEqual(2,len(set(paths)))
  self.assertTrue((self.output/'ui-snapshot-retries.txt').exists())
 def test_access_denial_is_not_retried_or_escalated(self):
  calls=[]
  def adb(*args,**kwargs):calls.append(args);return 'Permission denied' if args[:3]==('shell','uiautomator','dump') else ''
  with patch.object(self.module,'adb',side_effect=adb),patch.object(self.module.time,'sleep'):
   with self.assertRaises(PermissionError):self.module.ui()
  self.assertEqual(1,sum(c[:3]==('shell','uiautomator','dump') for c in calls))
  self.assertFalse(any(c[:2]==('shell','cat') for c in calls))
 def test_root_error_text_inside_valid_app_xml_is_not_a_file_access_error(self):
  def adb(*args,**kwargs):
   return "<?xml version='1.0'?><hierarchy><node text='Root: Permission denied'/></hierarchy>" if args[:2]==('shell','cat') else ''
  with patch.object(self.module,'adb',side_effect=adb):root,_=self.module.ui()
  self.assertEqual('Root: Permission denied',root.find('node').get('text'))
if __name__=='__main__':unittest.main()
