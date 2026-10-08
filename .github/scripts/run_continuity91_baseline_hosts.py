#!/usr/bin/env python3
"""Reverse only the new delta in a disposable view, then run unchanged historic gates."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from fetch_predecessor_objects import fetch_predecessor_objects

from continuity91_source_scope import (ROOT, BASE_COMMIT, LAYER_FOLDER, validate_checkout,
    previous_files, compilation_inputs, digest, EXTERNAL_PAYLOAD_FILES, committed_bytes)
from run_stability90_baseline_hosts import SOURCE_GATES, FUNCTION_GATES


def predecessor_view(root, destination):
    root, destination = Path(root), Path(destination)
    final = validate_checkout(root)
    previous = previous_files(root)
    missing = set(final) - compilation_inputs(root)
    assert missing <= EXTERNAL_PAYLOAD_FILES
    for name in ('android-app', '.github', 'updates'):
        shutil.copytree(root / name, destination / name, ignore=shutil.ignore_patterns('build', '.gradle', 'local.properties', '__pycache__'))
    shutil.copyfile(root / 'UI92_RUNTIME146_INPUTS.json', destination / 'UI92_RUNTIME146_INPUTS.json')
    subprocess.run(['git', 'init', '-q'], cwd=destination, check=True)
    fetch_predecessor_objects(root, destination, (BASE_COMMIT, '610a523ea524dff521b5d01e7aa78c421399594a', '9769663edcda3a1c33f2eb2594a6b4fd9b4e2a6f',
                   '1dbe40cc4e312c230524092dfe32a2c6cfb5f25d', '2730954591393c1b81f747cef72adb8940c5a82c', 'ec211b9ad81748dee30fec43f0a25849559e4623'))
    patch = root / LAYER_FOLDER / 'runtime.patch'
    subprocess.run(['git', 'apply', '--check', '-R', str(patch)], cwd=destination, check=True)
    subprocess.run(['git', 'apply', '-R', str(patch)], cwd=destination, check=True)
    actual = compilation_inputs(destination)
    assert actual == set(previous) - missing, 'Reversed V20.91 input inventory differs'
    assert all(digest(destination / name) == previous[name] for name in actual), 'Reversal did not reproduce V20.90 exact source'
    shutil.rmtree(destination / LAYER_FOLDER)
    # The old workflow is itself part of its historical gate's evidence. Its
    # current push ownership change must not alter that exact historic view.
    workflow = '.github/workflows/v2090-build.yml'
    (destination / workflow).write_bytes(committed_bytes(workflow, root))
    from stability90_source_scope import validate_checkout as validate_predecessor
    assert validate_predecessor(destination) == previous
    return {'exactNewLayerReversed': True, 'verifiedPredecessorInputs': len(previous),
            'missingUnchangedNativeInputs': sorted(missing), 'currentCheckoutUnmodified': True,
            'baselineVersion': 2090, 'baselineCommit': BASE_COMMIT}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--gate', action='append', choices=SOURCE_GATES + FUNCTION_GATES + ('test_stability90_evidence.py',))
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-v2091-predecessor-') as directory:
        view = Path(directory)
        report = predecessor_view(ROOT, view)
        for gate in args.gate or SOURCE_GATES + FUNCTION_GATES + ('test_stability90_evidence.py',):
            if gate == 'test_stability90_evidence.py':
                subprocess.run(['python3', str(view / '.github/scripts' / gate)], cwd=view, check=True)
            else:
                subprocess.run(['python3', str(view / '.github/scripts/run_stability90_baseline_hosts.py'), '--gate', gate], cwd=view, check=True)
        print(json.dumps(report))
