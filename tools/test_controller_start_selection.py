#!/usr/bin/env python3
"""Exercise the verbatim production startup method with isolated host IO.

The production runtime profile is compiled unchanged. Only Android preferences,
core storage, migration, config lookup, hooks and Root execution are adapters.
No core process, privileged command, network or user configuration is used.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
PREFS = '''package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback);
 boolean getBoolean(String key,boolean fallback);
 Editor edit();
 interface Editor {Editor putString(String key,String value); void apply();}
}'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--kotlin-lib', required=True)
    parser.add_argument('--expect-old-fallback', action='store_true')
    args = parser.parse_args()
    source = (PACKAGE / 'ProxyComposeController.kt').read_text()
    match = re.search(r'^    suspend fun start\([^\n]*\).*?^    }$', source, re.M | re.S)
    if not match:
        raise SystemExit('Cannot extract the complete production start method')
    method = match.group(0)
    exception = re.search(r'^internal class ProxyStartPreflightException[^\n]*', source, re.M)
    wrapped = '''package io.github.xgl34222220.hetu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
''' + (exception.group(0) + '\n' if exception else '') + '''
internal class ExtractedStartup(private val app: StartContext) {
 private val prefs = app.prefs
 private val configs = app.configs
 private val root = app.root
''' + method + '\n}\n'
    jdk = Path(os.environ.get('JAVA_HOME', ''))
    javac = str(jdk / 'bin/javac') if str(jdk) != '.' else 'javac'
    java = str(jdk / 'bin/java') if str(jdk) != '.' else 'java'
    jars = sorted(p for p in Path(args.kotlin_lib).glob('*.jar')
                  if p.name.startswith(('kotlin', 'annotations', 'trove')))
    if not jars:
        raise SystemExit('Existing Kotlin compiler/runtime jars are required')
    dependencies = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix='hetu-start-selection-') as directory:
        dest = Path(directory)
        (dest / 'SharedPreferences.java').write_text(PREFS)
        (dest / 'ExtractedStartup.kt').write_text(wrapped)
        subprocess.run([javac, '--release', '17', '-d', directory,
                        str(dest / 'SharedPreferences.java'),
                        str(PACKAGE / 'ProxyRuntimeProfile.java')], check=True)
        classpath = os.pathsep.join([directory, dependencies])
        subprocess.run([java, '-cp', dependencies, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                        '-d', directory, str(dest / 'ExtractedStartup.kt'),
                        str(ROOT / 'tests/ControllerStartSelectionTest.kt')], check=True)
        subprocess.run([java, '-cp', classpath, 'io.github.xgl34222220.hetu.ControllerStartSelectionTestKt',
                        'old' if args.expect_old_fallback else 'fixed'], check=True)
    print('Production start method SHA-256:', hashlib.sha256(method.encode()).hexdigest())


if __name__ == '__main__':
    main()
