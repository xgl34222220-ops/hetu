"""Fetch pinned history into disposable views without background shallow writers."""
from pathlib import Path
import re
import subprocess
import sys
import time


SHALLOW_RACE = 'fatal: shallow file has changed since we read it'


def fetch_predecessor_objects(root, destination, commits):
    root, destination = Path(root).resolve(), Path(destination).resolve()
    commits = tuple(dict.fromkeys(commits))
    if root == destination or not commits or any(
            re.fullmatch(r'[0-9a-f]{40}', commit) is None for commit in commits):
        raise ValueError('Expected distinct repositories and full pinned commit IDs')
    # A sequence of depth-one fetches can leave auto-gc running while the next
    # fetch rewrites shallow. Use one transaction and suppress auto-maintenance
    # for this invocation only; never alter the source repo or remove lock files.
    command = ['git', '-c', 'gc.auto=0', '-c', 'maintenance.auto=false',
               'fetch', '--no-auto-maintenance', '--quiet', '--no-tags',
               '--depth=1', str(root), *commits]
    for attempt in range(3):
        result = subprocess.run(command, cwd=destination, text=True, capture_output=True)
        if result.stdout:
            print(result.stdout, end='', file=sys.stdout)
        if result.stderr:
            print(result.stderr, end='', file=sys.stderr)
        if result.returncode == 0:
            break
        # Only the observed transient shallow race is retryable. Missing objects,
        # permission errors and other Git failures remain hard failures.
        if SHALLOW_RACE not in result.stderr or attempt == 2:
            result.check_returncode()
        time.sleep(0.1 * (attempt + 1))
    for commit in commits:
        subprocess.run(['git', 'cat-file', '-e', commit + '^{commit}'],
                       cwd=destination, check=True)
