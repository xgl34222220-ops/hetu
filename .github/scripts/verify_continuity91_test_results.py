#!/usr/bin/env python3
"""Verify all current identities and re-run the unchanged old verifier on its 631 subset."""
import argparse
from collections import Counter
import json
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

from continuity91_source_scope import ROOT, LAYER_FOLDER, predecessor_layer, previous_files, validate_layer
from verify_stability90_test_results import verify as verify_predecessor


def verify(results, layer, root=ROOT):
    expected = layer['finalUnitTestMethods']
    suites = {}
    for path in Path(results).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        name = suite.get('name')
        assert name in expected and name not in suites, 'Unexpected/duplicate V20.91 suite: ' + str(name)
        cases = list(suite.iter('testcase'))
        counts = {key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert counts == {'tests': len(expected[name]['methods']), 'failures': 0, 'errors': 0, 'skipped': 0}, (name, counts)
        assert len(cases) == counts['tests']
        assert Counter(case.get('name') for case in cases) == Counter(expected[name]['methods']), ('Current identity mismatch', name)
        assert all(case.get('classname') == name for case in cases), 'Cross-suite V20.91 testcase'
        assert not any(case.find(key) is not None for case in cases for key in ('failure', 'error', 'skipped'))
        suites[name] = counts
    assert set(suites) == set(expected), ('Missing current suites', sorted(set(expected) - set(suites)))
    predecessor, _ = predecessor_layer(root)
    with tempfile.TemporaryDirectory(prefix='hetu-v2091-baseline-xml-') as directory:
        destination = Path(directory)
        for path in Path(results).glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            if suite.get('name') in predecessor['finalUnitTestMethods']:
                ET.ElementTree(suite).write(destination / path.name, encoding='utf-8', xml_declaration=True)
        baseline = verify_predecessor(destination, predecessor, root)
    assert baseline['totalTests'] == 631 and baseline['xmlFiles'] == 64
    return {'result': 'PASS', 'totalTests': sum(item['tests'] for item in suites.values()), 'xmlFiles': len(suites),
            'failures': 0, 'errors': 0, 'skipped': 0, 'allPredecessor631TestcaseIdentitiesExecuted': True,
            'allOriginal545TestcaseIdentitiesExecuted': baseline['allOriginal545TestcaseIdentitiesExecuted'],
            'predecessorSubset': baseline, 'newTestsExecuted': layer['expectedUnitTests'] - 631, 'currentSuites': suites,
            'limitations': 'Actual Android JVM/Robolectric execution; separate from AOSP native traffic, phones, Root/radio and Google authentication.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'out/verification/continuity91-tests.json')
    args = parser.parse_args()
    layer = json.loads((ROOT / LAYER_FOLDER / 'inputs.json').read_text())
    validate_layer(layer, previous_files())
    report = verify(args.results, layer)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
