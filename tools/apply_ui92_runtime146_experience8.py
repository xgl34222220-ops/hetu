#!/usr/bin/env python3
"""One-time reviewed UI7 -> UI8 delta; refuses to replace any concurrently changed input."""
from pathlib import Path
import subprocess,json
BASE='ba2a6be65b03a1e1cdb190b08b431aa49a860cc0'
R='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
T='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
changes={}
def original(path):
    old=subprocess.check_output(['git','show',BASE+':'+path]).decode()
    assert Path(path).read_text()==old, 'Concurrent application edit: '+path
    return old
def one(s,a,b):
    assert s.count(a)==1,(a[:130],s.count(a))
    return s.replace(a,b)

p=R+'CompactHomeDashboard.kt';s=original(p)
s=one(s,'import androidx.compose.ui.platform.testTag\n','import androidx.compose.ui.platform.testTag\nimport androidx.compose.ui.layout.onSizeChanged\nimport androidx.compose.ui.zIndex\n')
s=one(s,'HomePalette(Color(0xFFF8FAFC),','HomePalette(Color(0xFFF6F8FD),')
s=one(s,'    contentInsets: WindowInsets = WindowInsets.safeDrawing,\n','    contentInsets: WindowInsets = WindowInsets.safeDrawing,\n    onPullRefresh: () -> Unit = onRefresh,\n')
s=one(s,'        val (elastic, elasticConnection) = rememberHomeElasticity(listState, motion)','        val (pull, pullConnection) = rememberHomePull(listState, data.refreshing,\n            !data.busy && data.operation == null, motion, onPullRefresh)')
s=one(s,'val collapseDistance = with(LocalDensity.current) { 40.dp.toPx() }','val collapseDistance = with(LocalDensity.current) { 30.dp.toPx() }')
a=s.index('        val snackbar = remember { SnackbarHostState() }')
b=s.index('        // The header is a sibling',a)
s=s[:a]+'''        val notice = rememberHomeNotice(data)
        var headerPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
'''+s[b:]
s=one(s,'HomeCollapsingHeader(collapse, motion, headerHaze, palette.text, elastic.offset) {','HomeCollapsingHeader(collapse, motion, headerHaze, palette.text,\n                modifier = Modifier.zIndex(2f).onSizeChanged { headerPx = it.height }) {')
s=one(s,'BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {','BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {')
s=one(s,'LazyColumn(Modifier.fillMaxSize().clipToBounds().nestedScroll(elasticConnection)','LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = pull.offsetPx }\n            .nestedScroll(pullConnection)')
s=one(s,'''            item("hero") {
                Box(Modifier.graphicsLayer {
                    translationY = elastic.offset * .15f
                    scaleY = 1f + elastic.offset / 2000f
                }) { HomeHero(data, onToggle, onReload, onRestart, onSettings, onWebUi, onLog) }
            }''','''            item("hero") { HomeHero(data, onToggle, onReload, onRestart, onSettings, onWebUi, onLog) }''')
s=one(s,'''            item("telemetry") { HomeTelemetryGrid(data, onConnections, onSubscription) }
        }
        }
        }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 96.dp))''','''            item("telemetry") { HomeTelemetryGrid(data, onConnections, onSubscription) }
        }
        HomePullIndicator(pull, motion, Modifier.align(Alignment.TopCenter))
        }
        }
        }
        HomeFeedbackPill(notice.value, motion, headerHaze,
            Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(contentInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = with(density) { headerPx.toDp() } + 8.dp, start = 16.dp, end = 16.dp)) { raw ->
            feedbackDetails = raw
            notice.value = null
        }''')
s=one(s,'''        val single = maxWidth / density.fontScale < 290.dp
        val cellWidth = if (single) maxWidth else (maxWidth - 12.dp) / 2''','''        // Measure the actual resolved system font and actual IPv4s, not a hard-coded device width.
        val halfAddressPx = with(density) { ((maxWidth - 12.dp) / 2 - 50.dp).roundToPx() }
        val ipv4Width = listOf(data.wan, data.lan).filter { !it.contains(':') }.maxOfOrNull {
            measurer.measure(androidx.compose.ui.text.AnnotatedString(it.ifBlank { "—" }),
                style = textStyle, softWrap = false, maxLines = 1).size.width
        } ?: 0
        val single = maxWidth / density.fontScale < 290.dp || ipv4Width > halfAddressPx
        val cellWidth = if (single) maxWidth else (maxWidth - 12.dp) / 2''')
