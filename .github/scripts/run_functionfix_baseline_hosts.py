#!/usr/bin/env python3
"""Reverse only the fully SHA-verified function layer in a disposable prior view."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

from functionfix_source_scope import ROOT, previous_files, validate_layer, validate_source_transforms, digest
from tools_intake_source_scope import EXTERNAL_PAYLOAD_FILES, compilation_inputs, validate_checkout_matches_generated

SOURCE_GATES = ('test_ui_source_scope.py', 'test_pdf85_source_scope.py',
                'test_tools_intake_source_scope.py', 'test_pdf85_final_source_scope.py',
                'test_auth_source_scope.py')
BASE_OBJECTS = ('2730954591393c1b81f747cef72adb8940c5a82c',
                'ec211b9ad81748dee30fec43f0a25849559e4623')


def predecessor_view(root, destination):
    root, destination = Path(root), Path(destination)
    previous = previous_files(root)
    layer_path = root / 'updates/v2086-functionfix/inputs.json'
    layer = json.loads(layer_path.read_text())
    validate_layer(layer, previous, root)
    patch = layer_path.with_name('runtime.patch')
    assert digest(patch) == layer['patchSha256']
    missing = previous.keys() - compilation_inputs(root)
    assert missing <= EXTERNAL_PAYLOAD_FILES
    final = {**previous, **layer['changedOrAddedFiles']}
    reverse_patches = [patch]
    # Newer presentation layers sit on top of the recorded function map. Their
    # deltas are legitimate current bytes; they are overlaid here and reversed
    # first so the disposable view still lands on the exact pre-function tree.
    homepanel_inputs = root / 'updates/v2087-home-panel-refactor/inputs.json'
    if homepanel_inputs.exists():
        from homepanel87_source_scope import validate_layer as validate_homepanel_layer
        homepanel = json.loads(homepanel_inputs.read_text())
        validate_homepanel_layer(homepanel, final)
        homepanel_patch = homepanel_inputs.with_name('ui.patch')
        assert digest(homepanel_patch) == homepanel['patchSha256'], \
            'Home/panel patch changed without input update'
        final = {**final, **homepanel['changedOrAddedFiles']}
        reverse_patches.insert(0, homepanel_patch)
    ui88_inputs = root / 'updates/v2088-ui-refactor/inputs.json'
    if ui88_inputs.exists():
        from ui88_source_scope import validate_layer as validate_ui88_layer
        ui88 = json.loads(ui88_inputs.read_text())
        validate_ui88_layer(ui88, final)
        ui88_patch = ui88_inputs.with_name('ui.patch')
        assert digest(ui88_patch) == ui88['patchSha256'], \
            'UI unification patch changed without input update'
        final = {**final, **ui88['changedOrAddedFiles']}
        reverse_patches.insert(0, ui88_patch)
    r153_inputs = root / 'updates/v2089-runtime153/inputs.json'
    if r153_inputs.exists():
        from runtime153_source_scope import validate_layer as validate_r153_layer
        r153 = json.loads(r153_inputs.read_text())
        validate_r153_layer(r153, final)
        r153_patch = r153_inputs.with_name('runtime.patch')
        assert digest(r153_patch) == r153['patchSha256'], \
            'r153 runtime patch changed without input update'
        final = {**final, **r153['changedOrAddedFiles']}
        reverse_patches.insert(0, r153_patch)
    # Every available current byte must match the recorded final map before
    # copying/reversing; this never hides unknown production changes.
    available_final = {name: sha for name, sha in final.items() if name not in missing}
    for name, sha in available_final.items():
        assert digest(root / name) == sha, 'Current function input differs from recorded layer: ' + name
    assert compilation_inputs(root) == set(available_final)
    shutil.copytree(root / 'android-app', destination / 'android-app',
                    ignore=shutil.ignore_patterns('build', '.gradle', 'local.properties'))
    shutil.copytree(root / '.github/scripts', destination / '.github/scripts', ignore=shutil.ignore_patterns('__pycache__'))
    shutil.copytree(root / 'updates', destination / 'updates')
    shutil.copyfile(root / 'UI92_RUNTIME146_INPUTS.json', destination / 'UI92_RUNTIME146_INPUTS.json')
    subprocess.run(['git', 'init', '-q'], cwd=destination, check=True)
    for commit in BASE_OBJECTS:
        subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1', str(root), commit], cwd=destination, check=True)
    for reverse_patch in reverse_patches:
        subprocess.run(['git', 'apply', '--check', '-R', str(reverse_patch)], cwd=destination, check=True)
        subprocess.run(['git', 'apply', '-R', str(reverse_patch)], cwd=destination, check=True)
    validate_source_transforms(destination, root)
    validate_checkout_matches_generated(destination, destination, {name: sha for name, sha in previous.items() if name not in missing})
    return {'baselineInputs': len(previous), 'locallyVerifiedBaselineInputs': len(previous) - len(missing),
            'missingUnchangedPayloadFiles': sorted(missing), 'exactFunctionPatchReversed': True,
            'currentCheckoutUnmodified': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--gate', choices=SOURCE_GATES, action='append')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='hetu-function-baseline-host-') as directory:
        view = Path(directory)
        report = predecessor_view(ROOT, view)
        for gate in args.gate or SOURCE_GATES:
            subprocess.run(['python3', str(view / '.github/scripts' / gate)], cwd=view, check=True)
        print(json.dumps(report))
