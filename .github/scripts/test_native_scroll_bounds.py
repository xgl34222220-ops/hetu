"""Replay the actual dock-clearance viewport and reject unsafe scroll surfaces."""
from pathlib import Path
import ast
import os
import tempfile
import types
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch
from native_scroll_bounds import scroll_bounds, scroll_gesture, scroll_swipe

PACKAGE = 'io.github.xgl34222220.hetu'


def view(bounds='[0,0][394,702]', package=PACKAGE):
    return ET.Element('node', {'class': 'android.widget.ScrollView', 'package': package,
                               'bounds': bounds, 'scrollable': 'true', 'enabled': 'true'})


class NativeScrollBounds(unittest.TestCase):
    def test_observed_dock_clearance_keeps_the_physical_swipe_inside_the_viewport(self):
        root = ET.parse(Path(__file__).with_name('fixtures') / 'scroll-tools-dock-clearance.xml').getroot()
        bounds = scroll_bounds(root, PACKAGE)
        self.assertEqual((0, 0, 394, 702), bounds)
        x1, y1, x2, y2 = scroll_swipe(bounds)
        self.assertEqual((197, 574, 197, 192), (x1, y1, x2, y2))
        self.assertTrue(0 < x1 < 394 and 0 < x2 < 394 and 0 < y2 < y1 < 702)
        self.assertGreater(y1, 65)

    def test_two_independent_scrollviews_are_rejected(self):
        root = ET.Element('hierarchy'); root.extend([view(), view()])
        with self.assertRaisesRegex(AssertionError, 'Expected one app'):
            scroll_bounds(root, PACKAGE)

    def test_another_application_cannot_receive_the_swipe(self):
        root = ET.Element('hierarchy'); root.append(view(package='other.application'))
        with self.assertRaisesRegex(AssertionError, 'Expected one app'):
            scroll_bounds(root, PACKAGE)

    def test_invalid_empty_negative_and_disabled_viewports_are_rejected(self):
        for bounds in ('', '[0,0][0,702]', '[-1,0][394,702]', '[0,702][394,0]', 'junk[0,0][394,702]'):
            with self.subTest(bounds=bounds):
                root = ET.Element('hierarchy'); root.append(view(bounds))
                with self.assertRaises(AssertionError):
                    scroll_bounds(root, PACKAGE)
        root = ET.Element('hierarchy'); node = view(); node.set('enabled', 'false'); root.append(node)
        with self.assertRaisesRegex(AssertionError, 'Disabled'):
            scroll_bounds(root, PACKAGE)

    def test_reverse_swipe_stays_inside_an_offset_viewport(self):
        bounds = (20, 100, 380, 702)
        up = scroll_swipe(bounds)
        down = scroll_swipe(bounds, reverse=True)
        self.assertEqual((up[2], up[3], up[0], up[1]), down)
        self.assertTrue(100 < down[1] < down[3] < 702)

    def test_target_edges_follow_the_actual_viewport_and_reverse_at_the_header(self):
        bounds = (0, 0, 394, 702)
        self.assertEqual(scroll_swipe(bounds), scroll_gesture(bounds))
        self.assertEqual(scroll_swipe(bounds), scroll_gesture(bounds, (14, 680, 379, 720)))
        self.assertEqual(scroll_swipe(bounds, reverse=True), scroll_gesture(bounds, (14, 24, 379, 104)))
        self.assertIsNone(scroll_gesture(bounds, (14, 100, 379, 180)))

    def smoke_definitions(self):
        source = ast.parse(Path(__file__).with_name('smoke_hetu_apk.py').read_text())
        definitions = [n for n in source.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.Assign, ast.FunctionDef))]
        module = types.ModuleType('smoke_scroll_definitions')
        with tempfile.TemporaryDirectory() as output, patch.dict(os.environ, {'ANDROID_HOME': output, 'HETU_SMOKE_OUT': output}):
            exec(compile(ast.Module(body=definitions, type_ignores=[]), 'smoke-scroll-definitions', 'exec'), module.__dict__)
        return module

    def run_click_on_fitted_page(self, visible):
        root = ET.Element('hierarchy')
        node = view(); node.set('scrollable', 'false'); root.append(node)
        if visible:
            ET.SubElement(node, 'node', {'text': '网络事件记录', 'package': PACKAGE, 'bounds': '[14,100][380,180]'})
        return self.smoke_definitions(), root

    def test_visible_target_on_a_fitted_page_receives_the_original_physical_tap(self):
        module, root = self.run_click_on_fitted_page(visible=True)
        with patch.object(module, 'ui', return_value=(root, '')), patch.object(module, 'adb') as adb, patch.object(module.time, 'sleep'):
            module.click('网络事件记录', scroll=True)
        adb.assert_called_once_with('shell', 'input', 'tap', '197', '140')

    def test_missing_target_on_a_fitted_page_fails_without_a_blind_swipe_or_tap(self):
        module, root = self.run_click_on_fitted_page(visible=False)
        with patch.object(module, 'ui', return_value=(root, '')), patch.object(module, 'adb') as adb, patch.object(module.time, 'sleep'):
            with self.assertRaisesRegex(AssertionError, 'Missing target'):
                module.click('网络事件记录', scroll=True)
        adb.assert_not_called()

    def settings_fixture(self):
        return ET.parse(Path(__file__).with_name('fixtures') / 'scroll-settings-dock-clearance.xml').getroot()

    def test_observed_settings_dock_clips_the_full_and_navigation_inset_viewports(self):
        for bottom in (852, 804):
            with self.subTest(bottom=bottom):
                root = self.settings_fixture()
                node = next(n for n in root.iter('node') if n.get('scrollable') == 'true')
                node.set('bounds', f'[0,0][394,{bottom}]')
                self.assertEqual((0, 0, 394, 736), scroll_bounds(root, PACKAGE))
                self.assertIsNotNone(scroll_gesture(scroll_bounds(root, PACKAGE), (78, 760, 136, 780)))

    def test_tools_clearance_and_child_pages_without_a_dock_keep_their_bounds(self):
        root = self.settings_fixture()
        node = next(n for n in root.iter('node') if n.get('scrollable') == 'true')
        node.set('bounds', '[0,0][394,702]')
        self.assertEqual((0, 0, 394, 702), scroll_bounds(root, PACKAGE))
        child = ET.Element('hierarchy'); child.append(view('[0,64][394,804]'))
        self.assertEqual((0, 64, 394, 804), scroll_bounds(child, PACKAGE))

    def test_two_native_dock_groups_are_rejected(self):
        root = self.settings_fixture()
        parents = {child: parent for parent in root.iter() for child in parent}
        tab = next(n for n in root.iter('node') if n.get('content-desc') == '首页')
        group = parents[parents[tab]]
        root.append(ET.fromstring(ET.tostring(group)))
        with self.assertRaisesRegex(AssertionError, 'one native dock group'):
            scroll_bounds(root, PACKAGE)

    def test_smoke_scrolls_about_above_the_actual_dock_before_physically_tapping(self):
        before = self.settings_fixture()
        ET.SubElement(before, 'node', {'text': '关于', 'package': PACKAGE, 'bounds': '[78,760][136,780]'})
        after = self.settings_fixture()
        ET.SubElement(after, 'node', {'text': '关于', 'package': PACKAGE, 'bounds': '[78,160][136,180]'})
        module = self.smoke_definitions()
        with patch.object(module, 'ui', side_effect=[(before, ''), (after, '')]), patch.object(module, 'adb') as adb, patch.object(module.time, 'sleep'):
            module.click('关于', scroll=True)
        self.assertEqual(2, adb.call_count)
        gesture, tap = [call.args for call in adb.call_args_list]
        self.assertEqual(('shell', 'input', 'swipe', '197', '601', '197', '199', '400'), gesture)
        self.assertEqual(('shell', 'input', 'tap', '107', '170'), tap)
        self.assertNotIn(('shell', 'input', 'tap', '107', '770'), [call.args for call in adb.call_args_list])

    def test_unmoving_target_under_the_dock_exhausts_seven_attempts_without_tapping_home(self):
        root = self.settings_fixture()
        ET.SubElement(root, 'node', {'text': '关于', 'package': PACKAGE, 'bounds': '[78,760][136,780]'})
        module = self.smoke_definitions()
        with patch.object(module, 'ui', return_value=(root, '')), patch.object(module, 'adb') as adb, patch.object(module.time, 'sleep'):
            with self.assertRaisesRegex(AssertionError, 'UI label unavailable: 关于'):
                module.click('关于', scroll=True)
        self.assertEqual(7, adb.call_count)
        self.assertTrue(all(call.args[:3] == ('shell', 'input', 'swipe') for call in adb.call_args_list))


if __name__ == '__main__':
    unittest.main(verbosity=2)
