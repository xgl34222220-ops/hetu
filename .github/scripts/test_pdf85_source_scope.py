#!/usr/bin/env python3
import copy,json,unittest
from pathlib import Path
from pdf85_source_scope import validate_layer,validate_changes,validate_shared_page
ROOT=Path(__file__).resolve().parents[2]
class PdfScope(unittest.TestCase):
    def setUp(self):self.layer=json.loads((ROOT/'updates/v2085-pdf-tools-settings/inputs.json').read_text())
    def test_exact_current_layer(self):validate_layer(self.layer)
    def test_frozen_home_and_panel_cannot_enter_layer(self):
        for path in ['android-app/app/src/main/java/io/github/xgl34222220/hetu/home/HomeScreen.kt','android-app/app/src/main/assets/hetu-root.sh','android-app/app/src/main/AndroidManifest.xml']:
            j=copy.deepcopy(self.layer);j['changedOrAddedFiles'][path]='0'*64
            with self.assertRaises(AssertionError):validate_layer(j)
    def test_baseline_test_count_cannot_be_reduced(self):
        j=copy.deepcopy(self.layer);j['baselineUnitTests']=421
        with self.assertRaises(AssertionError):validate_layer(j)
    def test_shared_canvas_option_keeps_original_default(self):
        before='    canvasColor: Color? = null,\n    val pageBrush = if (c.dark) {\nother'
        after=before.replace('    canvasColor: Color? = null,','    canvasColor: Color? = null,\n    flatCanvas: Boolean = false,').replace('    val pageBrush = if (c.dark) {','    val pageBrush = if (c.dark || flatCanvas) {')
        validate_shared_page(before,after)
        for wrong in [after.replace('flatCanvas: Boolean = false','flatCanvas: Boolean = true'),after.replace('other','changed')]:
            with self.assertRaises(AssertionError):validate_shared_page(before,wrong)
    def test_diagnostic_boundary_rejects_root_or_controller_actions(self):
        gradle='versionCode = 2084\nversionName = "0.12.14-v20-auth"'
        newer=gradle.replace('2084','2085').replace('0.12.14-v20-auth','0.12.15-v20-pdf')
        before='prefix\n            int count=0,otherCount=0;\nloop\n        }catch(Exception e){report.section("微信当前连接",suffix'
        after=before.replace('int count=0,otherCount=0;','int count=0,otherCount=0,googleCount=0;')
        validate_changes(gradle,newer,before,after)
        for wrong in [after.replace('loop','rootShell()'),after.replace('loop','MihomoControllerClient()'),after.replace('prefix','mutated')]:
            with self.assertRaises(AssertionError):validate_changes(gradle,newer,before,wrong)
if __name__=='__main__':unittest.main()
