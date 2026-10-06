#!/usr/bin/env python3
"""Require every new function suite plus the unchanged strict 487/51 baseline."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

from functionfix_source_scope import ROOT, previous_files, validate_layer
from verify_tools_intake_test_results import verify as verify_baseline


def verify(results, layer):
    expected = layer['newTestCounts']
    assert layer['baselineUnitTests'] == 487 and layer['baselineTestXmlFiles'] == 51
    assert layer['expectedUnitTests'] == 487 + sum(expected.values())
    assert layer['expectedTestXmlFiles'] == 51 + len(expected)
    additions = {}
    with tempfile.TemporaryDirectory() as directory:
        for path in Path(results).glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            name = suite.get('name')
            if name in expected:
                assert name not in additions, ('Duplicate function suite', name)
                counts = {key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
                assert counts == dict(tests=expected[name], failures=0, errors=0, skipped=0), (name, counts)
                additions[name] = counts
            else:
                shutil.copyfile(path, Path(directory) / path.name)
        baseline = verify_baseline(Path(directory))
    assert set(additions) == set(expected), ('Missing function suites', sorted(set(expected) - set(additions)))
    return {'baseline': baseline, 'newSuites': additions, 'totalTests': layer['expectedUnitTests'],
            'xmlFiles': layer['expectedTestXmlFiles'], 'allOriginal487TestsPreserved': True}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, default=ROOT / 'updates/v2086-functionfix/inputs.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'out/verification/functionfix-tests.json')
    args = parser.parse_args()
    layer = json.loads(args.inputs.read_text())
    validate_layer(layer, previous_files())
    report = verify(args.results, layer)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
