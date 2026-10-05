#!/usr/bin/env python3
"""Additive PDF layer: exact file whitelist and bounded diagnostic metadata change."""
import re
ALLOWED = {'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdvancedSettingsActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/MiscScreens.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsConfigScreen.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/GoogleConnectionEvidenceTest.java', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/ToolsPdf85Test.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/GoogleConnectionEvidence.java', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ToolScreens.kt', 'android-app/app/build.gradle.kts', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/Pdf85Frames1.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsComponents.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsStartupConfigScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/Pdf85Frames2.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java'}
def validate_layer(layer):
    assert layer['schema']==1 and layer['baseCommit']=='121e0ab99904e7ea862b1dcb350a59169aeab0cd'
    assert set(layer['changedOrAddedFiles'])==ALLOWED
    assert layer['expectedUnitTests']==479 and layer['baselineUnitTests']==422
    assert layer['newTestCounts']=={'GoogleConnectionEvidenceTest':8,'tools.ToolsPdf85Test':49}
def validate_changes(before_gradle,after_gradle,before_root,after_root):
    assert after_gradle==before_gradle.replace('versionCode = 2084','versionCode = 2085').replace('0.12.14-v20-auth','0.12.15-v20-pdf')
    start='            int count=0,otherCount=0;'
    end='        }catch(Exception e){report.section("微信当前连接",'
    a=before_root.index(start);b=before_root.index(end,a)
    c=after_root.index('            int count=0,otherCount=0,googleCount=0;');d=after_root.index(end,c)
    assert before_root[:a]==after_root[:c] and before_root[b:]==after_root[d:]
    delta=after_root[c:d]
    assert 'RootBridge' not in delta and 'rootShell' not in delta and 'MihomoControllerClient' not in delta

def validate_shared_page(before,after):
    expected=before.replace('    canvasColor: Color? = null,','    canvasColor: Color? = null,\n    flatCanvas: Boolean = false,').replace('    val pageBrush = if (c.dark) {','    val pageBrush = if (c.dark || flatCanvas) {')
    assert after==expected, 'Shared page changes must be opt-in only; default home/panel path frozen'
