#!/usr/bin/env python3
"""Keep every old unit selector, installation guard and fixed signing gate."""
from pathlib import Path
import re
import subprocess
import unittest

from functionfix_source_scope import ROOT, BASE_COMMIT


class FunctionFixWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = subprocess.check_output(['git', 'show', BASE_COMMIT + ':.github/workflows/ui-v76-build.yml'], cwd=ROOT).decode()
        cls.after = (ROOT / '.github/workflows/ui-v76-build.yml').read_text()

    def test_every_original487_selector_remains_in_order(self):
        def selectors(source):
            unit = source.split('    - name: Runtime unit tests\n', 1)[1].split('    - name: Android lint', 1)[0]
            return re.findall(r"'(\*[A-Za-z][A-Za-z0-9_.]*)'", unit)
        # Three existing wildcard selectors cover additional baseline suites;
        # 48 retained selectors produce the independently verified 51 XMLs.
        self.assertEqual(48, len(selectors(self.before)))
        self.assertEqual(selectors(self.before), selectors(self.after))
        self.assertIn('"${FUNCTION_TEST_FILTERS[@]}"'.replace('"', '\\"'), self.after)

    def test_api_installation_and_historical_smoke_bodies_stay_exact(self):
        def smoke(source):
            return re.split(r"(?m)^'?on'?:\s*$", source.split('  android-ui-smoke:\n', 1)[1], 1)[0]
        normalized = self.after.replace('Hetu-V20.86-', 'Hetu-V20.85-').replace("HETU_EXPECTED_VERSION: '2086'", "HETU_EXPECTED_VERSION: '2085'")
        self.assertEqual(smoke(self.before), smoke(normalized))

    def test_signing_and_read_only_permissions_non_cancel_remain(self):
        for marker in ('contents: read', 'actions: read', 'cancel-in-progress: false',
                       'key: hetu-ui10-debug-signing-v1', 'fail-on-cache-miss: true',
                       '701bbb0aaa5709cf2bebd96ff85ebd64c06cf6c3a2211ec21a9c51e534a5faad'):
            self.assertEqual(self.before.count(marker), self.after.count(marker), marker)

    def test_new_result_total_and_old_hosts_use_verified_wrappers(self):
        self.assertIn('verify_functionfix_test_results.py --results', self.after)
        self.assertIn('--expected-tests "$HETU_EXPECTED_TESTS"', self.after)
        self.assertIn('functionfix_ci_config.py runtime-snapshot', self.after)
        self.assertIn('verify_packaged_runtime.py verify', self.after)
        for gate in ('test_ui_source_scope.py', 'test_pdf85_source_scope.py', 'test_tools_intake_source_scope.py',
                     'test_pdf85_final_source_scope.py', 'test_auth_source_scope.py'):
            self.assertIn('run_functionfix_baseline_hosts.py --gate ' + gate, self.after)


if __name__ == '__main__': unittest.main()
