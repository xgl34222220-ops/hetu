#!/usr/bin/env python3
"""Exercise production Google metadata classification without Android, Root or network."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'

def main():
    home = os.environ.get('JAVA_HOME', '')
    javac = str(Path(home) / 'bin/javac') if home else 'javac'
    java = str(Path(home) / 'bin/java') if home else 'java'
    with tempfile.TemporaryDirectory(prefix='hetu-google-diagnostics-') as dest:
        sources = [PACKAGE / 'DiagnosticReport.java', PACKAGE / 'GoogleConnectionDiagnostics.java',
                   ROOT / 'tests/GoogleConnectionDiagnosticsTest.java', ROOT / 'tests/DiagnosticReportTest.java']
        subprocess.run([javac, '--release', '17', '-encoding', 'UTF-8', '-d', dest, *map(str, sources)], check=True)
        for test in ('GoogleConnectionDiagnosticsTest', 'DiagnosticReportTest'):
            subprocess.run([java, '-cp', dest, 'io.github.xgl34222220.hetu.' + test], check=True)

if __name__ == '__main__':
    main()
