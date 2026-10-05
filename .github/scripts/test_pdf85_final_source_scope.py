#!/usr/bin/env python3
"""Reject shared defaults, callback or frozen-input drift in the last PDF delta."""
import copy
import json
import subprocess
import unittest
from unittest.mock import patch

from pdf85_final_source_scope import ALLOWED, BASE_COMMIT, ROOT, previous_files, validate_layer, validate_presentation


class FinalPdfScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.before = {name: subprocess.check_output(['git', 'show', BASE_COMMIT + ':' + name], cwd=ROOT).decode() for name in ALLOWED}
        cls.after = {name: (ROOT / name).read_text() for name in ALLOWED}
        cls.layer = json.loads((ROOT / 'updates/v2085-pdf-final-geometry/inputs.json').read_text())
        cls.previous = previous_files(ROOT)

    def test_all_four_complete_sources_and_layer(self):
        validate_layer(self.layer, self.previous)
        for name in ALLOWED:
            validate_presentation(self.before[name], self.after[name], name)

    def test_shared_default_color_cannot_be_enabled(self):
        name = next(p for p in ALLOWED if p.endswith('/HxComponents.kt'))
        changed = self.after[name].replace('menuSelectedTextColor: Color? = null', 'menuSelectedTextColor: Color? = Color.Blue')
        self.assertNotEqual(changed, self.after[name])
        with self.assertRaises(AssertionError):
            validate_presentation(self.before[name], changed, name)

    def test_shared_default_visual_inset_cannot_be_enabled(self):
        name = next(p for p in ALLOWED if p.endswith('/HxComponents.kt'))
        changed = self.after[name].replace('menuSelectedVerticalVisualInset: Dp? = null', 'menuSelectedVerticalVisualInset: Dp? = 1.dp')
        self.assertNotEqual(changed, self.after[name])
        with self.assertRaises(AssertionError):
            validate_presentation(self.before[name], changed, name)

    def test_default_subtitle_style_cannot_change(self):
        name = next(p for p in ALLOWED if p.endswith('/ToolsDesign.kt'))
        changed = self.after[name].replace('subtitleStyle: TextStyle? = null', 'subtitleStyle: TextStyle? = ToolsTypography.barSubtitle')
        self.assertNotEqual(changed, self.after[name])
        with self.assertRaises(AssertionError):
            validate_presentation(self.before[name], changed, name)

    def test_callbacks_cannot_be_replaced(self):
        name = next(p for p in ALLOWED if p.endswith('/HxComponents.kt'))
        changed = self.after[name].replace('onPick(', 'ignoredPick(')
        self.assertNotEqual(changed, self.after[name])
        with self.assertRaises(AssertionError):
            validate_presentation(self.before[name], changed, name)

    def test_frozen_compilation_input_cannot_be_omitted(self):
        changed = copy.deepcopy(self.layer)
        changed['frozenFiles'].pop(next(iter(changed['frozenFiles'])))
        with self.assertRaises(AssertionError):
            validate_layer(changed, self.previous)

    def test_scope_cannot_include_manifest(self):
        changed = copy.deepcopy(self.layer)
        changed['changedOrAddedFiles']['android-app/app/src/main/AndroidManifest.xml'] = '0' * 64
        with self.assertRaises(AssertionError):
            validate_layer(changed, self.previous)

    def test_transforms_cannot_replace_authorized_path_with_manifest(self):
        from pdf85_final_source_scope import TRANSFORMS
        changed = copy.deepcopy(TRANSFORMS)
        replaced = next(iter(changed))
        changed['android-app/app/src/main/AndroidManifest.xml'] = changed.pop(replaced)
        self.assertEqual(len(changed), 4)
        with patch('pdf85_final_source_scope.TRANSFORMS', changed):
            with self.assertRaises(AssertionError):
                validate_layer(self.layer, self.previous)


if __name__ == '__main__':
    unittest.main()
