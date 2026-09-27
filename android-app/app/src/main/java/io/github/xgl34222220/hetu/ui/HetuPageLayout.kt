package io.github.xgl34222220.hetu.ui

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Use Android's resolved default face, including the user's OEM/system font. */
val HetuSystemFontFamily = FontFamily(Typeface.DEFAULT)

/** Shared with the LuoShu/BaiZe page rhythm; toolbar and dock use the same gutter. */
object HetuPageMetrics {
    val Gutter = 16.dp
    val Gap = 16.dp
    val ToolbarHeight = 64.dp
    val RowHeight = 72.dp
    val CardRadius = 16.dp
    val DockHeight = 72.dp
}

@Composable
fun HetuPrimaryHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = HetuPageMetrics.ToolbarHeight)
        .padding(horizontal = HetuPageMetrics.Gutter, vertical = 8.dp).testTag("page-header"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(ht(title), Modifier.weight(1f), color = LocalHetuTokens.current.textPrimary,
            fontFamily = HetuSystemFontFamily, fontSize = 26.sp, lineHeight = 34.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Header occupies layout space: no list row can be sliced under a blur overlay. */
@Composable
fun HetuPageList(title: String, modifier: Modifier = Modifier, listTag: String = "",
    state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(LocalHetuTokens.current.pageBackground).statusBarsPadding()) {
        HetuPrimaryHeader(title)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag(listTag), state = state,
            contentPadding = PaddingValues(start = HetuPageMetrics.Gutter, end = HetuPageMetrics.Gutter,
                top = 8.dp, bottom = hetuContentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(HetuPageMetrics.Gap), content = content)
    }
}

@Composable
fun HetuDetailList(title: String, onBack: () -> Unit, subtitle: String = "",
    modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(LocalHetuTokens.current.pageBackground).statusBarsPadding()) {
        Box(Modifier.padding(horizontal = HetuPageMetrics.Gutter)) { HetuPageHeader(title, onBack, subtitle, actions) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state,
            contentPadding = PaddingValues(start = HetuPageMetrics.Gutter, end = HetuPageMetrics.Gutter,
                top = 8.dp, bottom = hetuContentBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(HetuPageMetrics.Gap), content = content)
    }
}
