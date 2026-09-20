package io.github.xgl34222220.hetu

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import io.github.xgl34222220.hetu.ui.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.draw.clip

/** Presentation-only instruments: no network, core lifecycle or invented telemetry. */
@Composable
internal fun InstrumentProgress(value: Float, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val fraction = value.takeIf { it.isFinite() }?.coerceIn(0f,1f) ?: 0f
    val t = LocalHetuTokens.current
    Box(modifier.fillMaxWidth().height(5.dp).clip(CircleShape)
        .background(t.textMuted.copy(alpha = .12f))
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(accent, Color(0xFF42C9CE))), CircleShape))
    }
}

@Composable
private fun InstrumentValue(label: String, value: String, color: Color) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,color=color,fontSize=11.sp,lineHeight=15.sp)
        HetuNumber(value,style=MaterialTheme.typography.titleSmall.copy(fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold))
    }
}

/** Current byte rates only. A fixed icon is not represented as a signal-strength meter. */
@Composable
internal fun EngineThroughput(up: Long, down: Long) {
    val t=LocalHetuTokens.current
    val primary=MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(Brush.horizontalGradient(listOf(primary.copy(alpha=.035f),t.success.copy(alpha=.045f))))
        .padding(horizontal=12.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { InstrumentValue("↑ 实时上行",refSpeed(up),t.success) }
        Column(Modifier.weight(1f)) { InstrumentValue("↓ 实时下行",refSpeed(down),primary) }
    }
}

internal fun ticketUpdatedAt(raw: String, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if(raw.isBlank()) return "尚未更新"
    val instant=runCatching { Instant.parse(raw) }.getOrElse {
        runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    } ?: return "更新时间未知"
    if(instant.isBefore(Instant.EPOCH)) return "尚未更新"
    val date=instant.atZone(zone)
    val today=now.atZone(zone).toLocalDate()
    val prefix=when(date.toLocalDate()) { today -> "今天"; today.minusDays(1) -> "昨天"; else -> date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }
    return "$prefix ${date.format(DateTimeFormatter.ofPattern("HH:mm"))} 更新"
}

internal fun ticketExpireAt(expire: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if(expire<=0) return "到期日未上报"
    val millis=if(expire<10_000_000_000L) expire*1000L else expire
    return runCatching { "到期 " + Instant.ofEpochMilli(millis).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) }.getOrDefault("到期日未知")
}

@Composable
internal fun InstrumentSubscriptionTicket(name: String, provider: DashboardProviderUi?, host: String = "",
    placeholder: Boolean = false, refreshing: Boolean = false, success: Boolean = false,
    onEdit: () -> Unit, onRefresh: (() -> Unit)? = null) {
    val t=LocalHetuTokens.current
    val primary=MaterialTheme.colorScheme.primary
    val known=provider?.let { it.hasSubscriptionInfo && it.total>0L } == true
    var detail by rememberSaveable(name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().crystalMaterial(RoundedCornerShape(22.dp),tint=primary)
        .testTag("subscription-ticket:$name").padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min=48.dp).clickable(role=Role.Button,onClick=onEdit),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).crystalMaterial(RoundedCornerShape(12.dp),tint=primary,depth=CrystalDepth.InsetItem),contentAlignment=Alignment.Center) {
                    Icon(if(placeholder) Icons.Rounded.LinkOff else Icons.Rounded.Link,null,Modifier.size(20.dp),tint=if(placeholder)t.warning else primary)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(name,color=t.textPrimary,fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.Bold)
                    if(host.isNotBlank()) Text(host,color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
                    if(provider!=null) Text("${provider.nodes.size} 节点",color=primary,fontSize=11.sp,lineHeight=16.sp)
                    else if(placeholder) Text("待填写订阅地址",color=t.warning,fontSize=11.sp)
                }
            }
            IconButton(onClick=onEdit,modifier=Modifier.size(48.dp)) { Icon(Icons.Rounded.Edit,"编辑 $name",Modifier.size(18.dp),tint=t.textSecondary) }
            if(onRefresh!=null) IconButton(onClick=onRefresh,enabled=!refreshing,modifier=Modifier.size(48.dp)) {
                if(refreshing) HetuBusyIndicator(Modifier.size(18.dp)) else Icon(if(success)Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
                    "$name ${if(success)"更新完成" else "更新订阅"}",Modifier.size(19.dp),tint=if(success)t.success else primary)
            }
        }
        if(refreshing || success) Text(if(refreshing)"正在更新 $name" else "$name 更新完成",color=if(success)t.success else t.textSecondary,fontSize=12.sp)
        if(known && provider!=null) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("已用 / 总量",color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.weight(1f))
                Surface(shape=CircleShape,color=t.successContainer.copy(alpha=.7f)) {
                    Text("剩余 ${((1f-provider.ratio)*100).toInt()}%",Modifier.padding(horizontal=8.dp,vertical=4.dp),color=t.success,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                }
            }
            // Keep the full quota value out of the badge row. Reserve scalable glyph bounds.
            HetuNumber("${refBytes(provider.used)} / ${refBytes(provider.total)}",
                Modifier.fillMaxWidth().heightIn(min=with(LocalDensity.current){32.sp.toDp()}).testTag("ticket-usage:$name"),
                style=MaterialTheme.typography.titleSmall.copy(fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold))
            InstrumentProgress(provider.ratio,Modifier.testTag("ticket-progress:$name"))
        } else Text("订阅未上报流量信息",color=t.textSecondary,fontSize=12.sp,lineHeight=17.sp)
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(t.textMuted.copy(alpha=.22f),Offset.Zero,Offset(size.width,0f),strokeWidth=1f,
                pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),4.dp.toPx())))
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Surface(shape=CircleShape,color=primary.copy(alpha=.055f)) {
                    Text(ticketExpireAt(provider?.expire ?: 0),Modifier.padding(horizontal=8.dp,vertical=4.dp),color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
                }
                Text(ticketUpdatedAt(provider?.updatedAt.orEmpty()),color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
            }
            if(known) TextButton(onClick={detail=!detail},modifier=Modifier.heightIn(min=48.dp)) {
                Text(if(detail)"收起详情" else "流量详情",fontSize=11.sp)
            }
        }
        if(detail && known && provider!=null) {
            Text("剩余 ${refBytes(provider.remaining)}",color=t.textPrimary,fontSize=12.sp)
            Text("上传 ${refBytes(provider.upload)} · 下载 ${refBytes(provider.download)}",color=t.textSecondary,fontSize=12.sp,lineHeight=18.sp)
        }
    }
}
