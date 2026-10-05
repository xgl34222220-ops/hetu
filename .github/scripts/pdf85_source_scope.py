#!/usr/bin/env python3
"""Additive PDF layer: exact file whitelist and bounded diagnostic metadata change."""
import re
ALLOWED = {'android-app/app/src/main/java/io/github/xgl34222220/hetu/RootTproxyActivity.java', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyAdvancedSettingsActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxySubStoreActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyNetworkAutomationActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsDiagScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsDesign.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/GoogleConnectionEvidenceTest.java', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/ToolsPdf85Test.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/ConceptRemainingCoverageTest.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsReferenceIcons.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsStartupConfigScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/ToolScreens.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsIcons.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsSubscriptionScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsYamlEditor.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsConfigScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/LargeTitleParity59Test.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/MiscScreens.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyLogViewerActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/AdblockScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/SettingsReferenceComponents.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsCoresScreen.kt', 'android-app/app/build.gradle.kts', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsAppsScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsComponents.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/GoogleConnectionEvidence.java', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsImportScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsAdblockScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/ProxyLocalWebUiActivity.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/RuntimeEditorScreen.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsFeatureComponents.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/ConceptStateCoverageTest.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/app/HxComponents.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/Pdf85Frames2.kt', 'android-app/app/src/test/java/io/github/xgl34222220/hetu/tools/Pdf85Frames1.kt', 'android-app/app/src/main/java/io/github/xgl34222220/hetu/tools/ToolsEditorScreen.kt'}
PICKER_EXPECTATIONS_FILE = 'android-app/app/src/test/java/io/github/xgl34222220/hetu/SettingsPickerParity58Test.kt'
ALLOWED.add(PICKER_EXPECTATIONS_FILE)
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
    expected=expected.replace('import androidx.compose.foundation.layout.size\n','import androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.layout.requiredSize\n',1)
    expected=expected.replace('import androidx.compose.material.icons.rounded.ErrorOutline\n','import androidx.compose.material.icons.rounded.ErrorOutline\nimport androidx.compose.material.icons.rounded.Error\n',1)
    expected=expected.replace('showCopyLabel: Boolean = false) {','showCopyLabel: Boolean = false, referenceDocument: Boolean = false) {')
    expected=expected.replace('style = if (wrapLines) MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleLarge','style = if (referenceDocument) MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold) else if (wrapLines) MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold) else MaterialTheme.typography.titleLarge')
    expected=expected.replace('fontFamily = if (wrapLines) FontFamily.Default else FontFamily.Monospace','fontFamily = if (wrapLines && !referenceDocument) FontFamily.Default else FontFamily.Monospace')
    expected=expected.replace('                .heightIn(max = if (wrapLines) (LocalConfiguration.current.screenHeightDp * .49f).dp else 520.dp)\n                .padding(horizontal = 16.dp)', '                .heightIn(max = if (wrapLines) (LocalConfiguration.current.screenHeightDp * .49f).dp else 520.dp)\n                .padding(horizontal = if (referenceDocument) 10.5.dp else 16.dp)', 1)
    expected=expected.replace('    menuWidthOverride: Dp? = null,\n) {','    menuWidthOverride: Dp? = null,\n    maxVisibleChoices: Int? = null,\n) {')
    marker='            } else choices.forEachIndexed { index, choice ->'
    if marker in expected:
        a=expected.index(marker,expected.index('internal fun HxChoiceSheet'));b=expected.index('            if (!footer.isNullOrBlank())',a)
        body=expected[a:b].removeprefix('            } else ')
        expected=expected[:a]+'            } else if (maxVisibleChoices != null) {\n                Column(Modifier.fillMaxWidth().heightIn(max = (maxVisibleChoices * 42.5f).dp).verticalScroll(rememberScrollState())) {\n'+body+'                }\n            } else '+body+expected[b:]
    expected=expected.replace('fontSize = 20.sp, fontWeight = FontWeight.Bold) else if (wrapLines)', 'fontSize = if (title == "AdGuard") 30.sp else 24.sp, fontWeight = FontWeight.Bold) else if (wrapLines)')
    expected=expected.replace('                fontSize = 11.5.sp,\n                lineHeight = 16.sp,\n                color = c.text,', '                fontSize = if (referenceDocument) 10.sp else 11.5.sp,\n                lineHeight = if (referenceDocument) 14.sp else 16.sp,\n                letterSpacing = if (referenceDocument) 0.sp else androidx.compose.ui.unit.TextUnit.Unspecified,\n                color = c.text,')
    expected=expected.replace('    highlightError: Boolean = true,\n    plainFields:', '    highlightError: Boolean = true,\n    fieldOutlineColor: Color? = null,\n    plainFields:')
    expected=expected.replace('else if (compactPills) Color.Transparent else c.line, RoundedCornerShape(if (compactPills)', 'else if (compactPills) Color.Transparent else (fieldOutlineColor ?: c.line), RoundedCornerShape(if (compactPills)')
    expected=expected.replace('if (!message.isNullOrBlank() && messageBelowFields) {', 'if (!message.isNullOrBlank() && messageBelowFields && !(hideMessageOnError && error != null)) {')
    expected=expected.replace('    titleTextAlign: TextAlign = TextAlign.Center,\n    hideMessageOnError:', '    titleTextAlign: TextAlign = TextAlign.Center,\n    centerTitleOnError: Boolean = false,\n    hideMessageOnError:')
    expected=expected.replace('textAlign = titleTextAlign, modifier = Modifier.fillMaxWidth())', 'textAlign = if (centerTitleOnError && error != null) TextAlign.Center else titleTextAlign, modifier = Modifier.fillMaxWidth())')
    expected=expected.replace('    widthFraction: Float? = null,\n    titleFontSize:', '    widthFraction: Float? = null,\n    errorWidthFraction: Float? = null,\n    titleFontSize:')
    expected=expected.replace('    fieldOutlineColor: Color? = null,\n    plainFields:', '    fieldOutlineColor: Color? = null,\n    errorFieldOutlineColor: Color? = null,\n    plainFields:')
    expected=expected.replace('HxReferenceDialog(onDismiss, widthFraction = widthFraction ?: if (configFooter) .74f else .76f, contentPadding = 0.dp)', 'HxReferenceDialog(onDismiss, widthFraction = (if (error != null) errorWidthFraction else null) ?: widthFraction ?: if (configFooter) .74f else .76f, contentPadding = 0.dp)')
    expected=expected.replace('else (fieldOutlineColor ?: c.line), RoundedCornerShape(if (compactPills)', 'else ((if (error != null) errorFieldOutlineColor else null) ?: fieldOutlineColor ?: c.line), RoundedCornerShape(if (compactPills)')
    expected=expected.replace('            } else if (maxVisibleChoices != null) {\n                Column(Modifier.fillMaxWidth().heightIn(max = (maxVisibleChoices * 42.5f).dp).verticalScroll(rememberScrollState())) {\nchoices.forEachIndexed { index, choice ->\n                HxMenuItem(\n                    choice.label,\n                    onClick = { close { onPick(choice.id) } },\n                    description = choice.description,\n                    selected = choice.id == selected,\n                    selectedTextColor = if (presentation == HxChoicePresentation.Scale || !referenceSettings && dimBehind) c.accent else c.text,\n                    enabled = choice.enabled,\n                    choicePresentation = presentation,\n                )\n                if (referenceSettings && index < choices.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = c.line.copy(alpha = .4f))\n            }\n                }\n','            } else if (maxVisibleChoices != null) {\n                val density = LocalDensity.current\n                var visibleRowsHeight by remember(maxVisibleChoices, choices) { mutableStateOf<Int?>(null) }\n                @Composable fun choiceRow(index: Int, choice: HxChoice) {\n                HxMenuItem(\n                    choice.label,\n                    onClick = { close { onPick(choice.id) } },\n                    description = choice.description,\n                    selected = choice.id == selected,\n                    selectedTextColor = if (presentation == HxChoicePresentation.Scale || !referenceSettings && dimBehind) c.accent else c.text,\n                    enabled = choice.enabled,\n                    choicePresentation = presentation,\n                )\n                if (referenceSettings && index < choices.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 12.dp), thickness = .5.dp, color = c.line.copy(alpha = .4f))\n                }\n                Column(Modifier.fillMaxWidth().heightIn(max = visibleRowsHeight?.let { with(density) { it.toDp() } } ?: (maxVisibleChoices * 48.5f).dp).verticalScroll(rememberScrollState())) {\n                    Column(Modifier.onSizeChanged { visibleRowsHeight = it.height }) {\n                        choices.take(maxVisibleChoices).forEachIndexed { index, choice -> choiceRow(index, choice) }\n                    }\n                    choices.drop(maxVisibleChoices).forEachIndexed { index, choice -> choiceRow(index + maxVisibleChoices, choice) }\n                }\n',1)
    expected=expected.replace('    errorFieldOutlineColor: Color? = null,\n    plainFields: Boolean = false,','    errorFieldOutlineColor: Color? = null,\n    labelFontWeight: FontWeight? = null,\n    inputFontWeight: FontWeight? = null,\n    confirmColorOverride: Color? = null,\n    plainFields: Boolean = false,',1)
    expected=expected.replace('lineHeight = if (plainFields) 20.sp else 18.sp, fontWeight = FontWeight.Medium)','lineHeight = if (plainFields) 20.sp else 18.sp, fontWeight = labelFontWeight ?: FontWeight.Medium)',1)
    expected=expected.replace('fontSize = if (plainFields) 16.sp else 15.sp, lineHeight = 20.sp),','fontSize = if (plainFields) 16.sp else 15.sp, lineHeight = 20.sp, fontWeight = inputFontWeight ?: MaterialTheme.typography.bodyLarge.fontWeight),',1)
    expected=expected.replace('HxDialogButtons(confirmLabel, c.accent, referenceCorners = !compactPills,','HxDialogButtons(confirmLabel, confirmColorOverride ?: c.accent, referenceCorners = !compactPills,',1)
    expected=expected.replace('    confirmColorOverride: Color? = null,\n    plainFields: Boolean = false,','    confirmColorOverride: Color? = null,\n    normalPlainFieldHeight: Dp? = null,\n    normalPlainFieldVerticalPadding: Dp? = null,\n    plainFields: Boolean = false,',1)
    expected=expected.replace('heightIn(min = if (compactPills) 36.dp else if (configFooter) 42.dp else if (plainFields) 40.dp else 38.dp)','heightIn(min = (if (error == null && plainFields) normalPlainFieldHeight else null) ?: if (compactPills) 36.dp else if (configFooter) 42.dp else if (plainFields) 40.dp else 38.dp)',1)
    expected=expected.replace('.padding(horizontal = 12.dp, vertical = if (compactPills) 7.dp else if (configFooter) 10.dp else 9.dp)','.padding(horizontal = 12.dp, vertical = (if (error == null && plainFields) normalPlainFieldVerticalPadding else null) ?: if (compactPills) 7.dp else if (configFooter) 10.dp else 9.dp)',1)
    # Only explicit compact banner callers get the PDF presentation; legacy body remains exact.
    if 'internal fun HxBanner(' in expected:
        banner_start=expected.index('internal fun HxBanner(')
        banner_end=expected.index('\n}\n',banner_start)+3
        banner=expected[banner_start:banner_end]
        banner=banner.replace('    onAction: (() -> Unit)? = null,\n) {','    onAction: (() -> Unit)? = null,\n    referenceCompact: Boolean = false,\n) {',1)
        banner=banner.replace('    val bg by animateColorAsState(tone.bg(), tween(HxMotion.Medium), label = "bannerBg")\n','    val bg by animateColorAsState(tone.bg(), tween(HxMotion.Medium), label = "bannerBg")\n    if (referenceCompact) {\n        Row(modifier.fillMaxWidth().clip(Hx.rowShape).background(bg)\n            .animateContentSize(tween(HxMotion.Medium, easing = HxMotion.Emphasized))\n            .heightIn(min = 46.dp).padding(horizontal = 12.dp, vertical = 7.dp),\n            verticalAlignment = Alignment.CenterVertically) {\n            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {\n                Icon(if (tone == HxTone.Bad || tone == HxTone.Warn) Icons.Rounded.Error else Icons.Rounded.Info,\n                    null, tint = tone.fg(), modifier = Modifier.requiredSize(if (tone == HxTone.Bad || tone == HxTone.Warn) 23.dp else 18.dp))\n            }\n            Spacer(Modifier.width(8.dp))\n            Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp, lineHeight = 14.sp,\n                fontWeight = FontWeight.SemiBold), color = tone.fg(), modifier = Modifier.weight(1f))\n            if (actionLabel != null && onAction != null) {\n                Box(Modifier.heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onAction)\n                    .padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {\n                    Text(actionLabel, color = Hx.colors.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)\n                }\n            }\n        }\n        return\n    }\n',1)
        expected=expected[:banner_start]+banner+expected[banner_end:]
    expected=expected.replace('    flatCanvas: Boolean = false,\n    referenceLabel:','    flatCanvas: Boolean = false,\n    referenceTopBar: Boolean = false,\n    referenceLabel:',1)
    expected=expected.replace('style = MaterialTheme.typography.titleMedium.copy(fontSize = compactTitleFontSizeSp.sp, fontWeight = FontWeight.SemiBold),','style = MaterialTheme.typography.titleMedium.copy(fontSize = compactTitleFontSizeSp.sp, fontWeight = if (referenceTopBar) FontWeight.Bold else FontWeight.SemiBold),',1)
    expected=expected.replace('Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = ht("返回"), tint = c.text)','Icon(if (referenceTopBar) io.github.xgl34222220.hetu.home.HomeIcons.ChevronLeft else Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = ht("返回"), tint = c.text)',1)
    assert after==expected, 'Shared page changes must be opt-in only; default home/panel path frozen'


