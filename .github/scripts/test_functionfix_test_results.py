#!/usr/bin/env python3
"""Exercise the dynamic wrapper without substituting any old487 test result."""
import copy
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

import test_tools_intake_test_results as intake_fixture
from functionfix_source_scope import test_plan
from verify_functionfix_test_results import verify


class FunctionFixEvidenceTests(unittest.TestCase):
    def setUp(self):
        old = intake_fixture.ToolsIntakeEvidence(); old.setUp()
        self.addCleanup(old.doCleanups)
        self.old, self.root = old, old.root
        expected = {name: item['tests'] for name, item in test_plan().items()}
        self.layer = {'baselineUnitTests': 487, 'baselineTestXmlFiles': 51,
                      'newTestCounts': expected, 'expectedUnitTests': 487 + sum(expected.values()),
                      'expectedTestXmlFiles': 51 + len(expected)}
        for name, count in expected.items(): old.suite(name, count)

    def test_dynamic_additions_still_execute_strict487_chain(self):
        report = verify(self.root, self.layer)
        self.assertEqual(487, report['baseline']['totalTests'])
        self.assertEqual(51, report['baseline']['xmlFiles'])
        self.assertEqual(self.layer['expectedUnitTests'], report['totalTests'])

    def test_missing_new_suite_cannot_be_substituted(self):
        name = next(iter(self.layer['newTestCounts']))
        (self.root / ('TEST-' + name + '.xml')).unlink()
        self.old.suite('io.github.xgl34222220.hetu.UnlistedSubstitute', self.layer['newTestCounts'][name])
        with self.assertRaises(AssertionError): verify(self.root, self.layer)

    def test_duplicate_suite_failure_error_and_skip_fail(self):
        name, count = next(iter(self.layer['newTestCounts'].items()))
        for field in ('failures', 'errors', 'skipped'):
            self.old.suite(name, count, **{field: 1})
            with self.assertRaises(AssertionError): verify(self.root, self.layer)
            self.old.suite(name, count)
        source = self.root / ('TEST-' + name + '.xml')
        (self.root / 'TEST-duplicate.xml').write_bytes(source.read_bytes())
        with self.assertRaises(AssertionError): verify(self.root, self.layer)

    def test_extra_new_cases_never_replace_original_case(self):
        self.old.suite('io.github.xgl34222220.hetu.Baseline0', 350)
        with self.assertRaises(AssertionError): verify(self.root, self.layer)

    def test_layer_cannot_reduce_baseline_or_total(self):
        for key in ('baselineUnitTests', 'baselineTestXmlFiles', 'expectedUnitTests', 'expectedTestXmlFiles'):
            wrong = copy.deepcopy(self.layer); wrong[key] -= 1
            with self.assertRaises(AssertionError): verify(self.root, wrong)


if __name__ == '__main__': unittest.main()
