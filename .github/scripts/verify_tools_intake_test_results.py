#!/usr/bin/env python3
"""Require the unchanged 479-test baseline and every tool-intake interaction test."""
import argparse
import json
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET

from verify_pdf85_test_results import verify as verify_pdf_baseline

EXPECTED = {
    'io.github.xgl34222220.hetu.tools.ToolsDiagIntakeTest': 3,
    'io.github.xgl34222220.hetu.tools.ToolsDnsConsentIntakeTest': 5,
}


def verify(results):
    additions = {}
    with tempfile.TemporaryDirectory() as directory:
        for path in results.glob('TEST-*.xml'):
            suite = ET.parse(path).getroot()
            name = suite.get('name')
            if name in EXPECTED:
                assert name not in additions, ('Duplicate intake suite', name)
                counts = {key: int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')}
                assert counts == dict(tests=EXPECTED[name], failures=0, errors=0, skipped=0), (name, counts)
                additions[name] = counts
            else:
                shutil.copyfile(path, Path(directory) / path.name)
        baseline = verify_pdf_baseline(Path(directory))
    assert set(additions) == set(EXPECTED), ('Missing intake suites', sorted(additions))
    return {'baseline': baseline, 'newSuites': additions, 'totalTests': 487, 'xmlFiles': 51}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--results', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('out/verification/tools-intake-tests.json'))
    args = parser.parse_args()
    report = verify(args.results)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
