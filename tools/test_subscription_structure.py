#!/usr/bin/env python3
"""Run production subscription regressions with synthetic files and no network."""
import os
from pathlib import Path
import subprocess
import tempfile
from test_config_edit_library import CONTEXT, PREFS, PROFILE, PACKAGE, ROOT


def main():
    home = os.environ.get('JAVA_HOME', '')
    javac = str(Path(home) / 'bin/javac') if home else 'javac'
    java = str(Path(home) / 'bin/java') if home else 'java'
    with tempfile.TemporaryDirectory(prefix='hetu-subscription-structure-') as directory:
        dest = Path(directory)
        for name, source in [('Context.java', CONTEXT), ('SharedPreferences.java', PREFS), ('ProxyRuntimeProfile.java', PROFILE)]:
            (dest / name).write_text(source)
        sources = [PACKAGE / name for name in ['ProxyConfigLibrary.java', 'ConfigEditSnapshot.java', 'BundledProxyConfig.java']]
        sources += sorted(PACKAGE.glob('BundledProxyConfigData*.java'))
        sources += list(dest.glob('*.java'))
        sources += [ROOT / 'tests/SubscriptionStructureTest.java']
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', directory, *map(str, sources)], check=True)
        subprocess.run([java, '-cp', directory, 'io.github.xgl34222220.hetu.SubscriptionStructureTest', str(dest / 'files')], check=True)


if __name__ == '__main__':
    main()
