#!/usr/bin/env python3
"""Check additive tool evidence without dropping or substituting existing suites."""
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from auth_source_scope import NEW_TEST_CLASSES, NEW_TEST_COUNTS
from verify_pdf85_test_results import EXPECTED as PDF_EXPECTED
from verify_tools_intake_test_results import EXPECTED, verify


class ToolsIntakeEvidence(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for index in range(42):
            self.suite('io.github.xgl34222220.hetu.Baseline' + str(index), 351 if index == 0 else 1)
        for name in NEW_TEST_CLASSES:
            self.suite('io.github.xgl34222220.hetu.' + name, NEW_TEST_COUNTS[name])
        for name, count in {**PDF_EXPECTED, **EXPECTED}.items():
            self.suite(name, count)

    def suite(self, name, tests, **changes):
        counts = dict(tests=tests, failures=0, errors=0, skipped=0)
        counts.update(changes)
        element = ET.Element('testsuite', {'name': name, **{key: str(value) for key, value in counts.items()}})
        path = self.root / ('TEST-' + name + '.xml')
        path.write_bytes(ET.tostring(element))
        return path

    def test_all_original_and_intake_suites_are_required(self):
        report = verify(self.root)
        self.assertEqual(479, report['baseline']['totalTests'])
        self.assertEqual(487, report['totalTests'])
        self.assertEqual(51, report['xmlFiles'])

    def test_missing_suite_cannot_be_replaced_with_more_tests_in_another(self):
        names = list(EXPECTED)
        (self.root / ('TEST-' + names[0] + '.xml')).unlink()
        self.suite(names[1], sum(EXPECTED.values()))
        with self.assertRaises(AssertionError):
            verify(self.root)

    def test_new_tests_cannot_replace_existing_tests(self):
        self.suite('io.github.xgl34222220.hetu.Baseline0', 350)
        with self.assertRaises(AssertionError):
            verify(self.root)

    def test_failures_errors_and_skips_in_each_new_suite_are_rejected(self):
        for name, count in EXPECTED.items():
            for field in ('failures', 'errors', 'skipped'):
                with self.subTest(name=name, field=field):
                    self.suite(name, count, **{field: 1})
                    with self.assertRaises(AssertionError):
                        verify(self.root)
                    self.suite(name, count)

    def test_duplicate_intake_suite_is_rejected(self):
        name, count = next(iter(EXPECTED.items()))
        source = self.suite(name, count)
        (self.root / 'TEST-duplicate.xml').write_bytes(source.read_bytes())
        with self.assertRaises(AssertionError):
            verify(self.root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
