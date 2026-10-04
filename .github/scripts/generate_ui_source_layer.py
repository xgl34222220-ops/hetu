#!/usr/bin/env python3
"""Record the UI delta from verified V20.82, without replaying runtime migrations.

Only a temporary git fixture is modified. The previous integration manifest and
patch are retained; production runtime, permissions, native assets and dependency
versions are outside this presentation patch's permitted source scope.
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

from ui_source_scope import BASE_COMMIT, BASE_RUN, PREVIOUS_INPUTS_SHA256, validate_scope, validate_version_only

ROOT = Path(__file__).resolve().parents[2]
PREVIOUS_INPUTS = ROOT / 'updates/v2082-new-ui/inputs.json'
DEFAULT_DESTINATION = ROOT / 'updates/v2083-ui-polish'


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
    args = parser.parse_args()
    assert digest(PREVIOUS_INPUTS.read_bytes()) == PREVIOUS_INPUTS_SHA256, 'Verified V20.82 inputs changed'
    spec = json.loads(PREVIOUS_INPUTS.read_text())
    previous_files = {**spec['baselineFiles'], **spec['integratedFiles']}
    previous_patch = ROOT / 'updates/v2082-new-ui/integration.patch'
    assert digest(previous_patch.read_bytes()) == spec['patchSha256']
    names = compilation_inputs()
    assert names, 'No compilation inputs found'
    archive = git('archive', BASE_COMMIT, '--', 'android-app')
    with tempfile.TemporaryDirectory(prefix='hetu-ui-layer-') as directory:
        fixture = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(fixture, filter='data')
        baseline_names = {str(p.relative_to(fixture)) for p in
                          (fixture / 'android-app').rglob('*') if p.is_file()}
        assert baseline_names <= previous_files.keys(), 'Unrecorded verified baseline input'
        for name in baseline_names:
            assert digest((fixture / name).read_bytes()) == previous_files[name], name
        missing = baseline_names - names
        assert not missing, 'UI update removes existing input: ' + str(sorted(missing))
        changes = {name for name in names if name not in previous_files
                   or digest((ROOT / name).read_bytes()) != previous_files[name]}
        validate_scope(changes)
        if 'android-app/app/build.gradle.kts' in changes:
            validate_version_only((fixture / 'android-app/app/build.gradle.kts').read_text(),
                                  (ROOT / 'android-app/app/build.gradle.kts').read_text())
        git('init', '-q', cwd=fixture)
        git('add', 'android-app', cwd=fixture)
        for name in sorted(changes):
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        git('add', '--intent-to-add', 'android-app', cwd=fixture)
        patch = git('diff', '--binary', '--', 'android-app', cwd=fixture)
        assert patch, 'No V20.83 presentation changes to record'
        reproduced = fixture / 'reproduced'
        reproduced.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive)) as source:
            source.extractall(reproduced, filter='data')
        git('init', '-q', cwd=reproduced)
        temporary_patch = fixture / 'ui.patch'
        temporary_patch.write_bytes(patch)
        git('apply', '--check', str(temporary_patch), cwd=reproduced)
        git('apply', str(temporary_patch), cwd=reproduced)
        for name in baseline_names | changes:
            expected = (digest((ROOT / name).read_bytes()) if name in changes
                        else previous_files[name])
            assert digest((reproduced / name).read_bytes()) == expected, name
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=True)
        (output / 'ui.patch').write_bytes(patch)
        layer = {
            'schema': 1, 'baseCommit': BASE_COMMIT, 'baseRun': BASE_RUN,
            'previousInputsSha256': digest(PREVIOUS_INPUTS.read_bytes()),
            'previousPatchSha256': spec['patchSha256'],
            'patchSha256': digest(patch),
            'versionCode': 2083, 'versionName': '0.12.13-v20-ui',
            'changedOrAddedFiles': {name: digest((ROOT / name).read_bytes())
                                   for name in sorted(changes)},
            'protectedRuntimeUnchanged': True, 'reproducedSourceMatchesCheckout': True,
        }
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        print(json.dumps({'baseCommit': BASE_COMMIT, 'baseRun': BASE_RUN,
                          'changedOrAddedFiles': len(changes),
                          'patchSha256': layer['patchSha256'], 'output': str(output)}))


if __name__ == '__main__':
    main()
