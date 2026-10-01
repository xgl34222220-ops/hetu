#!/usr/bin/env python3
"""Exercise bound production subscription writes using real temporary files.

The source-edit API is tested here; no UI migration or runtime application is
implied. Android Context/SharedPreferences and the core enum are host adapters.
"""
import os
from pathlib import Path
import subprocess
import tempfile

from test_config_edit_library import CONTEXT, PREFS, PROFILE, PACKAGE, ROOT


def main():
    java_home = os.environ.get('JAVA_HOME', '')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    with tempfile.TemporaryDirectory(prefix='hetu-subscription-source-') as directory:
        dest = Path(directory)
        for name, contents in [('Context.java', CONTEXT), ('SharedPreferences.java', PREFS), ('ProxyRuntimeProfile.java', PROFILE)]:
            (dest / name).write_text(contents)
        sources = [PACKAGE / name for name in ['ProxyConfigLibrary.java', 'ConfigEditSnapshot.java', 'BundledProxyConfig.java']]
        sources += sorted(PACKAGE.glob('BundledProxyConfigData*.java'))
        sources += list(dest.glob('*.java'))
        sources.append(ROOT / 'tests/SubscriptionSourceSnapshotTest.java')
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', directory, *map(str, sources)], check=True)
        subprocess.run([java, '-cp', directory, 'io.github.xgl34222220.hetu.SubscriptionSourceSnapshotTest', str(dest / 'files')], check=True)


if __name__ == '__main__':
    main()
