#!/usr/bin/env python3
"""Record only the authorized V20.93 delta over the pinned predecessor (CI outcome tracked independently)."""
import argparse
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from continuity93_source_scope import (ROOT, BASE_COMMIT, ORIGINAL_TEST_HEAD, VERSION_CODE, VERSION_NAME,
    BUILD_FILE, ROOT_SCRIPT, AUTOSTART_SCRIPT, PROTECTED_FLAGS, LAYER_FOLDER, PRIOR_FOLDER,
    previous_files, predecessor_layer, historical_files, selected_tests, validate_version, validate_layer,
    compilation_inputs, digest, EXTERNAL_PAYLOAD_FILES, evidence_sources,
    validate_revision, validate_revision_test, REVISION_FILE, REVISION_TEST, other_payloads, host_fixture_deltas, recovered_artifacts)


def generate(root=ROOT, output=None):
    root = Path(root)
    output = root / LAYER_FOLDER if output is None else Path(output)
    predecessor, previous = predecessor_layer(root)
    actual = compilation_inputs(root)
    missing = set(previous) - actual
    assert missing <= EXTERNAL_PAYLOAD_FILES
    changes = {name for name in actual if name not in previous or digest(root / name) != previous[name]}
    final_tests = selected_tests(root, extra_sources=[name for name in changes if '/src/test/' in name])
    with tempfile.TemporaryDirectory(prefix='hetu-v2093-layer-') as directory:
        fixture = Path(directory)
        archive = subprocess.check_output(['git', 'archive', BASE_COMMIT, '--', 'android-app'], cwd=root)
        with tarfile.open(fileobj=io.BytesIO(archive)) as z:
            z.extractall(fixture, filter='data')
        assert compilation_inputs(fixture) == set(previous) - EXTERNAL_PAYLOAD_FILES
        assert all(digest(fixture / name) == previous[name] for name in compilation_inputs(fixture)), 'Pinned predecessor source differs'
        for name in EXTERNAL_PAYLOAD_FILES - missing:
            path = fixture / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, path)
        validate_version((fixture / BUILD_FILE).read_text(), (root / BUILD_FILE).read_text())
        validate_revision((fixture / REVISION_FILE).read_text(), (root / REVISION_FILE).read_text())
        validate_revision_test((fixture / REVISION_TEST).read_text(), (root / REVISION_TEST).read_text())
        subprocess.run(['git', 'init', '-q'], cwd=fixture, check=True)
        subprocess.run(['git', 'add', 'android-app'], cwd=fixture, check=True)
        for name in sorted(changes):
            path = fixture / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / name, path)
        subprocess.run(['git', 'add', '--intent-to-add', 'android-app'], cwd=fixture, check=True)
        patch = subprocess.check_output(['git', 'diff', '--binary', '--no-ext-diff', '--no-renames', '--', 'android-app'], cwd=fixture)
        patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
        temporary = fixture / 'runtime.patch'
        temporary.write_bytes(patch)
        layer = {'schema': 1, 'baseCommit': BASE_COMMIT,
            'baseAndroidAppTree': subprocess.check_output(['git', 'rev-parse', BASE_COMMIT + ':android-app'], cwd=root).decode().strip(),
            'originalTestHead': ORIGINAL_TEST_HEAD, 'versionCode': VERSION_CODE, 'versionName': VERSION_NAME,
            'previousInputsSha256': digest(root / PRIOR_FOLDER / 'inputs.json'),
            'previousPatchSha256': digest(root / PRIOR_FOLDER / 'runtime.patch'),
            'historicalFilesSha256': historical_files(root), 'patchSha256': digest(temporary),
            'authorizedFiles': sorted(changes), 'changedOrAddedFiles': {name: digest(root / name) for name in sorted(changes)},
            'frozenFiles': {name: value for name, value in sorted(previous.items()) if name not in changes},
            'externalPayloadFiles': {name: previous[name] for name in sorted(EXTERNAL_PAYLOAD_FILES)},
            'runtimePayloadCount': 23, 'rootScriptSha256': digest(root / ROOT_SCRIPT), 'autostartScriptSha256': digest(root / AUTOSTART_SCRIPT),
            'authorizedRootPayloadDeltas': {name: {'before': previous[name], 'after': digest(root / name)}
                                          for name in (ROOT_SCRIPT, AUTOSTART_SCRIPT)},
            'runtimeRevision': 154, 'predecessorRuntimeRevision': 153,
            'other21RuntimePayloadFiles': other_payloads(previous, root),
            'authorizedHostFixtureDeltas': host_fixture_deltas(root),
            'evidenceSourcesSha256': evidence_sources(root),
            'recoveredArtifactHashes': recovered_artifacts(root),
            'recoveryPipelineProvenance': 'reimplemented after partial source recovery; historical final pipeline unavailable',
            'baselineUnitTests': 644, 'baselineTestXmlFiles': 67,
            'baselineUnitTestMethods': predecessor['finalUnitTestMethods'], 'finalUnitTestMethods': final_tests,
            'expectedUnitTests': sum(len(item['methods']) for item in final_tests.values()), 'expectedTestXmlFiles': len(final_tests),
            **{name: True for name in PROTECTED_FLAGS}}
        validate_layer(layer, previous, root)
        output.mkdir(parents=True, exist_ok=True)
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        (output / 'runtime.patch').write_bytes(patch)
    return {'requiredInputs': len(previous.keys() | changes), 'localMissingNativeInputs': sorted(missing),
            'expectedUnitTests': layer['expectedUnitTests'], 'expectedTestXmlFiles': layer['expectedTestXmlFiles'],
            'changedOrAddedFiles': sorted(changes), 'patchSha256': layer['patchSha256'], 'output': str(output)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    print(json.dumps(generate(output=parser.parse_args().output)))
