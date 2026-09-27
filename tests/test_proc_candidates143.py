"""Run the actual candidate function against disappearing and vendor proc entries."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'android-app/app/src/main/assets/hetu-root.sh'


class ProcCandidateTests(unittest.TestCase):
    def test_proc_names_and_exit_races_are_silent(self):
        text = SOURCE.read_text()
        function = text[text.index('core_candidate(){'):text.index('\npidcore(){')]
        with tempfile.TemporaryDirectory() as tmp:
            proc = Path(tmp) / 'proc'; proc.mkdir()
            for pid, comm in [('42', 'mihomo'), ('44', 'unrelated'), ('8350_reg', 'core')]:
                folder = proc / pid; folder.mkdir(); (folder / 'comm').write_text(comm + '\n')
            for shell in ['/bin/sh', '/bin/bash', shutil.which('mksh')]:
                if not shell:
                    continue
                for pid, expected in [('42', 0), ('44', 1), ('8350_reg', 1), ('43', 1), ('', 1), ('1/exe', 1)]:
                    with self.subTest(shell=shell, pid=pid):
                        result = subprocess.run([shell, '-c', function + '\ncore_candidate "$1"', 'probe', pid],
                            env=dict(os.environ, CORE_PROCFS=str(proc), BASE=tmp), capture_output=True, text=True)
                        self.assertEqual(expected, result.returncode)
                        self.assertEqual('', result.stdout)
                        self.assertEqual('', result.stderr)


if __name__ == '__main__':
    unittest.main(verbosity=2)
