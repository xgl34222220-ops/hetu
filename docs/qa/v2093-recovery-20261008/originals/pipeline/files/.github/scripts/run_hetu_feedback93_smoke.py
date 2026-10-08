#!/usr/bin/env python3
"""Run original installed acceptance, then additional feedback cases in its owned session."""
from pathlib import Path
import hashlib
import json
import runpy

from feedback_smoke93 import run


def main():
    # run_path executes the original main and its original failure diagnostics.
    # Its complete 65 navigation cases and native 900s observation finish first.
    # The subsequent cold/warm PIDs belong only to the separate feedback proof.
    catalog = json.loads(Path(__file__).with_name('feedback93_original65.json').read_text())
    baseline = Path(__file__).with_name('smoke_hetu_apk.py')
    assert hashlib.sha256(baseline.read_bytes()).hexdigest() == catalog['sourceScriptsSha256']['.github/scripts/smoke_hetu_apk.py']
    namespace = runpy.run_path(str(baseline))
    run(namespace)


if __name__ == '__main__':
    main()
