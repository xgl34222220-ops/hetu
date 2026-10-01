#!/usr/bin/env python3
"""Exercise the real download function and config library on the host, without network.

Requires JDK 17+ and an existing Kotlin compiler library directory (KOTLIN_HOME/lib,
HETU_KOTLIN_LIB or --kotlin-lib). No packages are downloaded. Only Android Context,
SharedPreferences, Uri and the runtime-profile lookup use minimal host adapters.
The exact production download/filename functions are extracted without rewriting
their bodies and compiled together with the host tests and production Java reader.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import tempfile

from test_config_edit_library import CONTEXT, PREFS, PROFILE, PACKAGE, ROOT


URI = '''package android.net;
public final class Uri {
 private final java.net.URI value;
 private Uri(String text){value=java.net.URI.create(text);}
 public static Uri parse(String text){return new Uri(text);}
 public String getHost(){return value.getHost();}
 public static String decode(String text){return java.net.URLDecoder.decode(text,java.nio.charset.StandardCharsets.UTF_8);}
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin-lib', type=Path)
    args = parser.parse_args()
    kotlin_home = os.environ.get('KOTLIN_HOME', '')
    kotlin_lib = args.kotlin_lib or os.environ.get('HETU_KOTLIN_LIB')
    if not kotlin_lib and kotlin_home:
        kotlin_lib = Path(kotlin_home) / 'lib'
    if not kotlin_lib:
        parser.error('Set KOTLIN_HOME/HETU_KOTLIN_LIB or pass --kotlin-lib; no downloads are performed')
    kotlin_lib = Path(kotlin_lib)
    jars = sorted(path for path in kotlin_lib.glob('*.jar') if path.name.startswith(('kotlin', 'annotations', 'trove')))
    if not any('kotlin-compiler' in path.name for path in jars):
        parser.error('The Kotlin library directory must contain the compiler and its dependencies')
    if not any('kotlinx-coroutines-core' in path.name for path in jars):
        parser.error('The Kotlin library directory must contain the existing coroutines-core JVM jar')
    java_home = os.environ.get('JAVA_HOME', '')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    production = (PACKAGE / 'app/ConfigScreens.kt').read_text()
    start = production.index('private fun normalizeConfigName(')
    end = production.index('\n/* ---', start)
    # Keep the private functions in the same compilation unit as the tests.
    functions = production[start:end]
    if functions.count('private suspend fun downloadConfig(') != 1:
        raise RuntimeError('Could not extract the exact production download function')
    imports = re.findall(r'^import (?:android\.(?:content.Context|net.Uri)|kotlinx\.coroutines\.[\w.]+|java\.(?:io.IOException|net\.[\w.]+))$', production, re.M)
    harness = (ROOT / 'tests/ConfigDownloadHostTest.kt').read_text()
    harness = harness.replace('// INSERT_EXACT_PRODUCTION_FUNCTIONS', functions)
    harness = harness.replace('// INSERT_PRODUCTION_IMPORTS', '\n'.join(imports))
    profile = PROFILE.replace(' enum Core {', ' final Core core=Core.MIHOMO;\n static ProxyRuntimeProfile load(android.content.SharedPreferences prefs){return new ProxyRuntimeProfile();}\n enum Core {')
    with tempfile.TemporaryDirectory(prefix='hetu-config-download-test-') as directory:
        dest = Path(directory)
        for name, source in [('Context.java', CONTEXT), ('SharedPreferences.java', PREFS), ('ProxyRuntimeProfile.java', profile), ('Uri.java', URI)]:
            (dest / name).write_text(source)
        sources = [PACKAGE / name for name in ['ConfigDownloadReader.java', 'ProxyConfigLibrary.java', 'ConfigEditSnapshot.java', 'BundledProxyConfig.java']]
        sources += sorted(PACKAGE.glob('BundledProxyConfigData*.java'))
        sources += list(dest.glob('*.java'))
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', directory, *map(str, sources)], check=True)
        test = dest / 'ConfigDownloadHostTest.kt'
        test.write_text(harness)
        dependencies = os.pathsep.join(map(str, jars))
        classpath = os.pathsep.join([directory, dependencies])
        subprocess.run([java, '-cp', dependencies, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath, '-d', directory, str(test)], check=True)
        subprocess.run([java, '-cp', classpath, 'io.github.xgl34222220.hetu.ConfigDownloadHostTestKt', str(dest / 'files')], check=True, timeout=60)


if __name__ == '__main__':
    main()
