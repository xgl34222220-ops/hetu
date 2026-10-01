#!/usr/bin/env python3
"""Exercise production icon input with synthetic streams; no Android, files, or network IO."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'
java_home = os.environ.get('JAVA_HOME')
java = str(Path(java_home) / 'bin/java') if java_home else 'java'
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
caller = (SOURCE / 'ProxyPolicyIconsActivity.kt').read_text()
body = caller.split('fun importLocal(', 1)[1].split('\n}\n', 1)[0]
assert 'PolicyIconInput.read(input)' in body
assert 'input.readBytes()' not in body
assert body.index('PolicyIconInput.read(input)') < body.index('temp.writeBytes(bytes)')
assert 'openInputStream(uri)?.use' in body
with tempfile.TemporaryDirectory(prefix='hetu-icon-input-') as output:
    subprocess.run([javac, '-d', output, str(SOURCE / 'PolicyIconInput.java'),
                    str(ROOT / 'tests/PolicyIconInputTest.java')], check=True)
    subprocess.run([java, '-cp', output, 'io.github.xgl34222220.hetu.PolicyIconInputTest'], check=True)
print('Icon import call-site contracts: 4 passed')
