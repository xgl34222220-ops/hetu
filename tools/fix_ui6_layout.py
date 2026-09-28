#!/usr/bin/env python3
"""UI6 review follow-up: common baselines and unclipped (not partially-visible) geometry."""
from pathlib import Path
import json
root='android-app/app/src/main/java/io/github/xgl34222220/hetu/'
test='android-app/app/src/test/java/io/github/xgl34222220/hetu/'
def edit(path,old,new):
    p=Path(path);s=p.read_text()
    if new in s: return
    assert s.count(old)==1,(path,old[:100],s.count(old))
    p.write_text(s.replace(old,new))
p=root+'HomeProportions.kt'
edit(p,'val first: Dp, val second: Dp)', 'val first: Dp, val second: Dp, val baseline: Dp = 17.dp)')
p=root+'CompactHomeDashboard.kt'
edit(p,'min = if (LocalHomeCompactSpacing.current) 60.dp else 72.dp','min = if (LocalHomeCompactSpacing.current) 56.dp else 72.dp')
edit(p,'.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center)', '.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center)')
edit(p,'top = 4.dp, bottom = 0.dp','top = if (compact) 2.dp else 4.dp, bottom = 0.dp')
edit(p,'Arrangement.spacedBy(if (compact) 8.dp else 12.dp)', 'Arrangement.spacedBy(if (compact) 6.dp else 12.dp)')
edit(p,'Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp))', 'Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = if (LocalHomeCompactSpacing.current) 8.dp else 14.dp))')
edit(p,'Text(name, color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold', 'Text(name, color = p.muted, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold')
edit(p,'''        val second = maxOf(line,
            measuredHeight(data.region,regionWidth,textStyle.copy(fontSize=14.sp,lineHeight=20.sp,letterSpacing=0.sp),2),
            measuredHeight(data.lanInterface,regionWidth,textStyle.copy(fontSize=14.sp,lineHeight=20.sp,letterSpacing=0.sp),2))
        val rows = HomeGridRows(heading, maxOf(0.dp,(48.dp-heading)/2), first, second)''', '''        val regionStyle = textStyle.copy(fontSize=14.sp,lineHeight=20.sp,letterSpacing=0.sp)
        val anchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"),style=textStyle).firstBaseline
        val smallAnchor = measurer.measure(androidx.compose.ui.text.AnnotatedString("0"),style=regionStyle).firstBaseline
        val baseline = with(density) { anchor.toDp() }
        val baselineExtra = with(density) { (anchor-smallAnchor).coerceAtLeast(0f).toDp() }
        val second = maxOf(line,
            measuredHeight(data.region,regionWidth,regionStyle,2)+baselineExtra,
            measuredHeight(data.lanInterface,regionWidth,regionStyle,2)+baselineExtra)
        val rows = HomeGridRows(heading, maxOf(0.dp,(48.dp-heading)/2), first, second, baseline)''')
edit(p,'''                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.second), verticalAlignment = Alignment.Top) {
                        HomeLabel(if (shownLan) "接口" else "地区", Modifier.width(26.dp))''', '''                    Row(Modifier.fillMaxWidth().height(LocalHomeGridRows.current.second), verticalAlignment = Alignment.Top) {
                        val base = LocalHomeGridRows.current.baseline
                        val anchor = with(LocalDensity.current) { base.roundToPx() }
                        Spacer(Modifier.width(0.dp).height(LocalHomeGridRows.current.second).alignBy { anchor })
                        HomeLabel(if (shownLan) "接口" else "地区", Modifier.width(26.dp).alignByBaseline().testTag("home-label-network-region"))''')
edit(p,'Box(Modifier.clip(RoundedCornerShape(4.dp)).background(p.blue.copy(alpha = .06f))', 'Box(Modifier.alignByBaseline().clip(RoundedCornerShape(4.dp)).background(p.blue.copy(alpha = .06f))')
edit(p,'Modifier.weight(1f), color = p.text.copy(alpha = .82f)', 'Modifier.weight(1f).alignByBaseline().testTag("home-value-network-region"), color = p.text.copy(alpha = .82f)')
p=root+'NativeHomePolish.kt'
edit(p,'.padding(if (compact) 16.dp else 20.dp)', '.padding(if (compact) 14.dp else 20.dp)')
edit(p,'Spacer(Modifier.height(if (compact) 12.dp else 16.dp))','Spacer(Modifier.height(if (compact) 10.dp else 16.dp))')
p=test+'HomeUi6RegressionTest.kt'
edit(p,'''    private fun bounds(tag:String)=rule.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot''', '''    private fun bounds(tag:String):androidx.compose.ui.geometry.Rect {
        // assertIsDisplayed/boundsInRoot alone can accept an only-partially-visible card.
        // All test windows use mdpi, so these unclipped Dp coordinates equal physical pixels.
        val b=rule.onNodeWithTag(tag,useUnmergedTree=true).getUnclippedBoundsInRoot()
        return androidx.compose.ui.geometry.Rect(b.left.value,b.top.value,b.right.value,b.bottom.value)
    }''')
edit(p,'''        assertEquals(baseline("home-label-IP"),baseline("home-label-上行"),1f)''', '''        assertEquals(baseline("home-label-IP"),baseline("home-label-上行"),1f)
        assertEquals(baseline("home-value-network-region"),baseline("home-value-下行"),1f)
        assertEquals(baseline("home-label-network-region"),baseline("home-label-下行"),1f)''')
edit(p,'''        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        val dock=bounds("hetu-dock")''', '''        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        snapshot("ui6-first-screen-before-assertions")
        println("UNCLIPPED normal viewport=${bounds("compact-home")} cards=${tags.map(::bounds)} dock=${bounds("hetu-dock")}")
        val dock=bounds("hetu-dock")''')
edit(p,'''        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        assertTrue(bounds("home-resources").bottom<=bounds("hetu-dock").top-9f)''', '''        tags.forEach {rule.onNodeWithTag(it).assertIsDisplayed()}
        snapshot("ui6-medium-before-assertions")
        println("UNCLIPPED medium viewport=${bounds("compact-home")} cards=${tags.map(::bounds)} dock=${bounds("hetu-dock")}")
        assertTrue("Full card ${bounds("home-resources")} must clear dock ${bounds("hetu-dock")}",bounds("home-resources").bottom<=bounds("hetu-dock").top-9f)''')
print('UI6: second-row baselines, compact spacing and full unclipped-bound checks applied')
