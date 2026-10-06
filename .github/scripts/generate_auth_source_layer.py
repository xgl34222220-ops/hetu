#!/usr/bin/env python3
"""Record the authentication delta from the exact, fully validated V20.83 source.

Only a temporary Git fixture is modified. Existing UI manifests and patches are
immutable inputs; Root assets, native payloads, permissions and dependencies are
outside this update's permitted scope.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from auth_source_scope import (BASE_ANDROID_APP_TREE, BASE_COMMIT, BASE_RUN,
                               BASE_TESTS, NEW_TEST_CLASSES, NEW_TEST_COUNTS, PREVIOUS_INPUTS_SHA256,
                               PREVIOUS_PATCH_SHA256, VERSION_CODE, VERSION_NAME,
                               validate_layer, validate_scope, validate_version_only)
from auth_source_scope import HISTORY_FIXTURE, validate_history_fixture_only, SAFETY_FIXTURE, validate_safety_diagnostics_only
from ui_source_scope import PREVIOUS_INPUTS_SHA256 as UI82_INPUTS_SHA256

ROOT = Path(__file__).resolve().parents[2]
UI82_INPUTS = ROOT / 'updates/v2082-new-ui/inputs.json'
UI83_INPUTS = ROOT / 'updates/v2083-ui-polish/inputs.json'
DEFAULT_DESTINATION = ROOT / 'updates/v2084-controller-auth'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(*args, cwd=ROOT):
    return subprocess.run(['git', *args], cwd=cwd, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def compilation_inputs():
    names = git('ls-files', '--cached', '--others', '--exclude-standard', '--',
                'android-app').decode().splitlines()
    return {name for name in names if (ROOT / name).is_file()
            and '/build/' not in name and '/.gradle/' not in name
            and Path(name).name != 'local.properties'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=DEFAULT_DESTINATION)
    parser.add_argument('--expected-tests', type=int, required=True,
                        help='Exact final JUnit total, including the unchanged 392 baseline tests')
    args = parser.parse_args()
    assert digest(UI82_INPUTS.read_bytes()) == UI82_INPUTS_SHA256
    spec = json.loads(UI82_INPUTS.read_text())
    assert digest((UI82_INPUTS.with_name('integration.patch')).read_bytes()) == spec['patchSha256']
    assert digest(UI83_INPUTS.read_bytes()) == PREVIOUS_INPUTS_SHA256, 'Verified V20.83 inputs changed'
    previous = json.loads(UI83_INPUTS.read_text())
    assert digest(UI83_INPUTS.with_name('ui.patch').read_bytes()) == PREVIOUS_PATCH_SHA256
    assert previous['patchSha256'] == PREVIOUS_PATCH_SHA256
    previous_files = {**spec['baselineFiles'], **spec['integratedFiles'],
                      **previous['changedOrAddedFiles']}
    assert git('rev-parse', BASE_COMMIT + ':android-app').decode().strip() == BASE_ANDROID_APP_TREE
    names = compilation_inputs()
    assert names, 'No compilation inputs found'
    archive = git('archive', BASE_COMMIT, '--', 'android-app')
    with tempfile.TemporaryDirectory(prefix='hetu-auth-layer-') as directory:
        fixture = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(fixture, filter='data')
        baseline_names = {str(p.relative_to(fixture)) for p in
                          (fixture / 'android-app').rglob('*') if p.is_file()}
        assert baseline_names <= previous_files.keys(), 'Unrecorded verified baseline input'
        for name in baseline_names:
            assert digest((fixture / name).read_bytes()) == previous_files[name], name
        missing = baseline_names - names
        assert not missing, 'Authentication update removes an existing input: ' + str(sorted(missing))
        changes = {name for name in names if name not in previous_files
                   or digest((ROOT / name).read_bytes()) != previous_files[name]}
        validate_scope(changes)
        validate_version_only((fixture / 'android-app/app/build.gradle.kts').read_text(),
                              (ROOT / 'android-app/app/build.gradle.kts').read_text())
        if HISTORY_FIXTURE in changes:
            validate_history_fixture_only((fixture / HISTORY_FIXTURE).read_text(),
                                          (ROOT / HISTORY_FIXTURE).read_text())
        if SAFETY_FIXTURE in changes:
            validate_safety_diagnostics_only((fixture / SAFETY_FIXTURE).read_text(),
                                            (ROOT / SAFETY_FIXTURE).read_text())
        layer = {
            'schema': 1, 'baseCommit': BASE_COMMIT, 'baseRun': BASE_RUN,
            'baseAndroidAppTree': BASE_ANDROID_APP_TREE,
            'previousInputsSha256': PREVIOUS_INPUTS_SHA256,
            'previousPatchSha256': PREVIOUS_PATCH_SHA256,
            'versionCode': VERSION_CODE, 'versionName': VERSION_NAME,
            'baselineUnitTests': BASE_TESTS, 'expectedUnitTests': args.expected_tests,
            'newTestClasses': list(NEW_TEST_CLASSES),
            'newTestCounts': NEW_TEST_COUNTS,
            'changedOrAddedFiles': {name: digest((ROOT / name).read_bytes())
                                   for name in sorted(changes)},
            'runtimePayloadsUnchanged': True, 'dependenciesUnchanged': True,
            'manifestAndPermissionsUnchanged': True,
            'unchangedOutsideAuthScope': True, 'reproducedSourceMatchesCheckout': True,
        }
        validate_layer(layer)
        git('init', '-q', cwd=fixture)
        git('add', 'android-app', cwd=fixture)
        for name in sorted(changes):
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        git('add', '--intent-to-add', 'android-app', cwd=fixture)
        patch = git('diff', '--binary', '--', 'android-app', cwd=fixture)
        # This changes only an accepted blank context marker, never source bytes.
        patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
        assert patch, 'No V20.84 authentication changes to record'
        reproduced = fixture / 'reproduced'
        reproduced.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(reproduced, filter='data')
        git('init', '-q', cwd=reproduced)
        temporary_patch = fixture / 'runtime.patch'
        temporary_patch.write_bytes(patch)
        git('apply', '--check', str(temporary_patch), cwd=reproduced)
        git('apply', str(temporary_patch), cwd=reproduced)
        for name in baseline_names | changes:
            expected = layer['changedOrAddedFiles'].get(name, previous_files.get(name))
            assert digest((reproduced / name).read_bytes()) == expected, name
        layer['patchSha256'] = digest(patch)
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        (output / 'runtime.patch').write_bytes(patch)
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        print(json.dumps({'baseCommit': BASE_COMMIT, 'baseRun': BASE_RUN,
                          'changedOrAddedFiles': len(changes),
                          'expectedUnitTests': args.expected_tests,
                          'patchSha256': layer['patchSha256'], 'output': str(output)}))


if __name__ == '__main__':
    main()
