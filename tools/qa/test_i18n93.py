#!/usr/bin/env python3
"""Host checks for the 2026-10 i18n / cleanup pass (B4, B5, B10, B11).

Runs the word-table report (tools/qa/hetu_i18n_report.py) and fails only on regressions the
pass removed: string templates inside ht(), untranslatable keys on the pages this pass covered,
the stale shared-network key, wording-based start-failure branching, raw error.message surfaces
and the dead legacy pages. The full report is printed for QA (informational).
"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
import hetu_i18n_report as report  # noqa: E402

PKG = ROOT / report.PKG
COVERED = ('tools/ToolsAdblockScreen.kt', 'app/NetworkTestScreen.kt', 'app/OverrideScriptsSection.kt',
           'app/SpeedTestHistorySection.kt', 'app/SettingsConfigLibrary.kt', 'app/SettingsStartupConfigScreen.kt',
           'tools/ToolsNetworkScreens.kt', 'ProxyNetworkAutomationActivity.kt', 'ProxyAdvancedSettingsActivity.kt',
           'app/CoreConceptComponents.kt')


def read(rel):
    return (PKG / rel).read_text(encoding='utf-8')


class I18nReport93Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = report.scan()

    def test_report_script_runs_and_prints_a_summary(self):
        out = subprocess.run([sys.executable, str(ROOT / 'tools/qa/hetu_i18n_report.py')], capture_output=True, text=True, check=True).stdout
        self.assertIn('summary:', out)
        print('\n' + out.splitlines()[-1])

    def test_no_string_template_inside_ht(self):
        bad = [r for r in self.rows if r['kind'] == 'ht-template' and r['editable']]
        self.assertEqual([], bad, 'use ht("… %s").fill(x): a $template can never match a table key')

    def test_covered_pages_have_every_ht_key_in_the_table(self):
        bad = [r for r in self.rows if r['kind'] == 'ht-missing' and r['file'].endswith(COVERED)]
        self.assertEqual([], bad)

    def test_vm_feedback_is_in_the_table(self):
        keys = report.vocabulary()
        for key in ('代理已启动', '已重启，设置已生效', '正在启动…', '正在停止…', '正在重启…', '正在重载配置…',
                    '确认最终运行状态…', '重启失败', '订阅更新失败', '连接超时，请检查网络后重试'):
            self.assertIn(key, keys)

    def test_stale_key_removed(self):
        self.assertNotIn('开启后河图接管共享 / 转发流量；接口与 MAC 直连在 Root 层生效，修改后重启代理。', report.vocabulary())

    def test_word_table_lines_have_three_translations(self):
        text = read('ui/HetuLanguage.kt')
        for block in re.findall(r'"""\n(.*?)"""', text, re.S):
            for line in block.splitlines():
                if line.strip():
                    self.assertEqual(4, len(line.split('|')), line[:60])
                    self.assertNotIn('$', line, 'a raw-string $ would interpolate: ' + line[:60])


class Cleanup93Test(unittest.TestCase):
    def test_start_failure_is_typed(self):
        vm = read('app/HetuViewModel.kt')
        self.assertNotRegex(vm, r'reason\.contains\("(占位|尚未选择配置|仅所选应用代理)"\)')
        self.assertIn('hxStartFailure(error)', vm)
        controller = read('ProxyComposeController.kt')
        self.assertIn('sealed class ProxyStartProblem', controller)
        self.assertNotIn('error("当前是河图内置占位配置', controller)
        self.assertIn('static final class AppScopeException extends IOException', read('RootProxyManager.java'))

    def test_toasts_carry_an_explicit_tone(self):
        vm = read('app/HetuViewModel.kt')
        self.assertNotRegex(vm, r'\btoast\(errorText\(')
        self.assertIn('fun toastAs(tone: HxTone, text: String)', vm)
        self.assertIn('vm.toastTone(shownMessage)', read('app/HetuActivity.kt'))

    def test_error_surfaces_do_not_show_raw_jvm_text(self):
        self.assertNotRegex(read('app/ConfigScreens.kt'), r'(?:toast|problem = )\(?\w+\.message \?: "')
        self.assertNotIn('toast(error.message ?: "导出失败")', read('tools/ToolsAdapter.kt'))
        self.assertIn('hxErrorMessage(error, fallback)', read('app/HetuViewModel.kt'))

    def test_dead_legacy_pages_removed(self):
        automation = read('ProxyNetworkAutomationActivity.kt')
        self.assertNotIn('fun ProxyNetworkAutomationPage', automation)
        self.assertIn('fun HxNetworkMatchScreen', automation)
        adapter = read('tools/ToolsAdapter.kt')
        for legacy in ('ProxyAdblockChainActivity', 'ProxyCnIpSettingsActivity', 'ProxySharedNetworkSettingsActivity'):
            self.assertNotIn(legacy, adapter)


if __name__ == '__main__':
    unittest.main()
