#!/usr/bin/env python3
"""Regress missing, skipped and substituted suites in the new CI evidence gate."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from auth_source_scope import NEW_TEST_CLASSES, NEW_TEST_COUNTS
from verify_auth_test_results import verify_results


class AuthenticationEvidence(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for index in range(42):
            self.suite('Baseline' + str(index), 351 if index == 0 else 1)
        for name in NEW_TEST_CLASSES:
            self.suite(name, NEW_TEST_COUNTS[name])

    def suite(self, name, tests, failures=0, errors=0, skipped=0):
        root = ET.Element('testsuite', {'name': 'io.github.xgl34222220.hetu.' + name,
                                      'tests': str(tests), 'failures': str(failures),
                                      'errors': str(errors), 'skipped': str(skipped)})
        (self.root / ('TEST-' + name + '.xml')).write_bytes(ET.tostring(root))

    def test_exact_baseline_and_all_authentication_suites_pass(self):
        report = verify_results(self.root, 422)
        self.assertEqual(392, report['baseline']['tests'])
        self.assertEqual(30, report['authentication']['tests'])
        self.assertEqual(47, report['xmlFiles'])

    def test_same_total_cannot_substitute_new_tests_for_old_tests(self):
        self.suite('Baseline0', 350)
        self.suite(NEW_TEST_CLASSES[0], NEW_TEST_COUNTS[NEW_TEST_CLASSES[0]] + 1)
        with self.assertRaises(AssertionError):
            verify_results(self.root, 422)

    def test_missing_authentication_suite_is_rejected(self):
        (self.root / ('TEST-' + NEW_TEST_CLASSES[0] + '.xml')).unlink()
        self.suite(NEW_TEST_CLASSES[1], NEW_TEST_COUNTS[NEW_TEST_CLASSES[1]] + NEW_TEST_COUNTS[NEW_TEST_CLASSES[0]])
        with self.assertRaises(AssertionError):
            verify_results(self.root, 422)

    def test_failures_errors_and_skips_are_rejected_in_both_buckets(self):
        for name in ['Baseline0', NEW_TEST_CLASSES[0]]:
            tests = 351 if name == 'Baseline0' else NEW_TEST_COUNTS[name]
            for field in ['failures', 'errors', 'skipped']:
                with self.subTest(name=name, field=field):
                    self.suite(name, tests, **{field: 1})
                    with self.assertRaises(AssertionError):
                        verify_results(self.root, 422)
                    self.suite(name, tests)

    def test_incorrect_declared_total_is_rejected(self):
        with self.assertRaises(AssertionError):
            verify_results(self.root, 423)

    def test_baseline_xml_inventory_cannot_be_reduced(self):
        (self.root / 'TEST-Baseline1.xml').unlink()
        self.suite('Baseline0', 352)
        with self.assertRaises(AssertionError):
            verify_results(self.root, 422)


if __name__ == '__main__':
    unittest.main(verbosity=2)
