#!/usr/bin/env python3
"""Record the tools intake after the immutable V20.85 PDF layer.

This creates a new patch in a temporary fixture, validates the full SHA map,
and never rewrites the historic layers or applies migrations to the checkout.
"""
import argparse
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from tools_intake_source_scope import (
    BASE_COMMIT, BASE_ANDROID_APP_TREE, VERSION_CODE, VERSION_NAME,
    BASE_TESTS, EXPECTED_TESTS, BASE_TEST_XML_FILES, EXPECTED_TEST_XML_FILES,
    PREVIOUS_INPUTS_SHA256, PREVIOUS_PATCH_SHA256, HISTORICAL_FILES_SHA256,
    PROTECTED_FLAGS, NEW_TEST_COUNTS, EXTERNAL_PAYLOAD_FILES, compilation_inputs, digest,
    validate_layer, validate_scope, validate_new_source_files,
    validate_checkout_matches_generated, verified_previous_files,
)

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DESTINATION = ROOT / 'updates/v2085-tools-intake'


def git(*args, cwd=ROOT):
    return subprocess.run(['git', *args], cwd=cwd, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def generate(root, output):
    root = Path(root).resolve()
    output = Path(output).resolve()
    historical_directories = {(root / name).parent for name in HISTORICAL_FILES_SHA256}
    assert all(output != folder and folder not in output.parents for folder in historical_directories), 'Historic source layers are immutable'
    previous = verified_previous_files(root)
    assert git('rev-parse', BASE_COMMIT + ':android-app', cwd=root).decode().strip() == BASE_ANDROID_APP_TREE
    names = compilation_inputs(root)
    missing_payloads = set(previous) - names
    assert missing_payloads <= EXTERNAL_PAYLOAD_FILES, 'Existing source inputs missing: ' + str(sorted(missing_payloads - EXTERNAL_PAYLOAD_FILES))
    changes = {name for name in names if name not in previous or digest(root / name) != previous[name]}
    validate_scope(changes)
    archive = git('archive', BASE_COMMIT, '--', 'android-app', cwd=root)
    with tempfile.TemporaryDirectory(prefix='hetu-tools-intake-') as directory:
        fixture = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(fixture, filter='data')
        baseline_names = compilation_inputs(fixture)
        assert baseline_names == previous.keys() - EXTERNAL_PAYLOAD_FILES, 'Committed source input set differs from exact baseline'
        for name in baseline_names:
            assert digest(fixture / name) == previous[name], 'Committed intake baseline differs: ' + name
        # Native payloads are intentionally absent from Git.  Their full SHA
        # map remains frozen for CI reconstruction.  A local source-only proof
        # explicitly records any unavailable payloads and never calls it a
        # complete effective-source proof.
        for name in sorted(previous.keys() - baseline_names):
            if name in missing_payloads:
                continue
            assert digest(root / name) == previous[name], 'Unchanged external baseline payload differs: ' + name
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
        git('init', '-q', cwd=fixture)
        git('add', 'android-app', cwd=fixture)
        baseline = fixture / 'baseline'
        shutil.copytree(fixture / 'android-app', baseline / 'android-app')
        git('init', '-q', cwd=baseline)
        for name in sorted(changes):
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
        git('add', '--intent-to-add', 'android-app', cwd=fixture)
        patch = git('diff', '--binary', '--no-ext-diff', '--no-renames', '--', 'android-app', cwd=fixture)
        patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
        assert patch, 'No tools intake changes to record'
        temporary_patch = fixture / 'runtime.patch'
        temporary_patch.write_bytes(patch)
        layer = {
            'schema': 1, 'baseCommit': BASE_COMMIT, 'baseAndroidAppTree': BASE_ANDROID_APP_TREE,
            'previousInputsSha256': PREVIOUS_INPUTS_SHA256,
            'previousPatchSha256': PREVIOUS_PATCH_SHA256,
            'historicalFilesSha256': HISTORICAL_FILES_SHA256,
            'patchSha256': digest(temporary_patch),
            'versionCode': VERSION_CODE, 'versionName': VERSION_NAME,
            'baselineUnitTests': BASE_TESTS, 'expectedUnitTests': EXPECTED_TESTS,
            'baselineTestXmlFiles': BASE_TEST_XML_FILES, 'expectedTestXmlFiles': EXPECTED_TEST_XML_FILES,
            'newTestClasses': list(NEW_TEST_COUNTS), 'newTestCounts': NEW_TEST_COUNTS,
            'externalPayloadFiles': {name: previous[name] for name in sorted(EXTERNAL_PAYLOAD_FILES)},
            'changedOrAddedFiles': {name: digest(root / name) for name in sorted(changes)},
            'frozenFiles': {name: sha for name, sha in sorted(previous.items()) if name not in changes},
            **{name: True for name in PROTECTED_FLAGS},
        }
        validate_layer(layer, previous)
        validate_new_source_files(root, layer)
        git('apply', '--check', str(temporary_patch), cwd=baseline)
        git('apply', str(temporary_patch), cwd=baseline)
        final = {**previous, **layer['changedOrAddedFiles']}
        available_final = {name: sha for name, sha in final.items() if name not in missing_payloads}
        validate_checkout_matches_generated(baseline, root, available_final)
        output.mkdir(parents=True, exist_ok=True)
        (output / 'runtime.patch').write_bytes(patch)
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        return {'baseCommit': BASE_COMMIT, 'changedOrAddedFiles': len(changes),
                'frozenInputs': len(layer['frozenFiles']), 'requiredEffectiveInputs': len(final),
                'locallyVerifiedInputs': len(available_final),
                'locallyMissingUnchangedPayloadFiles': sorted(missing_payloads),
                'completeEffectiveSourceVerified': not missing_payloads,
                'expectedUnitTests': EXPECTED_TESTS, 'patchSha256': layer['patchSha256'],
                'output': str(output)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=DEFAULT_DESTINATION)
    args = parser.parse_args()
    print(json.dumps(generate(ROOT, args.output)))


if __name__ == '__main__':
    main()
