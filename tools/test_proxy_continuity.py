#!/usr/bin/env python3
"""Run the message-filter and connection-continuity regressions on the host JVM."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', required=True)
    parser.add_argument('--ecj', help='Optional compiler JAR when only a JRE is installed')
    parser.add_argument('--fixture', help='Write generated runtime YAML for Mihomo integration tests')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-continuity-') as temp:
        dest = Path(temp)
        # The generator only needs these provider constants, not Android RuleStore I/O.
        source = (PACKAGE / 'ProxyAdblockRules.java').read_text()
        constants = re.findall(r'    static final String (?:PROVIDER_NAME|ALLOW_PROVIDER_NAME|PROVIDER_PATH|ALLOW_PROVIDER_PATH) = "[^"\n]+";', source)
        if len(constants) != 4:
            raise RuntimeError('Unable to obtain production provider constants')
        stub = dest / 'ProxyAdblockRules.java'
        stub.write_text('package io.github.xgl34222220.hetu;\nfinal class ProxyAdblockRules {\n' + '\n'.join(constants) + '\n}\n')
        names = ('ProxyContinuity', 'MessagingFilterPolicy', 'MihomoStartupConfig', 'ProxyRuntimeProfile', 'ProxyRestoreScheduler', 'DiagnosticReport', 'RootStartupProbe', 'ProxyNetworkHandover', 'ProxyTaskCoalescer', 'ProxyLogLines', 'ProxyAdblockSession', 'ProxyRuntimeSettings', 'ProxyAsyncValue')
        sources = [str(PACKAGE / (name + '.java')) for name in names]
        tests = ('ProxyContinuityTest', 'ProxyRestoreSchedulerTest', 'DiagnosticReportTest', 'ProxyCoreProbeTest', 'RootStartupProbeTest', 'ProxyNetworkEventsTest', 'ProxyLogLinesTest', 'ProxyRuntimeSettingsTest', 'MihomoIpv6PolicyTest', 'ProxyAsyncValueTest')
        service_source = (PACKAGE / 'ProxyNetworkMatchService.java').read_text()
        if 'repairLiveNetworkIntegrity();' not in service_source or 'hetu-root.sh repair-network' not in service_source:
            raise RuntimeError('Default-network handover must trigger bounded in-place Hetu network repair')
        if '.closeAll()' in service_source:
            raise RuntimeError('Network handover must not flush all live proxy connections')
        sources += [str(stub)] + [str(ROOT / 'tests' / (name + '.java')) for name in tests]
        # Android's java.* stubs conflict with JVM modules in ECJ. Only the Android
        # API types are needed here; use the host JDK implementation of java.*.
        android_api = dest / 'android-api.jar'
        with zipfile.ZipFile(args.android_jar) as sdk, zipfile.ZipFile(android_api, 'w') as api:
            for name in sdk.namelist():
                if name.startswith('android/') and name.endswith('.class'):
                    api.writestr(name, sdk.read(name))
        compiler = ['java', '-jar', args.ecj] if args.ecj else [shutil.which('javac') or 'javac']
        subprocess.run(compiler + ['-source', '17', '-target', '17', '-encoding', 'UTF-8', '-cp', str(android_api), '-d', temp] + sources, check=True)
        command = ['java', '-cp', temp + os.pathsep + str(android_api), 'io.github.xgl34222220.hetu.ProxyContinuityTest']
        if args.fixture:
            fixture = Path(args.fixture).resolve()
            fixture.parent.mkdir(parents=True, exist_ok=True)
            command.append(str(fixture))
        subprocess.run(command, check=True)
        for name in tests[1:]:
            subprocess.run(['java', '-cp', temp + os.pathsep + str(android_api), 'io.github.xgl34222220.hetu.' + name], check=True)


if __name__ == '__main__':
    main()