s=one(s,'        val first = maxOf(line, measuredHeight(data.wan,addressWidth,textStyle), measuredHeight(data.lan,addressWidth,textStyle))','        val first = line // Addresses never create a second line above the region row.')
s=one(s,'''                        Text((if (shownLan) data.lan else data.wan).ifBlank { "—" },
                            Modifier.weight(1f).alignByBaseline().testTag("home-network-ip"), color = p.text,
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Default, letterSpacing = (-.5).sp,
                            softWrap = true, overflow = TextOverflow.Clip,
                            style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"))''','''                        HomeSingleLineAddress(if (shownLan) data.lan else data.wan, p.text,
                            Modifier.weight(1f).alignByBaseline().testTag("home-network-ip"))''')
changes[p]=s

p=R+'HomeInteraction7.kt';s=original(p)
s=one(s,'    stretch: Float = 0f,\n','    stretch: Float = 0f,\n    modifier: Modifier = Modifier,\n')
s=one(s,'    Box(Modifier.fillMaxWidth().testTag("home-header").semantics {','    Box(modifier.fillMaxWidth().testTag("home-header").semantics {')
changes[p]=s

p=R+'ReferenceProxyActivity.kt';s=original(p)
s=one(s,'        Color(0xFFF8FAFC)\n    }\n    val dockDensity', '        Color(0xFFF6F8FD)\n    }\n    val dockDensity')
a=s.index('                RefProxyPage.Home -> PullToRefreshBox(')
b=s.index('                    RefHome(',a)
prefix=s[a:b]
assert 'refreshHomeAll()' in prefix and 'windowInsetsPadding' in prefix
s=s[:a]+'                RefProxyPage.Home -> Box(Modifier.fillMaxSize()) {\n'+s[b:]
a=s.index('                    RefHome(',a)
b=s.index('                RefProxyPage.Panel ->',a)
part=s[a:b]
part=one(part,'                    onToggle = ::toggle,','''                    onPullRefresh = {
                        if (!homeRefreshing) scope.launch {
                            homeRefreshing = true
                            try {
                                refreshHomeAll()
                                if (message.isBlank()) message = "全部刷新完成"
                            } finally {
                                homeRefreshing = false
                            }
                        }
                    },
                    onToggle = ::toggle,''')
s=s[:a]+part+s[b:]
a=s.index('private fun RefHome(');b=s.index('\n@Composable\nprivate fun RefActionText(',a)
part=s[a:b]
part=one(part,'    onRefresh: () -> Unit,','    onRefresh: () -> Unit,\n    onPullRefresh: () -> Unit,')
part=one(part,'        onRefresh = onRefresh, onToggle = onToggle,','        onRefresh = onRefresh, onPullRefresh = onPullRefresh, onToggle = onToggle,')
s=s[:a]+part+s[b:]
changes[p]=s

p=R+'NativeHomePolish.kt';s=original(p)
s=one(s,'if (enabled && pressed && motion) .965f else 1f','if (enabled && pressed && motion) .97f else 1f')
changes[p]=s
p=R+'ui/HetuGlassDock.kt';s=original(p)
s=one(s,'if (pressed && motion) .975f else 1f','if (pressed && motion) .97f else 1f')
changes[p]=s
for p in [R+'ui/HetuTheme.kt','android-app/app/src/main/res/values/styles.xml']:
    s=original(p)
    assert 'F8FAFC' in s
    changes[p]=s.replace('F8FAFC','F6F8FD')
p='android-app/app/build.gradle.kts';s=original(p)
s=one(s,'versionCode = 1008','versionCode = 1009')
s=one(s,'versionName = "0.4.0-ui92-r146.7"','versionName = "0.4.0-ui92-r146.8"')
changes[p]=s
# Only the background color assertion is superseded by the new supplied specification.
p=T+'HomeUi6RegressionTest.kt';s=original(p)
assert '0xFFF8FAFC' in s
changes[p]=s.replace('0xFFF8FAFC','0xFFF6F8FD')
# RectangleShape is a Compose graphics shape, not a foundation.shape class.
p=R+'HomeExperience8.kt';s=Path(p).read_text()
changes[p]=s.replace('androidx.compose.foundation.shape.RectangleShape','RectangleShape')
for p,s in changes.items(): Path(p).write_text(s)
Path('integration-evidence').mkdir(exist_ok=True)
Path('integration-evidence/ui8-changed-paths.json').write_text(json.dumps(list(changes),indent=2))
print('Applied reviewed UI8 files:',*changes,sep='\n')
