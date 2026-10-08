#!/usr/bin/env python3
"""Run unmodified historical gates against their own exact predecessor source."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from stability90_source_scope import ROOT, predecessor_layers, validate_checkout, digest
from tools_intake_source_scope import compilation_inputs, EXTERNAL_PAYLOAD_FILES

SOURCE_GATES = ('test_ui_source_scope.py', 'test_pdf85_source_scope.py', 'test_tools_intake_source_scope.py',
                'test_pdf85_final_source_scope.py', 'test_auth_source_scope.py')
FUNCTION_GATES = ('test_functionfix_source_scope.py', 'test_functionfix_test_results.py', 'test_functionfix_workflow.py')
OBJECTS = ('1dbe40cc4e312c230524092dfe32a2c6cfb5f25d',
           '2730954591393c1b81f747cef72adb8940c5a82c', 'ec211b9ad81748dee30fec43f0a25849559e4623')


def predecessor_view(root, destination):
    root, destination = Path(root), Path(destination)
    final = validate_checkout(root)
    layers = predecessor_layers(root)
    missing = set(final) - compilation_inputs(root)
    assert missing <= EXTERNAL_PAYLOAD_FILES
    shutil.copytree(root / 'android-app', destination / 'android-app',
                    ignore=shutil.ignore_patterns('build', '.gradle', 'local.properties'))
    shutil.copytree(root / '.github', destination / '.github', ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copytree(root / 'updates', destination / 'updates')
    shutil.copyfile(root / 'UI92_RUNTIME146_INPUTS.json', destination / 'UI92_RUNTIME146_INPUTS.json')
    subprocess.run(['git', 'init', '-q'], cwd=destination, check=True)
    for commit in OBJECTS:
        subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1', str(root), commit], cwd=destination, check=True)
    latest = root / 'updates/v2090-stability-glass/inputs.json'
    layer = json.loads(latest.read_text())
    patches = [(latest.with_name('runtime.patch'), {**layers[-1][3]})]
    patches += [(patch, previous) for _, patch, previous, _ in reversed(layers[1:])]
    for patch, expected in patches:
        subprocess.run(['git', 'apply', '--check', '-R', str(patch)], cwd=destination, check=True)
        subprocess.run(['git', 'apply', '-R', str(patch)], cwd=destination, check=True)
        actual = compilation_inputs(destination)
        assert actual == set(expected) - missing, 'Reverse layer changed source inventory'
        assert all(digest(destination / n) == expected[n] for n in actual), 'Reverse layer did not reproduce exact predecessor'
    # The original v2086 wrapper deliberately knows only the original layer.
    # Remove later manifests only inside this disposable predecessor view.
    for folder in ('v2087-home-panel-refactor', 'v2088-ui-refactor', 'v2089-runtime153', 'v2090-stability-glass'):
        shutil.rmtree(destination / 'updates' / folder)
    return {'exactLaterLayersReversed': 4, 'v2086Inputs': len(layers[0][3]),
            'missingUnchangedNativeInputs': sorted(missing), 'currentCheckoutUnmodified': True,
            'historicalGatesRunOnOwnSource': True, 'baselineVersion': 2086, 'baselineRuntimeRevision': 152}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--gate', action='append', choices=SOURCE_GATES + FUNCTION_GATES)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-v2090-baseline-') as d:
        view = Path(d)
        report = predecessor_view(ROOT, view)
        for gate in args.gate or SOURCE_GATES + FUNCTION_GATES:
            if gate in SOURCE_GATES:
                subprocess.run(['python3', str(view / '.github/scripts/run_functionfix_baseline_hosts.py'), '--gate', gate], cwd=view, check=True)
            else:
                subprocess.run(['python3', str(view / '.github/scripts' / gate)], cwd=view, check=True)
        print(json.dumps(report))
