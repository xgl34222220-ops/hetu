#!/usr/bin/env python3
"""Exercise the ACTUAL RootBridge process runner with host-only Android API stubs.

No real su/root/mounts are used. A fake su runs the emitted script under each
available shell, substituting only framework BusyBox paths. This tests process
exit, timing, pipe draining and command quoting, NOT Android framework behavior.
"""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'io.github.xgl34222220.hetu'
JAVA = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'

class RootProcessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='hetu-root-process-')
        cls.work = Path(cls.temp.name)
        cls.bb = shutil.which('busybox')
        if not cls.bb:
            raise unittest.SkipTest('BusyBox required for native timeout regression')
        stubs = {
            'android/content/Context.java': 'package android.content; public class Context {}',
            'android/os/Looper.java': 'package android.os; public class Looper { public static Looper getMainLooper(){return null;} public static Looper myLooper(){return null;} }',
            'android/os/SystemClock.java': 'package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000L;} }',
            'org/json/JSONObject.java': '''package org.json; public class JSONObject {
                public Object put(String k,Object v){throw new UnsupportedOperationException();}
                public Object remove(String k){throw new UnsupportedOperationException();}
                public boolean optBoolean(String k,boolean d){throw new UnsupportedOperationException();}
                public String optString(String k,String d){throw new UnsupportedOperationException();}
            }''',
            'org/json/JSONTokener.java': '''package org.json; public class JSONTokener {
                public JSONTokener(String s){} public Object nextValue(){throw new UnsupportedOperationException();}
                public char nextClean(){throw new UnsupportedOperationException();}
            }''',
            'RootHarness.java': '''package io.github.xgl34222220.hetu;
                public class RootHarness { public static void main(String[] args) {
                    RootBridge.Result r=RootBridge.rootShell(null,args[0],Long.parseLong(args[1]));
                    System.out.println(r.code);
                    System.out.println(java.util.Base64.getEncoder().encodeToString(r.output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }}''',
        }
        inputs=[]
        for name,text in stubs.items():
            p=cls.work/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);inputs.append(p)
        subprocess.run(['javac','-encoding','UTF-8','-d',str(cls.work),*map(str,inputs),str(JAVA/'RootBridge.java'),str(JAVA/'RootShellCommand.java')],check=True)
        shim=cls.work/'su'
        shim.write_text('''#!/usr/bin/env python3
import os,sys
assert len(sys.argv)==3 and sys.argv[1]=='-c'
script=sys.argv[2]
for p in ('/data/adb/ksu/bin/busybox','/data/adb/ap/bin/busybox','/data/adb/magisk/busybox'):
    script=script.replace(p,os.environ['HETU_HOST_BB'])
shell=os.environ['HETU_HOST_SHELL']
args=[shell]+(['ash'] if shell.endswith('busybox') else [])
os.execv(shell,args+['-c',script])
''')
        shim.chmod(0o755)
        cls.shells=[p for p in ['/bin/sh','/bin/bash',cls.bb,shutil.which('mksh')] if p]

    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()

    def call(self,cmd,timeout=5000,shell='/bin/sh',cat=False):
        env=dict(os.environ,PATH=str(self.work)+os.pathsep+os.environ['PATH'],HETU_HOST_BB=self.bb,HETU_HOST_SHELL=shell,HETU_TEST_CAT='1' if cat else '0')
        start=time.monotonic()
        p=subprocess.run(['java','-cp',str(self.work),PACKAGE+'.RootHarness',cmd,str(timeout)],env=env,capture_output=True,text=True,timeout=timeout/1000+4)
        self.assertEqual(p.returncode,0,p.stderr)
        code,payload=p.stdout.split('\n')[:2]
        return int(code),base64.b64decode(payload).decode(),time.monotonic()-start

    def test_successful_json_returns_without_waiting_for_timer(self):
        for shell in self.shells:
            with self.subTest(shell=shell):
                code,out,elapsed=self.call('printf \'%s\\n\' \'{"ok":true,"version":"0.3.0-beta.1","enabled":false,"mounted":false}\'',10000,shell)
                self.assertEqual(code,0,out);self.assertTrue(json.loads(out)['ok'])
                self.assertLess(elapsed,2.5,elapsed)
                print('FAST',shell,round(elapsed,3),'s')

    def test_json_does_not_hide_nonzero_exit(self):
        for shell in self.shells:
            code,out,_=self.call('printf \'%s\\n\' \'{"ok":true}\'; exit 7',shell=shell)
            self.assertEqual(code,7,out)

    def test_quoted_command_and_old_timeout_text_are_data(self):
        code,out,_=self.call('printf \'%s\\n\' "a\'b" \'$HOME; $(false)\' \'__HETU_TIMEOUT__\'')
        self.assertEqual(code,0);self.assertEqual(out,"a'b\n$HOME; $(false)\n__HETU_TIMEOUT__\n")

    def test_large_output_is_drained(self):
        code,out,_=self.call("awk 'BEGIN { for(i=0;i<12000;i++)print \"1234567890abcdef\" }'")
        self.assertEqual(code,0);self.assertEqual(len(out),204000)

    def test_no_proc_children_dependency(self):
        # The command itself cannot read proc children; the wrapper must not try.
        code,out,_=self.call("cat() { return 91; }; printf '%s\\n' done")
        self.assertEqual(code,0);self.assertEqual(out,'done\n')

    def test_real_timeout_is_failure_even_after_json(self):
        code,out,elapsed=self.call('printf \'%s\\n\' \'{"ok":true}\'; exec sleep 10',3000)
        self.assertNotEqual(code,0);self.assertIn('{"ok":true}',out);self.assertLess(elapsed,4)

    def test_term_ignoring_process_is_killed(self):
        code,out,elapsed=self.call("trap '' TERM; exec sleep 10",4000)
        self.assertNotEqual(code,0);self.assertLess(elapsed,5)

    def test_stderr_and_exit_preserved(self):
        code,out,_=self.call("printf '%s\\n' denied >&2; exit 126")
        self.assertEqual(code,126);self.assertIn('denied',out)

if __name__=='__main__': unittest.main(verbosity=2)