def validate_webui_reference_header(before,after):
    """Tools WebUI status/glyph and PDF44 notice footer only; behavior stays exact."""
    extra = '.endpoint{display:flex;align-items:center;justify-content:center;gap:4px}\n.status.in-proxies:not(.error),.status.in-connections:not(.error){position:static;border:0;background:none;padding:0;gap:0}\n.status.in-proxies:not(.error) #status-label,.status.in-connections:not(.error) #status-label{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}\n.status.in-proxies:not(.error) .mark{width:10px;height:10px}.status.in-proxies:not(.error) .mark:after{font-size:8px;line-height:10px}\n.status.in-connections:not(.error){order:-1}.status.in-connections:not(.error) .mark{width:7px;height:7px}.status.in-connections:not(.error) .mark:after{content:none}\n#notice .dialog-footer{padding:3px 13px}\n'
    old = '<div><h1>河图 WebUI</h1><small id="backend">127.0.0.1:__PORT__</small></div><div id="status" class="status" role="status"><span class="mark"></span><span id="status-label">连接中</span></div>'
    new = '<div><h1>河图 WebUI</h1><div class="endpoint"><small id="backend">127.0.0.1:__PORT__</small><div id="status" class="status" role="status"><span class="mark"></span><span id="status-label">连接中</span></div></div></div>'
    render = ' const statusView=document.getElementById("status");\n statusView.classList.toggle("in-proxies",current==="proxies");statusView.classList.toggle("in-connections",current==="connections");\n'
    expected=before.replace('.tabs{display:grid;',extra+'.tabs{display:grid;',1).replace(old,new,1).replace('function render(){\n','function render(){\n'+render,1)
    expected=expected.replace("type==='Fallback'?'shield':'layers'", "type==='Fallback'?(['AI 稳定','AI稳定'].includes(name)?'bolt':'shield'):'layers'",1)
    assert old in before and after==expected, 'Only reference WebUI status, named group glyph and notice footer presentation may change'


