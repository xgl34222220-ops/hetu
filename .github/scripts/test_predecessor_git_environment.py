#!/usr/bin/env python3
"""Exercise the CI-scoped Git guard without modifying frozen historical drivers."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest


class PredecessorGitEnvironmentTests(unittest.TestCase):
    def setUp(self):
        workflow = (Path(__file__).parents[1] / 'workflows/v2093-build.yml').read_text()
        pairs = re.findall(r'^      (GIT_CONFIG_[A-Z_0-9]+): (.+)$', workflow, re.M)
        self.env = {**os.environ, **{key: value.strip("'") for key, value in pairs}}
        self.assertEqual(self.env['GIT_CONFIG_COUNT'], '2')
        self.assertEqual(self.env['GIT_CONFIG_KEY_0'], 'gc.auto')
        self.assertEqual(self.env['GIT_CONFIG_VALUE_0'], '0')
        self.assertEqual(self.env['GIT_CONFIG_KEY_1'], 'maintenance.auto')
        self.assertEqual(self.env['GIT_CONFIG_VALUE_1'], 'false')

    def git(self, repo, *args):
        return subprocess.check_output(['git', '-c', 'user.name=Fixture', '-c',
            'user.email=fixture@example.invalid', *args], cwd=repo, env=self.env,
            stderr=subprocess.PIPE).decode().strip()

    def test_configuration_reaches_nested_python_and_git(self):
        code = "import subprocess; print(subprocess.check_output(['git','config','--get', 'gc.auto']).decode().strip()); print(subprocess.check_output(['git','config','--get','maintenance.auto']).decode().strip())"
        output = subprocess.check_output([sys.executable, '-c', code], env=self.env).decode()
        self.assertEqual(output.splitlines(), ['0', 'false'])

    def test_sequential_nested_shallow_fetches_preserve_all_objects_and_source(self):
        def snapshot(repo):
            return {str(p.relative_to(repo)): p.read_bytes() for p in repo.rglob('*') if p.is_file()}
        with tempfile.TemporaryDirectory(prefix='hetu-git-env-') as directory:
            root = Path(directory)
            source = root / 'source'; source.mkdir(); self.git(source, 'init', '-q')
            commits = []
            for i in range(9):
                (source / 'fixture.txt').write_text('revision ' + str(i) + '\n')
                self.git(source, 'add', 'fixture.txt')
                self.git(source, 'commit', '-qm', str(i))
                commits.append(self.git(source, 'rev-parse', 'HEAD'))
            for name in ('view', 'nested', 'nested-again'):
                before = snapshot(source)
                destination = root / name; destination.mkdir(); self.git(destination, 'init', '-q')
                # These are the frozen drivers' actual command flags, unchanged.
                for commit in commits:
                    result = subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1',
                        str(source), commit], cwd=destination, env={**self.env, 'GIT_TRACE': '1'},
                        text=True, capture_output=True, check=True)
                    self.assertNotIn('run_command: git maintenance', result.stderr)
                    self.assertNotIn('run_command: git gc', result.stderr)
                self.assertEqual(snapshot(source), before)
                self.assertTrue((destination / '.git/shallow').is_file())
                self.assertFalse((destination / '.git/shallow.lock').exists())
                for i, commit in enumerate(commits):
                    self.assertEqual(self.git(destination, 'show', commit + ':fixture.txt'), 'revision ' + str(i))
                source = destination

    def test_missing_commit_still_fails_without_retry_or_skipping(self):
        with tempfile.TemporaryDirectory(prefix='hetu-git-fail-') as directory:
            repo = Path(directory); self.git(repo, 'init', '-q')
            result = subprocess.run(['git', 'fetch', '--quiet', '--no-tags', '--depth=1',
                str(repo), '0' * 40], cwd=repo, env=self.env, capture_output=True)
            self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
