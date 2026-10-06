#!/usr/bin/env python3
"""Preserve the exact 392-test baseline and verify every new authentication suite."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

from auth_source_scope import (BASE_TESTS, BASE_TEST_XML_FILES, NEW_TEST_CLASSES, NEW_TEST_COUNTS,
                               validate_layer)

KEYS = ('tests', 'failures', 'errors', 'skipped')


def verify_results(results, expected_tests):
    files = sorted(results.glob('TEST-*.xml'))
    baseline = {key: 0 for key in KEYS}
    authentication = {key: 0 for key in KEYS}
    new_suites = {}
    baseline_files = 0
    for path in files:
        suite = ET.parse(path).getroot()
        counts = {key: int(suite.get(key, 0)) for key in KEYS}
        assert all(value >= 0 for value in counts.values()), path
        name = suite.get('name', '').removeprefix('io.github.xgl34222220.hetu.')
        if name in NEW_TEST_CLASSES:
            assert name not in new_suites, 'Duplicate authentication test suite: ' + name
            assert counts['tests'] > 0, 'Authentication suite did not execute: ' + name
            assert counts == {'tests': NEW_TEST_COUNTS[name], 'failures': 0, 'errors': 0, 'skipped': 0}, (name, counts)
            new_suites[name] = counts
            target = authentication
        else:
            baseline_files += 1
            target = baseline
        for key, value in counts.items():
            target[key] += value
    assert baseline_files == BASE_TEST_XML_FILES, ('Baseline XML files changed', baseline_files)
    assert baseline == {'tests': BASE_TESTS, 'failures': 0, 'errors': 0, 'skipped': 0}, baseline
    assert set(new_suites) == set(NEW_TEST_CLASSES), ('Missing authentication suites', sorted(new_suites))
    assert authentication == {'tests': expected_tests - BASE_TESTS, 'failures': 0, 'errors': 0, 'skipped': 0}, authentication
    return {'baseline': baseline, 'baselineXmlFiles': baseline_files,
            'authentication': authentication, 'authenticationSuites': new_suites,
            'totalTests': expected_tests, 'xmlFiles': len(files)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, default=Path('updates/v2084-controller-auth/inputs.json'))
    parser.add_argument('--output', type=Path, default=Path('out/verification/controller-auth-tests.json'))
    args = parser.parse_args()
    layer = json.loads(args.inputs.read_text())
    validate_layer(layer)
    report = verify_results(args.results, layer['expectedUnitTests'])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
