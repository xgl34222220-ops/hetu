package io.github.xgl34222220.hetu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun CoreConceptGlyph(id: String) {
    val c = Hx.colors
    Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(if (id.startsWith("mihomo")) c.accentSoft else c.surfaceMuted), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(42.dp)) {
            fun point(x: Float, y: Float) = Offset(x * size.width / 24, y * size.height / 24)
            if (id.startsWith("mihomo")) {
                val p = Path().apply {
                    moveTo(point(2f,21f).x, point(2f,21f).y)
                    listOf(6f to 2f, 10f to 7f, 15f to 7f, 19f to 2f, 23f to 21f).forEach { (x,y) -> val q=point(x,y); lineTo(q.x,q.y) }
                    quadraticTo(size.width/2,size.height*1.06f,point(2f,21f).x,point(2f,21f).y); close()
                }
                drawPath(p,c.text)
                drawCircle(c.surface, size.width*.065f, point(9f,15f)); drawCircle(c.surface,size.width*.065f,point(17f,15f))
            } else if (id.contains("xray")) {
                val p=Path().apply { val points=listOf(2f to 3f,8f to 3f,22f to 22f,16f to 22f);points.forEachIndexed { i,(x,y)->val q=point(x,y);if(i==0)moveTo(q.x,q.y)else lineTo(q.x,q.y) };close() }
                drawPath(p,c.text);drawLine(c.text,point(20f,3f),point(3f,22f),size.width*.14f)
            } else {
                val p=Path().apply { val points=listOf(12f to 1f,23f to 7f,23f to 18f,12f to 24f,1f to 18f,1f to 7f);points.forEachIndexed {i,(x,y)->val q=point(x,y);if(i==0)moveTo(q.x,q.y)else lineTo(q.x,q.y)};close() }
                drawPath(p,c.text)
                drawLine(c.surface,point(1f,7f),point(12f,13f),1.5.dp.toPx());drawLine(c.surface,point(12f,13f),point(23f,7f),1.5.dp.toPx());drawLine(c.surface,point(12f,13f),point(12f,24f),1.5.dp.toPx())
            }
        }
    }
}

@Composable
internal fun CoreConceptButton(text: String,onClick: () -> Unit,modifier: Modifier=Modifier,icon: ImageVector?=null,enabled:Boolean=true,busy:Boolean=false,tone:HxTone=HxTone.Accent,filled:Boolean=true) {
    val c=Hx.colors
    val actionable = enabled && !busy
    val foreground = when {
        busy -> c.onAccent
        !enabled -> c.textFaint.copy(alpha = .6f)
        filled -> c.onAccent
        else -> tone.fg()
    }
    val background = when {
        busy -> c.textFaint.copy(alpha = .45f)
        !enabled || !filled -> c.surfaceMuted
        else -> tone.fg()
    }
    Row(modifier.heightIn(min=50.dp).clip(RoundedCornerShape(18.dp)).background(background)
        .clickable(enabled=actionable,onClick={ if (actionable) onClick() }).padding(horizontal=10.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center) {
        if(busy){HxSpinner(21.dp,foreground);Spacer(Modifier.width(9.dp))}
        Text(if (busy) "处理中" else text,fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=foreground,maxLines=1)
    }
}

internal fun coreFamilyVariantIds(family: String): List<String> = when (family) {
    "mihomo" -> listOf("mihomo", "mihomo-smart")
    "xray" -> listOf("xray")
    "sing-box" -> listOf("sing-box", "sing-box-ref1nd")
    else -> emptyList()
}
internal fun coreManagerVariantIds(family: String): List<String> = coreFamilyVariantIds(family) + listOf("v2fly", "hysteria")
internal fun coreManagerCards(statuses: List<ProxyCoreRemoteStatus>, variants: Map<String, String>): List<Pair<String, ProxyCoreRemoteStatus>> =
    listOf("mihomo", "xray", "sing-box").mapNotNull { family ->
        val selected = variants[family]?.takeIf { it in coreManagerVariantIds(family) } ?: family
        (statuses.firstOrNull { it.id == selected } ?: statuses.firstOrNull { it.id == family })?.let { family to it }
    }
internal fun coreManagerDisplayName(id: String, fallback: String): String = when(id) {
    "sing-box" -> "sing-box"
    "sing-box-ref1nd" -> "sing-box reF1nd"
    else -> fallback
}

@Composable
internal fun CoreManagerPicker(family: String, current: String, statuses: List<ProxyCoreRemoteStatus>, otherSelected: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val anchor = remember { HxAnchor.take() }
    @Composable fun ColumnScope.choices() {
        coreFamilyVariantIds(family).mapNotNull { id -> statuses.firstOrNull { it.id == id } }.forEach { item ->
            HxMenuItem(coreManagerDisplayName(item.id, item.label), { onPick(item.id) }, selected = item.id == current,
                enabled = item.id !in otherSelected, description = if (item.runtimeReady) "下载与版本管理" else "仅下载管理")
        }
        Text("其他可管理核心", color = Hx.colors.textFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        listOf("v2fly", "hysteria").mapNotNull { id -> statuses.firstOrNull { it.id == id } }.forEach { item ->
            HxMenuItem(item.label, { onPick(item.id) }, selected = item.id == current, enabled = item.id !in otherSelected,
                description = "独立核心 · 仅下载管理")
        }
        Text("不改变正在运行的代理", color = Hx.colors.textFaint, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
    }
    if (anchor != null) HxAnchoredMenu(anchor, onDismiss) { _ -> choices() }
    else HxSheet(onDismiss, title = "选择管理的核心") { choices() }
}
