"""Apply the latest four-card specification once, preserving the original146 runtime."""
from pathlib import Path
import json, subprocess
BASE='70f3d53a1a70c25d6d55b136328f8219be59eca1'
ROOT=Path('android-app/app/src/main/java/io/github/xgl34222220/hetu')
TEST=Path('android-app/app/src/test/java/io/github/xgl34222220/hetu')
changed=[]
def edit(path, fn):
    p=Path(path);old=p.read_text()
    assert old==subprocess.check_output(['git','show',BASE+':'+str(p)]).decode(),('UI9 source diverged',str(p))
    new=fn(old)
    if new!=old: p.write_text(new);changed.append(str(p))
def sub(s,a,b,count=1):
    assert s.count(a)==count,(a[:110],s.count(a),count)
    return s.replace(a,b)
def home(s):
    s=s.replace('F4F6FC','F4F6FB')
    s=sub(s,'HomeContinuousShape(24.dp)','HomeContinuousShape(20.dp)')
    s=sub(s,'size: Int = 16, align:','size: Int = 15, align:')
    s=sub(s,'Modifier.align(Alignment.TopCenter).padding(top = 10.dp, bottom = 8.dp,','Modifier.align(Alignment.TopCenter).padding(top = 8.dp, bottom = 8.dp,')
    s=sub(s,'HomeCollapsingHeader(collapse, motion, headerHaze, palette.text,','HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, centered = true,')
    s=sub(s,'Arrangement.spacedBy(if (compact) 4.dp else 12.dp)','Arrangement.spacedBy(if (compact) 8.dp else 10.dp)')
    a=s.index('@Composable\nprivate fun HomeShortcutStrip(');b=s.index('@Composable\nprivate fun HomeLatency(',a)
    s=s[:a]+'''@Composable
private fun HomeShortcutStrip(webUi: () -> Unit, log: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("home-shortcut-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeHeroShortcut("WebUI", "Web 界面", Modifier.weight(1f).testTag("home-webui"), webUi)
        HomeHeroShortcut("日志", "查看记录", Modifier.weight(1f).testTag("home-log"), log)
    }
}

@Composable
private fun HomeHeroShortcut(title: String, subtitle: String, modifier: Modifier, click: () -> Unit) {
    val p = LocalHomePalette.current
    HomeCard(modifier.heightIn(min = 60.dp), onClick = click, clickLabel = title) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.Center) {
            Text(title, color = p.text, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = p.muted, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

'''+s[b:]
    s=sub(s,'bottom = if (LocalHomeCompactSpacing.current) 4.dp else 14.dp','bottom = 8.dp')
    s=sub(s,'Text("延迟", Modifier.weight(1f), color = p.text, fontSize = 15.sp','Text("网络延迟", Modifier.weight(1f), color = p.text, fontSize = 14.sp')
    s=sub(s,'tint, 19, TextAlign.Center','tint, 18, TextAlign.Center')
    s=s.replace('LocalBentoStackedMetrics','LocalGridStackedMetrics')
    s=sub(s,'val cellWidth = (maxWidth - 1.dp) / 2','val cellWidth = (maxWidth - 10.dp) / 2')
    s=sub(s,'fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.5).sp, fontFeatureSettings = "tnum")','fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = 0.sp, fontFeatureSettings = "tnum")')
    s=sub(s,'val stacked = (cellWidth - 28.dp) / density.fontScale < 110.dp','''val addressStyle = textStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-.2).sp)
        val addressPixels = listOf(data.wan, data.lan).filterNot { it.contains(':') }.maxOfOrNull {
            measurer.measure(androidx.compose.ui.text.AnnotatedString(it.ifBlank { "—" }),
                style = addressStyle, softWrap = false, maxLines = 1).size.width
        } ?: 0
        val inlinePixels = with(density) { (cellWidth - 24.dp - 22.dp).roundToPx() }
        // Reclaim label space inside the card instead of clipping the IPv4 tail.
        val stacked = (cellWidth - 24.dp) / density.fontScale < 104.dp || addressPixels + 2 > inlinePixels''')
    s=sub(s,'(cellWidth - 28.dp - 54.dp)','(cellWidth - 24.dp - 54.dp)')
    s=sub(s,'val line = with(density) { 22.sp.toDp() }','val line = with(density) { 21.sp.toDp() }')
    s=sub(s,'val regionStyle = textStyle.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)','val regionStyle = textStyle.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp)')
    a=s.index('        CompositionLocalProvider(LocalHomeGridRows provides rows, LocalGridStackedMetrics provides stacked)')
    b=s.index('@Composable\nprivate fun HomeDataHeading(',a)
    s=s[:a]+'''        CompositionLocalProvider(LocalHomeGridRows provides rows, LocalGridStackedMetrics provides stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().testTag("home-network-group"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeNetwork(data, Modifier.weight(1f).height(rows.height))
                    HomeSpeed(data, Modifier.weight(1f).height(rows.height), connections)
                }
                Row(Modifier.fillMaxWidth().testTag("home-health-group"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HomeSubscription(data, Modifier.weight(1f).height(rows.height), subscription)
                    HomeResources(data, Modifier.weight(1f).height(rows.height))
                }
            }
        }
    }
}

'''+s[b:]
    s=s.replace('/** Two material surfaces with independent halves. Address content NEVER changes column count. */','/** Four independent surfaces, two equal columns. Address length never changes column count. */')
    s=sub(s,'fontSize = 15.sp, lineHeight = 21.sp,\n            fontWeight = FontWeight.Bold, maxLines = 1','fontSize = 14.sp, lineHeight = 20.sp,\n            fontWeight = FontWeight.Bold, maxLines = 1')
    s=sub(s,'Column(Modifier.fillMaxSize().padding(14.dp))','Column(Modifier.fillMaxSize().padding(12.dp))',4)
    a=s.index('                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {')
    b=s.index('                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.second)',a)
    s=s[:a]+'''                    if (LocalGridStackedMetrics.current) {
                        Column(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {
                            HomeLabel("IP", Modifier.testTag("home-label-IP"))
                            Spacer(Modifier.height(2.dp))
                            HomeSingleLineAddress(if (shownLan) data.lan else data.wan, p.text,
                                Modifier.fillMaxWidth().testTag("home-network-ip"))
                        }
                    } else Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.first)) {
                        val anchor = with(LocalDensity.current) { LocalHomeGridRows.current.baseline.roundToPx() }
                        Spacer(Modifier.width(0.dp).height(LocalHomeGridRows.current.first).alignBy { anchor })
                        HomeLabel("IP", Modifier.width(18.dp).alignByBaseline().testTag("home-label-IP"))
                        Spacer(Modifier.width(4.dp))
                        HomeSingleLineAddress(if (shownLan) data.lan else data.wan, p.text,
                            Modifier.weight(1f).alignByBaseline().testTag("home-network-ip"))
                    }
'''+s[b:]
    s=sub(s,'fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,\n                            maxLines = 2','fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold,\n                            maxLines = 2')
    s=sub(s,'HomeDataHeading("实时速率")','HomeDataHeading("网速")')
    s=sub(s,'HomeDataHeading(if (LocalGridStackedMetrics.current) "订阅" else "套餐订阅")','HomeDataHeading("订阅")')
    return sub(s,'HomeDataHeading("系统负载")','HomeDataHeading("资源占用")')
