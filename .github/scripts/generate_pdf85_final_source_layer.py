#!/usr/bin/env python3
"""Generate a four-file presentation patch without changing any prior layer."""
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

from pdf85_final_source_scope import (
    ALLOWED, BASE_COMMIT, HISTORICAL, ROOT, TRANSFORMS_PATH, digest,
    previous_files, validate_layer, validate_presentation,
)
from tools_intake_source_scope import EXTERNAL_PAYLOAD_FILES, compilation_inputs


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def main():
    previous = previous_files(ROOT)
    assert git('rev-parse', 'HEAD').decode().strip() == BASE_COMMIT
    changed = set(git('diff', '--name-only', 'HEAD', '--', 'android-app').decode().splitlines())
    assert changed == ALLOWED
    for name in ALLOWED:
        validate_presentation(git('show', BASE_COMMIT + ':' + name).decode(), (ROOT / name).read_text(), name)
    missing = set(previous) - compilation_inputs(ROOT)
    assert missing <= EXTERNAL_PAYLOAD_FILES
    for name, sha in previous.items():
        if name not in ALLOWED and name not in missing:
            assert digest(ROOT / name) == sha, 'Frozen input changed: ' + name
    destination = ROOT / 'updates/v2085-pdf-final-geometry'
    destination.mkdir(exist_ok=True)
    patch = git('diff', '--binary', '--no-ext-diff', '--no-renames', 'HEAD', '--', *sorted(ALLOWED))
    patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
    (destination / 'runtime.patch').write_bytes(patch)
    layer = {
        'schema': 1, 'baseCommit': BASE_COMMIT,
        'baseAndroidAppTree': git('rev-parse', BASE_COMMIT + ':android-app').decode().strip(),
        'versionCode': 2085, 'versionName': '0.12.15-v20-pdf',
        'expectedUnitTests': 487, 'expectedTestXmlFiles': 51,
        'historicalFilesSha256': HISTORICAL, 'transformsSha256': digest(TRANSFORMS_PATH),
        'patchSha256': digest(destination / 'runtime.patch'),
        'changedOrAddedFiles': {name: digest(ROOT / name) for name in sorted(ALLOWED)},
        'frozenFiles': {name: sha for name, sha in sorted(previous.items()) if name not in ALLOWED},
        **{name: True for name in ('homeAndPanelUnchanged', 'runtimePayloadsUnchanged',
            'dependenciesUnchanged', 'manifestAndPermissionsUnchanged', 'signingUnchanged',
            'allExistingTestsPreserved')},
    }
    validate_layer(layer, previous)
    final = {**previous, **layer['changedOrAddedFiles']}
    with tempfile.TemporaryDirectory(prefix='hetu-final-pdf-') as directory:
        work = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(git('archive', BASE_COMMIT, '--', 'android-app'))) as archive:
            archive.extractall(work, filter='data')
        assert compilation_inputs(work) == set(previous) - EXTERNAL_PAYLOAD_FILES
        subprocess.run(['git', 'apply', '--check', str(destination / 'runtime.patch')], cwd=work, check=True)
        subprocess.run(['git', 'apply', str(destination / 'runtime.patch')], cwd=work, check=True)
        for name, sha in final.items():
            if name not in EXTERNAL_PAYLOAD_FILES:
                assert digest(work / name) == sha and digest(ROOT / name) == sha, name
    (destination / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
    proof = {'baseCommit': BASE_COMMIT, 'requiredEffectiveInputs': len(final),
        'locallyVerifiedCommittedInputs': len(final) - len(EXTERNAL_PAYLOAD_FILES),
        'locallyMissingUnchangedPayloadFiles': sorted(missing),
        'completeEffectiveSourceVerified': not missing,
        'changedPresentationInputs': len(ALLOWED), 'frozenInputs': len(layer['frozenFiles']),
        'fullFourSourceExactTransformsVerified': True, 'allTenPriorLayerFilesUnchanged': True,
        'expectedUnitTests': 487, 'expectedTestXmlFiles': 51, 'ownCompiledAcceptance': 'pending',
        'patchSha256': layer['patchSha256']}
    (ROOT / 'docs/qa/20261005-v2085-final-pdf-source-candidate.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
