#!/usr/bin/env python3
"""Require every current testcase identity and separately audit the old545 subset."""
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

from stability90_source_scope import ROOT, validate_layer, previous_files
from verify_functionfix_test_results import verify as verify_function


def verify(results, layer, root=ROOT):
    expected = layer['finalUnitTestMethods']
    suites, identities = {}, {}
    for path in Path(results).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        name = suite.get('name')
        assert name in expected and name not in suites, 'Unexpected or duplicate current suite: ' + str(name)
        cases = list(suite.iter('testcase'))
        counts = {k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts == {'tests': len(expected[name]['methods']), 'failures': 0, 'errors': 0, 'skipped': 0}, (name, counts)
        assert len(cases) == counts['tests']
        assert not any(c.find(x) is not None for c in cases for x in ('failure', 'error', 'skipped'))
        actual = Counter(c.get('name') for c in cases)
        assert actual == Counter(expected[name]['methods']), ('Current testcase identity mismatch', name, actual)
        assert all(c.get('classname') == name for c in cases), 'Cross-suite testcase identity'
        suites[name], identities[name] = counts, actual
    assert set(suites) == set(expected), ('Missing current suites', sorted(set(expected) - set(suites)))
    # Run the unchanged487+58 verifier on a real XML subset containing exactly
    # the original545 testcase identities, then report current additions apart.
    with tempfile.TemporaryDirectory(prefix='hetu-v2090-baseline-xml-') as d:
        out = Path(d)
        for path in Path(results).glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            name = suite.get('name')
            if name not in layer['baselineUnitTestMethods']:
                continue
            keep = set(layer['baselineUnitTestMethods'][name]['methods'])
            for c in list(suite):
                if c.tag == 'testcase' and c.get('name') not in keep:
                    suite.remove(c)
            suite.set('tests', str(len(keep)))
            ET.ElementTree(suite).write(out / path.name, encoding='utf-8', xml_declaration=True)
        function_layer = json.loads((Path(root) / 'updates/v2086-functionfix/inputs.json').read_text())
        baseline = verify_function(out, function_layer)
    return {'result': 'PASS', 'totalTests': sum(x['tests'] for x in suites.values()), 'xmlFiles': len(suites),
            'failures': 0, 'errors': 0, 'skipped': 0, 'allOriginal545TestcaseIdentitiesExecuted': True,
            'baselineSubset': baseline, 'newTestsExecuted': layer['expectedUnitTests'] - 545,
            'currentSuites': suites,
            'limitations': 'This is Android JVM/Robolectric execution, not real-device or sustained proxy networking.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--results', type=Path, required=True)
    p.add_argument('--output', type=Path, default=ROOT / 'out/verification/stability90-tests.json')
    args = p.parse_args()
    layer = json.loads((ROOT / 'updates/v2090-stability-glass/inputs.json').read_text())
    validate_layer(layer, previous_files())
    report = verify(args.results, layer)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