NATIVE_SUBTITLE_HELPER = '    private TextView settingsSubtitle(String value){\n        return settingsSubtitle(value,14);\n    }\n\n    private TextView settingsSubtitle(String value,float size){\n        TextView text=u.text(value,size,u.muted,false);\n        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.P)text.setTypeface(android.graphics.Typeface.create(text.getTypeface(),600,false));\n        else text.setFontVariationSettings("\'wght\' 600");\n        return text;\n    }\n\n'
def validate_native_settings_typography(before, after):
    """Root settings UI only: all actions, listeners and configuration code stay exact."""
    assert before.count('u.text(subtitle,13,u.muted,false)') == 3
    assert before.count('TextView current=u.text(value,13,u.muted,false)') == 1
    marker='    private TextView settingRow('
    assert before.count(marker) == 1
    expected=before.replace('u.text(subtitle,13,u.muted,false)','settingsSubtitle(subtitle)')
    expected=expected.replace('TextView current=u.text(value,13,u.muted,false)','TextView current=settingsSubtitle(value)')
    switch_start=expected.index('    private CompoundButton switchRow(')
    switch_end=expected.index('    private void plainAction(',switch_start)
    switch=expected[switch_start:switch_end]
    assert switch.count('settingsSubtitle(subtitle)') == 1
    expected=expected[:switch_start]+switch.replace('settingsSubtitle(subtitle)','settingsSubtitle(subtitle,12.5f)',1)+expected[switch_end:]
    assert expected.count('启动时将必要的设置参数覆写到运行配置') == 1
    expected=expected.replace('启动时将必要的设置参数覆写到运行配置','启动时将必要的河图参数覆写到运行配置',1)
    mode_call='showChoice(modeValue,labels,descriptions,enabled,selected,184,index->'
    ipv6_call='showChoice(ipv6Value,labels,descriptions,new boolean[]{true,true,true,true},profile().ipv6.ordinal(),184,index->'
    assert before.count(mode_call) == 1 and before.count(ipv6_call) == 1
    expected=expected.replace(mode_call,mode_call.replace(',184,',',214,'),1)
    expected=expected.replace(ipv6_call,ipv6_call.replace(',184,',',214,'),1)
    expected=expected.replace(marker,NATIVE_SUBTITLE_HELPER+marker,1)
    assert after == expected, 'Native settings typography may not change Root or configuration behavior'


def validate_settings_picker_expectations(before, after):
    """PDF04 widths only; preserve both tests, every assertion and dim/Core guards."""
    mode='assertTrue(source.contains("enabled,selected,184,index"))'
    ipv6='assertTrue(source.contains("ordinal(),184,index"))'
    assert before.count(mode) == 1 and before.count(ipv6) == 1
    expected=before.replace(mode,mode.replace('184','214'),1).replace(ipv6,ipv6.replace('184','214'),1)
    assert after == expected, 'Only the two superseded PDF width expectations may change'
