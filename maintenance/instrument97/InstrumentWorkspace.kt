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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
private fun InstrumentCell(title: String, icon: ImageVector, modifier: Modifier, accent: Color,
    onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalHetuTokens.current
    Column(modifier.heightIn(min = 132.dp).crystalMaterial(RoundedCornerShape(22.dp), tint = accent)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(25.dp).background(accent.copy(alpha = .085f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(16.dp), tint = accent)
            }
            Text(title, color = t.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
        }
        content()
    }
}

@Composable
internal fun InstrumentBento(runtime: ProxyRuntimeSnapshot, connections: Int, up: Long, down: Long,
    used: Long, total: Long, count: Int, memory: Long, cpu: Float, onSubscription: () -> Unit) {
    val t = LocalHetuTokens.current
    val primary = MaterialTheme.colorScheme.primary
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val motion = LocalHetuMotionEnabled.current
    var lan by rememberSaveable { mutableStateOf(false) }
    val tilt = remember { Animatable(0f) }
    val ratio = if (total > 0L) (used.toDouble()/total).toFloat().coerceIn(0f,1f) else 0f
    @Composable fun Network(mod: Modifier) {
        InstrumentCell(if (lan) "局域网" else "出口", if (lan) Icons.Rounded.Wifi else Icons.Rounded.Public,
            mod.graphicsLayer { rotationY = tilt.value; cameraDistance = 24f * density }.testTag("instrument-network"), primary, {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                lan = !lan
                if (motion && !tilt.isRunning) scope.launch { tilt.animateTo(5f,tween(70)); tilt.animateTo(0f,tween(150)) }
            }) {
            val display = if (lan) runtime.lanAddress else when (runtime.wanState) {
                "success", "stale" -> runtime.wanAddress
                "loading" -> "检测中…"
                "failed" -> "检测失败"
                else -> "未检测"
            }
            HetuNumber(display, monospaced = lan || runtime.wanState in listOf("success","stale"),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp,lineHeight = 21.sp))
            val checked = if (runtime.wanCheckedAt > 0L) DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(runtime.wanCheckedAt)) else ""
            Text(if (lan) "${runtime.lanInterface} · $connections 连接" else when(runtime.wanState) {
                "success" -> "${countryEmoji(runtime.wanCountryCode)} ${runtime.wanRegion} · $checked"
                "stale" -> "上次检测 $checked · 尚未重新确认"
                "loading" -> "通过当前 Mihomo 规则检测"
                "failed" -> runtime.wanError.ifBlank { "稍后自动重试" }
                else -> if(runtime.running) "等待出口检测" else "代理已停止"
            }, color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
        }
    }
    @Composable fun Speed(mod: Modifier) {
        InstrumentCell("实时网速",Icons.Rounded.Speed,mod.testTag("instrument-speed"),t.success) {
            InstrumentValue("↑ 上行",refSpeed(up),t.success)
            InstrumentValue("↓ 下行",refSpeed(down),primary)
        }
    }
    @Composable fun Usage(mod: Modifier) {
        InstrumentCell("已用流量",Icons.Rounded.DataUsage,mod.testTag("instrument-usage"),primary,onSubscription) {
            HetuNumber(if(total>0L) refBytes(used) else "—",style=MaterialTheme.typography.titleLarge.copy(fontSize=19.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold))
            if(total>0L) InstrumentProgress(ratio,Modifier.testTag("home-usage-progress"))
            Text(if(total>0L) "总 ${refBytes(total)} · 剩余 ${((1f-ratio)*100).toInt()}%" else "$count 个订阅 · 未上报额度",
                color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
        }
    }
    @Composable fun Resource(mod: Modifier) {
        InstrumentCell("资源占用",Icons.Rounded.Memory,mod.testTag("instrument-resource"),primary) {
            InstrumentValue("内存",refBytes(memory),t.textSecondary)
            InstrumentValue("CPU",String.format(Locale.US,"%.1f%%",cpu),t.textSecondary)
            InstrumentProgress(cpu/100f,Modifier.testTag("home-cpu-progress"))
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("workspace-bento")) {
        val columns = hetuCompactColumns(maxWidth,152.dp)
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(columns==1) {
                Network(Modifier.fillMaxWidth()); Speed(Modifier.fillMaxWidth())
                Usage(Modifier.fillMaxWidth()); Resource(Modifier.fillMaxWidth())
            } else {
                Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Network(Modifier.weight(1f).fillMaxHeight()); Speed(Modifier.weight(1f).fillMaxHeight())
                }
                Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Usage(Modifier.weight(1f).fillMaxHeight()); Resource(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
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
        .testTag("subscription-ticket:$name").padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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
                Column(Modifier.weight(1f)) {
                    Text("已用 / 总量",color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
                    HetuNumber("${refBytes(provider.used)} / ${refBytes(provider.total)}",Modifier.testTag("ticket-usage:$name"),
                        style=MaterialTheme.typography.titleSmall.copy(fontSize=15.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold))
                }
                Surface(shape=CircleShape,color=t.successContainer.copy(alpha=.7f)) {
                    Text("剩余 ${((1f-provider.ratio)*100).toInt()}%",Modifier.padding(horizontal=8.dp,vertical=5.dp),color=t.success,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
                }
            }
            InstrumentProgress(provider.ratio,Modifier.testTag("ticket-progress:$name"))
        } else Text("订阅未上报流量信息",color=t.textSecondary,fontSize=12.sp,lineHeight=17.sp)
        Canvas(Modifier.fillMaxWidth().height(1.dp)) {
            drawLine(t.textMuted.copy(alpha=.22f),Offset.Zero,Offset(size.width,0f),strokeWidth=1f,
                pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),4.dp.toPx())))
        }
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Surface(shape=CircleShape,color=primary.copy(alpha=.055f)) {
                Text(ticketExpireAt(provider?.expire ?: 0),Modifier.padding(horizontal=8.dp,vertical=4.dp),color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
            }
            Text(ticketUpdatedAt(provider?.updatedAt.orEmpty()),color=t.textSecondary,fontSize=11.sp,lineHeight=16.sp)
        }
        if(known && provider!=null) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable(role=Role.Button,onClick={detail=!detail}),verticalAlignment=Alignment.CenterVertically) {
                Text(if(detail)"收起流量详情" else "流量详情",color=t.textSecondary,fontSize=11.sp,modifier=Modifier.weight(1f))
                Icon(if(detail)Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,null,Modifier.size(16.dp),tint=t.textSecondary)
            }
            if(detail) {
                Text("剩余 ${refBytes(provider.remaining)}",color=t.textPrimary,fontSize=12.sp)
                Text("上传 ${refBytes(provider.upload)} · 下载 ${refBytes(provider.download)}",color=t.textSecondary,fontSize=12.sp,lineHeight=18.sp)
            }
        }
    }
}
