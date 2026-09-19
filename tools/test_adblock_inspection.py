#!/usr/bin/env python3
"""Check atomic filter snapshots and the actual runtime YAML's UI detection."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/hetu'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android-jar', required=True)
    parser.add_argument('--ecj')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-adblock-test-') as temp:
        dest = Path(temp)
        android_api = dest / 'android-api.jar'
        with zipfile.ZipFile(args.android_jar) as sdk, zipfile.ZipFile(android_api, 'w') as api:
            for name in sdk.namelist():
                if name.startswith(('android/', 'org/json/')) and name.endswith('.class'):
                    api.writestr(name, sdk.read(name))
        # No Context, root process, Android service or disk publication is used by
        # these tests. Fail loudly if a regression tries to invoke the root bridge.
        bridge = dest / 'RootBridge.java'
        bridge.write_text('''package io.github.xgl34222220.hetu;
import android.content.Context;
import org.json.JSONObject;
final class RootBridge {
    static final class Result { int code; String output; }
    static JSONObject status(Context c) { throw new AssertionError("unexpected root status"); }
    static Result run(Context c,long timeout,String... args) { throw new AssertionError("unexpected root command"); }
    static JSONObject parseObject(String text) { throw new AssertionError("unexpected root response"); }
}
''')
        names = ('RuleStore', 'RuleProfiles', 'RuleUpdateGate', 'MessagingFilterPolicy',
                 'DnsPacket', 'DnsResponseFilter', 'ProxyAdblockRules', 'AdblockRuleInspection',
                 'ProxyRuntimeProfile', 'MihomoStartupConfig')
        tests = ('RuleStoreSnapshotTest', 'AdblockRuleInspectionTest', 'RuleStoreParserTest')
        sources = [str(PACKAGE / (name + '.java')) for name in names]
        sources += [str(ROOT / 'tests' / ('rule_parser_test.java' if name == 'RuleStoreParserTest' else name + '.java')) for name in tests]
        sources.append(str(bridge))
        compiler = ['java', '-jar', args.ecj] if args.ecj else [shutil.which('javac') or 'javac']
        subprocess.run(compiler + ['-source', '17', '-target', '17', '-encoding', 'UTF-8',
                                  '-cp', str(android_api), '-d', temp] + sources, check=True)
        for name in tests:
            subprocess.run(['java', '-cp', temp + os.pathsep + str(android_api),
                            'io.github.xgl34222220.hetu.' + name]
                           + ([str(path) for path in sorted((ROOT / 'android-app/app/src/main/assets/rules').glob('*.txt'))]
                              if name == 'RuleStoreParserTest' else []), check=True)


if __name__ == '__main__':
    main()
