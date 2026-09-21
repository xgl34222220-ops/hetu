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
    onEdit: () -> Unit, onRefresh: (() -> Unit)? = null, error: String = "") {
    SubscriptionBoardingTicket(name, provider, host, placeholder, refreshing, success, onEdit, onRefresh, error)
}
