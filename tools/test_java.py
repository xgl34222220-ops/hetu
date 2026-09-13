#!/usr/bin/env python3
"""Run pure protocol and rule parsing tests after build_app.py. No Root or network."""
import subprocess
from pathlib import Path
from build_app import ROOT, LOCAL_TOOLS, BUILD, sdk_paths, java_tool

def main():
    _, android = sdk_paths()
    dest = BUILD / 'host-tests'
    dest.mkdir(parents=True, exist_ok=True)
    cp = str(BUILD / 'classes') + ':' + str(android)
    sources = [ROOT / 'tests' / name for name in ('DnsPacketTest.java', 'DnsCacheTest.java', 'DnsUpstreamTest.java', 'rule_parser_test.java')]
    javac, java = java_tool('javac'), java_tool('java')
    command = [javac] if javac else [java, '-jar', str(LOCAL_TOOLS / 'ecj.jar')]
    subprocess.run(command + ['-source', '8', '-target', '8', '-encoding', 'UTF-8', '-cp', cp, '-d', str(dest)] + list(map(str, sources)), check=True)
    runtime = str(dest) + ':' + cp
    for name in ('DnsPacketTest', 'DnsCacheTest', 'DnsUpstreamTest'):
        subprocess.run([java, '-cp', runtime, 'io.github.xgl34222220.bichen.' + name], check=True)
    subprocess.run([java, '-cp', runtime, 'io.github.xgl34222220.bichen.RuleStoreParserTest'] + [str(ROOT / ('module/rules/' + s + '.txt')) for s in ['adaway', 'china', 'tracking', 'hagezi']], check=True)

if __name__ == '__main__':
    main()
