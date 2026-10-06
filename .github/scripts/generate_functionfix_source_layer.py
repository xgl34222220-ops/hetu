#!/usr/bin/env python3
"""Generate the bounded function layer in disposable fixtures after source stop."""
import argparse
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from functionfix_source_scope import (
    ROOT, BASE_COMMIT, BASE_ANDROID_APP_TREE, VERSION_CODE, VERSION_NAME, HISTORICAL,
    BASE_TESTS, BASE_TEST_XML_FILES, PREVIOUS_INPUTS_SHA256, PREVIOUS_PATCH_SHA256,
    TEST_PLAN_PATH, ROOT_SCRIPT, AUTOSTART_SCRIPT, PROTECTED_FLAGS, EXTERNAL_PAYLOAD_FILES,
    RUNTIME_INPUTS_SHA256,
    compilation_inputs, digest, previous_files, test_plan, validate_scope, validate_layer,
    validate_source_transforms, validate_new_tests,
)
from tools_intake_source_scope import validate_checkout_matches_generated


def git(*args, cwd=ROOT):
    return subprocess.check_output(['git', *args], cwd=cwd)


def generate(root, output):
    root, output = Path(root).resolve(), Path(output).resolve()
    assert all(output != (root / name).parent and (root / name).parent not in output.parents
               for name in HISTORICAL if name.startswith('updates/')), 'Historic source layer is immutable'
    previous, plan = previous_files(root), test_plan()
    assert git('rev-parse', BASE_COMMIT + ':android-app', cwd=root).decode().strip() == BASE_ANDROID_APP_TREE
    actual = compilation_inputs(root)
    missing = previous.keys() - actual
    assert missing <= EXTERNAL_PAYLOAD_FILES, 'Existing source input missing: ' + str(sorted(missing - EXTERNAL_PAYLOAD_FILES))
    changes = {name for name in actual if name not in previous or digest(root / name) != previous[name]}
    validate_scope(changes, plan)
    archive_bytes = git('archive', BASE_COMMIT, '--', 'android-app', cwd=root)
    with tempfile.TemporaryDirectory(prefix='hetu-functionfix-') as directory:
        fixture = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as archive:
            archive.extractall(fixture, filter='data')
        assert compilation_inputs(fixture) == previous.keys() - EXTERNAL_PAYLOAD_FILES
        for name in compilation_inputs(fixture):
            assert digest(fixture / name) == previous[name], 'Function baseline Git/source mismatch: ' + name
        for name in sorted(EXTERNAL_PAYLOAD_FILES - missing):
            assert digest(root / name) == previous[name], 'Native payload changed: ' + name
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
        validate_source_transforms(fixture, root)
        validate_new_tests(root, plan)
        git('init', '-q', cwd=fixture)
        git('add', 'android-app', cwd=fixture)
        reproduced = fixture / 'reproduced'
        shutil.copytree(fixture / 'android-app', reproduced / 'android-app')
        git('init', '-q', cwd=reproduced)
        for name in sorted(changes):
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, target)
        git('add', '--intent-to-add', 'android-app', cwd=fixture)
        patch = git('diff', '--binary', '--no-ext-diff', '--no-renames', '--', 'android-app', cwd=fixture)
        patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
        temporary_patch = fixture / 'runtime.patch'
        temporary_patch.write_bytes(patch)
        root_sha = digest(root / ROOT_SCRIPT)
        layer = {
            'schema': 1, 'baseCommit': BASE_COMMIT, 'baseAndroidAppTree': BASE_ANDROID_APP_TREE,
            'versionCode': VERSION_CODE, 'versionName': VERSION_NAME,
            'previousInputsSha256': PREVIOUS_INPUTS_SHA256, 'previousPatchSha256': PREVIOUS_PATCH_SHA256,
            'historicalFilesSha256': HISTORICAL, 'patchSha256': digest(temporary_patch),
            'testPlanSha256': digest(TEST_PLAN_PATH), 'baselineUnitTests': BASE_TESTS,
            'baselineTestXmlFiles': BASE_TEST_XML_FILES,
            'newTestCounts': {name: item['tests'] for name, item in plan.items()},
            'newTestFiles': {name: item['source'] for name, item in plan.items()},
            'expectedUnitTests': BASE_TESTS + sum(item['tests'] for item in plan.values()),
            'expectedTestXmlFiles': BASE_TEST_XML_FILES + len(plan),
            'changedOrAddedFiles': {name: digest(root / name) for name in sorted(changes)},
            'frozenFiles': {name: sha for name, sha in sorted(previous.items()) if name not in changes},
            'externalPayloadFiles': {name: previous[name] for name in sorted(EXTERNAL_PAYLOAD_FILES)},
            'runtimePayloadCount': 23, 'rootScriptSha256': root_sha,
            'originalRuntimeInputsSha256': RUNTIME_INPUTS_SHA256,
            'autostartScriptSha256': previous[AUTOSTART_SCRIPT],
            'runtimePayloadChanges': ({'assets/hetu-root.sh': {'before': previous[ROOT_SCRIPT], 'after': root_sha}}
                                     if ROOT_SCRIPT in changes else {}),
            **{name: True for name in PROTECTED_FLAGS},
        }
        validate_layer(layer, previous, root)
        git('apply', '--check', str(temporary_patch), cwd=reproduced)
        git('apply', str(temporary_patch), cwd=reproduced)
        final = {**previous, **layer['changedOrAddedFiles']}
        validate_checkout_matches_generated(reproduced, root, {name: sha for name, sha in final.items() if name not in missing})
        output.mkdir(parents=True, exist_ok=True)
        (output / 'runtime.patch').write_bytes(patch)
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        return {'baseCommit': BASE_COMMIT, 'changedOrAddedFiles': len(changes),
                'frozenInputs': len(layer['frozenFiles']), 'requiredEffectiveInputs': len(final),
                'locallyVerifiedInputs': len(final) - len(missing),
                'locallyMissingUnchangedPayloadFiles': sorted(missing),
                'completeEffectiveSourceVerified': not missing,
                'expectedUnitTests': layer['expectedUnitTests'], 'expectedTestXmlFiles': layer['expectedTestXmlFiles'],
                'patchSha256': layer['patchSha256'], 'rootScriptSha256': root_sha, 'output': str(output)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=ROOT / 'updates/v2086-functionfix')
    args = parser.parse_args()
    print(json.dumps(generate(ROOT, args.output)))
