#!/usr/bin/env python3
"""Test production configuration-library transactions on temporary local files.

Only Android Context/SharedPreferences and the core enum are replaced with host
adapters. The production library, snapshot comparison and bundled data are used.
No Root, VPN, account, real subscription or external network is involved.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'

CONTEXT = '''package android.content;
import java.io.File;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public Context getApplicationContext(){return this;}
 public abstract File getFilesDir();
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
}'''
PREFS = '''package android.content;
public interface SharedPreferences {
 String getString(String key,String fallback);
 Editor edit();
 interface Editor { Editor putString(String key,String value); Editor remove(String key); void apply(); }
}'''
PROFILE = '''package io.github.xgl34222220.hetu;
import java.util.*;
final class ProxyRuntimeProfile {
 enum Core {
  MIHOMO("mihomo"), MIHOMO_SMART("mihomo-smart");
  final String id,label; final Set<String> extensions=Set.of("yaml","yml");
  Core(String value){id=value;label=value;}
 }
}'''


def main():
    home = os.environ.get('JAVA_HOME', '')
    javac = str(Path(home) / 'bin/javac') if home else 'javac'
    java = str(Path(home) / 'bin/java') if home else 'java'
    with tempfile.TemporaryDirectory(prefix='hetu-config-edit-test-') as directory:
        dest = Path(directory)
        for name, source in [('Context.java', CONTEXT), ('SharedPreferences.java', PREFS), ('ProxyRuntimeProfile.java', PROFILE)]:
            (dest / name).write_text(source)
        sources = [PACKAGE / name for name in ['ProxyConfigLibrary.java', 'ConfigEditSnapshot.java', 'BundledProxyConfig.java']]
        sources += sorted(PACKAGE.glob('BundledProxyConfigData*.java'))
        sources += list(dest.glob('*.java'))
        sources += [ROOT / 'tests/ConfigEditLibraryTest.java']
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', directory, *map(str, sources)], check=True)
        subprocess.run([java, '-cp', directory, 'io.github.xgl34222220.hetu.ConfigEditLibraryTest', str(dest / 'files')], check=True)


if __name__ == '__main__':
    main()
