#!/usr/bin/env python3
"""Reproduce the integration from the verified effective source, then compare checkout bytes.

The historical patch chain is reversed only in a temporary fixture for before/after
regressions. It is never replayed against a checkout containing the new UI.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / 'updates/v2082-new-ui/inputs.json'

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def apply(patch, cwd, reverse=False):
    flags = ['-R'] if reverse else []
    subprocess.run(['git', 'apply', '--check', *flags, str(patch)], cwd=cwd, check=True)
    subprocess.run(['git', 'apply', *flags, str(patch)], cwd=cwd, check=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    spec = json.loads(INPUTS.read_text())
    assert digest(args.archive) == spec['archiveSha256'], 'Wrong effective source archive'
    patch = ROOT / 'updates/v2082-new-ui/integration.patch'
    assert digest(patch) == spec['patchSha256'], 'Integration patch changed without input update'
    out = ROOT / 'out/verification'
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='hetu-newui-') as directory:
        work = Path(directory)
        with tarfile.open(args.archive) as archive:
            archive.extractall(work, filter='data')
        for name, sha in spec['baselineFiles'].items():
            assert digest(work / name) == sha, 'Baseline mismatch: ' + name
        # Capture precise historic production sources for the existing fault fixtures.
        fixture = work / 'historical'
        fixture.mkdir()
        shutil.copytree(work / 'android-app', fixture / 'android-app')
        def capture(name, source):
            shutil.copyfile(fixture / ('android-app/app/src/main/java/io/github/xgl34222220/hetu/' + source), out / name)
        for version, folder in [(2081, 'v2081-trace-symbols'), (2080, 'v2080-journal-bounds'),
                                (2079, 'v2079-recovery-backpressure'), (2078, 'v2078-network-traces'),
                                (2077, 'v2077-network-recovery'), (2076, 'v2076-session-filter')]:
            old = ROOT / 'updates' / folder / 'runtime.patch'
            assert digest(old) == spec['historicalPatches'][str(version)]
            apply(old, fixture, reverse=True)
            if version == 2081: capture('v2080-network-journal.java', 'ProxyNetworkJournal.java')
            if version == 2080: capture('v2079-network-journal.java', 'ProxyNetworkJournal.java')
            if version == 2079: capture('v2078-network-service.java', 'ProxyNetworkMatchService.java')
            if version == 2076: capture('v2075-adblock.java', 'ProxyAdblockRules.java')
        apply(patch, work)
        for name, sha in spec['integratedFiles'].items():
            assert digest(work / name) == sha, 'Generated source mismatch: ' + name
            assert digest(ROOT / name) == sha, 'Checkout differs from effective source: ' + name
        for name, sha in spec['baselineFiles'].items():
            if name not in spec['integratedFiles']:
                assert digest(ROOT / name) == sha, 'V20.81 file regressed: ' + name
        expected = set(spec['baselineFiles']) | set(spec['integratedFiles'])
        actual = {str(p.relative_to(ROOT)) for p in (ROOT / 'android-app').rglob('*') if p.is_file()
                  and '/build/' not in str(p.relative_to(ROOT)) and '/.gradle/' not in str(p.relative_to(ROOT))
                  and p.name != 'local.properties'}
        assert actual <= expected, 'Unrecorded compilation input: ' + str(actual - expected)
        report = {'baseCommit': spec['baseCommit'], 'baseRun': spec['baseRun'],
                  'archiveSha256': spec['archiveSha256'], 'integrationPatchSha256': spec['patchSha256'],
                  'baselineFiles': len(spec['baselineFiles']), 'changedOrAddedFiles': len(spec['integratedFiles']),
                  'checkoutMatchesGeneratedSource': True, 'historicPatchesAppliedToCheckout': False}
        (out / 'effective-source-proof.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))

if __name__ == '__main__':
    main()
