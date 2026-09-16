#!/usr/bin/env python3
"""Run Bichen host regression tests without depending on a prior APK build."""
import argparse
import os
import subprocess
from build_app import ROOT, LOCAL_TOOLS, BUILD, sdk_paths, java_tool

PROTOCOL_TESTS = (
    'DnsPacketTest', 'DnsCacheTest', 'DnsUpstreamTest', 'DnsResponseFilterTest',
    'RuleProfilesTest', 'NetworkEpochTest', 'RuleUpdateGateTest', 'ModuleArchiveTest',
    'ProtectionStateTest', 'ProxyNetworkStateTest', 'ProxyAppPolicyTest',
    'DomainRuleProjectionTest',
)
PROTOCOL_CLASSES = (
    'DnsPacket', 'DnsCache', 'DnsUpstream', 'DnsResponseFilter', 'RuleProfiles',
    'NetworkEpoch', 'RuleUpdateGate', 'ModuleArchive', 'ProtectionState',
    'ProxyNetworkState', 'ProxyAppPolicy', 'DomainRuleProjection',
)
ANDROID_HOST_TESTS = ('ProxyRuntimeProfileTest', 'MihomoStartupConfigTest')
ANDROID_HOST_CLASSES = (
    'ProxyRuntimeProfile', 'MihomoStartupConfig', 'RuleStore',
    'RootBridge', 'RootShellCommand',
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol-only', action='store_true', help='No Android SDK, root, or external network required')
    args = parser.parse_args()

    dest = BUILD / ('protocol-tests' if args.protocol_only else 'host-tests')
    dest.mkdir(parents=True, exist_ok=True)
    package = ROOT / 'android-app/app/src/main/java/io/github/xgl34222220/bichen'

    names = list(PROTOCOL_TESTS)
    sources = [ROOT / 'tests' / (name + '.java') for name in names]
    sources += [package / (name + '.java') for name in PROTOCOL_CLASSES]

    if args.protocol_only:
        cp = str(dest)
    else:
        _, android = sdk_paths()
        # Compile the exact production classes exercised by host tests in this
        # invocation. The previous workflow relied on android-app/build/classes,
        # which only existed after the obsolete Java-only APK builder ran.
        cp = str(android)
        names += list(ANDROID_HOST_TESTS)
        sources += [ROOT / 'tests' / (name + '.java') for name in ANDROID_HOST_TESTS]
        sources += [package / (name + '.java') for name in ANDROID_HOST_CLASSES]
        sources.append(ROOT / 'tests/rule_parser_test.java')

    missing = [str(path) for path in sources if not path.is_file()]
    if missing:
        raise RuntimeError('Missing host-test sources: ' + ', '.join(missing))

    javac, java = java_tool('javac'), java_tool('java')
    if not java or (not javac and not (LOCAL_TOOLS / 'ecj.jar').is_file()):
        raise RuntimeError('JDK 8+ (or tooling/ecj.jar) is required for host tests')
    command = [javac] if javac else [java, '-jar', str(LOCAL_TOOLS / 'ecj.jar')]
    subprocess.run(
        command + ['-source', '8', '-target', '8', '-encoding', 'UTF-8', '-cp', cp, '-d', str(dest)] + list(map(str, sources)),
        check=True,
    )

    runtime = str(dest) + os.pathsep + cp
    for name in names:
        subprocess.run([java, '-cp', runtime, 'io.github.xgl34222220.bichen.' + name], check=True)

    if not args.protocol_only:
        subprocess.run(
            [java, '-cp', runtime, 'io.github.xgl34222220.bichen.RuleStoreParserTest']
            + [str(ROOT / ('module/rules/' + source + '.txt')) for source in ['adaway', 'china', 'tracking', 'hagezi']],
            check=True,
        )


if __name__ == '__main__':
    main()
