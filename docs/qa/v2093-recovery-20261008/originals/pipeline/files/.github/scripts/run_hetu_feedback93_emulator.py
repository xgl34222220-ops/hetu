#!/usr/bin/env python3
"""Use the unchanged owned AOSP supervisor with one fixed additional smoke entry."""
from pathlib import Path
import hashlib
import json
import sys

import run_hetu_emulator as original


class SmokeEntryAdapter:
    def __init__(self, subprocess_module, original_entry, feedback_entry):
        self.original = subprocess_module
        self.original_entry = str(original_entry)
        self.feedback_entry = str(feedback_entry)
        self.replacements = 0

    def __getattr__(self, name):
        return getattr(self.original, name)

    def run(self, command, *args, **kwargs):
        if isinstance(command, (list, tuple)) and list(command) == [sys.executable, self.original_entry]:
            assert self.replacements == 0, 'Do not run the additional smoke twice'
            self.replacements += 1
            command = [sys.executable, self.feedback_entry]
        return self.original.run(command, *args, **kwargs)


def main():
    assert len(sys.argv) == 1, 'The feedback entry is only the installed-candidate runner'
    catalog = json.loads(Path(__file__).with_name('feedback93_original65.json').read_text())
    root = Path(__file__).resolve().parents[2]
    assert all(hashlib.sha256((root / name).read_bytes()).hexdigest() == digest
               for name, digest in catalog['sourceScriptsSha256'].items()), 'Original installed acceptance source changed'
    real = original.subprocess
    adapter = SmokeEntryAdapter(real, Path(original.__file__).with_name('smoke_hetu_apk.py'),
                                Path(__file__).with_name('run_hetu_feedback93_smoke.py'))
    original.subprocess = adapter
    try:
        result = original.main()
        if result == 0:
            assert adapter.replacements == 1, 'Additional smoke entry was not executed'
        return result
    finally:
        original.subprocess = real


if __name__ == '__main__':
    raise SystemExit(main())
