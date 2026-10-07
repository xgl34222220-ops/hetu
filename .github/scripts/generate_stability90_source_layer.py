#!/usr/bin/env python3
"""Generate the new layer only after all owners stop editing Android sources."""
import argparse
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile

from stability90_source_scope import (ROOT, BASE_COMMIT, ORIGINAL_TEST_HEAD, VERSION_CODE, VERSION_NAME,
    BUILD_FILE, ROOT_SCRIPT, AUTOSTART_SCRIPT, PROTECTED_FLAGS, previous_files, historical_files,
    selected_tests, validate_version, validate_layer, compilation_inputs, digest, EXTERNAL_PAYLOAD_FILES)


def generate(root=ROOT, output=None):
    root = Path(root)
    output = root / 'updates/v2090-stability-glass' if output is None else Path(output)
    previous = previous_files(root)
    actual = compilation_inputs(root)
    missing = set(previous) - actual
    assert missing <= EXTERNAL_PAYLOAD_FILES
    changes = {n for n in actual if n not in previous or digest(root / n) != previous[n]}
    baseline = selected_tests(root, BASE_COMMIT)
    added_test_sources = [n for n in changes if '/src/test/' in n]
    final_tests = selected_tests(root, extra_sources=added_test_sources)
    with tempfile.TemporaryDirectory(prefix='hetu-v2090-layer-') as d:
        fixture = Path(d)
        archive = subprocess.check_output(['git', 'archive', BASE_COMMIT, '--', 'android-app'], cwd=root)
        with tarfile.open(fileobj=io.BytesIO(archive)) as z:
            z.extractall(fixture, filter='data')
        assert compilation_inputs(fixture) == set(previous) - EXTERNAL_PAYLOAD_FILES
        assert all(digest(fixture / n) == previous[n] for n in compilation_inputs(fixture)), 'Pinned main source does not equal verified predecessor layers'
        for n in EXTERNAL_PAYLOAD_FILES - missing:
            q = fixture / n
            q.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / n, q)
        validate_version((fixture / BUILD_FILE).read_text(), (root / BUILD_FILE).read_text())
        subprocess.run(['git', 'init', '-q'], cwd=fixture, check=True)
        subprocess.run(['git', 'add', 'android-app'], cwd=fixture, check=True)
        for n in sorted(changes):
            q = fixture / n
            q.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / n, q)
        subprocess.run(['git', 'add', '--intent-to-add', 'android-app'], cwd=fixture, check=True)
        patch = subprocess.check_output(['git', 'diff', '--binary', '--no-ext-diff', '--no-renames', '--', 'android-app'], cwd=fixture)
        patch = b'\n'.join(b'' if line == b' ' else line for line in patch.split(b'\n'))
        temporary = fixture / 'runtime.patch'
        temporary.write_bytes(patch)
        layer = {'schema': 1, 'baseCommit': BASE_COMMIT,
            'baseAndroidAppTree': subprocess.check_output(['git', 'rev-parse', BASE_COMMIT + ':android-app'], cwd=root).decode().strip(),
            'originalTestHead': ORIGINAL_TEST_HEAD, 'reusedMainHead': BASE_COMMIT,
            'versionCode': VERSION_CODE, 'versionName': VERSION_NAME,
            'previousInputsSha256': digest(root / 'updates/v2089-runtime153/inputs.json'),
            'previousPatchSha256': digest(root / 'updates/v2089-runtime153/runtime.patch'),
            'historicalFilesSha256': historical_files(root), 'patchSha256': digest(temporary),
            'authorizedFiles': sorted(changes),
            'changedOrAddedFiles': {n: digest(root / n) for n in sorted(changes)},
            'frozenFiles': {n: sha for n, sha in sorted(previous.items()) if n not in changes},
            'externalPayloadFiles': {n: previous[n] for n in sorted(EXTERNAL_PAYLOAD_FILES)},
            'runtimePayloadCount': 23, 'rootScriptSha256': digest(root / ROOT_SCRIPT),
            'autostartScriptSha256': previous[AUTOSTART_SCRIPT],
            'baselineUnitTests': 545, 'baselineTestXmlFiles': 56,
            'baselineUnitTestMethods': baseline, 'finalUnitTestMethods': final_tests,
            'expectedUnitTests': sum(len(x['methods']) for x in final_tests.values()),
            'expectedTestXmlFiles': len(final_tests), **{n: True for n in PROTECTED_FLAGS}}
        validate_layer(layer, previous, root)
        output.mkdir(parents=True, exist_ok=True)
        (output / 'inputs.json').write_text(json.dumps(layer, indent=2) + '\n')
        (output / 'runtime.patch').write_bytes(patch)
    return {'requiredInputs': len(previous.keys() | changes), 'localMissingNativeInputs': sorted(missing),
            'expectedUnitTests': layer['expectedUnitTests'], 'expectedTestXmlFiles': layer['expectedTestXmlFiles'],
            'patchSha256': layer['patchSha256'], 'output': str(output)}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path)
    print(json.dumps(generate(output=p.parse_args().output)))
