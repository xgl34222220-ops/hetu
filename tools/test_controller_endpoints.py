#!/usr/bin/env python3
"""Test actual client requests with Android preference adapters; never Root/public network."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = root / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--json-jar', required=True, type=Path)
args = parser.parse_args()
if not args.json_jar.is_file():
    raise SystemExit('An existing org.json dependency jar is required')
java_home = os.environ.get('JAVA_HOME', '')
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
stubs = {
    'Context.java': 'package android.content; public abstract class Context { public Context getApplicationContext(){return this;} public abstract SharedPreferences getSharedPreferences(String n,int m); }',
    'SharedPreferences.java': 'package android.content; public interface SharedPreferences { java.util.Map<String,?> getAll(); boolean getBoolean(String k,boolean d); String getString(String k,String d); int getInt(String k,int d); }',
    'Uri.java': 'package android.net; public final class Uri { public static String encode(String s){return java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");} }',
    'SystemClock.java': 'package android.os; public final class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} public static void sleep(long m){try{Thread.sleep(m);}catch(InterruptedException e){Thread.currentThread().interrupt();}} }',
    'MihomoStartupConfig.java': 'package io.github.xgl34222220.hetu; final class MihomoStartupConfig { static final int CONTROLLER_PORT=9090; }',
}
manager = (source / 'RootProxyManager.java').read_text()
assert not re.search(r'new\s+MihomoControllerClient\s*\(', manager), 'Root maintenance must not use the panel-selected client'
assert manager.count('MihomoControllerClient.forLocalRuntime(context)') >= 4
with tempfile.TemporaryDirectory(prefix='hetu-controller-endpoints-') as output:
    directory = Path(output)
    for name, text in stubs.items():
        (directory / name).write_text(text)
    sources = [*directory.glob('*.java'), source / 'MihomoControllerClient.java', root / 'tests/ControllerEndpointTest.java']
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-cp', str(args.json_jar), '-d', output, *map(str, sources)], check=True)
    subprocess.run([java, '-cp', output + os.pathsep + str(args.json_jar), 'io.github.xgl34222220.hetu.ControllerEndpointTest'], check=True)
print('Root client scope: all 4 current call sites use the local-only factory')
