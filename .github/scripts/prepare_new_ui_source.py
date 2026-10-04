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

from ui_source_scope import BASE_COMMIT, BASE_RUN, PREVIOUS_INPUTS_SHA256, validate_scope, validate_version_only

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / 'updates/v2082-new-ui/inputs.json'
LAYER_INPUTS = ROOT / 'updates/v2083-ui-polish/inputs.json'

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
    assert digest(INPUTS) == PREVIOUS_INPUTS_SHA256, 'Verified V20.82 inputs changed'
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
        final_files = {**spec['baselineFiles'], **spec['integratedFiles']}
        layer_report = None
        if LAYER_INPUTS.exists():
            layer = json.loads(LAYER_INPUTS.read_text())
            assert layer['schema'] == 1
            assert layer['baseCommit'] == BASE_COMMIT and layer['baseRun'] == BASE_RUN
            assert layer['previousInputsSha256'] == digest(INPUTS), 'V20.82 inputs changed'
            assert layer['previousPatchSha256'] == spec['patchSha256']
            assert layer['versionCode'] == 2083 and layer['versionName'] == '0.12.13-v20-ui'
            changes = layer['changedOrAddedFiles']
            validate_scope(changes)
            ui_patch = LAYER_INPUTS.with_name('ui.patch')
            assert digest(ui_patch) == layer['patchSha256'], 'Presentation patch changed without input update'
            previous_gradle = (work / 'android-app/app/build.gradle.kts').read_text()
            apply(ui_patch, work)
            validate_version_only(previous_gradle, (work / 'android-app/app/build.gradle.kts').read_text())
            for name, sha in changes.items():
                assert digest(work / name) == sha, 'Presentation source mismatch: ' + name
                assert sha != final_files.get(name), 'Unchanged input recorded as a delta: ' + name
            final_files.update(changes)
            layer_report = {'baseCommit': layer['baseCommit'], 'baseRun': layer['baseRun'],
                            'patchSha256': layer['patchSha256'], 'changedOrAddedFiles': len(changes),
                            'protectedRuntimeUnchanged': True}
        for name, sha in final_files.items():
            assert digest(work / name) == sha, 'Generated source input regressed: ' + name
            assert digest(ROOT / name) == sha, 'Checkout differs from generated source: ' + name
        expected = set(final_files)
        actual = {str(p.relative_to(ROOT)) for p in (ROOT / 'android-app').rglob('*') if p.is_file()
                  and '/build/' not in str(p.relative_to(ROOT)) and '/.gradle/' not in str(p.relative_to(ROOT))
                  and p.name != 'local.properties'}
        assert actual <= expected, 'Unrecorded compilation input: ' + str(actual - expected)
        report = {'baseCommit': spec['baseCommit'], 'baseRun': spec['baseRun'],
                  'archiveSha256': spec['archiveSha256'], 'integrationPatchSha256': spec['patchSha256'],
                  'baselineFiles': len(spec['baselineFiles']), 'changedOrAddedFiles': len(spec['integratedFiles']),
                  'checkoutMatchesGeneratedSource': True, 'historicPatchesAppliedToCheckout': False}
        if layer_report is not None:
            report['presentationLayer'] = layer_report
        (out / 'effective-source-proof.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report))

if __name__ == '__main__':
    main()