edit(ROOT/'CompactHomeDashboard.kt',home)
edit(ROOT/'HomeExperience8.kt',lambda s:sub(s,'fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-.5).sp,','fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-.2).sp,'))
edit(ROOT/'HomeProportions.kt',lambda s:sub(s,'28.dp + heading + gap + first + 4.dp + second + 14.dp','24.dp + heading + gap + first + 4.dp + second + 14.dp'))
def header(s):
    s=sub(s,'    stretch: Float = 0f,','    stretch: Float = 0f,\n    centered: Boolean = false,')
    s=sub(s,'fontSize = (28f - 8f * fraction).sp,','fontSize = (if (centered) 20f else 28f - 8f * fraction).sp,')
    s=sub(s,'maxOf(56.dp.roundToPx(), title.height, buttons.height)','maxOf((if (centered) 48.dp else 56.dp).roundToPx(), title.height, buttons.height)')
    s=sub(s,'title.placeRelative((leading + (centered - leading) * fraction).toInt(),','title.placeRelative((leading + (centered - leading) * (if (fixedCenter) 1f else fraction)).toInt(),')
    return sub(s,'    val fraction by animateFloatAsState(','    val fixedCenter = centered\n    val fraction by animateFloatAsState(')
edit(ROOT/'HomeInteraction7.kt',header)
def hero(s):
    a=s.index('@Composable\ninternal fun NativeStatusHero(');b=s.index('@Composable\ninternal fun NativeSpinner(',a)
    t=s[a:b].replace('HomeContinuousShape(28.dp)','HomeContinuousShape(26.dp)').replace('Color(0xFFDDE7FD)','Color(0xFFDCE6FC)')
    t=t.replace('padding(if (compact) 14.dp else 20.dp)','padding(if (compact) 16.dp else 18.dp)')
    t=t.replace('fontSize = 22.sp, lineHeight = 29.sp','fontSize = 21.sp, lineHeight = 28.sp')
    t=t.replace('fontSize = 15.sp,\n                        lineHeight = 21.sp','fontSize = 14.sp,\n                        lineHeight = 20.sp')
    t=t.replace('fontSize = 13.sp, lineHeight = 19.sp','fontSize = 12.sp, lineHeight = 18.sp')
    t=t.replace('fontSize = 15.sp, lineHeight = 21.sp','fontSize = 14.sp, lineHeight = 20.sp')
    t=t.replace('.padding(6.dp).heightIn(min = 48.dp)','.padding(4.dp).heightIn(min = 48.dp)').replace('else 84.dp','else 76.dp')
    return s[:a]+t+s[b:]
