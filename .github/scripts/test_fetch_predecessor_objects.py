#!/usr/bin/env python3
"""Controlled Git regression tests; no network or project payload required."""
import ast
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from fetch_predecessor_objects import fetch_predecessor_objects, SHALLOW_RACE


class FetchPredecessorTests(unittest.TestCase):
    def test_single_fetch_disables_background_maintenance_and_checks_every_pin(self):
        commits = ('a' * 40, 'b' * 40, 'a' * 40)
        with patch('fetch_predecessor_objects.subprocess.run') as run:
            run.return_value = subprocess.CompletedProcess([], 0, '', '')
            fetch_predecessor_objects('/source', '/view', commits)
        command = run.call_args_list[0].args[0]
        self.assertEqual(command, ['git', '-c', 'gc.auto=0', '-c', 'maintenance.auto=false',
            'fetch', '--no-auto-maintenance', '--quiet', '--no-tags', '--depth=1',
            '/source', 'a' * 40, 'b' * 40])
        self.assertEqual(run.call_count, 3)
        self.assertEqual([call.args[0][-1] for call in run.call_args_list[1:]],
                         ['a' * 40 + '^{commit}', 'b' * 40 + '^{commit}'])

    def test_exact_shallow_race_is_retried_then_pins_verified(self):
        with patch('fetch_predecessor_objects.subprocess.run') as run, \
                patch('fetch_predecessor_objects.time.sleep') as sleep:
            run.side_effect = [subprocess.CompletedProcess([], 128, '', SHALLOW_RACE),
                               subprocess.CompletedProcess([], 0, '', ''),
                               subprocess.CompletedProcess([], 0, '', '')]
            fetch_predecessor_objects('/source', '/view', ['a' * 40])
        self.assertEqual(run.call_count, 3)
        self.assertEqual(run.call_args_list[0], run.call_args_list[1])
        sleep.assert_called_once_with(0.1)

    def test_persistent_race_fails_after_three_attempts(self):
        with patch('fetch_predecessor_objects.subprocess.run') as run, \
                patch('fetch_predecessor_objects.time.sleep') as sleep:
            run.return_value = subprocess.CompletedProcess([], 128, '', SHALLOW_RACE)
            with self.assertRaises(subprocess.CalledProcessError):
                fetch_predecessor_objects('/source', '/view', ['a' * 40])
        self.assertEqual(run.call_count, 3)
        self.assertEqual(sleep.call_count, 2)

    def test_unrelated_failure_is_not_retried(self):
        with patch('fetch_predecessor_objects.subprocess.run') as run, \
                patch('fetch_predecessor_objects.time.sleep') as sleep:
            run.return_value = subprocess.CompletedProcess([], 128, '', 'fatal: missing object')
            with self.assertRaises(subprocess.CalledProcessError):
                fetch_predecessor_objects('/source', '/view', ['a' * 40])
        self.assertEqual(run.call_count, 1)
        sleep.assert_not_called()

    def test_failed_pin_verification_is_not_ignored(self):
        with patch('fetch_predecessor_objects.subprocess.run') as run:
            run.side_effect = [subprocess.CompletedProcess([], 0, '', ''),
                               subprocess.CalledProcessError(128, ['git', 'cat-file'])]
            with self.assertRaises(subprocess.CalledProcessError):
                fetch_predecessor_objects('/source', '/view', ['a' * 40])

    def test_invalid_or_mutable_pins_and_same_repository_are_rejected(self):
        with patch('fetch_predecessor_objects.subprocess.run') as run:
            for pins in ([], ['HEAD'], ['--all'], ['a' * 39]):
                with self.subTest(pins=pins), self.assertRaises(ValueError):
                    fetch_predecessor_objects('/source', '/view', pins)
            with self.assertRaises(ValueError):
                fetch_predecessor_objects('/source', '/source', ['a' * 40])
        run.assert_not_called()

    def test_real_nested_shallow_views_preserve_source_and_pinned_bytes(self):
        def git(repo, *args):
            return subprocess.check_output(['git', '-c', 'user.name=Fixture', '-c',
                'user.email=fixture@example.invalid', *args], cwd=repo, stderr=subprocess.PIPE).decode().strip()
        def snapshot(repo):
            return {str(p.relative_to(repo)): p.read_bytes() for p in repo.rglob('*') if p.is_file()}
        with tempfile.TemporaryDirectory(prefix='hetu-fetch-test-') as d:
            root = Path(d)
            source = root / 'source'; source.mkdir(); git(source, 'init', '-q')
            commits = []
            for i in range(4):
                (source / 'fixture.txt').write_text('revision ' + str(i) + '\n')
                git(source, 'add', 'fixture.txt'); git(source, 'commit', '-qm', 'revision ' + str(i))
                commits.append(git(source, 'rev-parse', 'HEAD'))
            before = snapshot(source)
            view = root / 'view'; view.mkdir(); git(view, 'init', '-q')
            fetch_predecessor_objects(source, view, commits)
            self.assertEqual(snapshot(source), before)
            self.assertTrue((view / '.git/shallow').is_file())
            view_before = snapshot(view)
            nested = root / 'nested'; nested.mkdir(); git(nested, 'init', '-q')
            fetch_predecessor_objects(view, nested, commits)
            self.assertEqual(snapshot(view), view_before)
            for repo in (view, nested):
                for i, commit in enumerate(commits):
                    self.assertEqual(git(repo, 'show', commit + ':fixture.txt'), 'revision ' + str(i))
                self.assertFalse((repo / '.git/shallow.lock').exists())

    def test_all_five_wrappers_use_shared_fetch_without_changing_pins(self):
        directory = Path(__file__).parent
        expected = {'run_functionfix_baseline_hosts.py': 2, 'run_stability90_baseline_hosts.py': 3,
                    'run_continuity91_baseline_hosts.py': 6, 'run_continuity92_baseline_hosts.py': 7,
                    'run_continuity93_baseline_hosts.py': 8}
        for filename, count in expected.items():
            tree = ast.parse((directory / filename).read_text())
            calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
                     and isinstance(node.func, ast.Name) and node.func.id == 'fetch_predecessor_objects']
            self.assertEqual(len(calls), 1, filename)
            pins = calls[0].args[2]
            if isinstance(pins, ast.Name):
                pins = next(node.value for node in tree.body if isinstance(node, ast.Assign)
                            and any(isinstance(t, ast.Name) and t.id == pins.id for t in node.targets))
            self.assertEqual(len(pins.elts), count, filename)
            self.assertNotIn("'fetch', '--quiet'", (directory / filename).read_text())


if __name__ == '__main__':
    unittest.main()
