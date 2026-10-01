#!/usr/bin/env python3
"""Run the exact VM measurement methods with controlled coroutine IO; no Android/network."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin-lib', type=Path, required=True)
    parser.add_argument('--source', type=Path, default=ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HetuViewModel.kt')
    args = parser.parse_args()
    jars = sorted(p for p in args.kotlin_lib.glob('*.jar') if p.name.startswith(('kotlin', 'annotations', 'trove')))
    if not any('kotlin-compiler' in p.name for p in jars) or not any('kotlinx-coroutines-core' in p.name for p in jars):
        parser.error('Use an existing Kotlin compiler/coroutines library directory; nothing is downloaded')
    source = args.source.read_text()
    start = source.index('    private suspend fun probe(node: String)')
    end = source.index('\n    fun testAll()', start)
    methods = source[start:end]
    assert methods.count('fun testGroup(') == 1 and methods.count('fun testNode(') == 1
    harness = (ROOT / 'tests/GroupMeasurementHostTest.kt').read_text().replace('// INSERT_EXACT_PRODUCTION_METHODS', methods)
    java_home = os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    cp = os.pathsep.join(map(str, jars))
    with tempfile.TemporaryDirectory(prefix='hetu-group-measurement-') as directory:
        test = Path(directory) / 'GroupMeasurementHostTest.kt'
        test.write_text(harness)
        subprocess.run([java, '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp, '-d', directory, str(test)], check=True)
        subprocess.run([java, '-cp', os.pathsep.join([directory, cp]), 'GroupMeasurementHostTestKt'], check=True, timeout=30)


if __name__ == '__main__':
    main()
