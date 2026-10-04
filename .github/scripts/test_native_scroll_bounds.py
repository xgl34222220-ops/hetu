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

    def run_click_on_fitted_page(self, visible):
        root = ET.Element('hierarchy')
        node = view(); node.set('scrollable', 'false'); root.append(node)
        if visible:
            ET.SubElement(node, 'node', {'text': '网络事件记录', 'package': PACKAGE, 'bounds': '[14,100][380,180]'})
        source = ast.parse(Path(__file__).with_name('smoke_hetu_apk.py').read_text())
        definitions = [n for n in source.body if isinstance(n, (ast.Import, ast.ImportFrom, ast.Assign, ast.FunctionDef))]
        module = types.ModuleType('smoke_scroll_definitions')
        with tempfile.TemporaryDirectory() as output, patch.dict(os.environ, {'ANDROID_HOME': output, 'HETU_SMOKE_OUT': output}):
            exec(compile(ast.Module(body=definitions, type_ignores=[]), 'smoke-scroll-definitions', 'exec'), module.__dict__)
        return module, root

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