edit(ROOT/'NativeHomePolish.kt',hero)
edit(ROOT/'ui/HetuGlassDock.kt',lambda s:sub(sub(s,'.height(64.dp + if (floating) 0.dp else bottomInset)','.height(60.dp + if (floating) 0.dp else bottomInset)'),'itemHeight = 52.dp,','itemHeight = 48.dp,'))
for p in [ROOT/'ReferenceProxyActivity.kt',ROOT/'ui/HetuTheme.kt',Path('android-app/app/src/main/res/values/styles.xml')]:
    edit(p,lambda s:s.replace('F4F6FC','F4F6FB'))
edit('android-app/app/build.gradle.kts',lambda s:sub(sub(s,'versionCode = 1010','versionCode = 1011'),'0.4.0-ui92-r146.9','0.4.0-ui92-r146.10'))
def tests(s):
    # Only explicitly superseded visual contracts change. Keep all tests and behavioral checks.
    s=s.replace('F4F6FC','F4F6FB').replace('assertEquals(28.sp,','assertEquals(20.sp,')
    s=s.replace('assertEquals(20.sp,layout("home-brand")','assertEquals(28.sp,layout("home-brand")')
    s=s.replace('assertEquals(16.sp,','assertEquals(14.sp,')
    s=s.replace('assertEquals(14.sp, layouts.first().layoutInput.style.fontSize)','assertEquals(15.sp, layouts.first().layoutInput.style.fontSize)')
    s=s.replace('assertEquals(22.sp, textLayout("hero-status-title")','assertEquals(21.sp, textLayout("hero-status-title")')
    s=s.replace('assertEquals(15.sp, textLayout("hero-uptime")','assertEquals(14.sp, textLayout("hero-uptime")')
    s=s.replace('assertEquals(13.sp, textLayout("hero-core")','assertEquals(12.sp, textLayout("hero-core")')
    s=s.replace('assertEquals(15.sp, textLayout("hero-config")','assertEquals(14.sp, textLayout("hero-config")')
    s=s.replace('assertWidthIsEqualTo(84.dp).assertHeightIsEqualTo(84.dp)','assertWidthIsEqualTo(76.dp).assertHeightIsEqualTo(76.dp)')
    s=s.replace('bounds("hero-capsule-surface").height>=60f','bounds("hero-capsule-surface").height>=56f')
    s=s.replace('assertEquals(header.left + 16f, brand.left, 1f)','assertEquals(header.center.x, brand.center.x, 1f)')
    s=s.replace('assertEquals(1f, resource.left - sub.right, 1f)','assertEquals(10f, resource.left - sub.right, 1f)')
    s=s.replace('assertEquals(14f, subBar.left - sub.left, 1f)','assertEquals(12f, subBar.left - sub.left, 1f)')
    s=s.replace('assertEquals(14f, sub.right - subBar.right, 1f)','assertEquals(12f, sub.right - subBar.right, 1f)')
    s=s.replace('assertEquals(1f,b[1].left-b[0].right,1f)','assertEquals(10f,b[1].left-b[0].right,1f)')
    s=s.replace('assertEquals(12f,b[2].top-b[0].bottom,1f)','assertEquals(10f,b[2].top-b[0].bottom,1f)')
    s=s.replace('node("$group-divider").assertExists()','node("$group-divider").assertDoesNotExist()\n        assertEquals(10f, (b.left - a.right).value, 1f)')
    s=s.replace('assertEquals(12f, (bounds("home-health-group").top - bounds("home-network-group").bottom).value, 1f)','assertEquals(10f, (bounds("home-health-group").top - bounds("home-network-group").bottom).value, 1f)')
    return s.replace('assertHeightIsEqualTo(52.dp)','assertHeightIsEqualTo(48.dp)')
for name in ['CompactHomeDashboardTest','HomeUi6RegressionTest','HomeUi7InteractionTest','HomeUi8ExperienceTest','HomeUi9BentoTest','NativeDockUi4Test']:
    edit(TEST/(name+'.kt'),tests)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui10-changed-paths.json').write_text(json.dumps(changed,indent=2))
print('\n'.join(changed))
