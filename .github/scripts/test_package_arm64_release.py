#!/usr/bin/env python3
"""Host regression for the arm64-v8a-only release entry filter (2026-10-13 arm64 release authorization)."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_arm64_release import dropped, SIGNATURE_FILE  # noqa: E402


class Arm64EntryFilterTest(unittest.TestCase):
    def test_other_abi_native_libraries_and_root_assets_are_dropped(self):
        for name in ('lib/x86_64/libhetu_core.so', 'lib/x86/libandroidx.graphics.path.so',
                     'lib/armeabi-v7a/libandroidx.graphics.path.so', 'assets/mihomo-root/x86_64/mihomo',
                     'assets/hetu-bpf/x86_64/hetu-bpf'):
            self.assertTrue(dropped(name), name)

    def test_arm64_and_abi_neutral_entries_are_kept(self):
        for name in ('lib/arm64-v8a/libhetu_core.so', 'lib/arm64-v8a/libandroidx.graphics.path.so',
                     'assets/mihomo-root/arm64-v8a/mihomo', 'assets/hetu-bpf/arm64-v8a/hetu-bpf',
                     'assets/hetu-bpf/hetu_match.o', 'assets/hetu-root.sh', 'assets/hetu-autostart.sh',
                     'assets/mihomo-revision.txt', 'assets/dexopt/baseline.prof', 'classes.dex', 'resources.arsc',
                     'assets/rules/x86_64.txt', 'assets/x86_64/readme'):
            self.assertFalse(dropped(name), name)

    def test_only_jar_signature_files_are_replaced(self):
        for name in ('META-INF/MANIFEST.MF', 'META-INF/CERT.SF', 'META-INF/CERT.RSA'):
            self.assertTrue(SIGNATURE_FILE.match(name), name)
        for name in ('META-INF/FastDoubleParser-LICENSE', 'META-INF/services/x.SF/y', 'META-INF/a/CERT.RSA'):
            self.assertFalse(SIGNATURE_FILE.match(name), name)


if __name__ == '__main__':
    unittest.main()
