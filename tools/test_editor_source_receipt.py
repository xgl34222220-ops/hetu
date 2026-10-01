#!/usr/bin/env python3
"""Exercise the production editor commit with temporary files and Android host adapters."""
import os
from pathlib import Path
import subprocess
import tempfile
from test_config_edit_library import CONTEXT, PREFS, PROFILE, PACKAGE, ROOT

java_home = os.environ.get('JAVA_HOME', '')
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
with tempfile.TemporaryDirectory(prefix='hetu-editor-source-') as directory:
    dest = Path(directory)
    for name, contents in [('Context.java', CONTEXT), ('SharedPreferences.java', PREFS), ('ProxyRuntimeProfile.java', PROFILE)]:
        (dest / name).write_text(contents)
    sources = [PACKAGE / name for name in ['ProxyConfigLibrary.java', 'ConfigEditSnapshot.java', 'BundledProxyConfig.java']]
    sources += sorted(PACKAGE.glob('BundledProxyConfigData*.java'))
    sources += list(dest.glob('*.java'))
    sources.append(ROOT / 'tests/EditorSourceReceiptTest.java')
    subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', directory, *map(str, sources)], check=True)
    subprocess.run([java, '-cp', directory, 'io.github.xgl34222220.hetu.EditorSourceReceiptTest', str(dest / 'files')], check=True)
