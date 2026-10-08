#!/usr/bin/env python3
"""Reproduce exact V20.92 source for unchanged host gates and its historical before run."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from fetch_predecessor_objects import fetch_predecessor_objects

from continuity93_source_scope import (ROOT, BASE_COMMIT, LAYER_FOLDER, validate_checkout,
    previous_files, compilation_inputs, digest, EXTERNAL_PAYLOAD_FILES, committed_bytes)
from run_stability90_baseline_hosts import SOURCE_GATES, FUNCTION_GATES

HISTORIC_GATES = SOURCE_GATES + FUNCTION_GATES + ('test_stability90_evidence.py', 'test_continuity91_evidence.py', 'test_continuity92_evidence.py')


def predecessor_view(root, destination):
    root, destination = Path(root), Path(destination)
    final = validate_checkout(root)
    previous = previous_files(root)
    missing = set(final) - compilation_inputs(root)
    assert missing <= EXTERNAL_PAYLOAD_FILES
    for name in ('android-app', '.github', 'updates', 'docs', 'tools'):
        shutil.copytree(root / name, destination / name,
                        ignore=shutil.ignore_patterns('build', '.gradle', 'local.properties', '__pycache__'))
    shutil.copyfile(root / 'UI92_RUNTIME146_INPUTS.json', destination / 'UI92_RUNTIME146_INPUTS.json')
    subprocess.run(['git', 'init', '-q'], cwd=destination, check=True)
    fetch_predecessor_objects(root, destination, (BASE_COMMIT, 'd7295d1aec1ffb17a0076d4dc9a8a4122d467447', '9fe4931b6e37a1a7f7f78fd27835069e2b354aa7', '610a523ea524dff521b5d01e7aa78c421399594a',
                   '9769663edcda3a1c33f2eb2594a6b4fd9b4e2a6f', '1dbe40cc4e312c230524092dfe32a2c6cfb5f25d',
                   '2730954591393c1b81f747cef72adb8940c5a82c', 'ec211b9ad81748dee30fec43f0a25849559e4623'))
    patch = root / LAYER_FOLDER / 'runtime.patch'
    subprocess.run(['git', 'apply', '--check', '-R', str(patch)], cwd=destination, check=True)
    subprocess.run(['git', 'apply', '-R', str(patch)], cwd=destination, check=True)
    actual = compilation_inputs(destination)
    assert actual == set(previous) - missing, 'Reversed V20.93 input inventory differs'
    assert all(digest(destination / name) == previous[name] for name in actual), 'V20.93 reversal did not reproduce exact V20.92 source'
    shutil.rmtree(destination / LAYER_FOLDER)
    for name in ('.github/workflows/v2092-build.yml', '.github/scripts/prepare_new_ui_source.py',
                 'tools/qa/test_root_autostart.py', 'tools/qa/test_root_health.py',
                 'tools/qa/test_network_recovery_stress.py'):
        (destination / name).write_bytes(committed_bytes(name, root))
    (destination / 'tools/qa/test_root_safety93.py').unlink(missing_ok=True)
    # Only this disposable view identifies itself as the actual pinned V20.92
    # commit. The current V20.93 source and its commit remain untouched.
    subprocess.run(['git', 'update-ref', 'HEAD', BASE_COMMIT], cwd=destination, check=True)
    from continuity92_source_scope import validate_checkout as validate_predecessor
    assert validate_predecessor(destination) == previous
    return {'exactNewLayerReversed': True, 'verifiedPredecessorInputs': len(previous),
            'missingUnchangedNativeInputs': sorted(missing), 'currentCheckoutUnmodified': True,
            'baselineVersion': 2092, 'baselineCommit': BASE_COMMIT}


def run_historical_before(root, view, output):
    root, view, output = Path(root), Path(view), Path(output)
    assert not output.exists(), 'Preserve prior historical before evidence'
    final = validate_checkout(root, allow_external_missing=False)
    output.mkdir(parents=True)
    # Preserve each original driver and its own actual source attribution.
    # The nested V20.91 run belongs to the V20.92 predecessor view, not V20.93.
    subprocess.run(['python3', str(view / '.github/scripts/run_continuity92_baseline_hosts.py'),
                    '--historical-before-output', str(output / 'historical-before91')], cwd=view, check=True)
    subprocess.run(['python3', str(view / '.github/scripts/run_continuity92_before_comparison.py'),
                    '--output', str(output / 'historical-before92')], cwd=view, check=True)
    original = json.loads((output / 'historical-before92/report.json').read_text())
    assert original['observedRepositoryCommit'] == BASE_COMMIT
    assert original['result'] == 'EXPECTED_REGRESSION_REPRODUCED' and original['currentCheckoutUnmodified'] is True
    assert validate_checkout(root, allow_external_missing=False) == final
    attribution = {'result': 'PASS', 'evidenceSourceCommit': BASE_COMMIT, 'evidenceVersion': 2092,
                   'candidateCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root).decode().strip(),
                   'candidateSourceUnaffected': True, 'historicalOnly': True, 'usedAsCurrent93Acceptance': False,
                   'nestedHistoricalEvidence': {'historical-before91': 'd7295d1aec1ffb17a0076d4dc9a8a4122d467447'},
                   'limitations': 'Historical V20.91 and V20.92 before proof; current V20.93 HTTP/UI results and sustained traffic are separately validated.'}
    (output / 'historical-attribution.json').write_text(json.dumps(attribution, indent=2) + '\n')
    return attribution


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--gate', action='append', choices=HISTORIC_GATES)
    parser.add_argument('--historical-before-output', type=Path)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-v2093-predecessor-') as directory:
        view = Path(directory)
        report = predecessor_view(ROOT, view)
        if args.historical_before_output:
            report['historicalBefore'] = run_historical_before(ROOT, view, args.historical_before_output.resolve())
        else:
            for gate in args.gate or HISTORIC_GATES:
                if gate == 'test_continuity92_evidence.py':
                    subprocess.run(['python3', str(view / '.github/scripts' / gate)], cwd=view, check=True)
                else:
                    subprocess.run(['python3', str(view / '.github/scripts/run_continuity92_baseline_hosts.py'), '--gate', gate], cwd=view, check=True)
        print(json.dumps(report))
