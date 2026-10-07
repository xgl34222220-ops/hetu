#!/usr/bin/env python3
"""Require unchanged, currently applicable legacy36 tests in independent XML."""
import argparse
from collections import Counter
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

from stability90_source_scope import ROOT, ORIGINAL_TEST_HEAD, TEST_ROOT, test_methods

SUITES = {'CompactHomeDashboardTest': 29, 'Ui92IntegrationTest': 3, 'NodeSelectionContinuityTest': 4}


def expected_methods(root=ROOT):
    root = Path(root)
    expected = {}
    for name, count in SUITES.items():
        path = TEST_ROOT + 'java/io/github/xgl34222220/hetu/' + name + '.kt'
        before = subprocess.check_output(['git', 'show', ORIGINAL_TEST_HEAD + ':' + path], cwd=root)
        assert before == (root / path).read_bytes(), 'Supplemental assertions were changed: ' + name
        methods = test_methods(before.decode())
        assert len(methods) == count
        expected['io.github.xgl34222220.hetu.' + name] = methods
    return expected


def verify(results, expected=None):
    expected = expected_methods() if expected is None else expected
    suites = {}
    for path in Path(results).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        name = suite.get('name')
        assert name in expected and name not in suites, ('Unexpected/duplicate supplemental suite', name)
        cases = list(suite.iter('testcase'))
        counts = {k: int(suite.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')}
        assert counts == {'tests': len(expected[name]), 'failures': 0, 'errors': 0, 'skipped': 0}, (name, counts)
        assert len(cases) == counts['tests']
        assert Counter(c.get('name') for c in cases) == Counter(expected[name]), ('Supplemental identity mismatch', name)
        assert all(c.get('classname') == name for c in cases), ('Cross-suite supplemental case', name)
        assert not any(c.find(x) is not None for c in cases for x in ('failure', 'error', 'skipped'))
        suites[name] = counts
    assert set(suites) == set(expected), ('Missing supplemental suites', sorted(set(expected) - set(suites)))
    return {'result': 'PASS', 'totalTests': 36, 'xmlFiles': 3, 'failures': 0, 'errors': 0, 'skipped': 0,
            'suites': suites, 'testcaseIdentities': expected,
            'assertionSource': ORIGINAL_TEST_HEAD,
            'limitations': 'Independent current JVM/Robolectric regression of retained legacy components and node selection; excluded from the original545 plus current additions count.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'out/supplemental36/36-test-result.json')
    args = parser.parse_args()
    try:
        report = verify(args.results)
    except Exception as error:
        report = {'result': 'FAIL', 'error': type(error).__name__ + ': ' + str(error),
                  'results': str(args.results), 'requiredSuites': SUITES}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        raise
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
