"""Replay the actual failing AOSP topology and reject ambiguous native surfaces."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from native_webview_bounds import webview_bounds

PACKAGE = 'io.github.xgl34222220.hetu'


def view(bounds='[0,24][394,804]', package=PACKAGE):
    return ET.Element('node', {'class': 'android.webkit.WebView', 'package': package, 'bounds': bounds})


class NativeWebViewBounds(unittest.TestCase):
    def test_observed_nested_document_is_not_a_second_native_container(self):
        root = ET.parse(Path(__file__).with_name('fixtures') / 'webview-duplicate-document.xml').getroot()
        self.assertEqual(((0, 24, 394, 804), 2), webview_bounds(root, PACKAGE))

    def test_two_independent_containers_are_still_rejected(self):
        root = ET.Element('hierarchy'); root.extend([view(), view()])
        with self.assertRaisesRegex(AssertionError, 'Expected one native'):
            webview_bounds(root, PACKAGE)

    def test_other_application_surface_cannot_be_selected(self):
        root = ET.Element('hierarchy'); root.append(view(package='other.application'))
        with self.assertRaisesRegex(AssertionError, 'Expected one native'):
            webview_bounds(root, PACKAGE)

    def test_empty_surface_cannot_receive_a_tap(self):
        root = ET.Element('hierarchy'); root.append(view('[0,24][0,804]'))
        with self.assertRaisesRegex(AssertionError, 'Empty/offscreen'):
            webview_bounds(root, PACKAGE)

    def test_negative_bounds_are_not_silently_converted_to_positive(self):
        root = ET.Element('hierarchy'); root.append(view('[-5,24][394,804]'))
        with self.assertRaisesRegex(AssertionError, 'Empty/offscreen'):
            webview_bounds(root, PACKAGE)

    def test_single_native_container_keeps_original_bounds(self):
        root = ET.Element('hierarchy'); root.append(view())
        self.assertEqual(((0, 24, 394, 804), 1), webview_bounds(root, PACKAGE))


if __name__ == '__main__':
    unittest.main(verbosity=2)
