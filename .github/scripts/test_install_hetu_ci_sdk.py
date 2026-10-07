#!/usr/bin/env python3
"""Execute controlled SDK manager processes; never access or modify an SDK."""
import contextlib
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from install_hetu_ci_sdk import packages_for, run_install, transient_reason

ZIP_FAILURE = 'Warning: An error occurred while preparing SDK package Android Emulator: Error on ZipFile unknown archive.\n'


class SdkInstallerTests(unittest.TestCase):
    def fixture(self, folder, outcomes):
        manager = folder / 'SDK manager fixture'
        calls = folder / 'calls.json'
        manager.write_text('#!/usr/bin/env python3\n' +
            'import json,sys,time\nfrom pathlib import Path\n' +
            f'calls=Path({str(calls)!r})\noutcomes={outcomes!r}\n' +
            'rows=json.loads(calls.read_text()) if calls.exists() else []\n' +
            'number=len(rows);rows.append(sys.argv[1:]);calls.write_text(json.dumps(rows))\n' +
            'item=outcomes[min(number,len(outcomes)-1)]\n' +
            'sys.stdout.write(item.get("stdout",""));sys.stdout.flush()\n' +
            'sys.stderr.write(item.get("stderr",""));sys.stderr.flush()\n' +
            'time.sleep(item.get("sleep",0))\nsys.exit(item["exit"])\n')
        manager.chmod(0o700)  # Private test executable, not a runner/SDK permission change.
        return manager, calls

    def execute(self, folder, outcomes, *, api=36, timeout=600):
        manager, calls = self.fixture(folder, outcomes)
        with contextlib.redirect_stdout(io.StringIO()):
            result = run_install(sdkmanager=manager, api=api, output=folder / 'evidence',
                                 retry_delay_seconds=0, timeout_seconds=timeout)
        self.assertEqual(json.loads((folder / 'evidence/attempts.json').read_text()), result)
        self.assertEqual(json.loads(calls.read_text()), [packages_for(api)] * len(result['attempts']))
        for row in result['attempts']:
            log = (folder / 'evidence' / row['log']).read_bytes()
            self.assertEqual(row['logBytes'], len(log))
            self.assertEqual(row['logSha256'], hashlib.sha256(log).hexdigest())
            self.assertGreaterEqual(row['actualWallSeconds'], 0)
            self.assertLessEqual(row['startedUtc'], row['endedUtc'])
            self.assertEqual(row['command'], [str(manager), *packages_for(api)])
        return result

    def test_actual_zip_failure_then_success_preserves_both_attempts_and_arguments(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            result = self.execute(out, [{'exit': 7, 'stderr': ZIP_FAILURE}, {'exit': 0, 'stdout': 'Installed\n'}])
            self.assertEqual(result['result'], 'PASS')
            self.assertEqual([x['exitCode'] for x in result['attempts']], [7, 0])
            self.assertEqual((out / 'evidence/attempt-01.log').read_text(), ZIP_FAILURE)
            self.assertTrue(result['attempts'][0]['retried'])
            self.assertFalse(result['attempts'][1]['retried'])

    def test_exhausted_zip_failure_stops_at_three_and_keeps_the_final_nonzero(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.execute(Path(d), [{'exit': 9, 'stdout': ZIP_FAILURE}])
            self.assertEqual(result['result'], 'FAIL')
            self.assertEqual(result['finalExitCode'], 9)
            self.assertEqual(len(result['attempts']), 3)
            self.assertEqual([x['retried'] for x in result['attempts']], [True, True, False])

    def test_nontransient_failure_does_not_reach_a_later_success(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.execute(Path(d), [{'exit': 2, 'stderr': 'Permission denied\n'}, {'exit': 0}])
            self.assertEqual(result['result'], 'FAIL')
            self.assertEqual(result['finalExitCode'], 2)
            self.assertEqual(len(result['attempts']), 1)

    def test_license_disk_package_and_java_errors_override_archive_retry(self):
        for error in ('License not accepted', 'No space left on device', 'Failed to find package',
                      'Unknown option --unsafe', 'UnsupportedClassVersionError'):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as d:
                result = self.execute(Path(d), [{'exit': 3, 'stderr': ZIP_FAILURE + error}, {'exit': 0}])
                self.assertEqual(result['result'], 'FAIL')
                self.assertEqual(len(result['attempts']), 1)

    def test_unknown_error_or_archive_text_without_sdk_download_context_is_not_retried(self):
        for error in ('unrecognized failure', 'Error on ZipFile unknown archive', 'HTTP response code: 503'):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as d:
                result = self.execute(Path(d), [{'exit': 4, 'stdout': error}, {'exit': 0}])
                self.assertEqual(result['result'], 'FAIL')
                self.assertEqual(len(result['attempts']), 1)

    def test_known_download_reset_and_server_error_retry_without_argument_changes(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.execute(Path(d), [
                {'exit': 1, 'stderr': 'Warning: An error occurred while preparing SDK package Android Emulator: Connection reset\n'},
                {'exit': 1, 'stderr': 'Failed to download: Server returned HTTP response code: 503\n'},
                {'exit': 0}], api=35)
            self.assertEqual(result['result'], 'PASS')
            self.assertEqual([x['exitCode'] for x in result['attempts']], [1, 1, 0])

    def test_success_executes_once_and_keeps_combined_stdout_stderr_raw_bytes(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            result = self.execute(out, [{'exit': 0, 'stdout': 'progress\r100%\n', 'stderr': 'warning\n'}])
            self.assertEqual(result['result'], 'PASS')
            self.assertEqual(len(result['attempts']), 1)
            self.assertEqual((out / 'evidence/attempt-01.log').read_bytes(), b'progress\r100%\nwarning\n')

    def test_process_timeout_is_preserved_and_is_not_inferred_to_be_a_transient_download(self):
        with tempfile.TemporaryDirectory() as d:
            result = self.execute(Path(d), [{'exit': 0, 'stdout': 'Downloading\n', 'sleep': .4}], timeout=.08)
            self.assertEqual(result['result'], 'FAIL')
            self.assertEqual(result['finalExitCode'], 124)
            self.assertEqual(len(result['attempts']), 1)
            self.assertEqual(result['attempts'][0]['classification'], 'sdkmanager_process_timeout')

    def test_existing_evidence_is_not_deleted_or_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            manager, calls = self.fixture(out, [{'exit': 0}])
            evidence = out / 'evidence';evidence.mkdir()
            old = evidence / 'attempt-01.log';old.write_bytes(b'prior-failure')
            with self.assertRaises(FileExistsError):
                run_install(sdkmanager=manager, api=36, output=evidence, retry_delay_seconds=0)
            self.assertEqual(old.read_bytes(), b'prior-failure')
            self.assertFalse(calls.exists())

    def test_only_the_exact_original_packages_are_allowed(self):
        self.assertEqual(packages_for(35), ['platform-tools', 'emulator', 'system-images;android-35;default;x86_64'])
        self.assertEqual(packages_for(36), ['platform-tools', 'emulator', 'system-images;android-36;default;x86_64'])
        with self.assertRaises(ValueError):
            packages_for(34)
        self.assertIsNone(transient_reason('Warning: An error occurred while preparing SDK package: HTTP response code: 403'))

    def test_actual_command_line_propagates_nonzero_exit_without_reaching_another_attempt(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d)
            manager, calls = self.fixture(out, [{'exit': 13, 'stderr': 'Permission denied\n'}, {'exit': 0}])
            completed = subprocess.run([sys.executable, str(Path(__file__).with_name('install_hetu_ci_sdk.py')),
                '--sdkmanager', str(manager), '--api', '36', '--output', str(out / 'evidence')],
                capture_output=True, check=False)
            self.assertEqual(completed.returncode, 13)
            self.assertEqual(len(json.loads(calls.read_text())), 1)
            report = json.loads((out / 'evidence/attempts.json').read_text())
            self.assertEqual(report['result'], 'FAIL')
            self.assertEqual(report['finalExitCode'], 13)


if __name__ == '__main__':
    unittest.main()
