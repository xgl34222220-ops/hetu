#!/usr/bin/env python3
"""Negative guards for the narrow current-layer dependency adapter."""
import ast
from pathlib import Path
import unittest
import subprocess
import tempfile
from unittest.mock import patch
from run_continuity93_network_soak import ROOT, DRIVER, adapt, run, ORIGINAL_SOURCES, CURRENT_SOURCES


class CurrentSoakAdapterTests(unittest.TestCase):
    def test_only_dependency_inventory_changes(self):
        original = (ROOT / DRIVER).read_text()
        adjusted = adapt(original)
        self.assertEqual(adjusted.replace(CURRENT_SOURCES, ORIGINAL_SOURCES, 1), original)
        self.assertEqual(adjusted.count("'LatencyProbeBudget'"), 1)

    def test_frozen_harness_and_duration_assertions_are_byte_identical(self):
        original = (ROOT / DRIVER).read_text()
        adjusted = adapt(original)
        def harness(source):
            tree = ast.parse(source)
            return next(ast.literal_eval(node.value) for node in tree.body if isinstance(node, ast.Assign)
                and any(isinstance(t, ast.Name) and t.id == 'HARNESS' for t in node.targets))
        self.assertEqual(harness(original), harness(adjusted))
        self.assertIn('require(elapsed>=duration,"duration too short")', harness(adjusted))
        self.assertIn("if wall_seconds < duration:", adjusted)

    def test_compiler_stderr_is_saved_and_failure_is_not_swallowed(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'evidence'
            failure = subprocess.CalledProcessError(1, ['javac'], output='compiler out', stderr='missing dependency')
            with patch('subprocess.run', side_effect=failure), self.assertRaises(subprocess.CalledProcessError):
                run(1, .1, output)
            self.assertEqual('compiler out', (output / 'compiler-stdout.txt').read_text())
            self.assertEqual('missing dependency', (output / 'compiler-stderr.txt').read_text())
            self.assertFalse((output / 'samples.jsonl').exists())

    def test_unknown_driver_edits_fail_closed(self):
        original = (ROOT / DRIVER).read_text()
        for altered in (original + '\n', original.replace('duration + 60', 'duration + 600'),
                        original.replace('require(elapsed>=duration', 'require(elapsed>=0')):
            with self.assertRaises(ValueError):
                adapt(altered)


if __name__ == '__main__':
    unittest.main()
